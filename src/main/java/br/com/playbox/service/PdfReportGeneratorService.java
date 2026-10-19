package br.com.playbox.service;

import br.com.playbox.model.RelatorioOperacoesDTO;
import br.com.playbox.model.RelatorioOperacoesDTO.MovimentacaoItem;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

@Service
public class PdfReportGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(PdfReportGeneratorService.class);

    // Paleta de cores idêntica ao modelo de referência
    private static final Color COLOR_BLUE_HEADER = new Color(66, 133, 244);    // #4285F4 (Métrica)
    private static final Color COLOR_GREEN_HEADER = new Color(52, 168, 83);   // #34A853 (Entradas)
    private static final Color COLOR_RED_HEADER = new Color(224, 102, 102);   // #E06666 (Saídas)
    private static final Color COLOR_BLACK_HEADER = new Color(0, 0, 0);       // #000000 (Lucro/Repasse)
    private static final Color COLOR_TABLE_HEADER_BG = new Color(245, 245, 245);
    private static final Color COLOR_BORDER = new Color(185, 185, 185);

    private static final Color COLOR_GREEN_TEXT = new Color(46, 125, 50);    // Recebido / Creditado
    private static final Color COLOR_RED_TEXT = new Color(198, 40, 40);      // Não Recebido / Não creditado
    private static final Color COLOR_GOLD_TEXT = new Color(212, 160, 23);    // PRÊMIO

    private static final DecimalFormatSymbols BRL_SYMBOLS = new DecimalFormatSymbols(Locale.of("pt", "BR"));
    static {
        BRL_SYMBOLS.setGroupingSeparator('.');
        BRL_SYMBOLS.setDecimalSeparator(',');
    }

    public static String formatCurrency(double val) {
        DecimalFormat df = new DecimalFormat("R$ #,##0.00", BRL_SYMBOLS);
        return df.format(val);
    }

    public static String formatPercent(double val) {
        DecimalFormat df = new DecimalFormat("#,##0.##", BRL_SYMBOLS);
        return df.format(val) + "%";
    }

    public byte[] gerarRelatorioOperacoesPdf(RelatorioOperacoesDTO dto, boolean incluirHistorico) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Margens confortáveis A4 (25pt esquerda/direita, 25pt topo/base)
        Document document = new Document(PageSize.A4, 25f, 25f, 25f, 25f);

        try {
            PdfWriter writer = PdfWriter.getInstance(document, baos);
            writer.setPageEvent(new PageNumberFooterEvent());
            document.open();

            // Fontes
            Font fontDocTitle = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13.5f, Color.BLACK);
            Font fontDocSubtitle = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12f, Color.BLACK);

            // 1. TÍTULO DO DOCUMENTO
            Paragraph titlePara = new Paragraph(dto.getTituloPeriodo(), fontDocTitle);
            titlePara.setAlignment(Element.ALIGN_CENTER);
            titlePara.setSpacingAfter(2f);
            document.add(titlePara);

            Paragraph unitPara = new Paragraph(dto.getUnidade(), fontDocSubtitle);
            unitPara.setAlignment(Element.ALIGN_CENTER);
            unitPara.setSpacingAfter(10f);
            document.add(unitPara);

            // 2. QUADROS RESUMO DE OPERAÇÕES
            // Tabela 1: Métricas de Pelúcia
            adicionarTabelaMetrica(document, dto);

            // Tabela 2: Entradas
            adicionarTabelaEntradas(document, dto);

            // Tabela 3: Saídas
            adicionarTabelaSaidas(document, dto);

            // Bloco de Lucro / Repasse
            adicionarBlocoLucroRepasse(document, dto);

            // 3. HISTÓRICO DE MOVIMENTAÇÕES (se solicitado)
            if (incluirHistorico && dto.getMovimentacoes() != null && !dto.getMovimentacoes().isEmpty()) {
                Paragraph histTitle = new Paragraph("Histórico de Movimentações", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9.5f, Color.BLACK));
                histTitle.setAlignment(Element.ALIGN_CENTER);
                histTitle.setSpacingBefore(8f);
                histTitle.setSpacingAfter(4f);
                document.add(histTitle);

                adicionarTabelaHistorico(document, dto);
            }

            document.close();
            log.info("Relatório PDF de operações gerado com sucesso para {} ({} bytes)",
                    dto.getUnidade(), baos.size());
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Erro ao gerar PDF de operações: {}", e.getMessage(), e);
            throw new RuntimeException("Falha na geração do relatório em PDF: " + e.getMessage(), e);
        }
    }

    private void adicionarTabelaMetrica(Document document, RelatorioOperacoesDTO dto) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100f);
        table.setWidths(new float[]{75f, 25f});
        table.setSpacingAfter(3f);

        // Header
        table.addCell(createHeaderCell("Métrica", COLOR_BLUE_HEADER, Element.ALIGN_LEFT));
        table.addCell(createHeaderCell("Valor Calculado", COLOR_BLUE_HEADER, Element.ALIGN_RIGHT));

        // Linhas
        table.addCell(createDataCell("Quantidade de PRÊMIOS Liberados", false, Element.ALIGN_LEFT));
        table.addCell(createDataCell(String.valueOf(dto.getQuantidadePremios()), false, Element.ALIGN_RIGHT));

        table.addCell(createDataCell("VALOR UNITÁRIO PELUCIA", false, Element.ALIGN_LEFT));
        table.addCell(createDataCell(formatCurrency(dto.getValorUnitarioPelucia()), false, Element.ALIGN_RIGHT));

        table.addCell(createDataCell("Custo Total Pelucia", false, Element.ALIGN_LEFT));
        table.addCell(createDataCell(formatCurrency(dto.getCustoTotalPelucia()), false, Element.ALIGN_RIGHT));

        document.add(table);
    }

    private void adicionarTabelaEntradas(Document document, RelatorioOperacoesDTO dto) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100f);
        table.setWidths(new float[]{75f, 25f});
        table.setSpacingAfter(3f);

        // Header
        table.addCell(createHeaderCell("ENTRADAS", COLOR_GREEN_HEADER, Element.ALIGN_LEFT));
        table.addCell(createHeaderCell("VALOR", COLOR_GREEN_HEADER, Element.ALIGN_RIGHT));

        table.addCell(createDataCell("ESPECIE", false, Element.ALIGN_LEFT));
        table.addCell(createDataCell(formatCurrency(dto.getEntradasEspecie()), false, Element.ALIGN_RIGHT));

        table.addCell(createDataCell("MAQUININHA", false, Element.ALIGN_LEFT));
        table.addCell(createDataCell(formatCurrency(dto.getEntradasMaquininha()), false, Element.ALIGN_RIGHT));

        table.addCell(createDataCell("TOTAL", true, Element.ALIGN_LEFT));
        table.addCell(createDataCell(formatCurrency(dto.getTotalEntradas()), true, Element.ALIGN_RIGHT));

        document.add(table);
    }

    private void adicionarTabelaSaidas(Document document, RelatorioOperacoesDTO dto) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100f);
        table.setWidths(new float[]{75f, 25f});
        table.setSpacingAfter(3f);

        // Header
        table.addCell(createHeaderCell("SAIDAS", COLOR_RED_HEADER, Element.ALIGN_LEFT));
        table.addCell(createHeaderCell("VALOR", COLOR_RED_HEADER, Element.ALIGN_RIGHT));

        table.addCell(createDataCell("Soma Total de Valor Estornado", false, Element.ALIGN_LEFT));
        table.addCell(createDataCell(formatCurrency(dto.getValorEstornado()), false, Element.ALIGN_RIGHT));

        String labelTaxa = "TAXA MAQUININHA (" + formatPercent(dto.getTaxaMaquininhaPercentual()) + ")";
        table.addCell(createDataCell(labelTaxa, false, Element.ALIGN_LEFT));
        table.addCell(createDataCell(formatCurrency(dto.getTaxaMaquininhaValor()), false, Element.ALIGN_RIGHT));

        table.addCell(createDataCell("Custo Total Pelucia", false, Element.ALIGN_LEFT));
        table.addCell(createDataCell(formatCurrency(dto.getCustoTotalPelucia()), false, Element.ALIGN_RIGHT));

        table.addCell(createDataCell("Mensalidade de Sistema de Telemetria", false, Element.ALIGN_LEFT));
        table.addCell(createDataCell(formatCurrency(dto.getMensalidadeTelemetria()), false, Element.ALIGN_RIGHT));

        table.addCell(createDataCell("Visitas Técnicas e manunteções (5x59,90)", false, Element.ALIGN_LEFT));
        table.addCell(createDataCell(formatCurrency(dto.getVisitasManutencao()), false, Element.ALIGN_RIGHT));

        table.addCell(createDataCell("TOTAL", true, Element.ALIGN_LEFT));
        table.addCell(createDataCell(formatCurrency(dto.getTotalSaidas()), true, Element.ALIGN_RIGHT));

        document.add(table);
    }

    private void adicionarBlocoLucroRepasse(Document document, RelatorioOperacoesDTO dto) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100f);
        table.setWidths(new float[]{75f, 25f});
        table.setSpacingAfter(4f);

        // Lucro Total (Fundo Preto com Texto Branco)
        table.addCell(createBlackSummaryCell("Lucro Total", Element.ALIGN_LEFT));
        table.addCell(createBlackSummaryCell(formatCurrency(dto.getLucroTotal()), Element.ALIGN_RIGHT));

        // Percentual Acordado / Repasse (25%)
        String labelPercent = formatPercent(dto.getPercentualRepasse());
        table.addCell(createBlackSummaryCell(labelPercent, Element.ALIGN_LEFT));
        table.addCell(createBlackSummaryCell(formatCurrency(dto.getValorRepasse()), Element.ALIGN_RIGHT));

        document.add(table);
    }

    private void adicionarTabelaHistorico(Document document, RelatorioOperacoesDTO dto) throws DocumentException {
        PdfPTable table = new PdfPTable(8);
        table.setWidthPercentage(100f);
        table.setWidths(new float[]{16f, 17f, 8.5f, 13f, 14.5f, 10f, 10.5f, 15.5f});
        table.setHeaderRows(1); // Repete o cabeçalho em todas as páginas automaticamente!

        // Cabeçalhos da tabela detalhada
        String[] headers = {
                "Data", "Máquina", "Valor", "na de Pagam", "Identificador MP", "Estornado", "Creditado na Máquina", "Motivo Estorno"
        };
        // Ajuste no cabeçalho exato: "Forma de Pagamento"
        headers[3] = "Forma de Pagamento";

        Font fontHeader = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, Color.BLACK);
        for (String h : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(h, fontHeader));
            cell.setBackgroundColor(COLOR_TABLE_HEADER_BG);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            cell.setBorderColor(COLOR_BORDER);
            cell.setPadding(3.5f);
            table.addCell(cell);
        }

        // Linhas de dados
        Font fontNormal = FontFactory.getFont(FontFactory.HELVETICA, 7.2f, Color.BLACK);
        Font fontPremio = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.2f, COLOR_GOLD_TEXT);
        Font fontGreen = FontFactory.getFont(FontFactory.HELVETICA, 7.2f, COLOR_GREEN_TEXT);
        Font fontRed = FontFactory.getFont(FontFactory.HELVETICA, 7.2f, COLOR_RED_TEXT);
        Font fontSmallReason = FontFactory.getFont(FontFactory.HELVETICA, 6.5f, COLOR_RED_TEXT);

        for (MovimentacaoItem item : dto.getMovimentacoes()) {
            // 1. Data
            table.addCell(createMovementCell(item.getDataHora(), fontNormal, Element.ALIGN_CENTER));

            // 2. Máquina
            table.addCell(createMovementCell(item.getMaquina(), fontNormal, Element.ALIGN_CENTER));

            // 3. Valor
            table.addCell(createMovementCell(item.getValor(), fontNormal, Element.ALIGN_CENTER));

            // 4. Forma de Pagamento (se for prêmio, destaca em dourado)
            Font fontForma = item.isPremio() ? fontPremio : fontNormal;
            table.addCell(createMovementCell(item.getFormaPagamento(), fontForma, Element.ALIGN_CENTER));

            // 5. Identificador MP (se for prêmio, destaca em dourado)
            Font fontId = item.isPremio() ? fontPremio : fontNormal;
            table.addCell(createMovementCell(item.getIdentificadorMp(), fontId, Element.ALIGN_CENTER));

            // 6. Estornado (Recebido = verde, Não Recebido = vermelho)
            Font fontEst = item.isEstornado() ? fontRed : fontGreen;
            table.addCell(createMovementCell(item.getEstornado(), fontEst, Element.ALIGN_CENTER));

            // 7. Creditado na Máquina (Creditado = verde, Não creditado = vermelho)
            Font fontCred = item.isNaoCreditado() ? fontRed : fontGreen;
            table.addCell(createMovementCell(item.getCreditadoNaMaquina(), fontCred, Element.ALIGN_CENTER));

            // 8. Motivo Estorno
            String motivo = item.getMotivoEstorno() != null ? item.getMotivoEstorno() : "";
            table.addCell(createMovementCell(motivo, fontSmallReason, Element.ALIGN_LEFT));
        }

        document.add(table);
    }

    private PdfPCell createHeaderCell(String text, Color bgColor, int align) {
        Font font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.5f, Color.WHITE);
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bgColor);
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorderColor(COLOR_BORDER);
        cell.setPadding(4f);
        return cell;
    }

    private PdfPCell createDataCell(String text, boolean bold, int align) {
        Font font = FontFactory.getFont(bold ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, 8.2f, Color.BLACK);
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorderColor(COLOR_BORDER);
        cell.setPadding(3.5f);
        return cell;
    }

    private PdfPCell createBlackSummaryCell(String text, int align) {
        Font font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8.8f, Color.WHITE);
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(COLOR_BLACK_HEADER);
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorderColor(Color.BLACK);
        cell.setPadding(4f);
        return cell;
    }

    private PdfPCell createMovementCell(String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text != null ? text : "", font));
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setBorderColor(COLOR_BORDER);
        cell.setPadding(3f);
        return cell;
    }

    /**
     * Adiciona rodapé de paginação em todas as páginas do relatório
     */
    private static class PageNumberFooterEvent extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            Font font = FontFactory.getFont(FontFactory.HELVETICA, 7.5f, Color.GRAY);
            String text = "Página " + writer.getPageNumber();
            ColumnText.showTextAligned(
                    cb,
                    Element.ALIGN_CENTER,
                    new Phrase(text, font),
                    (document.right() - document.left()) / 2 + document.leftMargin(),
                    document.bottom() - 12f,
                    0
            );
        }
    }
}
