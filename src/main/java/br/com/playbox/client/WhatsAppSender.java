package br.com.playbox.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSender.class);

    private final ApiClient client;
    private final String apiKey;
    private final String chatId;
    private final String sendPath;

    public WhatsAppSender(
            String baseUrl,
            String apiKey,
            String chatId,
            String sendPath,
            int timeoutSeconds,
            ObjectMapper objectMapper
    ) {
        this.client = new ApiClient(baseUrl, timeoutSeconds, objectMapper);
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.chatId = chatId != null ? chatId.trim() : "";
        this.sendPath = sendPath != null ? sendPath.trim() : "";
    }

    public boolean sendMessage(String message) {
        return sendMessage(this.chatId, message);
    }

    public boolean sendMessage(String targetChatId, String message) {
        if (message == null || message.isBlank()) {
            log.warn("Mensagem WhatsApp vazia, nenhum envio realizado.");
            return false;
        }

        String to = (targetChatId != null && !targetChatId.isBlank()) ? targetChatId.trim() : this.chatId;

        if (to.isBlank()) {
            log.warn("chatId de destino do WhatsApp não configurado, mensagem não enviada.");
            return false;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        if (!apiKey.isBlank()) {
            headers.put("x-api-key", apiKey);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("to", to);
        payload.put("message", message);

        String serviceName = "WhatsAppBot";

        try {
            log.info("Enviando mensagem via WhatsAppBot para {}", to);
            JsonNode response = client.post(sendPath, headers, payload, serviceName);
            if (response != null) {
                log.info("Mensagem WhatsApp enviada com sucesso para {}.", to);
                return true;
            }
        } catch (IntegrationException e) {
            log.error("Falha ao enviar mensagem via WhatsApp: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Erro inesperado ao enviar mensagem via WhatsApp: {}", e.getMessage(), e);
            return false;
        }

        return false;
    }

    public String getChatId() {
        return chatId;
    }

    public String getSendPath() {
        return sendPath;
    }
}
