package br.com.playbox;

import br.com.playbox.controller.PlayBoxModulosController;
import br.com.playbox.dto.AtualizarFirmwareDTO;
import br.com.playbox.dto.ComandoRespostaDTO;
import br.com.playbox.dto.EnviarCreditoDTO;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
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
        when(mqttService.enviarPulsoCredito(eq("PB-00001"), eq(2), eq("PAY-20261030-98421"))).thenReturn(true);

        EnviarCreditoDTO dto = new EnviarCreditoDTO(2, "PAY-20261030-98421");
        ResponseEntity<ComandoRespostaDTO> res = controller.enviarCredito("PB-00001", dto);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("SUCCESS", res.getBody().getStatus());
        assertEquals("PB-00001", res.getBody().getDeviceId());
        assertEquals(2, res.getBody().getQuantidade());
        assertEquals("PAY-20261030-98421", res.getBody().getTxId());
    }

    @Test
    @DisplayName("Deve retornar serviço indisponível se MQTT desconectado")
    void testEnviarCreditoDesconectado() {
        when(mqttService.enviarPulsoCredito(anyString(), anyInt(), anyString())).thenReturn(false);

        EnviarCreditoDTO dto = new EnviarCreditoDTO(1, null);
        ResponseEntity<ComandoRespostaDTO> res = controller.enviarCredito("PB-00001", dto);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("ERROR", res.getBody().getStatus());
    }

    @Test
    @DisplayName("Deve resetar módulo remotamente via MQTT")
    void testResetarModulo() {
        when(mqttService.enviarResetRemoto("PB-00001")).thenReturn(true);

        ResponseEntity<ComandoRespostaDTO> res = controller.resetarModulo("PB-00001");
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("SUCCESS", res.getBody().getStatus());
    }

    @Test
    @DisplayName("Deve enviar comando OTA para o módulo")
    void testAtualizarFirmwareRemoto() {
        when(mqttService.enviarComandoOTA("PB-00001", "http://ota.playbox.com/firmware.bin")).thenReturn(true);

        AtualizarFirmwareDTO dto = new AtualizarFirmwareDTO("http://ota.playbox.com/firmware.bin");
        ResponseEntity<ComandoRespostaDTO> res = controller.atualizarFirmwareRemoto("PB-00001", dto);
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("SUCCESS", res.getBody().getStatus());
    }

    @Test
    @DisplayName("Deve registrar entrada em espécie com sucesso via REST")
    void testRegistrarEspecieSucesso() {
        Transacao txMock = new Transacao();
        txMock.setExternalTxId("CASH-PB-00001-001");
        txMock.setValor(5.0);

        when(mqttService.registrarTransacaoEspecieManual(eq("PB-00001"), eq(1), eq(5.0), eq("CASH-PB-00001-001"))).thenReturn(txMock);

        br.com.playbox.dto.EnviarEspecieDTO dto = new br.com.playbox.dto.EnviarEspecieDTO(1, 5.0, "CASH-PB-00001-001");
        ResponseEntity<ComandoRespostaDTO> res = controller.registrarEspecie("PB-00001", dto);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("SUCCESS", res.getBody().getStatus());
        assertEquals("PB-00001", res.getBody().getDeviceId());
        assertEquals(1, res.getBody().getQuantidade());
        assertEquals("CASH-PB-00001-001", res.getBody().getTxId());
    }

    @Test
    @DisplayName("Deve registrar saída de pelúcia com sucesso via REST")
    void testRegistrarPremioSucesso() {
        Transacao txMock = new Transacao();
        txMock.setExternalTxId("PREMIO-PB-00001-001");
        txMock.setQuantidadePremios(1.0);

        when(mqttService.registrarSaidaPeluciaManual(eq("PB-00001"), eq(1), eq("PREMIO-PB-00001-001"))).thenReturn(txMock);

        br.com.playbox.dto.EnviarPremioDTO dto = new br.com.playbox.dto.EnviarPremioDTO(1, "PREMIO-PB-00001-001");
        ResponseEntity<ComandoRespostaDTO> res = controller.registrarPremio("PB-00001", dto);

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals("SUCCESS", res.getBody().getStatus());
        assertEquals("PB-00001", res.getBody().getDeviceId());
        assertEquals(1, res.getBody().getQuantidade());
        assertEquals("PREMIO-PB-00001-001", res.getBody().getTxId());
    }
}
