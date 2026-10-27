package br.com.playbox;

import br.com.playbox.entity.Coleta;
import br.com.playbox.entity.Estabelecimento;
import br.com.playbox.entity.Maquina;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ColetaServiceTest {

    @Test
    void testColetaCriacaoPendente() {
        Maquina maq = new Maquina();
        maq.setId(1L);
        maq.setNomeIntegracao("Praça do Sabor");

        Estabelecimento est = new Estabelecimento();
        est.setId(10L);
        est.setNome("Praça do Sabor");
        maq.setEstabelecimento(est);

        LocalDateTime dataColeta = LocalDateTime.of(2026, 10, 21, 14, 30);
        LocalDateTime dataUltima = LocalDateTime.of(2026, 10, 3, 9, 47);

        // Registro de coleta sem valor contado ainda
        Coleta coleta = new Coleta(maq, est, dataColeta, dataUltima, 380.0, null, "Visita de rotina", "João");

        assertEquals("PENDENTE_CONFERENCIA", coleta.getStatus());
        assertEquals(380.0, coleta.getValorEsperado());
        assertNull(coleta.getValorReal());
        assertNull(coleta.getDiferenca());
        assertNull(coleta.getDataHoraConferencia());
        assertEquals("Visita de rotina", coleta.getObservacoes());
        assertEquals("João", coleta.getResponsavel());
    }

    @Test
    void testColetaConferenciaExata() {
        Maquina maq = new Maquina();
        maq.setId(1L);

        Coleta coleta = new Coleta(maq, null, LocalDateTime.now(), null, 250.0, null, null, null);
        assertEquals("PENDENTE_CONFERENCIA", coleta.getStatus());

        // Agora realiza a conferência
        LocalDateTime dataConf = LocalDateTime.of(2026, 10, 21, 20, 0);
        coleta.registrarConferencia(250.0, dataConf, "Batimento perfeito");

        assertEquals("CONFERIDO", coleta.getStatus());
        assertEquals(250.0, coleta.getValorReal());
        assertEquals(0.0, coleta.getDiferenca());
        assertEquals(dataConf, coleta.getDataHoraConferencia());
        assertTrue(coleta.getObservacoes().contains("Batimento perfeito"));
    }

    @Test
    void testColetaConferenciaComFalta() {
        Maquina maq = new Maquina();
        maq.setId(1L);

        Coleta coleta = new Coleta(maq, null, LocalDateTime.now(), null, 300.0, null, null, null);
        coleta.registrarConferencia(280.0, LocalDateTime.now(), "Faltou 20 reais");

        assertEquals("CONFERIDO_DIVERGENTE", coleta.getStatus());
        assertEquals(280.0, coleta.getValorReal());
        assertEquals(-20.0, coleta.getDiferenca());
    }

    @Test
    void testColetaConferenciaComSobra() {
        Maquina maq = new Maquina();
        maq.setId(1L);

        Coleta coleta = new Coleta(maq, null, LocalDateTime.now(), null, 100.0, null, null, null);
        coleta.registrarConferencia(120.0, LocalDateTime.now(), "Sobrou 20 reais");

        assertEquals("CONFERIDO_DIVERGENTE", coleta.getStatus());
        assertEquals(120.0, coleta.getValorReal());
        assertEquals(20.0, coleta.getDiferenca());
    }

    @Test
    void testCancelarColeta() {
        Maquina maq = new Maquina();
        maq.setId(1L);

        Coleta coleta = new Coleta(maq, null, LocalDateTime.now(), null, 150.0, null, "Observação inicial", null);
        assertEquals("PENDENTE_CONFERENCIA", coleta.getStatus());
        assertFalse(coleta.isCancelada());

        coleta.cancelar("Lançada por engano na máquina errada");
        assertEquals("CANCELADA", coleta.getStatus());
        assertTrue(coleta.isCancelada());
        assertTrue(coleta.getObservacoes().contains("[CANCELADA]: Lançada por engano na máquina errada"));
    }

    @Test
    void testCalcularPreviaPrimeiraColeta() {
        br.com.playbox.repository.ColetaRepository coletaRepo = org.mockito.Mockito.mock(br.com.playbox.repository.ColetaRepository.class);
        br.com.playbox.repository.MaquinaRepository maquinaRepo = org.mockito.Mockito.mock(br.com.playbox.repository.MaquinaRepository.class);
        br.com.playbox.repository.TransacaoRepository transacaoRepo = org.mockito.Mockito.mock(br.com.playbox.repository.TransacaoRepository.class);
        br.com.playbox.service.TransactionImportService importService = org.mockito.Mockito.mock(br.com.playbox.service.TransactionImportService.class);
        br.com.playbox.config.AppProperties props = new br.com.playbox.config.AppProperties();

        br.com.playbox.service.ColetaService service = new br.com.playbox.service.ColetaService(
                coletaRepo, maquinaRepo, transacaoRepo, importService, props, null, null
        );

        Maquina maq = new Maquina();
        maq.setId(10L);
        maq.setNomeIntegracao("Máquina Teste");
        org.mockito.Mockito.when(maquinaRepo.findById(10L)).thenReturn(java.util.Optional.of(maq));
        org.mockito.Mockito.when(coletaRepo.findFirstByMaquinaIdAndStatusNotAndDataHoraColetaBeforeOrderByDataHoraColetaDesc(
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.eq("CANCELADA"), org.mockito.ArgumentMatchers.any()
        )).thenReturn(java.util.Optional.empty());

        org.mockito.Mockito.when(transacaoRepo.sumValorCashByMaquinaUntil(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(450.0);
        org.mockito.Mockito.when(transacaoRepo.sumValorTotalByMaquinaUntil(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1200.0);
        org.mockito.Mockito.when(transacaoRepo.sumQuantidadePremiosByMaquinaUntil(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(25.0);

        br.com.playbox.service.ColetaService.PreviaColetaDTO previa = service.calcularPrevia(10L, LocalDateTime.of(2026, 10, 27, 14, 0));

        assertNotNull(previa);
        assertTrue(previa.primeiraColeta());
        assertEquals(450.0, previa.valorEsperado());
        assertEquals(450.0, previa.totalEntradasEspecie());
        assertEquals(1200.0, previa.totalFaturadoPeriodo());
        assertEquals(25.0, previa.quantidadePremiosPeriodo());
        assertTrue(previa.periodoFormatado().contains("Marco Zero"));
    }

    @Test
    void testPainelProximasColetas() {
        br.com.playbox.repository.ColetaRepository coletaRepo = org.mockito.Mockito.mock(br.com.playbox.repository.ColetaRepository.class);
        br.com.playbox.repository.MaquinaRepository maquinaRepo = org.mockito.Mockito.mock(br.com.playbox.repository.MaquinaRepository.class);
        br.com.playbox.repository.TransacaoRepository transacaoRepo = org.mockito.Mockito.mock(br.com.playbox.repository.TransacaoRepository.class);
        br.com.playbox.service.TransactionImportService importService = org.mockito.Mockito.mock(br.com.playbox.service.TransactionImportService.class);
        br.com.playbox.config.AppProperties props = new br.com.playbox.config.AppProperties();

        br.com.playbox.service.ColetaService service = new br.com.playbox.service.ColetaService(
                coletaRepo, maquinaRepo, transacaoRepo, importService, props, null, null
        );

        Maquina maq1 = new Maquina();
        maq1.setId(1L);
        maq1.setNomeIntegracao("Máquina Loja A");

        Maquina maq2 = new Maquina();
        maq2.setId(2L);
        maq2.setNomeIntegracao("Máquina Loja B");

        org.mockito.Mockito.when(maquinaRepo.findByAtivoTrue()).thenReturn(java.util.List.of(maq1, maq2));

        // Maq1 nunca coletada (marco zero) com R$ 350 em espécie
        org.mockito.Mockito.when(coletaRepo.findFirstByMaquinaIdAndStatusNotOrderByDataHoraColetaDesc(1L, "CANCELADA"))
                .thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(transacaoRepo.sumValorCashByMaquinaUntil(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(350.0);

        // Maq2 teve coleta há 10 dias com R$ 200 em espécie
        LocalDateTime dezDiasAtras = LocalDateTime.now().minusDays(10);
        Coleta c2 = new Coleta();
        c2.setDataHoraColeta(dezDiasAtras);
        org.mockito.Mockito.when(coletaRepo.findFirstByMaquinaIdAndStatusNotOrderByDataHoraColetaDesc(2L, "CANCELADA"))
                .thenReturn(java.util.Optional.of(c2));
        org.mockito.Mockito.when(transacaoRepo.sumValorCashByMaquinaAndPeriod(
                org.mockito.ArgumentMatchers.eq(2L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        )).thenReturn(200.0);

        br.com.playbox.service.ColetaService.PainelProximasColetasDTO painel = service.obterPainelProximasColetas();

        assertNotNull(painel);
        assertEquals(550.0, painel.totalEsperadoCofres());
        assertEquals(2, painel.totalMaquinas());
        assertEquals(2, painel.maquinasComSaldo());
        assertEquals(2, painel.maquinas().size());
        assertEquals(1L, painel.maquinas().get(0).maquinaId());
        assertEquals(350.0, painel.maquinas().get(0).valorEsperado());
        assertEquals(2L, painel.maquinas().get(1).maquinaId());
        assertEquals(200.0, painel.maquinas().get(1).valorEsperado());
    }
}

