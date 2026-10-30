package br.com.playbox.service;

import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.Estabelecimento;
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
    private final HistoricoVinculoService historicoVinculoService;
    private final ObjectMapper objectMapper;

    private MqttAsyncClient mqttClient;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile boolean connecting = false;

    @org.springframework.beans.factory.annotation.Autowired
    public PlayBoxMqttService(
            AppProperties properties,
            MaquinaRepository maquinaRepository,
            TransacaoRepository transacaoRepository,
            EstoqueService estoqueService,
            HistoricoVinculoService historicoVinculoService,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.maquinaRepository = maquinaRepository;
        this.transacaoRepository = transacaoRepository;
        this.estoqueService = estoqueService;
        this.historicoVinculoService = historicoVinculoService;
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    public PlayBoxMqttService(
            AppProperties properties,
            MaquinaRepository maquinaRepository,
            TransacaoRepository transacaoRepository,
            EstoqueService estoqueService,
            ObjectMapper objectMapper
    ) {
        this(properties, maquinaRepository, transacaoRepository, estoqueService, null, objectMapper);
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
                // Subscreve em wildcard para receber status, eventos e telemetrias de todos os módulos
                String topicFilter = baseTopic + "/#";
                mqttClient.subscribe(topicFilter, 1);
                log.info("[PLAYBOX-MQTT] Inscrito no tópico de telemetria e eventos dos módulos: {}", topicFilter);
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
        if (topic == null || topic.endsWith("/comando")) {
            return; // Ignora comandos enviados pelo próprio backend
        }

        String payloadStr = new String(message.getPayload(), StandardCharsets.UTF_8);
        log.info("[PLAYBOX-MQTT] Mensagem recebida no tópico {}: {}", topic, payloadStr);

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
     * Processa mensagem recebida de um módulo e sincroniza dados no banco.
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

        // Extrai deviceId do tópico ou do JSON
        String deviceId = extrairDeviceIdDoTopico(topic);
        if (deviceId == null) {
            deviceId = extrairTexto(json, "maquina_id", "deviceId", "device_id", "id");
        }

        if (deviceId == null || deviceId.isBlank()) {
            log.warn("[PLAYBOX-MQTT] Não foi possível identificar o ID do módulo no tópico {}", topic);
            return;
        }

        final String finalDeviceId = deviceId;
        Maquina maquina = obterOuCadastrarMaquina(finalDeviceId);

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

        // 1. ODOMETRO: Créditos Digitais
        long digitais = extrairLong(json, "total_digitais", "total_digitais_acumulado", "total_pix_acumulado", "total_pix", "total_pulsos", "total_acumulado");
        if (digitais > 0) {
            maquina.setTotalDigitais(digitais);
        }

        // 2. ODOMETRO: Relógio de Entrada (Dinheiro Físico / Fichas - 12V)
        long entradaAtual = extrairLong(json, "total_relogio_entrada", "relogio_entrada", "entrada");
        if (entradaAtual > 0) {
            Long entradaAnterior = maquina.getTotalRelogioEntrada();
            if (entradaAnterior != null && entradaAnterior > 0 && entradaAtual > entradaAnterior) {
                long delta = entradaAtual - entradaAnterior;
                log.info("[PLAYBOX-MQTT] 💵 ENTRADA EM ESPÉCIE DETECTADA PELO ODÔMETRO: Módulo {} registrou +{} entrada(s) mecânica(s) (12V)! Odômetro: {}", finalDeviceId, delta, entradaAtual);
                registrarTransacaoEspecie(maquina, delta, null, entradaAtual, null);
            }
            maquina.setTotalRelogioEntrada(entradaAtual);
        }

        // 3. ODOMETRO: Relógio de Saída (Pelúcias Ganhas / Prêmios - 12V)
        long saidaAtual = extrairLong(json, "total_relogio_saida", "relogio_saida", "saida");
        if (saidaAtual > 0) {
            Long saidaAnterior = maquina.getTotalRelogioSaida();
            if (saidaAnterior != null && saidaAnterior > 0 && saidaAtual > saidaAnterior) {
                int delta = (int) (saidaAtual - saidaAnterior);
                log.info("[PLAYBOX-MQTT] 🧸 PREMIAÇÃO DETECTADA PELO ODÔMETRO: Módulo {} liberou +{} pelúcia(s)! Odômetro: {}", finalDeviceId, delta, saidaAtual);
                registrarSaidaPelucia(maquina, delta, saidaAtual, null);
            }
            maquina.setTotalRelogioSaida(saidaAtual);
        }

        // 4. EVENTOS EXPLÍCITOS (Confirmação de Crédito OK, Entrada em Espécie, Saída de Pelúcia)
        String evento = extrairTexto(json, "evento", "status", "tipo", "action");
        String tipo = extrairTexto(json, "tipo");

        // Evento: Confirmação de pulso digital injetado com sucesso (ACK do firmware)
        if ("ok".equalsIgnoreCase(evento) || "sucesso".equalsIgnoreCase(evento) || "success".equalsIgnoreCase(evento)) {
            String txId = extrairTexto(json, "tx_id", "txId");
            if (txId == null || txId.isBlank()) {
                txId = "TX-MQTT-" + System.currentTimeMillis();
            }
            int liberados = (int) extrairLong(json, "pulsos_liberados", "qty", "quantidade");
            if (liberados <= 0) liberados = 1;
            log.info("[PLAYBOX-MQTT] ⚡ Pulso de crédito digital confirmado no módulo {}! TxId: {}, Liberados: {}", finalDeviceId, txId, liberados);
            registrarTransacaoCreditoDigital(maquina, txId, liberados, null);
        }
        // Evento: Entrada em espécie (dinheiro físico / moedeiro 12V)
        else if ("relogio_entrada".equalsIgnoreCase(evento) || "especie".equalsIgnoreCase(evento) || "dinheiro".equalsIgnoreCase(evento)
                || "cash".equalsIgnoreCase(evento) || "coin".equalsIgnoreCase(evento) || "especie".equalsIgnoreCase(tipo) || "dinheiro".equalsIgnoreCase(tipo)) {
            int pulsos = (int) extrairLong(json, "pulsos", "qtd", "quantidade");
            if (pulsos <= 0) pulsos = 1;
            Double valor = json.hasNonNull("valor") ? json.get("valor").asDouble() : null;
            String txId = extrairTexto(json, "tx_id", "txId");
            Long odo = (entradaAtual > 0) ? entradaAtual : null;
            log.info("[PLAYBOX-MQTT] 💵 Evento de entrada em espécie recebido para módulo {}: pulsos={}, valor={}", finalDeviceId, pulsos, valor);
            registrarTransacaoEspecie(maquina, pulsos, valor, odo, txId);
        }
        // Evento: Saída de pelúcia ganha (sensor óptico 12V)
        else if ("relogio_saida".equalsIgnoreCase(evento) || "premio".equalsIgnoreCase(evento) || "pelucia".equalsIgnoreCase(evento)
                || "pelucia_ganha".equalsIgnoreCase(evento) || "prize".equalsIgnoreCase(evento) || "saida".equalsIgnoreCase(evento)
                || "pelucia_ganha".equalsIgnoreCase(tipo) || "premio".equalsIgnoreCase(tipo)) {
            int qtd = (int) extrairLong(json, "pulsos", "qtd", "quantidade");
            if (qtd <= 0) qtd = 1;
            String txId = extrairTexto(json, "tx_id", "txId");
            Long odo = (saidaAtual > 0) ? saidaAtual : null;
            log.info("[PLAYBOX-MQTT] 🧸 Evento de saída de pelúcia recebido para módulo {}: qtd={}", finalDeviceId, qtd);
            registrarSaidaPelucia(maquina, qtd, odo, txId);
        }

        maquinaRepository.save(maquina);
    }

    public Maquina obterOuCadastrarMaquina(String deviceId) {
        return maquinaRepository.findByIntegracaoAndExternalId("PLAYBOX", deviceId.trim())
                .orElseGet(() -> autoCadastrarMaquinaPlayBox(deviceId.trim()));
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

    /**
     * Registra ou confirma uma transação de crédito digital (Pix, Cartão, API) para o módulo.
     */
    @Transactional
    public Transacao registrarTransacaoCreditoDigital(String deviceId, String txId, int pulsos, Double valorInformado) {
        Maquina maquina = obterOuCadastrarMaquina(deviceId);
        return registrarTransacaoCreditoDigital(maquina, txId, pulsos, valorInformado);
    }

    /**
     * Registra ou confirma uma transação de crédito digital vinculada a uma Máquina.
     */
    @Transactional
    public Transacao registrarTransacaoCreditoDigital(Maquina maquina, String txId, int pulsos, Double valorInformado) {
        if (txId == null || txId.isBlank()) {
            txId = "TX-PLAYBOX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }
        String finalTxId = txId.trim();
        Optional<Transacao> existing = transacaoRepository.findByIntegracaoAndExternalTxId("PLAYBOX", finalTxId);
        if (existing.isPresent()) {
            log.info("[PLAYBOX-MQTT] Transação digital já existente confirmada: {}", finalTxId);
            return existing.get();
        }

        double valorUnitario = 5.0; // Padrão R$ 5,00 por jogada
        double valorTotal = (valorInformado != null && valorInformado > 0) ? valorInformado : (pulsos * valorUnitario);

        Estabelecimento est = maquina.getEstabelecimento();
        if (est == null && historicoVinculoService != null) {
            est = historicoVinculoService.findEstabelecimentoVigente(maquina.getId(), LocalDateTime.now());
        }

        Transacao tx = new Transacao(
                finalTxId,
                "PLAYBOX",
                maquina,
                est,
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

        Transacao salvo = transacaoRepository.save(tx);
        log.info("[PLAYBOX-MQTT] ⚡ Transação de crédito digital gravada: {} (R$ {}, {} pulsos) para módulo {}",
                finalTxId, valorTotal, pulsos, maquina.getExternalId());
        return salvo;
    }

    /**
     * Registra transação em dinheiro físico (12V) oriunda de evento ou odômetro.
     */
    @Transactional
    public Transacao registrarTransacaoEspecie(Maquina maquina, long pulsos, Double valorInformado, Long odometroTotal, String customTxId) {
        String txId = (customTxId != null && !customTxId.isBlank())
                ? customTxId.trim()
                : ((odometroTotal != null && odometroTotal > 0)
                    ? ("CASH_" + maquina.getExternalId() + "_" + odometroTotal)
                    : ("CASH_" + maquina.getExternalId() + "_" + System.currentTimeMillis()));

        Optional<Transacao> existing = transacaoRepository.findByIntegracaoAndExternalTxId("PLAYBOX", txId);
        if (existing.isPresent()) {
            return existing.get();
        }

        double valorUnitario = 5.0; // Padrão R$ 5,00 por moeda/ficha mecânica
        double valorTotal = (valorInformado != null && valorInformado > 0) ? valorInformado : (pulsos * valorUnitario);

        Estabelecimento est = maquina.getEstabelecimento();
        if (est == null && historicoVinculoService != null) {
            est = historicoVinculoService.findEstabelecimentoVigente(maquina.getId(), LocalDateTime.now());
        }

        Transacao tx = new Transacao(
                txId,
                "PLAYBOX",
                maquina,
                est,
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

        Transacao salvo = transacaoRepository.save(tx);
        log.info("[PLAYBOX-MQTT] 💵 Transação em dinheiro físico (12V) gravada: {} (R$ {}) para módulo {}",
                txId, valorTotal, maquina.getExternalId());
        return salvo;
    }

    /**
     * Registra saída de pelúcia ganha (sensor óptico 12V), abata estoque e gera registro de transação.
     */
    @Transactional
    public Transacao registrarSaidaPelucia(Maquina maquina, int quantidade, Long odometroTotal, String customTxId) {
        if (quantidade <= 0) {
            quantidade = 1;
        }

        String txId = (customTxId != null && !customTxId.isBlank())
                ? customTxId.trim()
                : ((odometroTotal != null && odometroTotal > 0)
                    ? ("SAIDA_ODOMETRO_" + maquina.getExternalId() + "_" + odometroTotal)
                    : ("PREMIO_" + maquina.getExternalId() + "_" + System.currentTimeMillis()));

        Optional<Transacao> existing = transacaoRepository.findByIntegracaoAndExternalTxId("PLAYBOX", txId);
        Transacao tx;
        if (existing.isPresent()) {
            tx = existing.get();
        } else {
            Estabelecimento est = maquina.getEstabelecimento();
            if (est == null && historicoVinculoService != null) {
                est = historicoVinculoService.findEstabelecimentoVigente(maquina.getId(), LocalDateTime.now());
            }

            tx = new Transacao(
                    txId,
                    "PLAYBOX",
                    maquina,
                    est,
                    LocalDateTime.now(),
                    "PREMIOS",
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    (double) quantidade
            );
            tx = transacaoRepository.save(tx);
            log.info("[PLAYBOX-MQTT] 🧸 Transação de saída de pelúcia gravada no banco: {} ({} pelúcia(s)) para módulo {}",
                    txId, quantidade, maquina.getExternalId());
        }

        // Abate o estoque automaticamente
        try {
            estoqueService.processarSaidaAutomatica(maquina, LocalDate.now(), quantidade, "PLAYBOX", txId);
        } catch (Exception e) {
            log.warn("[PLAYBOX-MQTT] Aviso ao abater estoque automático do módulo {}: {}", maquina.getExternalId(), e.getMessage());
        }

        return tx;
    }

    /**
     * Helper manual para registrar entrada em espécie via REST API.
     */
    @Transactional
    public Transacao registrarTransacaoEspecieManual(String deviceId, int pulsos, Double valor, String txId) {
        Maquina maquina = obterOuCadastrarMaquina(deviceId);
        return registrarTransacaoEspecie(maquina, pulsos, valor, null, txId);
    }

    /**
     * Helper manual para registrar saída de pelúcia via REST API.
     */
    @Transactional
    public Transacao registrarSaidaPeluciaManual(String deviceId, int quantidade, String txId) {
        Maquina maquina = obterOuCadastrarMaquina(deviceId);
        return registrarSaidaPelucia(maquina, quantidade, null, txId);
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

    public String extrairDeviceIdDoTopico(String topic) {
        if (topic == null || topic.isBlank()) return null;
        String baseTopic = properties.getMqtt().getBaseTopic();
        if (topic.startsWith(baseTopic + "/")) {
            String remainder = topic.substring(baseTopic.length() + 1);
            String[] parts = remainder.split("/");
            if (parts.length > 0 && !parts[0].isBlank()) {
                return parts[0].trim();
            }
        }
        // Fallback para tópicos de 4 partes ex: playbox/maquinas/PB-00001/status
        String[] parts = topic.split("/");
        if (parts.length >= 4 && ("status".equalsIgnoreCase(parts[parts.length - 1])
                || "evento".equalsIgnoreCase(parts[parts.length - 1])
                || "telemetria".equalsIgnoreCase(parts[parts.length - 1]))) {
            return parts[parts.length - 2].trim();
        }
        return null;
    }

    private String extrairTexto(JsonNode json, String... keys) {
        for (String k : keys) {
            if (json.hasNonNull(k)) {
                String val = json.get(k).asText();
                if (val != null && !val.isBlank()) {
                    return val.trim();
                }
            }
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
