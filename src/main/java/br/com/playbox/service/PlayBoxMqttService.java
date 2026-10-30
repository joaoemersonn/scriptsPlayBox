package br.com.playbox.service;

import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.Maquina;
import br.com.playbox.entity.Transacao;
import br.com.playbox.repository.MaquinaRepository;
import br.com.playbox.repository.TransacaoRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

@Service
public class PlayBoxMqttService implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(PlayBoxMqttService.class);

    private final AppProperties properties;
    private final MaquinaRepository maquinaRepository;
    private final TransacaoRepository transacaoRepository;
    private final EstoqueService estoqueService;
    private final ObjectMapper objectMapper;

    private MqttAsyncClient mqttClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean connecting = false;

    public PlayBoxMqttService(
            AppProperties properties,
            MaquinaRepository maquinaRepository,
            TransacaoRepository transacaoRepository,
            EstoqueService estoqueService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.maquinaRepository = maquinaRepository;
        this.transacaoRepository = transacaoRepository;
        this.estoqueService = estoqueService;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    @PostConstruct
    public void init() {
        if (!properties.getMqtt().isEnabled()) {
            log.info("[PLAYBOX-MQTT] Integração nativa PlayBox MQTT está DESATIVADA por configuração (MQTT_ENABLED=false).");
            return;
        }

        log.info("[PLAYBOX-MQTT] Inicializando serviço MQTT PlayBox. Broker: {} (Base Topic: {})",
                properties.getMqtt().getBrokerUrl(), properties.getMqtt().getBaseTopic());

        // Conecta em thread assíncrona para não atrasar nem bloquear a inicialização do Spring Boot
        scheduler.schedule(this::conectarBroker, 1, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void cleanup() {
        scheduler.shutdownNow();
        if (mqttClient != null) {
            try {
                if (mqttClient.isConnected()) {
                    mqttClient.disconnect();
                }
                mqttClient.close();
                log.info("[PLAYBOX-MQTT] Cliente MQTT desconectado e recursos liberados com sucesso.");
            } catch (Exception e) {
                log.warn("[PLAYBOX-MQTT] Aviso ao encerrar cliente MQTT: {}", e.getMessage());
            }
        }
    }

    private synchronized void conectarBroker() {
        if (mqttClient != null && mqttClient.isConnected()) {
            return;
        }
        if (connecting) {
            return;
        }
        connecting = true;

        AppProperties.Mqtt cfg = properties.getMqtt();
        String brokerUrl = cfg.getBrokerUrl();
        String clientId = cfg.getClientId() + "-" + UUID.randomUUID().toString().substring(0, 6);

        try {
            if (mqttClient == null) {
                mqttClient = new MqttAsyncClient(brokerUrl, clientId, new MemoryPersistence());
                mqttClient.setCallback(this);
            }

            MqttConnectOptions options = new MqttConnectOptions();
            options.setAutomaticReconnect(true);
            options.setCleanSession(true);
            options.setConnectionTimeout(cfg.getConnectionTimeout());
            options.setKeepAliveInterval(cfg.getKeepAliveSeconds());

            if (cfg.getUsername() != null && !cfg.getUsername().isBlank()) {
                options.setUserName(cfg.getUsername().trim());
                if (cfg.getPassword() != null) {
                    options.setPassword(cfg.getPassword().trim().toCharArray());
                }
            }

            log.info("[PLAYBOX-MQTT] Tentando conectar ao broker MQTT: {}...", brokerUrl);
            IMqttToken token = mqttClient.connect(options);
            token.waitForCompletion(10000);
            log.info("[PLAYBOX-MQTT] Conexão iniciada com sucesso!");
        } catch (Exception e) {
            log.warn("[PLAYBOX-MQTT] Não foi possível conectar ao broker MQTT agora: {}. Tentaremos novamente em 15 segundos...", e.getMessage());
            scheduler.schedule(this::conectarBroker, 15, TimeUnit.SECONDS);
        } finally {
            connecting = false;
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.info("[PLAYBOX-MQTT] Conectado ao broker MQTT: {} (reconnect={})", serverURI, reconnect);
        inscreverTopicosTelemetria();
    }

    private void inscreverTopicosTelemetria() {
        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                String baseTopic = properties.getMqtt().getBaseTopic();
                String topicFilter = baseTopic + "/+/status";
                mqttClient.subscribe(topicFilter, 1);
                log.info("[PLAYBOX-MQTT] Inscrito no tópico de telemetria das máquinas: {}", topicFilter);
            }
        } catch (Exception e) {
            log.error("[PLAYBOX-MQTT] Erro ao subscrever nos tópicos de telemetria: {}", e.getMessage());
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("[PLAYBOX-MQTT] Conexão com broker MQTT perdida: {}. Paho reconectará automaticamente...",
                cause != null ? cause.getMessage() : "Desconhecido");
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        String payloadStr = new String(message.getPayload(), StandardCharsets.UTF_8);
        log.debug("[PLAYBOX-MQTT] Mensagem recebida no tópico {}: {}", topic, payloadStr);

        try {
            processarMensagemStatus(topic, payloadStr);
        } catch (Exception e) {
            log.error("[PLAYBOX-MQTT] Erro ao processar mensagem recebida do tópico {}: {}", topic, e.getMessage(), e);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // Confirmação de entrega QoS 1/2
    }

    /**
     * Processa mensagem recebida de uma máquina e sincroniza dados no banco.
     */
    @Transactional
    public void processarMensagemStatus(String topic, String payload) {
        JsonNode json;
        try {
            json = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("[PLAYBOX-MQTT] Payload inválido no tópico {}: {}", topic, payload);
            return;
        }

        // Extrai deviceId do tópico (ex: playbox/maquinas/PB-00001/status -> PB-00001)
        String deviceId = extrairDeviceIdDoTopico(topic);
        if (deviceId == null && json.hasNonNull("maquina_id")) {
            deviceId = json.get("maquina_id").asText().trim();
        }

        if (deviceId == null || deviceId.isBlank()) {
            log.warn("[PLAYBOX-MQTT] Não foi possível identificar o ID do módulo no tópico {}", topic);
            return;
        }

        final String finalDeviceId = deviceId;
        Maquina maquina = maquinaRepository.findByIntegracaoAndExternalId("PLAYBOX", finalDeviceId)
                .orElseGet(() -> autoCadastrarMaquinaPlayBox(finalDeviceId));

        maquina.setOnline(true);
        maquina.setLastHeartbeat(LocalDateTime.now());

        if (json.hasNonNull("ip")) {
            maquina.setIp(json.get("ip").asText().trim());
        }
        if (json.hasNonNull("rssi")) {
            maquina.setRssi(json.get("rssi").asInt());
        }
        if (json.hasNonNull("uptime")) {
            maquina.setUptimeSeconds(json.get("uptime").asLong());
        }

        String evento = json.hasNonNull("evento") ? json.get("evento").asText() : "";

        // 1. ODOMETRO: Créditos Digitais
        long digitais = extrairLong(json, "total_digitais", "total_pix_acumulado", "total_pix", "total_pulsos", "total_acumulado");
        if (digitais > 0) {
            maquina.setTotalDigitais(digitais);
        }

        // 2. ODOMETRO: Relógio de Entrada (Dinheiro Físico / Fichas - 12V)
        if (json.hasNonNull("relogio_entrada")) {
            long entradaAtual = json.get("relogio_entrada").asLong();
            Long entradaAnterior = maquina.getTotalRelogioEntrada();

            if (entradaAnterior != null && entradaAnterior > 0 && entradaAtual > entradaAnterior) {
                long delta = entradaAtual - entradaAnterior;
                log.info("[PLAYBOX-MQTT] 💵 ENTRADA EM ESPÉCIE: Máquina {} registrou +{} entrada(s) mecânica(s) (12V)!", finalDeviceId, delta);
                registrarTransacaoEspecie(maquina, delta, entradaAtual);
            }
            maquina.setTotalRelogioEntrada(entradaAtual);
        }

        // 3. ODOMETRO: Relógio de Saída (Pelúcias Ganhas / Prêmios - 12V)
        if (json.hasNonNull("relogio_saida")) {
            long saidaAtual = json.get("relogio_saida").asLong();
            Long saidaAnterior = maquina.getTotalRelogioSaida();

            if (saidaAnterior != null && saidaAnterior > 0 && saidaAtual > saidaAnterior) {
                int delta = (int) (saidaAtual - saidaAnterior);
                log.info("[PLAYBOX-MQTT] 🧸 PREMIAÇÃO DETECTADA: Máquina {} liberou +{} pelúcia(s)! Odômetro: {}", finalDeviceId, delta, saidaAtual);
                try {
                    String ref = "SAIDA_ODOMETRO_" + finalDeviceId + "_" + saidaAtual;
                    estoqueService.processarSaidaAutomatica(maquina, LocalDate.now(), delta, "PLAYBOX", ref);
                } catch (Exception e) {
                    log.warn("[PLAYBOX-MQTT] Aviso ao abater estoque automático da máquina {}: {}", finalDeviceId, e.getMessage());
                }
            }
            maquina.setTotalRelogioSaida(saidaAtual);
        }

        // 4. EVENTO: Confirmação de pulso digital injetado com sucesso
        if ("ok".equalsIgnoreCase(evento)) {
            String txId = json.hasNonNull("tx_id") ? json.get("tx_id").asText() : ("TX-MQTT-" + System.currentTimeMillis());
            int liberados = json.hasNonNull("pulsos_liberados") ? json.get("pulsos_liberados").asInt() : 1;
            log.info("[PLAYBOX-MQTT] ⚡ Pulso de crédito digital confirmado na máquina {}! TxId: {}, Liberados: {}", finalDeviceId, txId, liberados);
            registrarTransacaoCreditoDigital(maquina, txId, liberados);
        }

        maquinaRepository.save(maquina);
    }

    private Maquina autoCadastrarMaquinaPlayBox(String deviceId) {
        log.info("[PLAYBOX-MQTT] Novo módulo PlayBox detectado na rede: {}. Criando cadastro automático...", deviceId);
        Maquina nova = new Maquina();
        nova.setIntegracao("PLAYBOX");
        nova.setExternalId(deviceId);
        nova.setNomeIntegracao("PlayBox Módulo - " + deviceId);
        nova.setDescricao("Módulo de Telemetria e Crédito Nativo PlayBox");
        nova.setAtivo(true);
        nova.setOnline(true);
        nova.setLastHeartbeat(LocalDateTime.now());
        nova.setEstoqueAtual(0);
        nova.setEstoqueMinimoAlerta(30);
        nova.setCapacidadeMaxima(100);
        return maquinaRepository.save(nova);
    }

    private void registrarTransacaoCreditoDigital(Maquina maquina, String txId, int pulsos) {
        try {
            Optional<Transacao> existing = transacaoRepository.findByIntegracaoAndExternalTxId("PLAYBOX", txId);
            if (existing.isEmpty()) {
                double valorUnitario = 5.0; // Padrão de jogada
                double valorTotal = pulsos * valorUnitario;

                Transacao tx = new Transacao(
                        txId,
                        "PLAYBOX",
                        maquina,
                        maquina.getEstabelecimento(),
                        LocalDateTime.now(),
                        "CREDITO_DIGITAL",
                        valorTotal,
                        0.0,
                        valorTotal, // registrado em valor_pix / digital
                        0.0,
                        0.0,
                        0.0,
                        0.0
                );
                transacaoRepository.save(tx);
                log.info("[PLAYBOX-MQTT] Transação digital gravada: {} (R$ {})", txId, valorTotal);
            }
        } catch (Exception e) {
            log.warn("[PLAYBOX-MQTT] Aviso ao salvar transação de crédito digital {}: {}", txId, e.getMessage());
        }
    }

    private void registrarTransacaoEspecie(Maquina maquina, long pulsos, long odometroTotal) {
        try {
            String txId = "CASH_" + maquina.getExternalId() + "_" + odometroTotal;
            Optional<Transacao> existing = transacaoRepository.findByIntegracaoAndExternalTxId("PLAYBOX", txId);
            if (existing.isEmpty()) {
                double valorUnitario = 5.0;
                double valorTotal = pulsos * valorUnitario;

                Transacao tx = new Transacao(
                        txId,
                        "PLAYBOX",
                        maquina,
                        maquina.getEstabelecimento(),
                        LocalDateTime.now(),
                        "DINHEIRO",
                        valorTotal,
                        valorTotal, // valor_cash
                        0.0,
                        0.0,
                        0.0,
                        0.0,
                        0.0
                );
                transacaoRepository.save(tx);
                log.info("[PLAYBOX-MQTT] Transação de dinheiro físico (12V) registrada: {} (R$ {})", txId, valorTotal);
            }
        } catch (Exception e) {
            log.warn("[PLAYBOX-MQTT] Aviso ao salvar transação em espécie da máquina {}: {}", maquina.getExternalId(), e.getMessage());
        }
    }

    /**
     * Envia comando para liberar pulsos de crédito na máquina via MQTT.
     */
    public boolean enviarPulsoCredito(String deviceId, int quantidade, String txId) {
        if (!isConnected()) {
            log.error("[PLAYBOX-MQTT] Falha ao enviar pulso: Cliente MQTT não está conectado.");
            return false;
        }

        String idTransacao = (txId != null && !txId.isBlank()) ? txId : ("TX-" + System.currentTimeMillis());
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("cmd", "pulse");
        cmd.put("qty", Math.max(1, quantidade));
        cmd.put("tx_id", idTransacao);

        return publicarComando(deviceId, cmd);
    }

    /**
     * Envia comando para resetar configurações do módulo remotamente via MQTT.
     */
    public boolean enviarResetRemoto(String deviceId) {
        if (!isConnected()) {
            return false;
        }
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("cmd", "reset_config");
        return publicarComando(deviceId, cmd);
    }

    /**
     * Envia comando para o módulo realizar atualização OTA via URL.
     */
    public boolean enviarComandoOTA(String deviceId, String firmwareUrl) {
        if (!isConnected()) {
            return false;
        }
        Map<String, Object> cmd = new HashMap<>();
        cmd.put("cmd", "ota");
        cmd.put("url", firmwareUrl);
        return publicarComando(deviceId, cmd);
    }

    private boolean publicarComando(String deviceId, Map<String, Object> cmd) {
        try {
            String topic = properties.getMqtt().getBaseTopic() + "/" + deviceId + "/comando";
            String jsonPayload = objectMapper.writeValueAsString(cmd);

            MqttMessage msg = new MqttMessage(jsonPayload.getBytes(StandardCharsets.UTF_8));
            msg.setQos(1);
            msg.setRetained(false);

            mqttClient.publish(topic, msg);
            log.info("[PLAYBOX-MQTT] 🚀 Comando publicado para [{}] no tópico {}: {}", deviceId, topic, jsonPayload);
            return true;
        } catch (Exception e) {
            log.error("[PLAYBOX-MQTT] Erro ao publicar comando para {}: {}", deviceId, e.getMessage());
            return false;
        }
    }

    public boolean isConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }

    public Map<String, Object> getStatusInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        AppProperties.Mqtt cfg = properties.getMqtt();
        info.put("enabled", cfg.isEnabled());
        info.put("brokerUrl", cfg.getBrokerUrl());
        info.put("brokerHost", cfg.getBrokerHost());
        info.put("brokerPort", cfg.getBrokerPort());
        info.put("baseTopic", cfg.getBaseTopic());
        info.put("connected", isConnected());
        info.put("connecting", connecting);
        info.put("clientId", cfg.getClientId());
        return info;
    }

    private String extrairDeviceIdDoTopico(String topic) {
        if (topic == null) return null;
        String[] parts = topic.split("/");
        // Exemplo: playbox / maquinas / PB-00001 / status
        if (parts.length >= 4 && "status".equalsIgnoreCase(parts[parts.length - 1])) {
            return parts[parts.length - 2];
        }
        return null;
    }

    private long extrairLong(JsonNode json, String... keys) {
        for (String k : keys) {
            if (json.hasNonNull(k)) {
                return json.get(k).asLong();
            }
        }
        return 0L;
    }
}
