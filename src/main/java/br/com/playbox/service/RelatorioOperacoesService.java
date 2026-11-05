package br.com.playbox.service;

import br.com.playbox.client.VendProMaxClient;
import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
import br.com.playbox.model.RelatorioOperacoesDTO;
import br.com.playbox.model.RelatorioOperacoesDTO.MovimentacaoItem;
import br.com.playbox.repository.EstabelecimentoRepository;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.TransacaoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class RelatorioOperacoesService {

    private static final Logger log = LoggerFactory.getLogger(RelatorioOperacoesService.class);
    private static final ZoneId ZONE_ID = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
    private static final DateTimeFormatter DISPLAY_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy - HH:mm:ss");
    private static final DateTimeFormatter DMY_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final RelatorioService relatorioService;
    private final EstabelecimentoRepository estabelecimentoRepository;
    private final MaquinaRepository maquinaRepository;
    private final TransacaoRepository transacaoRepository;
    private final VendProMaxClient vendClient;
    private final AppProperties properties;
    private final PdfReportGeneratorService pdfGenerator;
    private final TransactionImportService transactionImportService;

    @org.springframework.beans.factory.annotation.Autowired
    public RelatorioOperacoesService(
            RelatorioService relatorioService,
            EstabelecimentoRepository estabelecimentoRepository,
            MaquinaRepository maquinaRepository,
            TransacaoRepository transacaoRepository,
            VendProMaxClient vendClient,
            AppProperties properties,
            PdfReportGeneratorService pdfGenerator,
            TransactionImportService transactionImportService
    ) {
        this.relatorioService = relatorioService;
        this.estabelecimentoRepository = estabelecimentoRepository;
        this.maquinaRepository = maquinaRepository;
        this.transacaoRepository = transacaoRepository;
        this.vendClient = vendClient;
        this.properties = properties;
        this.pdfGenerator = pdfGenerator;
        this.transactionImportService = transactionImportService;
    }

    public RelatorioOperacoesService(
            RelatorioService relatorioService,
            EstabelecimentoRepository estabelecimentoRepository,
            MaquinaRepository maquinaRepository,
            TransacaoRepository transacaoRepository,
            VendProMaxClient vendClient,
            AppProperties properties,
            PdfReportGeneratorService pdfGenerator
    ) {
        this(relatorioService, estabelecimentoRepository, maquinaRepository, transacaoRepository, vendClient, properties, pdfGenerator, null);
    }

    public RelatorioOperacoesDTO compilarRelatorio(
            Long estabelecimentoId,
            Long maquinaId,
            String periodo,
            LocalDate dataInicio,
            LocalDate dataFim,
            Double valorUnitarioPelucia,
            Double taxaMaquininhaPercentual,
            Double mensalidadeTelemetria,
            Double visitasManutencao,
            Double percentualRepasse,
            String tituloUnidadeCustomizado
    ) {
        RelatorioService.PeriodoCalculado p = relatorioService.calcularPeriodo(periodo, dataInicio, dataFim);
        RelatorioOperacoesDTO dto = new RelatorioOperacoesDTO();

        // 0. Garante que as transações do período estejam importadas do provedor externo
        if (transactionImportService != null) {
            transactionImportService.ensureTransactionsImported(p.inicio().toLocalDate(), p.fim().toLocalDate());
        }

        // 1. Título do Período
        String tituloPeriodo = (p.titulo() != null && !p.titulo().isBlank())
                ? p.titulo()
                : String.format("Relatório Mensal de Operações (%s a %s)", p.inicio().format(DMY_FORMATTER), p.fim().format(DMY_FORMATTER));
        dto.setTituloPeriodo(tituloPeriodo);

        // 2. Resolução de Estabelecimento e Máquinas
        Estabelecimento est = null;
        if (estabelecimentoId != null) {
            est = estabelecimentoRepository.findById(estabelecimentoId).orElse(null);
        }

        List<Maquina> maquinas;
        if (maquinaId != null) {
            Optional<Maquina> opt = maquinaRepository.findById(maquinaId);
            maquinas = opt.map(List::of).orElseGet(ArrayList::new);
            if (est == null && !maquinas.isEmpty() && maquinas.get(0).getEstabelecimento() != null) {
                est = maquinas.get(0).getEstabelecimento();
            }
        } else if (estabelecimentoId != null) {
            maquinas = maquinaRepository.findByEstabelecimentoId(estabelecimentoId);
        } else {
            maquinas = maquinaRepository.findByAtivoTrue();
        }

        // 3. Título da Unidade, Título do Documento e Nome do Arquivo
        String entityName;
        String cidade = null;
        Integer unidadeNum = null;

        if (est != null) {
            entityName = est.getNome() != null ? est.getNome().trim() : "Estabelecimento #" + est.getId();
            cidade = est.getCidade();
            if (!maquinas.isEmpty() && maquinas.get(0).getNumeroPlanilha() != null) {
                unidadeNum = maquinas.get(0).getNumeroPlanilha();
            } else if (est.getId() != null) {
                unidadeNum = est.getId().intValue();
            }
            String unidadeStr = (unidadeNum != null) ? String.format("Unidade %02d-%s", unidadeNum, entityName) : entityName;
            dto.setUnidade(unidadeStr);
            dto.setEstabelecimentoNome(est.getNome());
            dto.setCidade(est.getCidade());
        } else if (tituloUnidadeCustomizado != null && !tituloUnidadeCustomizado.isBlank()) {
            entityName = tituloUnidadeCustomizado.trim();
            dto.setUnidade(entityName);
        } else if (!maquinas.isEmpty() && maquinas.size() == 1) {
            Maquina m0 = maquinas.get(0);
            entityName = m0.getNomeIntegracao() != null ? m0.getNomeIntegracao().trim() : "Máquina #" + m0.getId();
            dto.setUnidade(entityName);
            if (m0.getEstabelecimento() != null) {
                cidade = m0.getEstabelecimento().getCidade();
                dto.setCidade(cidade);
                dto.setEstabelecimentoNome(m0.getEstabelecimento().getNome());
            }
        } else {
            entityName = "Geral";
            dto.setUnidade("PlayBox - Geral");
        }

        // Título e Nome do Documento / Arquivo (ex: "Relatório Geral 10-2026", "Relatório Praça do Sabor - 11-2026")
        String periodoFormatado = extrairPeriodoFormatado(p, periodo);
        String tituloDocumento;
        if ("Geral".equalsIgnoreCase(entityName) || "PlayBox - Geral".equalsIgnoreCase(entityName)) {
            tituloDocumento = "Relatório Geral " + periodoFormatado;
        } else {
            tituloDocumento = "Relatório " + entityName + " - " + periodoFormatado;
        }
        dto.setTituloDocumento(tituloDocumento);
        dto.setNomeArquivo(sanitizarNomeArquivo(tituloDocumento));

        // Subtítulo descritivo para o cabeçalho do PDF
        String intervaloDatas = p.inicio().format(DMY_FORMATTER) + " a " + p.fim().format(DMY_FORMATTER);
        String subtitulo;
        if (est != null) {
            String unidadePrefix = dto.getUnidade();
            if (cidade != null && !cidade.isBlank()) {
                subtitulo = unidadePrefix + " • " + cidade + " (" + intervaloDatas + ")";
            } else {
                subtitulo = unidadePrefix + " (" + intervaloDatas + ")";
            }
        } else if (tituloUnidadeCustomizado != null && !tituloUnidadeCustomizado.isBlank()) {
            subtitulo = tituloUnidadeCustomizado.trim() + " (" + intervaloDatas + ")";
        } else if (!maquinas.isEmpty() && maquinas.size() == 1) {
            subtitulo = dto.getUnidade() + " (" + intervaloDatas + ")";
        } else {
            subtitulo = "PlayBox Consolidado (" + intervaloDatas + ")";
        }
        dto.setSubtituloDocumento(subtitulo);

        // 4. Parâmetros de Custo (com suporte a override e fallback para o estabelecimento)
        Double defaultPelucia = (est != null && est.getValorUnitarioPelucia() != null) ? est.getValorUnitarioPelucia() : 19.90;
        Double defaultTaxa = (est != null && est.getTaxaMaquininha() != null) ? est.getTaxaMaquininha() : 4.99;
        Double defaultTelem = (est != null && est.getMensalidadeTelemetria() != null) ? est.getMensalidadeTelemetria() : 69.90;
        Double defaultVisitas = (est != null && est.getVisitasManutencao() != null) ? est.getVisitasManutencao() : 350.00;
        Double defaultRepasse = (est != null && est.getPercentualRepasse() != null) ? est.getPercentualRepasse() : 25.0;

        dto.setValorUnitarioPelucia(valorUnitarioPelucia != null ? valorUnitarioPelucia : defaultPelucia);
        dto.setTaxaMaquininhaPercentual(taxaMaquininhaPercentual != null ? taxaMaquininhaPercentual : defaultTaxa);
        dto.setMensalidadeTelemetria(mensalidadeTelemetria != null ? mensalidadeTelemetria : defaultTelem);
        dto.setVisitasManutencao(visitasManutencao != null ? visitasManutencao : defaultVisitas);
        dto.setPercentualRepasse(percentualRepasse != null ? percentualRepasse : defaultRepasse);

        // 5. Coleta de dados a partir da base consolidada de transações (VendProMax, Zapizi, PayXYZ)
        log.info("Consolidando relatório de operações a partir da base de transações (período {} a {})...", p.inicio(), p.fim());
        List<Transacao> transacoes = transacaoRepository.findFiltered(
                p.inicio(),
                p.fim(),
                estabelecimentoId,
                est != null ? est.getCidade() : null,
                maquinaId,
                null
        );

        double cash = 0.0;
        double pix = 0.0;
        double credito = 0.0;
        double debito = 0.0;
        double estornos = 0.0;
        double premios = 0.0;

        for (Transacao t : transacoes) {
            cash += t.getValorCashEfetivo();
            pix += t.getValorPixEfetivo();
            credito += t.getValorCreditoEfetivo();
            debito += t.getValorDebitoEfetivo();
            estornos += t.getValorEstornoEfetivo();
            premios += t.getQuantidadePremiosEfetiva();

            String maqNome = (t.getEstabelecimento() != null)
                    ? t.getEstabelecimento().getNome() + (t.getEstabelecimento().getCidade() != null && !t.getEstabelecimento().getCidade().isBlank() ? " - " + t.getEstabelecimento().getCidade() : "")
                    : (t.getMaquina() != null && t.getMaquina().getNomeIntegracao() != null ? t.getMaquina().getNomeIntegracao() : "PlayBox");

            String dataFormatada = t.getTimestamp().format(DISPLAY_DATE_FORMATTER);
            String tipoFormatado = formatarTipoPagamentoPtBr(t);

            // Linha de faturamento
            if (t.getValorEfetivo() > 0) {
                dto.getMovimentacoes().add(new MovimentacaoItem(
                        dataFormatada,
                        maqNome,
                        PdfReportGeneratorService.formatCurrency(t.getValorEfetivo()),
                        tipoFormatado,
                        t.getExternalTxId(),
                        "Recebido",
                        "Creditado",
                        "",
                        false,
                        false,
                        false
                ));
            }
            // Linha de estorno (se houver)
            if (t.getValorEstornoEfetivo() > 0) {
                dto.getMovimentacoes().add(new MovimentacaoItem(
                        dataFormatada,
                        maqNome,
                        PdfReportGeneratorService.formatCurrency(t.getValorEstornoEfetivo()),
                        tipoFormatado,
                        t.getExternalTxId(),
                        "Não Recebido",
                        "Estornado",
                        "Estorno",
                        false,
                        true,
                        true
                ));
            }
            // Linha de prêmio
            if (t.getQuantidadePremiosEfetiva() > 0) {
                for (int i = 0; i < (int) t.getQuantidadePremiosEfetiva(); i++) {
                    dto.getMovimentacoes().add(new MovimentacaoItem(
                            dataFormatada,
                            maqNome,
                            "-",
                            "PRÊMIO",
                            "PRÊMIO",
                            "Recebido",
                            "Creditado",
                            "",
                            true,
                            false,
                            false
                    ));
                }
            }
        }

        dto.setEntradasEspecie(cash);
        dto.setEntradasPix(pix);
        dto.setEntradasCartaoCredito(credito);
        dto.setEntradasCartaoDebito(debito);
        dto.setEntradasCartao(credito + debito);
        dto.setEntradasMaquininha(pix + credito + debito);
        dto.setValorEstornado(estornos);
        dto.setQuantidadePremios((int) premios);

        // 6. Recalcula todos os totais matemáticos de saídas, lucro e repasse
        dto.recalcularTotais();

        return dto;
    }

    private MovimentacaoItem parseMovimentacaoVendProMax(JsonNode p, String nomeMaquinaStr) {
        String dataStr = p.hasNonNull("data") ? p.get("data").asText() : null;
        String dataFormatada = "";
        if (dataStr != null) {
            try {
                dataFormatada = Instant.parse(dataStr)
                        .atZone(ZONE_ID)
                        .format(DISPLAY_DATE_FORMATTER);
            } catch (Exception e) {
                dataFormatada = dataStr;
            }
        }

        String tipoRaw = p.hasNonNull("tipo") ? p.get("tipo").asText().trim() : "";
        boolean isPremio = "PREMIO".equalsIgnoreCase(tipoRaw);

        String formaPagamento;
        if (isPremio) {
            formaPagamento = "PRÊMIO";
        } else if ("CASH".equalsIgnoreCase(tipoRaw)) {
            formaPagamento = "Espécie";
        } else if ("bank_transfer".equalsIgnoreCase(tipoRaw)) {
            formaPagamento = "Pix";
        } else if ("debit_card".equalsIgnoreCase(tipoRaw)) {
            formaPagamento = "Débito";
        } else if ("credit_card".equalsIgnoreCase(tipoRaw) || "prepaid_card".equalsIgnoreCase(tipoRaw) || "account_money".equalsIgnoreCase(tipoRaw)) {
            formaPagamento = "Crédito";
        } else {
            formaPagamento = tipoRaw;
        }

        String mpId = p.hasNonNull("mercadoPagoId") ? p.get("mercadoPagoId").asText().trim() : "";
        if (isPremio && (mpId.isEmpty() || "null".equalsIgnoreCase(mpId))) {
            mpId = "PRÊMIO";
        }

        String valorStr;
        if (isPremio) {
            valorStr = "-";
        } else {
            double v = p.hasNonNull("valor") ? p.get("valor").asDouble() : 0.0;
            valorStr = PdfReportGeneratorService.formatCurrency(v);
        }

        boolean estornado = p.hasNonNull("estornado") && p.get("estornado").asBoolean();
        String motivoEstorno = p.hasNonNull("motivoEstorno") ? p.get("motivoEstorno").asText().trim() : "";
        boolean isNaoRecebido = estornado || (!motivoEstorno.isEmpty() && !motivoEstorno.equalsIgnoreCase("null"));
        String estornadoStr = isNaoRecebido ? "Não Recebido" : "Recebido";

        boolean creditado = !p.hasNonNull("creditadonamaquina") || p.get("creditadonamaquina").asBoolean();
        String creditadoStr = creditado ? "Creditado" : "Não creditado";

        return new MovimentacaoItem(
                dataFormatada,
                nomeMaquinaStr,
                valorStr,
                formaPagamento,
                mpId,
                estornadoStr,
                creditadoStr,
                motivoEstorno,
                isPremio,
                isNaoRecebido,
                !creditado
        );
    }

    public byte[] gerarPdf(
            Long estabelecimentoId,
            Long maquinaId,
            String periodo,
            LocalDate dataInicio,
            LocalDate dataFim,
            Double valorUnitarioPelucia,
            Double taxaMaquininhaPercentual,
            Double mensalidadeTelemetria,
            Double visitasManutencao,
            Double percentualRepasse,
            String tituloUnidade,
            boolean incluirHistorico
    ) {
        return gerarPdf(
                estabelecimentoId,
                maquinaId,
                periodo,
                dataInicio,
                dataFim,
                valorUnitarioPelucia,
                taxaMaquininhaPercentual,
                mensalidadeTelemetria,
                visitasManutencao,
                percentualRepasse,
                tituloUnidade,
                incluirHistorico,
                false
        );
    }

    public byte[] gerarPdf(
            Long estabelecimentoId,
            Long maquinaId,
            String periodo,
            LocalDate dataInicio,
            LocalDate dataFim,
            Double valorUnitarioPelucia,
            Double taxaMaquininhaPercentual,
            Double mensalidadeTelemetria,
            Double visitasManutencao,
            Double percentualRepasse,
            String tituloUnidade,
            boolean incluirHistorico,
            boolean incluirIdentificador
    ) {
        return gerarPdf(
                estabelecimentoId, maquinaId, periodo, dataInicio, dataFim,
                valorUnitarioPelucia, taxaMaquininhaPercentual, mensalidadeTelemetria,
                visitasManutencao, percentualRepasse, tituloUnidade,
                incluirHistorico, incluirIdentificador, true
        );
    }

    public record RelatorioPdfResult(byte[] pdfBytes, String nomeArquivo, RelatorioOperacoesDTO dto) {}

    public RelatorioPdfResult gerarPdfComMetadados(
            Long estabelecimentoId,
            Long maquinaId,
            String periodo,
            LocalDate dataInicio,
            LocalDate dataFim,
            Double valorUnitarioPelucia,
            Double taxaMaquininhaPercentual,
            Double mensalidadeTelemetria,
            Double visitasManutencao,
            Double percentualRepasse,
            String tituloUnidade,
            boolean incluirHistorico,
            boolean incluirIdentificador,
            boolean incluirRepasse
    ) {
        RelatorioOperacoesDTO dto = compilarRelatorio(
                estabelecimentoId,
                maquinaId,
                periodo,
                dataInicio,
                dataFim,
                valorUnitarioPelucia,
                taxaMaquininhaPercentual,
                mensalidadeTelemetria,
                visitasManutencao,
                percentualRepasse,
                tituloUnidade
        );

        byte[] pdf = pdfGenerator.gerarRelatorioOperacoesPdf(dto, incluirHistorico, incluirIdentificador, incluirRepasse);
        return new RelatorioPdfResult(pdf, dto.getNomeArquivo(), dto);
    }

    public byte[] gerarPdf(
            Long estabelecimentoId,
            Long maquinaId,
            String periodo,
            LocalDate dataInicio,
            LocalDate dataFim,
            Double valorUnitarioPelucia,
            Double taxaMaquininhaPercentual,
            Double mensalidadeTelemetria,
            Double visitasManutencao,
            Double percentualRepasse,
            String tituloUnidade,
            boolean incluirHistorico,
            boolean incluirIdentificador,
            boolean incluirRepasse
    ) {
        return gerarPdfComMetadados(
                estabelecimentoId,
                maquinaId,
                periodo,
                dataInicio,
                dataFim,
                valorUnitarioPelucia,
                taxaMaquininhaPercentual,
                mensalidadeTelemetria,
                visitasManutencao,
                percentualRepasse,
                tituloUnidade,
                incluirHistorico,
                incluirIdentificador,
                incluirRepasse
        ).pdfBytes();
    }

    public static String extrairPeriodoFormatado(RelatorioService.PeriodoCalculado p, String periodoOriginal) {
        if (p == null || p.inicio() == null) return "";
        String pNorm = periodoOriginal != null ? periodoOriginal.trim().toLowerCase() : "";
        if (pNorm.isBlank() && p.periodo() != null) {
            pNorm = p.periodo().trim().toLowerCase();
        }

        LocalDate inicio = p.inicio().toLocalDate();
        LocalDate fim = p.fim() != null ? p.fim().toLocalDate() : inicio;

        if ("ano".equals(pNorm)) {
            return String.valueOf(inicio.getYear());
        }

        if ("hoje".equals(pNorm) || "ontem".equals(pNorm) || "24h-anterior".equals(pNorm) || "24h".equals(pNorm) || "12h-atual".equals(pNorm) || "12h".equals(pNorm)) {
            return inicio.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        }

        boolean isMesInteiro = (inicio.getDayOfMonth() == 1 && fim.getDayOfMonth() == fim.lengthOfMonth() && inicio.getMonth() == fim.getMonth() && inicio.getYear() == fim.getYear())
                || "mes".equals(pNorm) || "mes-anterior".equals(pNorm) || "setembro".equals(pNorm);

        if (isMesInteiro) {
            return String.format("%02d-%d", inicio.getMonthValue(), inicio.getYear());
        }

        if (inicio.equals(fim)) {
            return inicio.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
        }

        return inicio.format(DateTimeFormatter.ofPattern("dd-MM-yyyy")) + " a " + fim.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));
    }

    public static String sanitizarNomeArquivo(String nome) {
        if (nome == null || nome.isBlank()) return "relatorio.pdf";
        String sanitized = nome.replaceAll("[\\\\/:*?\"<>|]", "-")
                .replaceAll("\\s+", " ")
                .trim();
        if (!sanitized.toLowerCase().endsWith(".pdf")) {
            sanitized += ".pdf";
        }
        return sanitized;
    }

    public static String formatarTipoPagamentoPtBr(Transacao t) {
        if (t == null) return "Venda";
        String raw = t.getTipoPagamento() != null ? t.getTipoPagamento().trim().toUpperCase() : "";

        if (raw.contains("CREDIT_CARD") || raw.equals("CREDITO") || raw.equals("CREDIT")) {
            return "Cartão de crédito";
        }
        if (raw.contains("DEBIT_CARD") || raw.equals("DEBITO") || raw.equals("DEBIT") || raw.contains("PREPAID")) {
            return "Cartão de débito";
        }
        if (raw.contains("PIX") || raw.contains("BANK_TRANSFER") || raw.contains("ACCOUNT_MONEY")
                || raw.contains("MERCADOPAGO") || raw.contains("CREDITO_DIGITAL") || raw.contains("CREDITO-REMOTO")
                || raw.contains("VENDA MANUAL")) {
            return "Pix";
        }
        if (raw.contains("CASH") || raw.contains("DINHEIRO") || raw.contains("ESPECIE")) {
            return "Dinheiro em espécie";
        }
        if (raw.contains("PREMIO")) {
            return "Prêmio";
        }

        // Fallbacks por colunas numéricas caso tipoPagamento seja genérico
        if (t.getValorCreditoEfetivo() > 0 && t.getValorDebitoEfetivo() == 0 && t.getValorPixEfetivo() == 0 && t.getValorCashEfetivo() == 0) {
            return "Cartão de crédito";
        }
        if (t.getValorDebitoEfetivo() > 0 && t.getValorCreditoEfetivo() == 0 && t.getValorPixEfetivo() == 0 && t.getValorCashEfetivo() == 0) {
            return "Cartão de débito";
        }
        if (t.getValorPixEfetivo() > 0 && t.getValorCashEfetivo() == 0 && t.getValorCreditoEfetivo() == 0 && t.getValorDebitoEfetivo() == 0) {
            return "Pix";
        }
        if (t.getValorCashEfetivo() > 0 && t.getValorPixEfetivo() == 0 && t.getValorCreditoEfetivo() == 0 && t.getValorDebitoEfetivo() == 0) {
            return "Dinheiro em espécie";
        }

        return !raw.isBlank() ? raw : "Venda";
    }
}
