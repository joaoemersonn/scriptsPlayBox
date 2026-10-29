package br.com.playbox.service;

import br.com.playbox.client.PayXYZClient;
import br.com.playbox.client.VendProMaxClient;
import br.com.playbox.client.ZapiziClient;
import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.TransacaoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class TransactionImportService {

    private static final Logger log = LoggerFactory.getLogger(TransactionImportService.class);
    private static final ZoneId BRASILIA_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final VendProMaxClient vendClient;
    private final PayXYZClient payxyzClient;
    private final ZapiziClient zapiziClient;
    private final AppProperties properties;
    private final MaquinaRepository maquinaRepository;
    private final TransacaoRepository transacaoRepository;
    private final EstoqueService estoqueService;
    private final HistoricoVinculoService historicoVinculoService;

    public TransactionImportService(
            VendProMaxClient vendClient,
            PayXYZClient payxyzClient,
            ZapiziClient zapiziClient,
            AppProperties properties,
            MaquinaRepository maquinaRepository,
            TransacaoRepository transacaoRepository,
            EstoqueService estoqueService
    ) {
        this(vendClient, payxyzClient, zapiziClient, properties, maquinaRepository, transacaoRepository, estoqueService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TransactionImportService(
            VendProMaxClient vendClient,
            PayXYZClient payxyzClient,
            ZapiziClient zapiziClient,
            AppProperties properties,
            MaquinaRepository maquinaRepository,
            TransacaoRepository transacaoRepository,
            EstoqueService estoqueService,
            HistoricoVinculoService historicoVinculoService
    ) {
        this.vendClient = vendClient;
        this.payxyzClient = payxyzClient;
        this.zapiziClient = zapiziClient;
        this.properties = properties;
        this.maquinaRepository = maquinaRepository;
        this.transacaoRepository = transacaoRepository;
        this.estoqueService = estoqueService;
        this.historicoVinculoService = historicoVinculoService;
    }

    public int importDailyTransactions(LocalDate date) {
        LocalDateTime startOfDay = date.atStartOfDay();
        ZonedDateTime startZoned = date.atStartOfDay(BRASILIA_ZONE);
        ZonedDateTime endZoned = date.atTime(LocalTime.MAX).atZone(BRASILIA_ZONE);

        String inicioStr = ISO_FORMATTER.format(startZoned);
        String fimStr = ISO_FORMATTER.format(endZoned);

        String dayId = date.toString(); // YYYY-MM-DD
        log.info("Iniciando importação de transações do dia {} ({} a {})...", dayId, inicioStr, fimStr);

        int count = 0;
        count += importVendProMax(dayId, inicioStr, fimStr, startOfDay);
        count += importPayXYZ(dayId, inicioStr, fimStr, startOfDay);
        count += importZapizi(dayId, inicioStr, fimStr, startOfDay);

        log.info("Importação do dia {} finalizada. {} transações/registros gravados/atualizados.", dayId, count);
        return count;
    }

    private LocalDateTime parseTimestamp(String isoString, LocalDateTime defaultVal) {
        if (isoString == null || isoString.isBlank()) {
            return defaultVal;
        }
        try {
            return ZonedDateTime.parse(isoString.trim()).withZoneSameInstant(BRASILIA_ZONE).toLocalDateTime();
        } catch (Exception e1) {
            try {
                return LocalDateTime.parse(isoString.trim(), DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception e2) {
                return defaultVal;
            }
        }
    }

    private void cleanupLegacyConsolidado(String integracao, String legacyExternalTxId) {
        try {
            Optional<Transacao> optOld = transacaoRepository.findByIntegracaoAndExternalTxId(integracao, legacyExternalTxId);
            if (optOld.isPresent()) {
                Transacao old = optOld.get();
                if ("CONSOLIDADO".equalsIgnoreCase(old.getTipoPagamento())) {
                    log.info("Removendo transação consolidada legada substituída por detalhada: {} ({})", legacyExternalTxId, integracao);
                    transacaoRepository.delete(old);
                }
            }
        } catch (Exception e) {
            log.warn("Aviso ao limpar transação consolidada legada {}: {}", legacyExternalTxId, e.getMessage());
        }
    }

    private int importVendProMax(String dayId, String inicio, String fim, LocalDateTime timestamp) {
        int count = 0;
        try {
            String token = vendClient.login(properties.getVendpromax().getEmail(), properties.getVendpromax().getPassword());
            List<JsonNode> machines = vendClient.getMachines(token);

            for (JsonNode m : machines) {
                if (!m.hasNonNull("id")) continue;
                String extId = m.get("id").asText();

                // 1. Obter pagamentos detalhados individuais
                JsonNode pagamentosPeriodo = vendClient.getPagamentosPeriodo(token, extId, inicio, fim);

                if (pagamentosPeriodo != null && pagamentosPeriodo.hasNonNull("pagamentos") && pagamentosPeriodo.get("pagamentos").isArray()) {
                    for (JsonNode p : pagamentosPeriodo.get("pagamentos")) {
                        String pId = p.hasNonNull("id") ? p.get("id").asText() : null;
                        if (pId == null || pId.isBlank()) continue;

                        double valor = p.hasNonNull("valor") ? p.get("valor").asDouble() : 0.0;
                        String tipo = p.hasNonNull("tipo") ? p.get("tipo").asText().toUpperCase() : "PIX";
                        boolean estornado = p.hasNonNull("estornado") && p.get("estornado").asBoolean();
                        double valorEstorno = estornado ? valor : 0.0;

                        LocalDateTime txTime = timestamp;
                        if (p.hasNonNull("data")) {
                            txTime = parseTimestamp(p.get("data").asText(), timestamp);
                        }

                        double valorCash = "CASH".equalsIgnoreCase(tipo) ? valor : 0.0;
                        double valorPix = "PIX".equalsIgnoreCase(tipo) ? valor : 0.0;
                        double valorCredito = "CREDITO".equalsIgnoreCase(tipo) ? valor : 0.0;
                        double valorDebito = "DEBITO".equalsIgnoreCase(tipo) ? valor : 0.0;

                        String externalTxId = "VENDPROMAX_TX_" + pId;
                        saveOrUpdateTransaction("VENDPROMAX", externalTxId, extId, txTime, tipo,
                                valor, valorCash, valorPix, valorCredito, valorDebito, valorEstorno, 0.0);
                        count++;
                    }
                }

                // 2. Obter contagem de prêmios liberados
                JsonNode premios = vendClient.getReport(token, "relatorio-premios", extId, inicio, fim, false);
                double quantPremios = (premios != null && premios.hasNonNull("quantidade")) ? premios.get("quantidade").asDouble() : 0.0;
                if (quantPremios > 0.0) {
                    String prizeTxId = "VENDPROMAX_PREMIOS_" + extId + "_" + dayId;
                    saveOrUpdateTransaction("VENDPROMAX", prizeTxId, extId, timestamp, "PREMIOS",
                            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, quantPremios);
                    count++;
                }

                // 3. Limpeza do consolidado antigo para evitar duplicidade de valores
                String legacyExternalTxId = "VENDPROMAX_" + extId + "_" + dayId;
                cleanupLegacyConsolidado("VENDPROMAX", legacyExternalTxId);
            }
        } catch (Exception e) {
            log.error("Erro na importação de VendProMax para o dia {}: {}", dayId, e.getMessage());
        }
        return count;
    }

    private int importPayXYZ(String dayId, String inicio, String fim, LocalDateTime timestamp) {
        int count = 0;
        try {
            String token = payxyzClient.login(properties.getPayxyz().getEmail(), properties.getPayxyz().getPassword(), true);
            List<JsonNode> machines = payxyzClient.getMachines(token);
            List<Object> maquinaIds = new ArrayList<>();
            for (JsonNode m : machines) {
                if (m.hasNonNull("id")) {
                    maquinaIds.add(m.get("id").asText());
                }
            }
            if (maquinaIds.isEmpty()) return 0;

            List<JsonNode> rawReports = payxyzClient.getReport(token, inicio, fim, maquinaIds);
            for (JsonNode item : rawReports) {
                JsonNode maquinaNode = item.hasNonNull("maquina") ? item.get("maquina") : null;
                if (maquinaNode == null || !maquinaNode.hasNonNull("id")) continue;

                String extId = maquinaNode.get("id").asText();

                if (item.hasNonNull("vendasByPaymentType") && item.get("vendasByPaymentType").isArray()) {
                    for (JsonNode p : item.get("vendasByPaymentType")) {
                        String forma = p.hasNonNull("formaDePagamento") ? p.get("formaDePagamento").asText().toUpperCase() : "PIX";
                        JsonNode dados = p.hasNonNull("dados") ? p.get("dados") : null;
                        if (dados != null) {
                            double vt = dados.hasNonNull("valorTotal") ? dados.get("valorTotal").asDouble() : 0.0;
                            double ve = dados.hasNonNull("valorTotalEstorno") ? dados.get("valorTotalEstorno").asDouble() : 0.0;

                            if (vt > 0.0 || ve > 0.0) {
                                double valorCash = ("ESPECIE".equalsIgnoreCase(forma) || "DINHEIRO".equalsIgnoreCase(forma) || "CASH".equalsIgnoreCase(forma)) ? vt : 0.0;
                                double valorPix = "PIX".equalsIgnoreCase(forma) ? vt : 0.0;
                                double valorCredito = "CREDITO".equalsIgnoreCase(forma) ? vt : 0.0;
                                double valorDebito = "DEBITO".equalsIgnoreCase(forma) ? vt : 0.0;

                                String externalTxId = "PAYXYZ_" + forma + "_" + extId + "_" + dayId;
                                saveOrUpdateTransaction("PAYXYZ", externalTxId, extId, timestamp, forma,
                                        vt, valorCash, valorPix, valorCredito, valorDebito, ve, 0.0);
                                count++;
                            }
                        }
                    }
                }

                double quantPremios = 0.0;
                if (item.hasNonNull("faturamentoPorEstoque") && item.get("faturamentoPorEstoque").isArray()) {
                    for (JsonNode est : item.get("faturamentoPorEstoque")) {
                        if (est.hasNonNull("totalEstoque")) {
                            quantPremios += est.get("totalEstoque").asDouble();
                        }
                    }
                }

                if (quantPremios > 0.0) {
                    String prizeTxId = "PAYXYZ_PREMIOS_" + extId + "_" + dayId;
                    saveOrUpdateTransaction("PAYXYZ", prizeTxId, extId, timestamp, "PREMIOS",
                            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, quantPremios);
                    count++;
                }

                // Limpeza do consolidado antigo para evitar duplicidade
                String legacyExternalTxId = "PAYXYZ_" + extId + "_" + dayId;
                cleanupLegacyConsolidado("PAYXYZ", legacyExternalTxId);
            }
        } catch (Exception e) {
            log.error("Erro na importação de PayXYZ para o dia {}: {}", dayId, e.getMessage());
        }
        return count;
    }

    private int importZapizi(String dayId, String inicio, String fim, LocalDateTime timestamp) {
        int count = 0;
        try {
            JsonNode loginData = zapiziClient.login(properties.getZapizi().getEmail(), properties.getZapizi().getPassword(), null);
            String token = (loginData != null && loginData.hasNonNull("token")) ? loginData.get("token").asText() : null;
            String refreshToken = (loginData != null && loginData.hasNonNull("refreshToken")) ? loginData.get("refreshToken").asText() : null;
            Integer zapOrgId = properties.getZapizi().getOrganizationId();

            // 1. Obter vendas detalhadas individuais via core/sales
            int page = 0;
            int limit = 100;
            boolean hasMore = true;

            while (hasMore) {
                JsonNode salesResponse = zapiziClient.getSales(token, inicio, fim, page, limit, zapOrgId, refreshToken);
                if (salesResponse != null && salesResponse.hasNonNull("data") && salesResponse.get("data").isArray()) {
                    JsonNode dataArray = salesResponse.get("data");
                    for (JsonNode item : dataArray) {
                        String saleId = item.hasNonNull("id") ? item.get("id").asText() : null;
                        String extId = item.hasNonNull("machineUniqueCode") ? item.get("machineUniqueCode").asText().trim() : null;
                        if (saleId == null || extId == null || extId.isBlank()) continue;

                        String provider = item.hasNonNull("paymentProvider") ? item.get("paymentProvider").asText().toUpperCase() : "PIX";
                        double total = item.hasNonNull("total") ? item.get("total").asDouble() : 0.0;
                        String status = item.hasNonNull("status") ? item.get("status").asText() : "PAID";
                        boolean isRefund = "REFUNDED".equalsIgnoreCase(status);
                        double estorno = isRefund ? total : 0.0;

                        LocalDateTime txTime = timestamp;
                        if (item.hasNonNull("created")) {
                            txTime = parseTimestamp(item.get("created").asText(), timestamp);
                        }

                        double cash = ("DINHEIRO".equalsIgnoreCase(provider) || "ESPECIE".equalsIgnoreCase(provider) || "CASH".equalsIgnoreCase(provider)) ? total : 0.0;
                        double pix = "PIX".equalsIgnoreCase(provider) ? total : 0.0;
                        double credito = "CREDITO".equalsIgnoreCase(provider) ? total : 0.0;
                        double debito = "DEBITO".equalsIgnoreCase(provider) ? total : 0.0;

                        String externalTxId = "ZAPIZI_TX_" + saleId;
                        saveOrUpdateTransaction("ZAPIZI", externalTxId, extId, txTime, provider,
                                total, cash, pix, credito, debito, estorno, 0.0);
                        count++;
                    }

                    if (dataArray.size() < limit) {
                        hasMore = false;
                    } else {
                        page++;
                    }
                } else {
                    hasMore = false;
                }
            }

            // 2. Obter dinheiro em espécie (noteiros) e prêmios entregues via telemetria
            JsonNode telemetryData = zapiziClient.getTelemetry(
                    token, inicio, fim, zapOrgId, refreshToken
            );

            if (telemetryData != null && telemetryData.hasNonNull("telemetryList") && telemetryData.get("telemetryList").isArray()) {
                for (JsonNode item : telemetryData.get("telemetryList")) {
                    String extId = null;
                    if (item.hasNonNull("machineUniqueCode") && !item.get("machineUniqueCode").asText().isBlank()) {
                        extId = item.get("machineUniqueCode").asText().trim();
                    }
                    if (extId == null) continue;

                    String locationName = item.hasNonNull("locationName") ? item.get("locationName").asText().trim() : "";
                    if ("Sem Loja".equalsIgnoreCase(locationName)) {
                        Optional<Maquina> optM = maquinaRepository.findByIntegracaoAndExternalId("ZAPIZI", extId);
                        if (optM.isEmpty() || optM.get().getEstabelecimento() == null) {
                            continue;
                        }
                    }

                    double cash = item.hasNonNull("cash") ? item.get("cash").asDouble() : (item.hasNonNull("especie") ? item.get("especie").asDouble() : (item.hasNonNull("dinheiro") ? item.get("dinheiro").asDouble() : 0.0));
                    double delivered = item.hasNonNull("products") ? item.get("products").asDouble() : 0.0;

                    if (cash > 0.0 || delivered > 0.0) {
                        String cashTxId = "ZAPIZI_CASH_" + extId + "_" + dayId;
                        saveOrUpdateTransaction("ZAPIZI", cashTxId, extId, timestamp, "DINHEIRO",
                                cash, cash, 0.0, 0.0, 0.0, 0.0, delivered);
                        count++;
                    }

                    // Limpeza do consolidado antigo da máquina para este dia
                    String legacyExternalTxId = "ZAPIZI_" + extId + "_" + dayId;
                    cleanupLegacyConsolidado("ZAPIZI", legacyExternalTxId);
                }
            }
        } catch (Exception e) {
            log.error("Erro na importação de Zapizi para o dia {}: {}", dayId, e.getMessage());
        }
        return count;
    }

    public int importTransactionsRange(LocalDate start, LocalDate end) {
        log.info("Iniciando importação de transações no intervalo de {} a {}...", start, end);
        int total = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            total += importDailyTransactions(date);
        }
        log.info("Importação de {} a {} finalizada. Total de transações gravadas/atualizadas: {}", start, end, total);
        return total;
    }

    public void ensureTransactionsImported(LocalDate start, LocalDate end) {
        LocalDateTime startDt = start.atStartOfDay();
        LocalDateTime endDt = end.atTime(LocalTime.MAX);
        long count = transacaoRepository.countByTimestampBetween(startDt, endDt);
        if (count == 0) {
            log.info("Nenhuma transação encontrada no banco entre {} e {}. Disparando importação automática...", start, end);
            importTransactionsRange(start, end);
        }
    }

    @Transactional
    public void saveOrUpdateTransaction(
            String integracao,
            String externalTxId,
            String machineExternalId,
            LocalDateTime timestamp,
            String tipoPagamento,
            Double valor,
            Double valorCash,
            Double valorPix,
            Double valorCredito,
            Double valorDebito,
            Double valorEstorno,
            Double quantidadePremios
    ) {
        Optional<Maquina> optMaquina = maquinaRepository.findByIntegracaoAndExternalId(integracao, machineExternalId);
        Maquina maquina = optMaquina.orElse(null);

        // Resolução temporal do estabelecimento no momento exato em que a venda ocorreu
        Estabelecimento estabelecimento = null;
        if (maquina != null) {
            if (historicoVinculoService != null && timestamp != null) {
                estabelecimento = historicoVinculoService.findEstabelecimentoVigente(maquina.getId(), timestamp);
            }
            if (estabelecimento == null) {
                // Fallback para o estabelecimento associado atualmente à máquina se não houver histórico específico
                estabelecimento = maquina.getEstabelecimento();
            }
        }

        Optional<Transacao> existing = transacaoRepository.findByIntegracaoAndExternalTxId(integracao, externalTxId);
        if (existing.isPresent()) {
            Transacao tx = existing.get();
            tx.setValor(valor);
            tx.setValorCash(valorCash);
            tx.setValorPix(valorPix);
            tx.setValorCredito(valorCredito);
            tx.setValorDebito(valorDebito);
            tx.setValorEstorno(valorEstorno);
            tx.setQuantidadePremios(quantidadePremios);
            if (maquina != null) tx.setMaquina(maquina);
            // IMUTABILIDADE HISTÓRICA: Não sobrescreve o estabelecimento anterior se a máquina foi movida posteriormente.
            // Apenas atualiza se a transação estiver sem estabelecimento ou se foi resolvido via histórico temporal.
            if (tx.getEstabelecimento() == null && estabelecimento != null) {
                tx.setEstabelecimento(estabelecimento);
            }
            transacaoRepository.save(tx);
        } else {
            Transacao tx = new Transacao(
                    externalTxId,
                    integracao,
                    maquina,
                    estabelecimento,
                    timestamp,
                    tipoPagamento,
                    valor,
                    valorCash,
                    valorPix,
                    valorCredito,
                    valorDebito,
                    valorEstorno,
                    quantidadePremios
            );
            transacaoRepository.save(tx);
        }

        // Atualização automática de estoque caso haja prêmios saídos
        if (maquina != null && quantidadePremios != null && quantidadePremios > 0) {
            estoqueService.processarSaidaAutomatica(
                    maquina,
                    timestamp != null ? timestamp.toLocalDate() : LocalDate.now(),
                    quantidadePremios.intValue(),
                    integracao,
                    externalTxId
            );
        }
    }
}
