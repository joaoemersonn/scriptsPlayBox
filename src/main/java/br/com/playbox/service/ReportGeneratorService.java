package br.com.playbox.service;

import br.com.playbox.client.*;
import br.com.playbox.config.AppProperties;
import br.com.playbox.model.MachineReport;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ReportGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(ReportGeneratorService.class);

    private final VendProMaxClient vendClient;
    private final PayXYZClient payxyzClient;
    private final ZapiziClient zapiziClient;
    private final WhatsAppSender sender;
    private final AppProperties properties;
    private final RelatorioService relatorioService;
    private final TransactionImportService transactionImportService;
    private final ConfiguracaoService configuracaoService;

    @Autowired
    public ReportGeneratorService(
            VendProMaxClient vendClient,
            PayXYZClient payxyzClient,
            ZapiziClient zapiziClient,
            AppProperties properties,
            RelatorioService relatorioService,
            TransactionImportService transactionImportService,
            ConfiguracaoService configuracaoService
    ) {
        this.vendClient = vendClient;
        this.payxyzClient = payxyzClient;
        this.zapiziClient = zapiziClient;
        this.properties = properties;
        this.relatorioService = relatorioService;
        this.transactionImportService = transactionImportService;
        this.configuracaoService = configuracaoService;

        AppProperties.Whatsapp wa = properties != null ? properties.getWhatsapp() : null;
        String baseUrl = wa != null && wa.getBaseUrl() != null ? wa.getBaseUrl() : "http://whatsapp_bot:3035";
        String sendPath = wa != null && wa.getSendPath() != null ? wa.getSendPath() : "/api/messages/send";
        String chatId = wa != null && wa.getReportChatId() != null ? wa.getReportChatId() : "";
        String apiKey = wa != null && wa.getApiKey() != null ? wa.getApiKey() : "";

        this.sender = new WhatsAppSender(
                baseUrl,
                apiKey,
                chatId,
                sendPath,
                properties != null ? properties.getTimeoutSeconds() : 30,
                null
        );
    }

    public ReportGeneratorService(
            VendProMaxClient vendClient,
            PayXYZClient payxyzClient,
            ZapiziClient zapiziClient,
            AppProperties properties
    ) {
        this(vendClient, payxyzClient, zapiziClient, properties, null, null, null);
    }

    public record ReportPeriod(String title, String startDate, String endDate) {}

    public ReportPeriod buildReportDates(String periodo, ZoneId zoneId) {
        ZonedDateTime hoje = ZonedDateTime.now(zoneId);
        ZonedDateTime ontem = hoje.minusDays(1);

        String titulo;
        ZonedDateTime inicio;
        ZonedDateTime fim;

        DateTimeFormatter dayMonthYear = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        DateTimeFormatter dayMonth = DateTimeFormatter.ofPattern("dd/MM");

        if ("24h-anterior".equalsIgnoreCase(periodo)) {
            inicio = ontem.withHour(0).withMinute(0).withSecond(0).withNano(0);
            fim = ontem.withHour(23).withMinute(59).withSecond(59).withNano(999_000_000);
            titulo = "Relatório de " + ontem.format(dayMonthYear);
        } else if ("12h-atual".equalsIgnoreCase(periodo)) {
            inicio = hoje.minusHours(12);
            fim = hoje;
            titulo = "Relatório das Últimas 12h (" + hoje.format(dayMonthYear) + ")";
        } else {
            // "mes" or any other period
            inicio = ontem.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
            fim = ontem.withHour(23).withMinute(59).withSecond(59).withNano(999_000_000);
            String mesNome = getMonthNamePt(ontem.getMonthValue());
            titulo = "Relatório do Mês de " + mesNome + " (até " + ontem.format(dayMonth) + ")";
        }

        DateTimeFormatter isoFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        String inicioStr = inicio.format(isoFormatter);
        String fimStr = fim.format(isoFormatter);

        return new ReportPeriod(titulo, inicioStr, fimStr);
    }

    public static String getMonthNamePt(int monthNumber) {
        return switch (monthNumber) {
            case 1 -> "Janeiro";
            case 2 -> "Fevereiro";
            case 3 -> "Março";
            case 4 -> "Abril";
            case 5 -> "Maio";
            case 6 -> "Junho";
            case 7 -> "Julho";
            case 8 -> "Agosto";
            case 9 -> "Setembro";
            case 10 -> "Outubro";
            case 11 -> "Novembro";
            case 12 -> "Dezembro";
            default -> "";
        };
    }

    public List<MachineReport> buildVendProMaxReport(String inicio, String fim) {
        String token = vendClient.login(properties.getVendpromax().getEmail(), properties.getVendpromax().getPassword());
        List<JsonNode> machines = vendClient.getMachines(token);
        List<MachineReport> reportList = new ArrayList<>();

        for (JsonNode machine : machines) {
            if (!machine.hasNonNull("id")) {
                String nome = machine.hasNonNull("nome") ? machine.get("nome").asText() : "N/A";
                log.info("Pulando máquina sem ID: {}", nome);
                continue;
            }

            Object maquinaId = machine.get("id").asText();
            String nome = machine.hasNonNull("nome") ? machine.get("nome").asText().trim() : "N/A";

            JsonNode cash = vendClient.getReport(token, "relatorio-01-cash", maquinaId, inicio, fim, false);
            JsonNode estornos = vendClient.getReport(token, "relatorio-04-estornos", maquinaId, inicio, fim, false);
            JsonNode pagamentos = vendClient.getReport(token, "relatorio-03-pagamentos", maquinaId, inicio, fim, false);
            JsonNode premios = vendClient.getReport(token, "relatorio-premios", maquinaId, inicio, fim, false);

            double valorCash = (cash != null && cash.hasNonNull("valor")) ? cash.get("valor").asDouble() : 0.0;
            double valorEstornos = (estornos != null && estornos.hasNonNull("valor")) ? estornos.get("valor").asDouble() : 0.0;
            double valorPix = (pagamentos != null && pagamentos.hasNonNull("pix")) ? pagamentos.get("pix").asDouble() : 0.0;
            double valorCredito = (pagamentos != null && pagamentos.hasNonNull("credito")) ? pagamentos.get("credito").asDouble() : 0.0;
            double valorDebito = (pagamentos != null && pagamentos.hasNonNull("debito")) ? pagamentos.get("debito").asDouble() : 0.0;
            double quantidadePremios = (premios != null && premios.hasNonNull("quantidade")) ? premios.get("quantidade").asDouble() : 0.0;

            MachineReport report = new MachineReport(
                    maquinaId,
                    nome,
                    valorEstornos,
                    valorCash,
                    valorPix,
                    valorCredito,
                    valorDebito,
                    quantidadePremios
            );
            reportList.add(report);
        }

        return reportList;
    }

    public List<MachineReport> buildPayXYZReport(String inicio, String fim) {
        String token = payxyzClient.login(properties.getPayxyz().getEmail(), properties.getPayxyz().getPassword(), true);
        List<JsonNode> machines = payxyzClient.getMachines(token);

        List<Object> maquinaIds = new ArrayList<>();
        for (JsonNode machine : machines) {
            if (machine.hasNonNull("id")) {
                maquinaIds.add(machine.get("id").asText());
            }
        }

        if (maquinaIds.isEmpty()) {
            return List.of();
        }

        List<JsonNode> rawReports = payxyzClient.getReport(token, inicio, fim, maquinaIds);
        List<MachineReport> reportList = new ArrayList<>();

        for (JsonNode item : rawReports) {
            JsonNode maquina = item.hasNonNull("maquina") ? item.get("maquina") : null;
            if (maquina == null || !maquina.hasNonNull("id")) {
                continue;
            }

            Object maquinaId = maquina.get("id").asText();
            String nome = machineName(maquina);

            double valorEstornos = 0.0;
            double valorEspecie = 0.0;
            double valorPix = 0.0;
            double valorCredito = 0.0;
            double valorDebito = 0.0;
            double quantidadePremios = 0.0;

            if (item.hasNonNull("vendasByPaymentType") && item.get("vendasByPaymentType").isArray()) {
                for (JsonNode paymentData : item.get("vendasByPaymentType")) {
                    String forma = paymentData.hasNonNull("formaDePagamento") ? paymentData.get("formaDePagamento").asText() : "";
                    JsonNode dados = paymentData.hasNonNull("dados") ? paymentData.get("dados") : null;
                    double valorTotal = (dados != null && dados.hasNonNull("valorTotal")) ? dados.get("valorTotal").asDouble() : 0.0;
                    double valorTotalEstorno = (dados != null && dados.hasNonNull("valorTotalEstorno")) ? dados.get("valorTotalEstorno").asDouble() : 0.0;

                    valorEstornos += valorTotalEstorno;

                    if ("especie".equalsIgnoreCase(forma)) {
                        valorEspecie += valorTotal;
                    } else if ("pix".equalsIgnoreCase(forma)) {
                        valorPix += valorTotal;
                    } else if ("credito".equalsIgnoreCase(forma)) {
                        valorCredito += valorTotal;
                    } else if ("debito".equalsIgnoreCase(forma)) {
                        valorDebito += valorTotal;
                    }
                }
            }

            if (item.hasNonNull("vendasByDay") && item.get("vendasByDay").isArray()) {
                for (JsonNode dayData : item.get("vendasByDay")) {
                    if (dayData.hasNonNull("dados") && dayData.get("dados").hasNonNull("valorTotalEstorno")) {
                        valorEstornos += dayData.get("dados").get("valorTotalEstorno").asDouble();
                    }
                }
            }

            if (item.hasNonNull("faturamentoPorEstoque") && item.get("faturamentoPorEstoque").isArray()) {
                for (JsonNode estoqueData : item.get("faturamentoPorEstoque")) {
                    if (estoqueData.hasNonNull("totalEstoque")) {
                        quantidadePremios += estoqueData.get("totalEstoque").asDouble();
                    }
                }
            }

            MachineReport report = new MachineReport(
                    maquinaId,
                    nome,
                    valorEstornos,
                    valorEspecie,
                    valorPix,
                    valorCredito,
                    valorDebito,
                    quantidadePremios
            );
            reportList.add(report);
        }

        return reportList;
    }

    private String machineName(JsonNode maquina) {
        return maquina.hasNonNull("nome") ? maquina.get("nome").asText().trim() : "N/A";
    }

    public List<MachineReport> buildZapiziReport(String inicio, String fim) {
        JsonNode loginData = zapiziClient.login(properties.getZapizi().getEmail(), properties.getZapizi().getPassword(), null);
        String token = (loginData != null && loginData.hasNonNull("token")) ? loginData.get("token").asText() : null;
        String refreshToken = (loginData != null && loginData.hasNonNull("refreshToken")) ? loginData.get("refreshToken").asText() : null;
        Integer organizationId = properties.getZapizi().getOrganizationId();

        JsonNode reportData = zapiziClient.getReports(
                token,
                inicio,
                fim,
                null,
                null,
                "",
                "",
                0,
                1000,
                organizationId,
                refreshToken
        );

        List<MachineReport> reportList = new ArrayList<>();
        if (reportData != null && reportData.hasNonNull("data") && reportData.get("data").isArray()) {
            for (JsonNode item : reportData.get("data")) {
                String nome = "N/A";
                if (item.hasNonNull("location") && !item.get("location").asText().isBlank()) {
                    nome = item.get("location").asText();
                } else if (item.hasNonNull("machineUniqueCode") && !item.get("machineUniqueCode").asText().isBlank()) {
                    nome = item.get("machineUniqueCode").asText();
                }

                Object idMaquina = null;
                if (item.hasNonNull("machineUniqueCode") && !item.get("machineUniqueCode").asText().isBlank()) {
                    idMaquina = item.get("machineUniqueCode").asText();
                } else if (item.hasNonNull("location") && !item.get("location").asText().isBlank()) {
                    idMaquina = item.get("location").asText();
                }

                double refund = item.hasNonNull("refund") ? item.get("refund").asDouble() : 0.0;
                double cash = item.hasNonNull("cash") ? item.get("cash").asDouble() : 0.0;
                double pix = item.hasNonNull("pix") ? item.get("pix").asDouble() : 0.0;
                double credit = item.hasNonNull("credit") ? item.get("credit").asDouble() : 0.0;
                double debit = item.hasNonNull("debit") ? item.get("debit").asDouble() : 0.0;
                double delivered = item.hasNonNull("productsDelivered") ? item.get("productsDelivered").asDouble() : 0.0;

                MachineReport report = new MachineReport(
                        idMaquina,
                        nome.trim(),
                        refund,
                        cash,
                        pix,
                        credit,
                        debit,
                        delivered
                );
                reportList.add(report);
            }
        }

        return reportList;
    }

    @SafeVarargs
    public final List<MachineReport> unifyReports(List<MachineReport>... reports) {
        Map<String, MachineReport> unified = new LinkedHashMap<>();

        for (List<MachineReport> reportList : reports) {
            if (reportList == null) continue;
            for (MachineReport report : reportList) {
                String key = String.valueOf(report.getIdMaquina()) + "::" + report.getNomeMaquina();
                MachineReport target = unified.computeIfAbsent(key, k -> new MachineReport(report.getIdMaquina(), report.getNomeMaquina()));

                target.setValorEstornos(target.getValorEstornos() + report.getValorEstornos());
                target.setValorEspecie(target.getValorEspecie() + report.getValorEspecie());
                target.setValorPix(target.getValorPix() + report.getValorPix());
                target.setValorCredito(target.getValorCredito() + report.getValorCredito());
                target.setValorDebito(target.getValorDebito() + report.getValorDebito());
                target.setQuantidadePremios(target.getQuantidadePremios() + report.getQuantidadePremios());
                target.updateTotals();
            }
        }

        List<MachineReport> result = new ArrayList<>(unified.values());
        result.sort(Comparator
                .comparing(MachineReport::getNomeMaquina, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(r -> String.valueOf(r.getIdMaquina()))
        );

        return result;
    }

    public static String formatCurrency(double value) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.of("pt", "BR"));
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        DecimalFormat df = new DecimalFormat("R$ #,##0.00", symbols);
        return df.format(value);
    }

    /**
     * Formatação simplificada compatível com a lista de MachineReports.
     */
    public String formatWhatsAppMessage(List<MachineReport> reportData, String title) {
        List<String> lines = new ArrayList<>();
        lines.add("*📊 " + title + " 📊*");

        if (reportData == null || reportData.isEmpty()) {
            lines.add("Nenhum dado disponível para este período.");
            return String.join("\n", lines);
        }

        double totalValor = reportData.stream().mapToDouble(MachineReport::getValorTotal).sum();
        double totalPremios = reportData.stream().mapToDouble(MachineReport::getQuantidadePremios).sum();
        double receitaGeral = totalPremios > 0.0 ? (totalValor / totalPremios) : 0.0;

        lines.add("");
        lines.add("*Resumo Geral:*");
        lines.add("💰 Valor Total: " + formatCurrency(totalValor));
        lines.add("🎁 Prêmios Liberados: " + ((int) totalPremios) + " unidades");
        lines.add("💸 Receita/Prêmio: " + formatCurrency(receitaGeral));

        return String.join("\n", lines).trim();
    }

    /**
     * Formatação detalhada com suporte a "Resumo Geral" e "Detalhado por Estabelecimento".
     * Regra do usuário: Se o estabelecimento tiver apenas 1 máquina, a máquina fica oculta.
     * Só detalha cada máquina se o estabelecimento tiver 2 ou mais máquinas.
     */
    public String formatWhatsAppMessageDetailed(Map<String, Object> relatorioData, String title, String tipoExibicao) {
        List<String> lines = new ArrayList<>();
        lines.add("*📊 " + title + " 📊*");

        if (relatorioData == null || !relatorioData.containsKey("resumoGeral")) {
            lines.add("Nenhum dado disponível para este período.");
            return String.join("\n", lines);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> resumo = (Map<String, Object>) relatorioData.get("resumoGeral");
        double bruto = resumo.containsKey("faturamentoBruto") ? ((Number) resumo.get("faturamentoBruto")).doubleValue() : 0.0;
        double liquido = resumo.containsKey("faturamentoLiquido") ? ((Number) resumo.get("faturamentoLiquido")).doubleValue() : bruto;
        double estornos = resumo.containsKey("totalEstornos") ? ((Number) resumo.get("totalEstornos")).doubleValue() : 0.0;
        double premios = resumo.containsKey("quantidadePremios") ? ((Number) resumo.get("quantidadePremios")).doubleValue() : 0.0;
        double receitaPorPremio = premios > 0.0 ? (bruto / premios) : 0.0;
        double testesDesc = resumo.containsKey("totalTestesDesconsiderados") ? ((Number) resumo.get("totalTestesDesconsiderados")).doubleValue() : 0.0;

        @SuppressWarnings("unchecked")
        Map<String, Object> formas = resumo.containsKey("formasPagamento") ? (Map<String, Object>) resumo.get("formasPagamento") : Map.of();
        double especie = formas.containsKey("especie") ? ((Number) formas.get("especie")).doubleValue() : 0.0;
        double pix = formas.containsKey("pix") ? ((Number) formas.get("pix")).doubleValue() : 0.0;
        double cartao = (formas.containsKey("credito") ? ((Number) formas.get("credito")).doubleValue() : 0.0)
                + (formas.containsKey("debito") ? ((Number) formas.get("debito")).doubleValue() : 0.0);

        lines.add("");
        lines.add("*Resumo Geral:*");
        lines.add("💰 Faturamento Total: " + formatCurrency(bruto));
        lines.add("💵 Espécie: " + formatCurrency(especie) + " | 📱 PIX: " + formatCurrency(pix) + " | 💳 Cartão: " + formatCurrency(cartao));
        if (estornos > 0) {
            lines.add("↩️ Estornos: -" + formatCurrency(estornos) + " (Líq.: " + formatCurrency(liquido) + ")");
        }
        if (testesDesc > 0) {
            lines.add("🧪 Testes Desconsiderados: -" + formatCurrency(testesDesc));
        }
        lines.add("🎁 Prêmios Liberados: " + ((int) premios) + " unidades");
        lines.add("💸 Média/Prêmio: " + formatCurrency(receitaPorPremio));

        // Se o modo for DETALHADO_ESTABELECIMENTO:
        if ("DETALHADO_ESTABELECIMENTO".equalsIgnoreCase(tipoExibicao) && relatorioData.containsKey("porEstabelecimento")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> estabelecimentos = (List<Map<String, Object>>) relatorioData.get("porEstabelecimento");

            if (estabelecimentos != null && !estabelecimentos.isEmpty()) {
                lines.add("");
                lines.add("━━━━━━━━━━━━━━━━━━━━━━");
                lines.add("*📍 Detalhamento por Estabelecimento:*");

                for (Map<String, Object> est : estabelecimentos) {
                    String nome = (String) est.getOrDefault("nome", "Sem Estabelecimento");
                    String cidade = (String) est.getOrDefault("cidade", "");
                    double fatEst = est.containsKey("faturamentoBruto") ? ((Number) est.get("faturamentoBruto")).doubleValue() : 0.0;
                    double premEst = est.containsKey("quantidadePremios") ? ((Number) est.get("quantidadePremios")).doubleValue() : 0.0;

                    String estHeader = "📍 *" + nome + "*";
                    if (cidade != null && !cidade.isBlank()) {
                        estHeader += " (" + cidade + ")";
                    }
                    lines.add("");
                    lines.add(estHeader);
                    lines.add("   💰 Faturamento: " + formatCurrency(fatEst) + " | 🎁 " + ((int) premEst) + " prêmios");

                    // Regra do usuário: Se o estabelecimento tiver apenas 1 máquina, a máquina fica oculta.
                    // Só detalha cada máquina se o estabelecimento tiver mais de uma (> 1).
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> maquinas = (List<Map<String, Object>>) est.get("maquinas");

                    if (maquinas != null && maquinas.size() > 1) {
                        for (Map<String, Object> m : maquinas) {
                            String mNome = (String) m.getOrDefault("nome", "Máquina");
                            double mBruto = m.containsKey("faturamentoBruto") ? ((Number) m.get("faturamentoBruto")).doubleValue() : 0.0;
                            double mPremios = m.containsKey("quantidadePremios") ? ((Number) m.get("quantidadePremios")).doubleValue() : 0.0;
                            lines.add("   • " + mNome + ": " + formatCurrency(mBruto) + " (" + ((int) mPremios) + " prêmios)");
                        }
                    }
                }
            }
        }

        return String.join("\n", lines).trim();
    }

    public synchronized void generateAndSendReport(String periodo) {
        ZoneId zoneId = ZoneId.of("America/Sao_Paulo");
        ReportPeriod reportPeriod = buildReportDates(periodo, zoneId);

        String tipoExibicao = configuracaoService != null ? configuracaoService.getTipoExibicaoRelatorio() : "DETALHADO_ESTABELECIMENTO";
        String targetChatId = configuracaoService != null ? configuracaoService.getReportChatId() : null;

        log.info("Iniciando geração de relatório. Período: {}, Título: {}, Modo: {}, Destino WhatsApp: {}",
                periodo, reportPeriod.title(), tipoExibicao, targetChatId != null ? targetChatId : "(default)");

        try {
            String message = null;

            // 1. Prioriza geração a partir do banco de dados local com import automático do período
            if (relatorioService != null) {
                try {
                    Map<String, Object> relatorioCompleto = relatorioService.gerarRelatorioJson(periodo, null, null, null, null, null, null, false);
                    if (relatorioCompleto != null && relatorioCompleto.containsKey("resumoGeral")) {
                        message = formatWhatsAppMessageDetailed(relatorioCompleto, reportPeriod.title(), tipoExibicao);
                    }
                } catch (Exception e) {
                    log.warn("Falha ao gerar relatório detalhado a partir da base local: {}", e.getMessage());
                }
            }

            // 2. Se o banco não tiver dados para o período ou serviços não estiverem disponíveis, fallback direto nas APIs
            if (message == null) {
                log.info("Executando consulta direta nas APIs integradoras (fallback)...");
                List<MachineReport> vendReport = buildVendProMaxReport(reportPeriod.startDate(), reportPeriod.endDate());
                List<MachineReport> payReport = buildPayXYZReport(reportPeriod.startDate(), reportPeriod.endDate());
                List<MachineReport> zapiziReport = buildZapiziReport(reportPeriod.startDate(), reportPeriod.endDate());
                List<MachineReport> unified = unifyReports(vendReport, payReport, zapiziReport);
                message = formatWhatsAppMessage(unified, reportPeriod.title());
            }

            log.info("--- Mensagem WhatsApp - {} ---\n{}", reportPeriod.title(), message);
            sender.sendMessage(targetChatId, message);
        } catch (IntegrationException error) {
            log.error("Erro na integração ao gerar relatório: {}", error.getMessage());
            sender.sendMessage(targetChatId, error.getMessage());
        } catch (Exception error) {
            log.error("Erro inesperado ao gerar relatório: {}", error.getMessage(), error);
            sender.sendMessage(targetChatId, "Erro inesperado ao gerar relatório: " + error.getMessage());
        }
    }
}
