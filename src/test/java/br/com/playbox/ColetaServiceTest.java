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
}

