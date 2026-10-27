package br.com.playbox;

import br.com.playbox.controller.RelatorioAgendadoController;
import br.com.playbox.entity.RelatorioAgendado;
import br.com.playbox.service.RelatorioAgendadoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

public class RelatorioAgendadoControllerTest {

    private RelatorioAgendadoService service;
    private RelatorioAgendadoController controller;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(RelatorioAgendadoService.class);
        controller = new RelatorioAgendadoController(service);
    }

    @Test
    void testListarTodos() {
        RelatorioAgendado r1 = new RelatorioAgendado();
        r1.setId(1L);
        r1.setNome("Relatório 1");

        when(service.listarTodos()).thenReturn(List.of(r1));

        ResponseEntity<List<RelatorioAgendado>> resp = controller.listarTodos();
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(1, resp.getBody().size());
        assertEquals("Relatório 1", resp.getBody().get(0).getNome());
    }

    @Test
    void testCriar() {
        RelatorioAgendado r = new RelatorioAgendado();
        r.setNome("Novo");
        r.setTipo("FATURAMENTO_24H");

        RelatorioAgendado salvo = new RelatorioAgendado();
        salvo.setId(10L);
        salvo.setNome("Novo");
        salvo.setTipo("FATURAMENTO_24H");

        when(service.salvar(any(RelatorioAgendado.class))).thenReturn(salvo);

        ResponseEntity<RelatorioAgendado> resp = controller.criar(r);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        assertNotNull(resp.getBody().getId());
    }

    @Test
    void testToggleAtivo() {
        RelatorioAgendado r = new RelatorioAgendado();
        r.setId(5L);
        r.setAtivo(false);

        when(service.toggleAtivo(5L)).thenReturn(r);

        ResponseEntity<RelatorioAgendado> resp = controller.toggleAtivo(5L);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertFalse(resp.getBody().getAtivo());
    }

    @Test
    void testDispararAgora() {
        when(service.executarDisparo(5L)).thenReturn(true);
        RelatorioAgendado r = new RelatorioAgendado();
        r.setId(5L);
        r.setUltimoStatus("SUCCESS");
        r.setUltimaMensagem("Enviado com sucesso");
        when(service.buscarPorId(5L)).thenReturn(Optional.of(r));

        ResponseEntity<Map<String, Object>> resp = controller.dispararAgora(5L);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertTrue((Boolean) resp.getBody().get("success"));
        assertEquals("SUCCESS", resp.getBody().get("ultimoStatus"));
    }
}
