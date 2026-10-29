package br.com.playbox.service;

import br.com.playbox.client.PayXYZClient;
import br.com.playbox.client.VendProMaxClient;
import br.com.playbox.client.ZapiziClient;
import br.com.playbox.config.AppProperties;
import br.com.playbox.dto.ConciliacaoDTO;
import br.com.playbox.dto.ConciliacaoDTO.ItemConciliacaoDTO;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.TransacaoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ConciliacaoService {

    private static final Logger log = LoggerFactory.getLogger(ConciliacaoService.class);
    private static final ZoneId BRASILIA_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final MaquinaRepository maquinaRepository;
    private final TransacaoRepository transacaoRepository;
    private final VendProMaxClient vendClient;
    private final PayXYZClient payxyzClient;
    private final ZapiziClient zapiziClient;
    private final AppProperties properties;

    public ConciliacaoService(
            MaquinaRepository maquinaRepository,
            TransacaoRepository transacaoRepository,
            VendProMaxClient vendClient,
            PayXYZClient payxyzClient,
            ZapiziClient zapiziClient,
            AppProperties properties
    ) {
        this.maquinaRepository = maquinaRepository;
        this.transacaoRepository = transacaoRepository;
        this.vendClient = vendClient;
        this.payxyzClient = payxyzClient;
        this.zapiziClient = zapiziClient;
        this.properties = properties;
    }

    public ConciliacaoDTO conciliar(
            LocalDate dataInicio,
            LocalDate dataFim,
            Long estabelecimentoId,
            Long maquinaId,
            String integracao
    ) {
        if (dataInicio == null) dataInicio = LocalDate.now(BRASILIA_ZONE).withDayOfMonth(1);
        if (dataFim == null) dataFim = LocalDate.now(BRASILIA_ZONE);
        if (dataInicio.isAfter(dataFim)) {
            LocalDate tmp = dataInicio;
            dataInicio = dataFim;
            dataFim = tmp;
        }

        LocalDateTime inicioDt = dataInicio.atStartOfDay();
        LocalDateTime fimDt = dataFim.atTime(LocalTime.MAX);

        String inicioIso = ISO_FORMATTER.format(dataInicio.atStartOfDay(BRASILIA_ZONE));
        String fimIso = ISO_FORMATTER.format(dataFim.atTime(LocalTime.MAX).atZone(BRASILIA_ZONE));

        log.info("Iniciando conciliação de transações entre {} e {} ({} a {})...", dataInicio, dataFim, inicioIso, fimIso);

        List<Maquina> maquinas = maquinaRepository.findAll();
        if (maquinaId != null) {
            maquinas = maquinas.stream().filter(m -> m.getId().equals(maquinaId)).toList();
        }
        if (estabelecimentoId != null) {
            maquinas = maquinas.stream().filter(m -> m.getEstabelecimento() != null && m.getEstabelecimento().getId().equals(estabelecimentoId)).toList();
        }
        if (integracao != null && !integracao.isBlank()) {
            maquinas = maquinas.stream().filter(m -> m.getIntegracao() != null && m.getIntegracao().equalsIgnoreCase(integracao.trim())).toList();
        }

        // Cache pré-carregado para Zapizi para evitar chamadas redundantes em lote
        Map<String, ApiMachineMetrics> zapiziMetricsMap = new HashMap<>();
        boolean zapiziChecked = false;

        // Tokens de autenticação sob demanda
        String vendToken = null;
        String payToken = null;

        List<ItemConciliacaoDTO> itens = new ArrayList<>();
        double totalGeralBanco = 0.0;
        double totalGeralApi = 0.0;
        int totalConciliadas = 0;
        int totalDivergentes = 0;

        for (Maquina m : maquinas) {
            String inte = m.getIntegracao() != null ? m.getIntegracao().toUpperCase() : "";
            String extId = m.getExternalId();
            Estabelecimento est = m.getEstabelecimento();

            // 1. Dados no Banco de Dados local
            List<Transacao> txs = transacaoRepository.findByPeriodAndFilters(inicioDt, fimDt, m.getId(), null);
            double brutoBanco = 0.0;
            double estornoBanco = 0.0;
            double cashBanco = 0.0;
            double pixBanco = 0.0;
            double cartaoBanco = 0.0;
            int countValidas = 0;

            for (Transacao t : txs) {
                if (Boolean.TRUE.equals(t.getTeste()) && (t.getValorTeste() == null || t.getValorTeste() <= 0.0)) {
                    continue; // 100% teste desconsiderado
                }
                countValidas++;
                brutoBanco += t.getValorEfetivo();
                cashBanco += t.getValorCashEfetivo();
                pixBanco += t.getValorPixEfetivo();
                cartaoBanco += (t.getValorCreditoEfetivo() + t.getValorDebitoEfetivo());
                estornoBanco += t.getValorEstornoEfetivo();
            }
            double totalBanco = Math.round((brutoBanco - estornoBanco) * 100.0) / 100.0;

            // 2. Consulta de valores na API da integradora
            ApiMachineMetrics apiMetrics = new ApiMachineMetrics();
            try {
                if ("VENDPROMAX".equals(inte)) {
                    if (vendToken == null) {
                        vendToken = vendClient.login(properties.getVendpromax().getEmail(), properties.getVendpromax().getPassword());
                    }
                    apiMetrics = fetchVendProMaxMetrics(vendToken, extId, inicioIso, fimIso);
                } else if ("ZAPIZI".equals(inte)) {
                    if (!zapiziChecked) {
                        zapiziMetricsMap = fetchAllZapiziMetrics(inicioIso, fimIso);
                        zapiziChecked = true;
                    }
                    apiMetrics = zapiziMetricsMap.getOrDefault(extId != null ? extId.trim() : "", new ApiMachineMetrics());
                } else if ("PAYXYZ".equals(inte)) {
                    if (payToken == null) {
                        payToken = payxyzClient.login(properties.getPayxyz().getEmail(), properties.getPayxyz().getPassword(), true);
                    }
                    apiMetrics = fetchPayXYZMetrics(payToken, extId, inicioIso, fimIso);
                }
            } catch (Exception e) {
                log.warn("Erro ao consultar integradora {} para máquina {}: {}", inte, extId, e.getMessage());
                apiMetrics.erro = "Falha de comunicação com a API: " + e.getMessage();
            }

            double totalApi = Math.round(apiMetrics.total * 100.0) / 100.0;
            double diferenca = Math.round((totalBanco - totalApi) * 100.0) / 100.0;

            String status;
            String mensagem;

            if (apiMetrics.erro != null) {
                status = "DIVERGENTE";
                mensagem = apiMetrics.erro;
                totalDivergentes++;
            } else if (Math.abs(diferenca) < 0.05) {
                status = "CONCILIADO";
                mensagem = (totalBanco == 0.0 && totalApi == 0.0) ? "Sem movimentação no período" : "Valores 100% conciliados";
                totalConciliadas++;
            } else {
                status = "DIVERGENTE";
                mensagem = String.format("Divergência de R$ %.2f (Banco: R$ %.2f | API: R$ %.2f)", diferenca, totalBanco, totalApi);
                totalDivergentes++;
            }

            totalGeralBanco += totalBanco;
            totalGeralApi += totalApi;

            itens.add(new ItemConciliacaoDTO(
                    m.getId(),
                    m.getNomeIntegracao() != null ? m.getNomeIntegracao() : ("Máquina #" + m.getId()),
                    extId,
                    est != null ? est.getId() : null,
                    est != null ? est.getNome() : "Sem Estabelecimento",
                    est != null ? est.getCidade() : "",
                    inte,
                    totalBanco,
                    totalApi,
                    diferenca,
                    Math.round(cashBanco * 100.0) / 100.0,
                    Math.round(apiMetrics.cash * 100.0) / 100.0,
                    Math.round(pixBanco * 100.0) / 100.0,
                    Math.round(apiMetrics.pix * 100.0) / 100.0,
                    Math.round(cartaoBanco * 100.0) / 100.0,
                    Math.round(apiMetrics.cartao * 100.0) / 100.0,
                    Math.round(estornoBanco * 100.0) / 100.0,
                    Math.round(apiMetrics.estorno * 100.0) / 100.0,
                    countValidas,
                    status,
                    mensagem
            ));
        }

        totalGeralBanco = Math.round(totalGeralBanco * 100.0) / 100.0;
        totalGeralApi = Math.round(totalGeralApi * 100.0) / 100.0;
        double diferencaGeral = Math.round((totalGeralBanco - totalGeralApi) * 100.0) / 100.0;
        String statusGeral = (Math.abs(diferencaGeral) < 0.05 && totalDivergentes == 0) ? "CONCILIADO" : "DIVERGENTE";

        return new ConciliacaoDTO(
                dataInicio.toString(),
                dataFim.toString(),
                totalGeralBanco,
                totalGeralApi,
                diferencaGeral,
                statusGeral,
                maquinas.size(),
                totalConciliadas,
                totalDivergentes,
                itens
        );
    }

    private ApiMachineMetrics fetchVendProMaxMetrics(String token, String extId, String inicio, String fim) {
        ApiMachineMetrics m = new ApiMachineMetrics();
        if (token == null || extId == null) return m;

        JsonNode resp = vendClient.getPagamentosPeriodo(token, extId, inicio, fim);
        if (resp != null && resp.hasNonNull("pagamentos") && resp.get("pagamentos").isArray()) {
            for (JsonNode p : resp.get("pagamentos")) {
                double val = p.hasNonNull("valor") ? p.get("valor").asDouble() : 0.0;
                String tipo = p.hasNonNull("tipo") ? p.get("tipo").asText().toUpperCase() : "PIX";
                boolean est = p.hasNonNull("estornado") && p.get("estornado").asBoolean();

                if (est) {
                    m.estorno += val;
                } else {
                    m.total += val;
                    if ("CASH".equalsIgnoreCase(tipo)) m.cash += val;
                    else if ("PIX".equalsIgnoreCase(tipo)) m.pix += val;
                    else m.cartao += val;
                }
            }
        }
        return m;
    }

    private Map<String, ApiMachineMetrics> fetchAllZapiziMetrics(String inicio, String fim) {
        Map<String, ApiMachineMetrics> map = new HashMap<>();
        try {
            JsonNode loginData = zapiziClient.login(properties.getZapizi().getEmail(), properties.getZapizi().getPassword(), null);
            String token = (loginData != null && loginData.hasNonNull("token")) ? loginData.get("token").asText() : null;
            String refreshToken = (loginData != null && loginData.hasNonNull("refreshToken")) ? loginData.get("refreshToken").asText() : null;
            Integer zapOrgId = properties.getZapizi().getOrganizationId();

            if (token == null) return map;

            // 1. Vendas detalhadas individuais
            int page = 0;
            int limit = 100;
            boolean hasMore = true;

            while (hasMore) {
                JsonNode salesResponse = zapiziClient.getSales(token, inicio, fim, page, limit, zapOrgId, refreshToken);
                if (salesResponse != null && salesResponse.hasNonNull("data") && salesResponse.get("data").isArray()) {
                    JsonNode dataArray = salesResponse.get("data");
                    for (JsonNode item : dataArray) {
                        String extId = item.hasNonNull("machineUniqueCode") ? item.get("machineUniqueCode").asText().trim() : null;
                        if (extId == null || extId.isBlank()) continue;

                        ApiMachineMetrics metrics = map.computeIfAbsent(extId, k -> new ApiMachineMetrics());
                        double total = item.hasNonNull("total") ? item.get("total").asDouble() : 0.0;
                        String status = item.hasNonNull("status") ? item.get("status").asText() : "PAID";
                        String provider = item.hasNonNull("paymentProvider") ? item.get("paymentProvider").asText().toUpperCase() : "PIX";

                        if ("REFUNDED".equalsIgnoreCase(status)) {
                            metrics.estorno += total;
                        } else {
                            metrics.total += total;
                            if ("DINHEIRO".equalsIgnoreCase(provider) || "ESPECIE".equalsIgnoreCase(provider) || "CASH".equalsIgnoreCase(provider)) {
                                metrics.cash += total;
                            } else if ("PIX".equalsIgnoreCase(provider)) {
                                metrics.pix += total;
                            } else {
                                metrics.cartao += total;
                            }
                        }
                    }

                    if (dataArray.size() < limit) hasMore = false;
                    else page++;
                } else {
                    hasMore = false;
                }
            }

            // 2. Telemetria para noteiros (cash em espécie)
            JsonNode telemetry = zapiziClient.getTelemetry(token, inicio, fim, zapOrgId, refreshToken);
            if (telemetry != null && telemetry.hasNonNull("telemetryList") && telemetry.get("telemetryList").isArray()) {
                for (JsonNode t : telemetry.get("telemetryList")) {
                    String extId = t.hasNonNull("machineUniqueCode") ? t.get("machineUniqueCode").asText().trim() : null;
                    if (extId == null || extId.isBlank()) continue;

                    ApiMachineMetrics metrics = map.computeIfAbsent(extId, k -> new ApiMachineMetrics());
                    double cash = t.hasNonNull("cash") ? t.get("cash").asDouble() : 0.0;
                    metrics.cash += cash;
                    metrics.total += cash;
                }
            }
        } catch (Exception e) {
            log.error("Erro ao obter métricas consolidadas da Zapizi para conciliação: {}", e.getMessage());
        }
        return map;
    }

    private ApiMachineMetrics fetchPayXYZMetrics(String token, String extId, String inicio, String fim) {
        ApiMachineMetrics m = new ApiMachineMetrics();
        if (token == null || extId == null) return m;

        List<JsonNode> reports = payxyzClient.getReport(token, inicio, fim, List.of(extId));
        if (reports != null && !reports.isEmpty()) {
            JsonNode item = reports.get(0);
            if (item.hasNonNull("vendasByPaymentType") && item.get("vendasByPaymentType").isArray()) {
                for (JsonNode p : item.get("vendasByPaymentType")) {
                    String forma = p.hasNonNull("formaDePagamento") ? p.get("formaDePagamento").asText().toUpperCase() : "PIX";
                    JsonNode dados = p.hasNonNull("dados") ? p.get("dados") : null;
                    if (dados != null) {
                        double vt = dados.hasNonNull("valorTotal") ? dados.get("valorTotal").asDouble() : 0.0;
                        double ve = dados.hasNonNull("valorTotalEstorno") ? dados.get("valorTotalEstorno").asDouble() : 0.0;

                        m.total += (vt - ve);
                        m.estorno += ve;

                        if ("ESPECIE".equalsIgnoreCase(forma) || "DINHEIRO".equalsIgnoreCase(forma) || "CASH".equalsIgnoreCase(forma)) {
                            m.cash += vt;
                        } else if ("PIX".equalsIgnoreCase(forma)) {
                            m.pix += vt;
                        } else {
                            m.cartao += vt;
                        }
                    }
                }
            }
        }
        return m;
    }

    private static class ApiMachineMetrics {
        double total = 0.0;
        double cash = 0.0;
        double pix = 0.0;
        double cartao = 0.0;
        double estorno = 0.0;
        String erro = null;
    }
}
