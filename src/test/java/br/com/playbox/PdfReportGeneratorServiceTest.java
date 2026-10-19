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

        byte[] pdfBytes = generator.gerarRelatorioOperacoesPdf(dto, true);

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 500);

        // Assinatura de cabeçalho do PDF: %PDF-
        assertEquals('%', (char) pdfBytes[0]);
        assertEquals('P', (char) pdfBytes[1]);
        assertEquals('D', (char) pdfBytes[2]);
        assertEquals('F', (char) pdfBytes[3]);
    }
}
