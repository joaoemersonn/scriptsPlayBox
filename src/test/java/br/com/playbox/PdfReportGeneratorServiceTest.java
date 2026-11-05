package br.com.playbox;

import br.com.playbox.model.RelatorioOperacoesDTO;
import br.com.playbox.model.RelatorioOperacoesDTO.MovimentacaoItem;
import br.com.playbox.service.PdfReportGeneratorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PdfReportGeneratorServiceTest {

    private final PdfReportGeneratorService generator = new PdfReportGeneratorService();

    @Test
    @DisplayName("Deve gerar PDF de operações completo com sucesso")
    void testGerarPdfCompleto() {
        RelatorioOperacoesDTO dto = new RelatorioOperacoesDTO();
        dto.setTituloPeriodo("Relatório Mensal de Operações (01/09/2026 a 30/09/2026)");
        dto.setUnidade("Unidade 02-Praça do Sabor");
        dto.setQuantidadePremios(33);
        dto.setValorUnitarioPelucia(19.90);
        dto.setEntradasEspecie(842.00);
        dto.setEntradasMaquininha(657.00);
        dto.setValorEstornado(4.00);
        dto.setTaxaMaquininhaPercentual(4.99);
        dto.setMensalidadeTelemetria(69.90);
        dto.setVisitasManutencao(350.00);
        dto.setPercentualRepasse(25.0);
        dto.recalcularTotais();

        assertEquals(656.70, dto.getCustoTotalPelucia());
        assertEquals(1499.00, dto.getTotalEntradas());
        assertEquals(74.80, dto.getTaxaMaquininhaValor());
        assertEquals(1155.40, dto.getTotalSaidas());
        assertEquals(343.60, dto.getLucroTotal());
        assertEquals(85.90, dto.getValorRepasse());

        MovimentacaoItem item1 = new MovimentacaoItem(
                "30/09/2026 - 20:03:25",
                "Praça do Sabor - Custodia",
                "-",
                "PRÊMIO",
                "PRÊMIO",
                "Recebido",
                "Creditado",
                "",
                true,
                false,
                false
        );
        MovimentacaoItem item2 = new MovimentacaoItem(
                "30/09/2026 - 20:03:04",
                "Praça do Sabor - Custodia",
                "R$ 4,00",
                "Pix",
                "180705205275",
                "Recebido",
                "Creditado",
                "",
                false,
                false,
                false
        );

        dto.setMovimentacoes(List.of(item1, item2));

        byte[] pdfBytesSemId = generator.gerarRelatorioOperacoesPdf(dto, true, false);
        assertNotNull(pdfBytesSemId);
        assertTrue(pdfBytesSemId.length > 500);
        assertEquals('%', (char) pdfBytesSemId[0]);

        byte[] pdfBytesComId = generator.gerarRelatorioOperacoesPdf(dto, true, true);
        assertNotNull(pdfBytesComId);
        assertTrue(pdfBytesComId.length > 500);
        assertEquals('%', (char) pdfBytesComId[0]);

        byte[] pdfBytesSemRepasse = generator.gerarRelatorioOperacoesPdf(dto, true, false, false);
        assertNotNull(pdfBytesSemRepasse);
        assertTrue(pdfBytesSemRepasse.length > 500);
        assertEquals('%', (char) pdfBytesSemRepasse[0]);
    }

    @Test
    @DisplayName("Deve gerar PDF com titulo customizado, subtitulo e metadados dinâmicos")
    void testGerarPdfComTituloCustomizado() {
        RelatorioOperacoesDTO dto = new RelatorioOperacoesDTO();
        dto.setTituloDocumento("Relatório Praça do Sabor - 11-2026");
        dto.setSubtituloDocumento("Unidade 02-Praça do Sabor • Sertânia (01/11/2026 a 30/11/2026)");
        dto.setNomeArquivo("Relatório Praça do Sabor - 11-2026.pdf");
        dto.setTituloPeriodo("Relatório Mensal de Operações");
        dto.setUnidade("Unidade 02-Praça do Sabor");
        dto.recalcularTotais();

        byte[] pdf = generator.gerarRelatorioOperacoesPdf(dto, false);
        assertNotNull(pdf);
        assertTrue(pdf.length > 500);
        assertEquals('%', (char) pdf[0]);
        assertEquals("Relatório Praça do Sabor - 11-2026", dto.getTituloDocumento());
        assertEquals("Relatório Praça do Sabor - 11-2026.pdf", dto.getNomeArquivo());
    }

    @Test
    @DisplayName("Deve sanitizar nome de arquivo e formatar periodo corretamente")
    void testSanitizarNomeArquivoEPeriodo() {
        String sanitized = br.com.playbox.service.RelatorioOperacoesService.sanitizarNomeArquivo("Relatório: Loja/Principal * 10/2026?");
        assertEquals("Relatório- Loja-Principal - 10-2026-.pdf", sanitized);

        String semExtensao = br.com.playbox.service.RelatorioOperacoesService.sanitizarNomeArquivo("Relatório Geral 10-2026");
        assertEquals("Relatório Geral 10-2026.pdf", semExtensao);

        var pMes = new br.com.playbox.service.RelatorioService.PeriodoCalculado(
                "mes-anterior",
                "Relatório de Outubro",
                java.time.LocalDateTime.of(2026, 10, 1, 0, 0),
                java.time.LocalDateTime.of(2026, 10, 31, 23, 59, 59)
        );
        String periodoFormatado = br.com.playbox.service.RelatorioOperacoesService.extrairPeriodoFormatado(pMes, "mes-anterior");
        assertEquals("10-2026", periodoFormatado);
    }
}
