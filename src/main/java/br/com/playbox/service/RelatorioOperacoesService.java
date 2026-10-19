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

    public RelatorioOperacoesService(
            RelatorioService relatorioService,
            EstabelecimentoRepository estabelecimentoRepository,
            MaquinaRepository maquinaRepository,
            TransacaoRepository transacaoRepository,
            VendProMaxClient vendClient,
            AppProperties properties,
            PdfReportGeneratorService pdfGenerator
    ) {
        this.relatorioService = relatorioService;
        this.estabelecimentoRepository = estabelecimentoRepository;
        this.maquinaRepository = maquinaRepository;
        this.transacaoRepository = transacaoRepository;
        this.vendClient = vendClient;
        this.properties = properties;
        this.pdfGenerator = pdfGenerator;
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

        // 1. Título do Período
        String tituloPeriodo = String.format("Relatório Mensal de Operações (%s a %s)",
                p.inicio().format(DMY_FORMATTER), p.fim().format(DMY_FORMATTER));
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

        // 3. Título da Unidade
        if (tituloUnidadeCustomizado != null && !tituloUnidadeCustomizado.isBlank()) {
            dto.setUnidade(tituloUnidadeCustomizado.trim());
        } else if (est != null) {
            Integer num = (!maquinas.isEmpty() && maquinas.get(0).getNumeroPlanilha() != null)
                    ? maquinas.get(0).getNumeroPlanilha()
                    : est.getId().intValue();
            dto.setUnidade(String.format("Unidade %02d-%s", num, est.getNome()));
            dto.setEstabelecimentoNome(est.getNome());
            dto.setCidade(est.getCidade());
        } else if (!maquinas.isEmpty()) {
            Maquina m0 = maquinas.get(0);
            dto.setUnidade(m0.getNomeIntegracao() != null ? m0.getNomeIntegracao() : "Máquina #" + m0.getId());
        } else {
            dto.setUnidade("PlayBox - Geral");
        }

        // 4. Parâmetros de Custo (fixos por enquanto como solicitado, com suporte a override)
        dto.setValorUnitarioPelucia(valorUnitarioPelucia != null ? valorUnitarioPelucia : 19.90);
        dto.setTaxaMaquininhaPercentual(taxaMaquininhaPercentual != null ? taxaMaquininhaPercentual : 4.99);
        dto.setMensalidadeTelemetria(mensalidadeTelemetria != null ? mensalidadeTelemetria : 69.90);
        dto.setVisitasManutencao(visitasManutencao != null ? visitasManutencao : 350.00);
        dto.setPercentualRepasse(percentualRepasse != null ? percentualRepasse : 25.0);

        // 5. Coleta de dados (Tenta obter dados detalhados da API VendProMax caso haja máquina VendProMax)
        boolean dadosVendObtidos = false;
        String inicioIso = p.inicio().atZone(ZONE_ID).format(ISO_FORMATTER);
        String fimIso = p.fim().atZone(ZONE_ID).format(ISO_FORMATTER);

        for (Maquina m : maquinas) {
            if ("VENDPROMAX".equalsIgnoreCase(m.getIntegracao()) && m.getExternalId() != null) {
                try {
                    String token = vendClient.login(properties.getVendpromax().getEmail(), properties.getVendpromax().getPassword());
                    if (token != null) {
                        JsonNode cashNode = vendClient.getReport(token, "relatorio-01-cash", m.getExternalId(), inicioIso, fimIso, false);
                        JsonNode pagamentosNode = vendClient.getReport(token, "relatorio-03-pagamentos", m.getExternalId(), inicioIso, fimIso, false);
                        JsonNode estornosNode = vendClient.getReport(token, "relatorio-04-estornos", m.getExternalId(), inicioIso, fimIso, false);
                        JsonNode premiosNode = vendClient.getReport(token, "relatorio-premios", m.getExternalId(), inicioIso, fimIso, false);
                        JsonNode pagamentosPeriodo = vendClient.getPagamentosPeriodo(token, m.getExternalId(), inicioIso, fimIso);

                        double cash = (cashNode != null && cashNode.hasNonNull("valor")) ? cashNode.get("valor").asDouble() : 0.0;
                        double estornos = (estornosNode != null && estornosNode.hasNonNull("valor")) ? estornosNode.get("valor").asDouble() : 0.0;
                        double pix = (pagamentosNode != null && pagamentosNode.hasNonNull("pix")) ? pagamentosNode.get("pix").asDouble() : 0.0;
                        double credito = (pagamentosNode != null && pagamentosNode.hasNonNull("credito")) ? pagamentosNode.get("credito").asDouble() : 0.0;
                        double debito = (pagamentosNode != null && pagamentosNode.hasNonNull("debito")) ? pagamentosNode.get("debito").asDouble() : 0.0;
                        int qtdPremios = (premiosNode != null && premiosNode.hasNonNull("quantidade")) ? premiosNode.get("quantidade").asInt() : 0;

                        double maquininha = pix + credito + debito;

                        dto.setEntradasEspecie(dto.getEntradasEspecie() + cash);
                        dto.setEntradasMaquininha(dto.getEntradasMaquininha() + maquininha);
                        dto.setValorEstornado(dto.getValorEstornado() + estornos);
                        dto.setQuantidadePremios(dto.getQuantidadePremios() + qtdPremios);

                        // Processamento do histórico de movimentações detalhado
                        if (pagamentosPeriodo != null && pagamentosPeriodo.hasNonNull("pagamentos") && pagamentosPeriodo.get("pagamentos").isArray()) {
                            String nomeMaquinaStr = (est != null)
                                    ? est.getNome() + (est.getCidade() != null && !est.getCidade().isBlank() ? " - " + est.getCidade() : "")
                                    : (m.getNomeIntegracao() != null ? m.getNomeIntegracao() : "Máquina #" + m.getId());

                            for (JsonNode item : pagamentosPeriodo.get("pagamentos")) {
                                MovimentacaoItem mi = parseMovimentacaoVendProMax(item, nomeMaquinaStr);
                                if (mi != null) {
                                    dto.getMovimentacoes().add(mi);
                                }
                            }
                        }

                        dadosVendObtidos = true;
                    }
                } catch (Exception e) {
                    log.warn("Falha ao consultar detalhes em tempo real da máquina {}: {}", m.getExternalId(), e.getMessage());
                }
            }
        }

        // Se não for máquina VendProMax ou se a chamada online falhar, consolida a partir do banco de dados local
        if (!dadosVendObtidos) {
            log.info("Consolidando relatório de operações a partir da base local de transações...");
            List<Transacao> transacoes = transacaoRepository.findFiltered(
                    p.inicio(),
                    p.fim(),
                    estabelecimentoId,
                    est != null ? est.getCidade() : null,
                    maquinaId,
                    null
            );

            double cash = 0.0;
            double maquininha = 0.0;
            double estornos = 0.0;
            double premios = 0.0;

            for (Transacao t : transacoes) {
                cash += (t.getValorCash() != null ? t.getValorCash() : 0.0);
                double mq = (t.getValorPix() != null ? t.getValorPix() : 0.0)
                        + (t.getValorCredito() != null ? t.getValorCredito() : 0.0)
                        + (t.getValorDebito() != null ? t.getValorDebito() : 0.0);
                maquininha += mq;
                estornos += (t.getValorEstorno() != null ? t.getValorEstorno() : 0.0);
                premios += (t.getQuantidadePremios() != null ? t.getQuantidadePremios() : 0.0);

                String maqNome = (t.getEstabelecimento() != null)
                        ? t.getEstabelecimento().getNome() + (t.getEstabelecimento().getCidade() != null ? " - " + t.getEstabelecimento().getCidade() : "")
                        : (t.getMaquina() != null && t.getMaquina().getNomeIntegracao() != null ? t.getMaquina().getNomeIntegracao() : "PlayBox");

                String dataFormatada = t.getTimestamp().format(DISPLAY_DATE_FORMATTER);

                // Linha de faturamento
                if (t.getValor() != null && t.getValor() > 0) {
                    dto.getMovimentacoes().add(new MovimentacaoItem(
                            dataFormatada,
                            maqNome,
                            PdfReportGeneratorService.formatCurrency(t.getValor()),
                            t.getTipoPagamento() != null ? t.getTipoPagamento() : "Venda",
                            t.getExternalTxId(),
                            "Recebido",
                            "Creditado",
                            "",
                            false,
                            false,
                            false
                    ));
                }
                // Linha de prêmio
                if (t.getQuantidadePremios() != null && t.getQuantidadePremios() > 0) {
                    for (int i = 0; i < t.getQuantidadePremios().intValue(); i++) {
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
            dto.setEntradasMaquininha(maquininha);
            dto.setValorEstornado(estornos);
            dto.setQuantidadePremios((int) premios);
        }

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

        return pdfGenerator.gerarRelatorioOperacoesPdf(dto, incluirHistorico);
    }
}
