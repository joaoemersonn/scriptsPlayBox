package br.com.playbox.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public class ApiClient {

    private static final Logger log = LoggerFactory.getLogger(ApiClient.class);

    protected final String baseUrl;
    protected final HttpClient httpClient;
    protected final Duration timeout;
    protected final ObjectMapper objectMapper;

    public ApiClient(String baseUrl, int timeoutSeconds, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    protected JsonNode request(
            String method,
            String path,
            Map<String, String> headers,
            Object payload,
            Map<String, String> queryParams,
            String serviceName
    ) {
        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        if (path != null && !path.isBlank()) {
            urlBuilder.append("/").append(path.replaceAll("^/+", ""));
        }

        if (queryParams != null && !queryParams.isEmpty()) {
            urlBuilder.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (!first) {
                    urlBuilder.append("&");
                }
                first = false;
                urlBuilder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append("=")
                        .append(URLEncoder.encode(entry.getValue() != null ? entry.getValue() : "", StandardCharsets.UTF_8));
            }
        }

        String fullUrl = urlBuilder.toString();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .timeout(timeout);

        if (headers != null) {
            headers.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    requestBuilder.header(k, v);
                }
            });
        }

        if ("POST".equalsIgnoreCase(method)) {
            String jsonBody = "";
            if (payload != null) {
                if (payload instanceof String strPayload) {
                    jsonBody = strPayload;
                } else {
                    try {
                        jsonBody = objectMapper.writeValueAsString(payload);
                    } catch (JsonProcessingException e) {
                        throw new IntegrationException(serviceName, "Erro ao serializar payload JSON: " + e.getMessage());
                    }
                }
            }
            requestBuilder.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            if (headers == null || !headers.containsKey("Content-Type") && !headers.containsKey("content-type")) {
                requestBuilder.header("Content-Type", "application/json");
            }
        } else {
            requestBuilder.GET();
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Erro na requisição {} {}: {}", method.toUpperCase(), fullUrl, e.getMessage());
            throw new IntegrationException(serviceName, e.getMessage());
        }

        String responseBody = response.body();
        int statusCode = response.statusCode();

        if (statusCode >= 400) {
            log.error("Erro ao obter dados integracao: {}", serviceName);
            log.error("Erro na requisição {} {}: HTTP status {}", method.toUpperCase(), fullUrl, statusCode);
            if (responseBody != null && !responseBody.isBlank()) {
                log.error("Detalhes da resposta: {}", responseBody);
            }
            throw new IntegrationException(serviceName, responseBody);
        }

        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }

        JsonNode responseData;
        try {
            responseData = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            log.error("Resposta JSON inválida em {}: {}", fullUrl, responseBody);
            throw new IntegrationException(serviceName, "Resposta JSON inválida");
        }

        if (responseData.isObject() && responseData.has("message") && !responseData.has("data")) {
            String details = responseData.get("message").asText();
            log.error("Erro retornado pelo serviço {}: {}", serviceName, details);
            throw new IntegrationException(serviceName, details);
        }

        return responseData;
    }

    public JsonNode get(String path, Map<String, String> headers, Map<String, String> queryParams, String serviceName) {
        return request("GET", path, headers, null, queryParams, serviceName);
    }

    public JsonNode post(String path, Map<String, String> headers, Object payload, String serviceName) {
        return request("POST", path, headers, payload, null, serviceName);
    }
}

