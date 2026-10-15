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
public class PayXYZClient extends ApiClient {

    @Autowired
    public PayXYZClient(AppProperties properties, ObjectMapper objectMapper) {
        super(
                (properties != null && properties.getPayxyz() != null) ? properties.getPayxyz().getBaseUrl() : "https://api2.payxyz.app.br/v2",
                properties != null ? properties.getTimeoutSeconds() : 30,
                objectMapper
        );
    }

    public PayXYZClient(String baseUrl, int timeoutSeconds, ObjectMapper objectMapper) {
        super(baseUrl, timeoutSeconds, objectMapper);
    }

    public static Map<String, String> defaultHeaders(String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "*/*");
        headers.put("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");
        headers.put("Cache-Control", "max-age=0");
        headers.put("Origin", "https://payxyz.app.br");
        headers.put("Referer", "https://payxyz.app.br/");
        headers.put("Sec-Fetch-Dest", "empty");
        headers.put("Sec-Fetch-Mode", "cors");
        headers.put("Sec-Fetch-Site", "same-site");
        headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36");
        headers.put("content-type", "application/json");
        headers.put("sec-ch-ua", "\"Google Chrome\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"macOS\"");
        if (token != null && !token.isBlank()) {
            headers.put("x-access-token", token);
        }
        return headers;
    }

    public String login(String email, String senha, boolean keepConnected) {
        Map<String, Object> payload = Map.of(
                "email", email != null ? email : "",
                "senha", senha != null ? senha : "",
                "keepConnected", keepConnected
        );
        JsonNode response = post("login/login-cliente", defaultHeaders(null), payload, "PayXYZ");
        if (response != null && response.has("token")) {
            return response.get("token").asText();
        }
        return null;
    }

    public List<JsonNode> getMachines(String token) {
        if (token == null || token.isBlank()) {
            return List.of();
        }
        JsonNode response = get("maquinas", defaultHeaders(token), null, "PayXYZ");
        List<JsonNode> machines = new ArrayList<>();
        if (response != null && response.has("maquinas") && response.get("maquinas").isArray()) {
            response.get("maquinas").forEach(machines::add);
        }
        return machines;
    }

    public List<JsonNode> getReport(String token, String startDate, String endDate, List<Object> maquinaIds) {
        if (token == null || token.isBlank() || maquinaIds == null || maquinaIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> payload = Map.of(
                "maquinaIds", maquinaIds,
                "dataInicio", startDate,
                "dataFim", endDate
        );
        JsonNode response = post("maquinas/relatorios", defaultHeaders(token), payload, "PayXYZ");
        List<JsonNode> reports = new ArrayList<>();
        if (response != null && response.isArray()) {
            response.forEach(reports::add);
        }
        return reports;
    }
}
