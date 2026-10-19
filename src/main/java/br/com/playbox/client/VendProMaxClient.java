package br.com.playbox.client;

import br.com.playbox.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class VendProMaxClient extends ApiClient {

    @Autowired
    public VendProMaxClient(AppProperties properties, ObjectMapper objectMapper) {
        super(
                (properties != null && properties.getVendpromax() != null) ? properties.getVendpromax().getBaseUrl() : "https://api.vendpromax.com.br/api",
                properties != null ? properties.getTimeoutSeconds() : 30,
                objectMapper
        );
    }

    public VendProMaxClient(String baseUrl, int timeoutSeconds, ObjectMapper objectMapper) {
        super(baseUrl, timeoutSeconds, objectMapper);
    }

    public static Map<String, String> defaultHeaders(String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("sec-ch-ua-platform", "\"macOS\"");
        headers.put("Referer", "https://sistema.vendpromax.com.br/");
        headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36");
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("sec-ch-ua", "\"Google Chrome\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"");
        headers.put("Content-Type", "application/json");
        headers.put("sec-ch-ua-mobile", "?0");
        if (token != null && !token.isBlank()) {
            headers.put("x-access-token", token);
        }
        return headers;
    }

    public String login(String email, String senha) {
        Map<String, Object> payload = Map.of(
                "email", email != null ? email : "",
                "senha", senha != null ? senha : ""
        );
        JsonNode response = post("login-cliente", defaultHeaders(null), payload, "VendProMax");
        if (response != null && response.has("token")) {
            return response.get("token").asText();
        }
        return null;
    }

    public List<JsonNode> getMachines(String token) {
        if (token == null || token.isBlank()) {
            return List.of();
        }
        JsonNode response = get("maquinas", defaultHeaders(token), null, "VendProMax");
        List<JsonNode> machines = new ArrayList<>();
        if (response != null && response.isArray()) {
            response.forEach(machines::add);
        }
        return machines;
    }

    public JsonNode getReport(String token, String endpoint, Object maquinaId, String dataInicio, String dataFim, boolean isAdm) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataInicio", dataInicio);
        payload.put("dataFim", dataFim);
        payload.put("maquinaId", maquinaId);
        payload.put("isAdm", isAdm);

        return post(endpoint, defaultHeaders(token), payload, "VendProMax");
    }

    public JsonNode getPagamentosPeriodo(String token, Object maquinaId, String dataInicio, String dataFim) {
        if (token == null || token.isBlank() || maquinaId == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("dataInicio", dataInicio);
        payload.put("dataFim", dataFim);

        return post("pagamentos-periodo/" + maquinaId, defaultHeaders(token), payload, "VendProMax");
    }
}
