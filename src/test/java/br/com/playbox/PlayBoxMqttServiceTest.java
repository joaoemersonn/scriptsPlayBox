package br.com.playbox;

import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.TransacaoRepository;
import br.com.playbox.service.EstoqueService;
import br.com.playbox.service.PlayBoxMqttService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlayBoxMqttServiceTest {

    private AppProperties properties;
    private MaquinaRepository maquinaRepository;
    private TransacaoRepository transacaoRepository;
    private EstoqueService estoqueService;
    private ObjectMapper objectMapper;
    private PlayBoxMqttService mqttService;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        properties.getMqtt().setEnabled(false); // não tenta conectar na rede durante testes unitários
        properties.getMqtt().setBrokerHost("localhost");
        properties.getMqtt().setBrokerPort(1883);
        properties.getMqtt().setBaseTopic("playbox/maquinas");

        maquinaRepository = mock(MaquinaRepository.class);
        transacaoRepository = mock(TransacaoRepository.class);
        estoqueService = mock(EstoqueService.class);
        objectMapper = new ObjectMapper();

        mqttService = new PlayBoxMqttService(
                properties,
                maquinaRepository,
                transacaoRepository,
                estoqueService,
                objectMapper
        );
    }

    @Test
    @DisplayName("Deve autocadastrar máquina e atualizar dados ao receber evento 'online'")
    void testProcessarMensagemOnline() {
        when(maquinaRepository.findByIntegracaoAndExternalId("PLAYBOX", "PB-00001")).thenReturn(Optional.empty());
        when(maquinaRepository.save(any(Maquina.class))).thenAnswer(i -> i.getArgument(0));

        String topic = "playbox/maquinas/PB-00001/status";
        String payload = """
            {
              "evento": "online",
              "maquina_id": "PB-00001",
              "ip": "192.168.1.150",
              "total_digitais": 8,
              "relogio_entrada": 35,
              "relogio_saida": 4
            }
            """;

        mqttService.processarMensagemStatus(topic, payload);

        ArgumentCaptor<Maquina> captor = ArgumentCaptor.forClass(Maquina.class);
        verify(maquinaRepository, atLeastOnce()).save(captor.capture());
        Maquina salva = captor.getValue();

        assertEquals("PLAYBOX", salva.getIntegracao());
        assertEquals("PB-00001", salva.getExternalId());
        assertEquals("192.168.1.150", salva.getIp());
        assertTrue(salva.getOnline());
        assertEquals(8L, salva.getTotalDigitais());
        assertEquals(35L, salva.getTotalRelogioEntrada());
        assertEquals(4L, salva.getTotalRelogioSaida());
    }

    @Test
    @DisplayName("Deve registrar saída de estoque automática ao detectar incremento no odômetro de pelúcias")
    void testProcessarMensagemPingComPremioDetectado() {
        Maquina m = new Maquina();
        m.setId(10L);
        m.setIntegracao("PLAYBOX");
        m.setExternalId("PB-00001");
        m.setTotalRelogioSaida(4L);
        m.setTotalRelogioEntrada(35L);

        when(maquinaRepository.findByIntegracaoAndExternalId("PLAYBOX", "PB-00001")).thenReturn(Optional.of(m));
        when(maquinaRepository.save(any(Maquina.class))).thenAnswer(i -> i.getArgument(0));

        String topic = "playbox/maquinas/PB-00001/status";
        String payload = """
            {
              "evento": "ping",
              "maquina_id": "PB-00001",
              "rssi": -55,
              "total_digitais": 8,
              "relogio_entrada": 35,
              "relogio_saida": 5,
              "uptime": 1200
            }
            """;

        mqttService.processarMensagemStatus(topic, payload);

        // Verifica que abateu 1 pelúcia (5 - 4 = 1) no EstoqueService
        verify(estoqueService, times(1)).processarSaidaAutomatica(
                eq(m),
                any(LocalDate.class),
                eq(1),
                eq("PLAYBOX"),
                eq("SAIDA_ODOMETRO_PB-00001_5")
        );

        assertEquals(5L, m.getTotalRelogioSaida());
        assertEquals(-55, m.getRssi());
        assertEquals(1200L, m.getUptimeSeconds());
    }

    @Test
    @DisplayName("Deve registrar transação digital ao receber confirmação de pulso executado (evento 'ok')")
    void testProcessarMensagemOkPulsoConfirmado() {
        Maquina m = new Maquina();
        m.setId(10L);
        m.setIntegracao("PLAYBOX");
        m.setExternalId("PB-00001");

        when(maquinaRepository.findByIntegracaoAndExternalId("PLAYBOX", "PB-00001")).thenReturn(Optional.of(m));
        when(maquinaRepository.save(any(Maquina.class))).thenAnswer(i -> i.getArgument(0));
        when(transacaoRepository.findByIntegracaoAndExternalTxId("PLAYBOX", "TX-TEST-999")).thenReturn(Optional.empty());

        String topic = "playbox/maquinas/PB-00001/status";
        String payload = """
            {
              "evento": "ok",
              "tx_id": "TX-TEST-999",
              "maquina_id": "PB-00001",
              "pulsos_liberados": 2,
              "total_digitais_acumulado": 10,
              "relogio_entrada": 35,
              "relogio_saida": 5
            }
            """;

        mqttService.processarMensagemStatus(topic, payload);

        ArgumentCaptor<Transacao> txCaptor = ArgumentCaptor.forClass(Transacao.class);
        verify(transacaoRepository, times(1)).save(txCaptor.capture());
        Transacao tx = txCaptor.getValue();

        assertEquals("TX-TEST-999", tx.getExternalTxId());
        assertEquals("PLAYBOX", tx.getIntegracao());
        assertEquals("CREDITO_DIGITAL", tx.getTipoPagamento());
        assertEquals(10.0, tx.getValor()); // 2 pulsos * R$ 5,00
    }

    @Test
    @DisplayName("Deve registrar transação digital ao receber ACK com status 'ok' (firmware ESP8266 OLED)")
    void testProcessarMensagemStatusOkOledFirmware() {
        Maquina m = new Maquina();
        m.setId(10L);
        m.setIntegracao("PLAYBOX");
        m.setExternalId("PB-00001");

        when(maquinaRepository.findByIntegracaoAndExternalId("PLAYBOX", "PB-00001")).thenReturn(Optional.of(m));
        when(maquinaRepository.save(any(Maquina.class))).thenAnswer(i -> i.getArgument(0));
        when(transacaoRepository.findByIntegracaoAndExternalTxId("PLAYBOX", "TX-OLED-555")).thenReturn(Optional.empty());

        // Payload idêntico ao gerado em pixmaq_playbox.ino
        String topic = "playbox/maquinas/PB-00001/status";
        String payload = """
            {
              "status": "ok",
              "tx_id": "TX-OLED-555",
              "maquina_id": "PB-00001",
              "pulsos_liberados": 3,
              "total_acumulado": 15
            }
            """;

        mqttService.processarMensagemStatus(topic, payload);

        ArgumentCaptor<Transacao> txCaptor = ArgumentCaptor.forClass(Transacao.class);
        verify(transacaoRepository, times(1)).save(txCaptor.capture());
        Transacao tx = txCaptor.getValue();

        assertEquals("TX-OLED-555", tx.getExternalTxId());
        assertEquals("PLAYBOX", tx.getIntegracao());
        assertEquals("CREDITO_DIGITAL", tx.getTipoPagamento());
        assertEquals(15.0, tx.getValor()); // 3 pulsos * R$ 5,00
        assertEquals(15.0, tx.getValorPix());
    }

    @Test
    @DisplayName("Deve registrar transação em espécie ao receber evento explícito de entrada de moedeiro 12V")
    void testProcessarMensagemEventoEspecie() {
        Maquina m = new Maquina();
        m.setId(10L);
        m.setIntegracao("PLAYBOX");
        m.setExternalId("PB-00001");

        when(maquinaRepository.findByIntegracaoAndExternalId("PLAYBOX", "PB-00001")).thenReturn(Optional.of(m));
        when(maquinaRepository.save(any(Maquina.class))).thenAnswer(i -> i.getArgument(0));
        when(transacaoRepository.findByIntegracaoAndExternalTxId(eq("PLAYBOX"), anyString())).thenReturn(Optional.empty());

        // Payload do firmware Zapizi-compatível quando moeda/nota é inserida
        String topic = "playbox/maquinas/PB-00001/status";
        String payload = """
            {
              "evento": "relogio_entrada",
              "maquina_id": "PB-00001",
              "tipo": "especie",
              "total_relogio_entrada": 40,
              "relogio_saida": 5
            }
            """;

        mqttService.processarMensagemStatus(topic, payload);

        ArgumentCaptor<Transacao> txCaptor = ArgumentCaptor.forClass(Transacao.class);
        verify(transacaoRepository, atLeastOnce()).save(txCaptor.capture());
        Transacao tx = txCaptor.getValue();

        assertEquals("PLAYBOX", tx.getIntegracao());
        assertEquals("DINHEIRO", tx.getTipoPagamento());
        assertEquals(5.0, tx.getValor());
        assertEquals(5.0, tx.getValorCash());
    }

    @Test
    @DisplayName("Deve registrar saída de pelúcia ao receber evento explícito de saída 12V")
    void testProcessarMensagemEventoSaidaPelucia() {
        Maquina m = new Maquina();
        m.setId(10L);
        m.setIntegracao("PLAYBOX");
        m.setExternalId("PB-00001");

        when(maquinaRepository.findByIntegracaoAndExternalId("PLAYBOX", "PB-00001")).thenReturn(Optional.of(m));
        when(maquinaRepository.save(any(Maquina.class))).thenAnswer(i -> i.getArgument(0));
        when(transacaoRepository.findByIntegracaoAndExternalTxId(eq("PLAYBOX"), anyString())).thenReturn(Optional.empty());

        // Payload do firmware Zapizi-compatível quando pelúcia passa pelo sensor óptico
        String topic = "playbox/maquinas/PB-00001/status";
        String payload = """
            {
              "evento": "relogio_saida",
              "maquina_id": "PB-00001",
              "tipo": "pelucia_ganha",
              "total_relogio_saida": 6,
              "relogio_entrada": 40
            }
            """;

        mqttService.processarMensagemStatus(topic, payload);

        // Verifica que abateu estoque
        verify(estoqueService, atLeastOnce()).processarSaidaAutomatica(
                eq(m),
                any(LocalDate.class),
                eq(1),
                eq("PLAYBOX"),
                anyString()
        );

        // Verifica que registrou a transação de premiação
        ArgumentCaptor<Transacao> txCaptor = ArgumentCaptor.forClass(Transacao.class);
        verify(transacaoRepository, atLeastOnce()).save(txCaptor.capture());
        Transacao tx = txCaptor.getValue();

        assertEquals("PLAYBOX", tx.getIntegracao());
        assertEquals("PREMIOS", tx.getTipoPagamento());
        assertEquals(1.0, tx.getQuantidadePremios());
        assertEquals(0.0, tx.getValor());
    }

    @Test
    @DisplayName("Deve registrar imediatamente crédito digital no banco de dados")
    void testRegistrarTransacaoCreditoDigitalDireto() {
        Maquina m = new Maquina();
        m.setId(10L);
        m.setIntegracao("PLAYBOX");
        m.setExternalId("PB-00001");

        when(maquinaRepository.findByIntegracaoAndExternalId("PLAYBOX", "PB-00001")).thenReturn(Optional.of(m));
        when(transacaoRepository.findByIntegracaoAndExternalTxId("PLAYBOX", "TX-MANUAL-1")).thenReturn(Optional.empty());
        when(transacaoRepository.save(any(Transacao.class))).thenAnswer(i -> i.getArgument(0));

        Transacao tx = mqttService.registrarTransacaoCreditoDigital("PB-00001", "TX-MANUAL-1", 2, 10.0);

        assertNotNull(tx);
        assertEquals("TX-MANUAL-1", tx.getExternalTxId());
        assertEquals("CREDITO_DIGITAL", tx.getTipoPagamento());
        assertEquals(10.0, tx.getValor());
        assertEquals(10.0, tx.getValorPix());
    }

    @Test
    @DisplayName("Deve retornar informações corretas de configuração e status")
    void testStatusInfo() {
        Map<String, Object> status = mqttService.getStatusInfo();
        assertNotNull(status);
        assertEquals(false, status.get("enabled"));
        assertEquals("localhost", status.get("brokerHost"));
        assertEquals(1883, status.get("brokerPort"));
        assertEquals("playbox/maquinas", status.get("baseTopic"));
        assertEquals(false, status.get("connected"));
    }
}
