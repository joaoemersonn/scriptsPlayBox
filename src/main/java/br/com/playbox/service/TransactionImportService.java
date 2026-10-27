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

    private int importVendProMax(String dayId, String inicio, String fim, LocalDateTime timestamp) {
        int count = 0;
        try {
            String token = vendClient.login(properties.getVendpromax().getEmail(), properties.getVendpromax().getPassword());
            List<JsonNode> machines = vendClient.getMachines(token);

            for (JsonNode m : machines) {
                if (!m.hasNonNull("id")) continue;
                String extId = m.get("id").asText();

                JsonNode cash = vendClient.getReport(token, "relatorio-01-cash", extId, inicio, fim, false);
                JsonNode estornos = vendClient.getReport(token, "relatorio-04-estornos", extId, inicio, fim, false);
                JsonNode pagamentos = vendClient.getReport(token, "relatorio-03-pagamentos", extId, inicio, fim, false);
                JsonNode premios = vendClient.getReport(token, "relatorio-premios", extId, inicio, fim, false);

                double valorCash = 0.0;
                if (cash != null) {
                    if (cash.hasNonNull("valor")) valorCash = cash.get("valor").asDouble();
                    else if (cash.hasNonNull("total")) valorCash = cash.get("total").asDouble();
                    else if (cash.hasNonNull("cash")) valorCash = cash.get("cash").asDouble();
                    else if (cash.hasNonNull("valorTotal")) valorCash = cash.get("valorTotal").asDouble();
                }
                double valorEstornos = (estornos != null && estornos.hasNonNull("valor")) ? estornos.get("valor").asDouble() : 0.0;
                double valorPix = (pagamentos != null && pagamentos.hasNonNull("pix")) ? pagamentos.get("pix").asDouble() : 0.0;
                double valorCredito = (pagamentos != null && pagamentos.hasNonNull("credito")) ? pagamentos.get("credito").asDouble() : 0.0;
                double valorDebito = (pagamentos != null && pagamentos.hasNonNull("debito")) ? pagamentos.get("debito").asDouble() : 0.0;
                double quantPremios = (premios != null && premios.hasNonNull("quantidade")) ? premios.get("quantidade").asDouble() : 0.0;

                double totalValor = valorCash + valorPix + valorCredito + valorDebito;
                if (totalValor == 0.0 && valorEstornos == 0.0 && quantPremios == 0.0) {
                    continue; // Sem movimentação
                }

                String externalTxId = "VENDPROMAX_" + extId + "_" + dayId;
                saveOrUpdateTransaction("VENDPROMAX", externalTxId, extId, timestamp, "CONSOLIDADO",
                        totalValor, valorCash, valorPix, valorCredito, valorDebito, valorEstornos, quantPremios);
                count++;
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

                double valorTotal = 0.0;
                double valorEstornos = 0.0;
                double valorEspecie = 0.0;
                double valorPix = 0.0;
                double valorCredito = 0.0;
                double valorDebito = 0.0;
                double quantPremios = 0.0;

                if (item.hasNonNull("vendasByPaymentType") && item.get("vendasByPaymentType").isArray()) {
                    for (JsonNode p : item.get("vendasByPaymentType")) {
                        String forma = p.hasNonNull("formaDePagamento") ? p.get("formaDePagamento").asText() : "";
                        JsonNode dados = p.hasNonNull("dados") ? p.get("dados") : null;
                        if (dados != null) {
                            double vt = dados.hasNonNull("valorTotal") ? dados.get("valorTotal").asDouble() : 0.0;
                            double ve = dados.hasNonNull("valorTotalEstorno") ? dados.get("valorTotalEstorno").asDouble() : 0.0;
                            valorTotal += vt;
                            valorEstornos += ve;

                            if ("especie".equalsIgnoreCase(forma) || "dinheiro".equalsIgnoreCase(forma) || "cash".equalsIgnoreCase(forma)) valorEspecie += vt;
                            else if ("pix".equalsIgnoreCase(forma)) valorPix += vt;
                            else if ("credito".equalsIgnoreCase(forma)) valorCredito += vt;
                            else if ("debito".equalsIgnoreCase(forma)) valorDebito += vt;
                        }
                    }
                }

                if (item.hasNonNull("faturamentoPorEstoque") && item.get("faturamentoPorEstoque").isArray()) {
                    for (JsonNode est : item.get("faturamentoPorEstoque")) {
                        if (est.hasNonNull("totalEstoque")) {
                            quantPremios += est.get("totalEstoque").asDouble();
                        }
                    }
                }

                if (valorTotal == 0.0 && valorEstornos == 0.0 && quantPremios == 0.0) {
                    continue;
                }

                String externalTxId = "PAYXYZ_" + extId + "_" + dayId;
                saveOrUpdateTransaction("PAYXYZ", externalTxId, extId, timestamp, "CONSOLIDADO",
                        valorTotal, valorEspecie, valorPix, valorCredito, valorDebito, valorEstornos, quantPremios);
                count++;
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
                    double pix = item.hasNonNull("pix") ? item.get("pix").asDouble() : 0.0;
                    double card = item.hasNonNull("card") ? item.get("card").asDouble() : 0.0;
                    double total = item.hasNonNull("total") ? item.get("total").asDouble() : (cash + pix + card);
                    double delivered = item.hasNonNull("products") ? item.get("products").asDouble() : 0.0;

                    if (total == 0.0 && delivered == 0.0) {
                        continue;
                    }

                    String externalTxId = "ZAPIZI_" + extId + "_" + dayId;
                    saveOrUpdateTransaction("ZAPIZI", externalTxId, extId, timestamp, "CONSOLIDADO",
                            total, cash, pix, card, 0.0, 0.0, delivered);
                    count++;
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
