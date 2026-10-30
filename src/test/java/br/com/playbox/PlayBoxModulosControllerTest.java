package br.com.playbox;

import br.com.playbox.controller.PlayBoxModulosController;
import br.com.playbox.entity.Maquina;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.service.PlayBoxMqttService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayBoxModulosControllerTest {

    private PlayBoxMqttService mqttService;
    private MaquinaRepository maquinaRepository;
    private PlayBoxModulosController controller;

    @BeforeEach
    void setUp() {
        mqttService = mock(PlayBoxMqttService.class);
        maquinaRepository = mock(MaquinaRepository.class);
        controller = new PlayBoxModulosController(mqttService, maquinaRepository);
    }

    @Test
    @DisplayName("Deve retornar status dos módulos e conexão com o broker MQTT")
    void testGetMqttStatus() {
        when(mqttService.getStatusInfo()).thenReturn(Map.of("connected", true, "brokerHost", "broker.hivemq.com"));

        Maquina m1 = new Maquina();
        m1.setOnline(true);
        Maquina m2 = new Maquina();
        m2.setOnline(false);

        when(maquinaRepository.findByIntegracao("PLAYBOX")).thenReturn(List.of(m1, m2));

        ResponseEntity<Map<String, Object>> res = controller.getMqttStatus();
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(2, res.getBody().get("totalModulosPlayBox"));
        assertEquals(1L, res.getBody().get("modulosOnline"));
    }

    @Test
    @DisplayName("Deve listar todos os módulos PlayBox")
    void testListarModulosPlayBox() {
        Maquina m = new Maquina();
        m.setIntegracao("PLAYBOX");
        m.setExternalId("PB-00001");
        when(maquinaRepository.findByIntegracao("PLAYBOX")).thenReturn(List.of(m));

        ResponseEntity<List<Maquina>> res = controller.listarModulosPlayBox();
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(1, res.getBody().size());
        assertEquals("PB-00001", res.getBody().get(0).getExternalId());
    }

    @Test
    @DisplayName("Deve enviar comando de crédito para o módulo com sucesso")
    void testEnviarCreditoSucesso() {
        when(mqttService.enviarPulsoCredito(eq("PB-00001"), eq(2), anyString())).thenReturn(true);

        ResponseEntity<Map<String, Object>> res = controller.enviarCredito("PB-00001", Map.of("quantidade", 2, "tx_id", "TX-100"));
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("SUCCESS", res.getBody().get("status"));
        assertEquals("PB-00001", res.getBody().get("deviceId"));
        assertEquals(2, res.getBody().get("quantidade"));
    }

    @Test
    @DisplayName("Deve retornar serviço indisponível se MQTT desconectado")
    void testEnviarCreditoDesconectado() {
        when(mqttService.enviarPulsoCredito(anyString(), anyInt(), anyString())).thenReturn(false);

        ResponseEntity<Map<String, Object>> res = controller.enviarCredito("PB-00001", Map.of("quantidade", 1));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertEquals("ERROR", res.getBody().get("status"));
    }

    @Test
    @DisplayName("Deve resetar módulo remotamente via MQTT")
    void testResetarModulo() {
        when(mqttService.enviarResetRemoto("PB-00001")).thenReturn(true);

        ResponseEntity<Map<String, Object>> res = controller.resetarModulo("PB-00001");
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("SUCCESS", res.getBody().get("status"));
    }

    @Test
    @DisplayName("Deve enviar comando OTA para o módulo")
    void testAtualizarFirmwareRemoto() {
        when(mqttService.enviarComandoOTA("PB-00001", "http://ota.playbox.com/firmware.bin")).thenReturn(true);

        ResponseEntity<Map<String, Object>> res = controller.atualizarFirmwareRemoto("PB-00001", Map.of("url", "http://ota.playbox.com/firmware.bin"));
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("SUCCESS", res.getBody().get("status"));
    }
}
