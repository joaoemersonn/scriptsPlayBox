package br.com.playbox;

import br.com.playbox.config.AppProperties;
import br.com.playbox.model.MachineReport;
import br.com.playbox.service.ReportGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportGeneratorServiceTest {

    private ReportGeneratorService reportService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        reportService = new ReportGeneratorService(null, null, null, properties);
    }

    @Test
    void testFormatCurrency() {
        assertEquals("R$ 0,00", ReportGeneratorService.formatCurrency(0.0));
        assertEquals("R$ 489,00", ReportGeneratorService.formatCurrency(489.0));
        assertEquals("R$ 1.234,56", ReportGeneratorService.formatCurrency(1234.56));
    }

    @Test
    void testBuildReportDates24hAnterior() {
        ZoneId zoneId = ZoneId.of("America/Sao_Paulo");
        ReportGeneratorService.ReportPeriod period = reportService.buildReportDates("24h-anterior", zoneId);

        assertNotNull(period);
        assertTrue(period.title().startsWith("Relatório de "));
        assertTrue(period.startDate().endsWith("T00:00:00.000Z"));
        assertTrue(period.endDate().endsWith("T23:59:59.999Z"));
    }

    @Test
    void testBuildReportDates12hAtual() {
        ZoneId zoneId = ZoneId.of("America/Sao_Paulo");
        ReportGeneratorService.ReportPeriod period = reportService.buildReportDates("12h-atual", zoneId);

        assertNotNull(period);
        assertTrue(period.title().startsWith("Relatório das Últimas 12h ("));
    }

    @Test
    void testBuildReportDatesMes() {
        ZoneId zoneId = ZoneId.of("America/Sao_Paulo");
        ReportGeneratorService.ReportPeriod period = reportService.buildReportDates("mes", zoneId);

        assertNotNull(period);
        assertTrue(period.title().startsWith("Relatório do Mês de "));
        assertTrue(period.startDate().endsWith("-01T00:00:00.000Z"));
        assertTrue(period.endDate().endsWith("T23:59:59.999Z"));
    }

    @Test
    void testUnifyReports() {
        MachineReport r1 = new MachineReport("m1", "Casa Nobre", 0, 10, 20, 0, 0, 2);
        MachineReport r2 = new MachineReport("m1", "Casa Nobre", 5, 0, 0, 30, 40, 1);
        MachineReport r3 = new MachineReport("m2", "Lan House", 0, 50, 0, 0, 0, 1);

        List<MachineReport> unified = reportService.unifyReports(List.of(r1, r3), List.of(r2));

        assertEquals(2, unified.size());

        MachineReport casaNobre = unified.stream().filter(m -> m.getNomeMaquina().equals("Casa Nobre")).findFirst().orElseThrow();
        assertEquals(5.0, casaNobre.getValorEstornos());
        assertEquals(10.0, casaNobre.getValorEspecie());
        assertEquals(20.0, casaNobre.getValorPix());
        assertEquals(30.0, casaNobre.getValorCredito());
        assertEquals(40.0, casaNobre.getValorDebito());
        assertEquals(100.0, casaNobre.getValorTotal()); // 10 + 20 + 30 + 40
        assertEquals(3.0, casaNobre.getQuantidadePremios()); // 2 + 1
        assertEquals(100.0 / 3.0, casaNobre.getReceitaPorPremioLiberado(), 0.001);
    }

    @Test
    void testFormatWhatsAppMessage() {
        MachineReport m1 = new MachineReport("1", "Casa Nobre - Sertania", 0, 36, 0, 0, 0, 2);
        MachineReport m2 = new MachineReport("2", "Lan House - Custódia", 0, 2, 0, 0, 0, 2);

        String message = reportService.formatWhatsAppMessage(List.of(m1, m2), "Relatório de 31/07/2026");

        assertTrue(message.contains("*📊 Relatório de 31/07/2026 📊*"));
        assertTrue(message.contains("*Resumo Geral:*"));
        assertTrue(message.contains("💰 Valor Total: R$ 38,00"));
        assertTrue(message.contains("🎁 Prêmios Liberados: 4 unidades"));
        assertTrue(message.contains("💸 Receita/Prêmio: R$ 9,50"));
        assertTrue(message.contains("🤖 *Casa Nobre - Sertania*"));
        assertTrue(message.contains("🤖 *Lan House - Custódia*"));
    }
}

