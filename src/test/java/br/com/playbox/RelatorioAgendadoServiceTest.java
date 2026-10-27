package br.com.playbox;

import br.com.playbox.entity.RelatorioAgendado;
import br.com.playbox.repository.RelatorioAgendadoRepository;
import br.com.playbox.service.DynamicSchedulerService;
import br.com.playbox.service.EstoqueAlertaService;
import br.com.playbox.service.RelatorioAgendadoService;
import br.com.playbox.service.ReportGeneratorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class RelatorioAgendadoServiceTest {

    private RelatorioAgendadoRepository repository;
    private ReportGeneratorService reportGeneratorService;
    private EstoqueAlertaService estoqueAlertaService;
    private DynamicSchedulerService dynamicSchedulerService;
    private RelatorioAgendadoService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(RelatorioAgendadoRepository.class);
        reportGeneratorService = Mockito.mock(ReportGeneratorService.class);
        estoqueAlertaService = Mockito.mock(EstoqueAlertaService.class);
        dynamicSchedulerService = Mockito.mock(DynamicSchedulerService.class);

        service = new RelatorioAgendadoService(
                repository,
                reportGeneratorService,
                estoqueAlertaService,
                dynamicSchedulerService
        );
    }

    @Test
    void testRecalcularCronDiario() {
        RelatorioAgendado r = new RelatorioAgendado();
        r.setNome("Teste Diário");
        r.setTipo("FATURAMENTO_24H");
        r.setFrequencia("DIARIO");
        r.setHora("07:30");
        r.recalcularCron();

        assertEquals("0 30 7 * * *", r.getCronExpression());
    }

    @Test
    void testRecalcularCronIntervaloMinutos() {
        RelatorioAgendado r = new RelatorioAgendado();
        r.setNome("Teste Intervalo");
        r.setTipo("FATURAMENTO_HOJE");
        r.setFrequencia("INTERVALO");
        r.setIntervaloMinutos(30);
        r.recalcularCron();

        assertEquals("0 */30 * * * *", r.getCronExpression());
    }

    @Test
    void testRecalcularCronIntervaloHoras() {
        RelatorioAgendado r = new RelatorioAgendado();
        r.setNome("Teste Intervalo 2h");
        r.setTipo("FATURAMENTO_HOJE");
        r.setFrequencia("INTERVALO");
        r.setIntervaloMinutos(120);
        r.recalcularCron();

        assertEquals("0 0 */2 * * *", r.getCronExpression());
    }

    @Test
    void testRecalcularCronMensal() {
        RelatorioAgendado r = new RelatorioAgendado();
        r.setNome("Teste Mensal");
        r.setTipo("FATURAMENTO_MES");
        r.setFrequencia("MENSAL");
        r.setDiaDoMes(5);
        r.setHora("14:15");
        r.recalcularCron();

        assertEquals("0 15 14 5 * *", r.getCronExpression());
    }

    @Test
    void testSalvarRelatorioReagenda() {
        RelatorioAgendado r = new RelatorioAgendado();
        r.setNome("Novo Agendamento");
        r.setTipo("FATURAMENTO_24H");
        r.setFrequencia("DIARIO");
        r.setHora("00:00");

        when(repository.save(any(RelatorioAgendado.class))).thenAnswer(invocation -> {
            RelatorioAgendado saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });

        RelatorioAgendado salvo = service.salvar(r);
        assertNotNull(salvo.getId());
        assertEquals("0 0 0 * * *", salvo.getCronExpression());
        verify(dynamicSchedulerService, times(1)).rescheduleRelatoriosAgendados();
    }

    @Test
    void testExecutarDisparoFaturamento24h() {
        RelatorioAgendado r = new RelatorioAgendado();
        r.setId(10L);
        r.setNome("Disparo 24h");
        r.setTipo("FATURAMENTO_24H");
        r.setTipoExibicao("GERAL");
        r.setWhatsappChatId("12345@c.us");

        when(repository.findById(10L)).thenReturn(Optional.of(r));
        when(reportGeneratorService.generateAndSendReport(eq("24h-anterior"), eq("GERAL"), eq("12345@c.us")))
                .thenReturn(true);
        when(repository.save(any(RelatorioAgendado.class))).thenReturn(r);

        boolean sucesso = service.executarDisparo(10L);
        assertTrue(sucesso);
        assertEquals("SUCCESS", r.getUltimoStatus());
        assertNotNull(r.getUltimoDisparo());
    }

    @Test
    void testExecutarDisparoAlertaEstoque() {
        RelatorioAgendado r = new RelatorioAgendado();
        r.setId(11L);
        r.setNome("Disparo Estoque");
        r.setTipo("ALERTA_ESTOQUE");
        r.setWhatsappChatId("grupo@g.us");

        when(repository.findById(11L)).thenReturn(Optional.of(r));
        when(estoqueAlertaService.verificarEEnviarAlertas(eq("grupo@g.us"))).thenReturn(3);
        when(repository.save(any(RelatorioAgendado.class))).thenReturn(r);

        boolean sucesso = service.executarDisparo(11L);
        assertTrue(sucesso);
        assertEquals("SUCCESS", r.getUltimoStatus());
        assertTrue(r.getUltimaMensagem().contains("3 lojas críticas"));
    }
}
