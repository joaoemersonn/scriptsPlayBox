package br.com.playbox.client;

import br.com.playbox.config.AppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ZapiziClient extends ApiClient {

    private String lastToken;
    private String lastRefreshToken;
    private Integer defaultOrganizationId = 3852;

    @Autowired
    public ZapiziClient(AppProperties properties, ObjectMapper objectMapper) {
        super(
                (properties != null && properties.getZapizi() != null) ? properties.getZapizi().getBaseUrl() : "https://portal.zapizi.com.br/v1",
                properties != null ? properties.getTimeoutSeconds() : 30,
                objectMapper
        );
        this.defaultOrganizationId = (properties != null && properties.getZapizi() != null && properties.getZapizi().getOrganizationId() != null)
                ? properties.getZapizi().getOrganizationId()
                : 3852;
    }

    public ZapiziClient(String baseUrl, int timeoutSeconds, Integer defaultOrganizationId, ObjectMapper objectMapper) {
        super(baseUrl, timeoutSeconds, objectMapper);
        this.defaultOrganizationId = defaultOrganizationId != null ? defaultOrganizationId : 3852;
    }

    public Map<String, String> defaultHeaders(
            String token,
            String refreshToken,
            Integer organizationId,
            String contextType
    ) {
        Integer effectiveOrgId = organizationId != null ? organizationId : defaultOrganizationId;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("accept", "application/json, text/plain, */*");
        headers.put("accept-language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");
        if (token != null && !token.isBlank()) {
            headers.put("authorization", "Bearer " + token);
        }
        headers.put("content-type", "application/json");
        headers.put("user-agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-site", "same-origin");
        headers.put("referer", "https://portal.zapizi.com.br/dashboard/machine/list");
        headers.put("sec-ch-ua", "\"Not;A=Brand\";v=\"8\", \"Chromium\";v=\"150\", \"Google Chrome\";v=\"150\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"macOS\"");
        headers.put("priority", "u=1, i");

        if (refreshToken != null && !refreshToken.isBlank()) {
            headers.put("x-refresh-token", refreshToken);
        }

        List<String> cookieParts = new ArrayList<>();
        cookieParts.add("pys_start_session=true");
        cookieParts.add("pys_first_visit=true");
        cookieParts.add("pysTrafficSource=google.com");
        cookieParts.add("pys_landing_page=https://zap.zapizi.com.br/zapizi-home/");
        cookieParts.add("last_pysTrafficSource=google.com");
        cookieParts.add("last_pys_landing_page=https://zap.zapizi.com.br/zapizi-home/");
        cookieParts.add("_fbp=fb.1.1785517238728.5950198252");

        if (effectiveOrgId != null) {
            String selectedContext = "{\"contextType\":\"organization\",\"contextId\":" + effectiveOrgId +
                    ",\"name\":\"PlayBox BR\",\"logoPath\":null,\"description\":\"PlayBox BR\",\"sortOrder\":1}";
            String selectedOrg = "{\"id\":" + effectiveOrgId +
                    ",\"name\":\"PlayBox BR\",\"logoPath\":null,\"description\":\"PlayBox BR\",\"active\":true}";
            cookieParts.add("selectedContext=" + selectedContext);
            cookieParts.add("selectOrgnaization=" + selectedOrg);
        }
        if (token != null && !token.isBlank()) {
            cookieParts.add("accessToken=" + token);
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            cookieParts.add("refreshToken=" + refreshToken);
        }
        headers.put("cookie", String.join("; ", cookieParts));

        if (effectiveOrgId != null) {
            headers.put("organizationid", String.valueOf(effectiveOrgId));
            headers.put("x-context-id", String.valueOf(effectiveOrgId));
            headers.put("x-context-type", contextType != null ? contextType : "organization");
        }

        return headers;
    }

    public JsonNode login(String usernameOrEmail, String password, String refreshToken) {
        Map<String, Object> payload = Map.of(
                "usernameOrEmail", usernameOrEmail != null ? usernameOrEmail : "",
                "password", password != null ? password : ""
        );
        JsonNode response = post(
                "access/auth/login",
                defaultHeaders(null, refreshToken, defaultOrganizationId, "organization"),
                payload,
                "Zapizi"
        );
        if (response != null && response.isObject()) {
            if (response.has("token")) {
                this.lastToken = response.get("token").asText();
            }
            if (response.has("refreshToken")) {
                this.lastRefreshToken = response.get("refreshToken").asText();
            }
            return response;
        }
        return null;
    }

    public List<JsonNode> getMachines(
            String token,
            Integer organizationId,
            int page,
            int limit,
            String search,
            String uniqueCode,
            String tag,
            String locationName,
            String status,
            List<String> machineTypes,
            List<String> vendStatuses
    ) {
        if (token == null || token.isBlank()) {
            return List.of();
        }

        Map<String, Object> advancedSearch = new LinkedHashMap<>();
        advancedSearch.put("search", search != null ? search : "");
        advancedSearch.put("uniqueCode", uniqueCode != null ? uniqueCode : "");
        advancedSearch.put("tag", tag != null ? tag : "");
        advancedSearch.put("locationName", locationName != null ? locationName : "");
        advancedSearch.put("status", status != null ? status : "ALL");
        advancedSearch.put("machineTypes", machineTypes != null ? machineTypes : List.of("POS"));
        advancedSearch.put("vendStatuses", vendStatuses != null ? vendStatuses : List.of());
        advancedSearch.put("orderBy", Map.of("orderBy", "string", "order", "desc"));

        String advancedSearchJson;
        try {
            advancedSearchJson = objectMapper.writeValueAsString(advancedSearch);
        } catch (JsonProcessingException e) {
            throw new IntegrationException("Zapizi", "Erro ao serializar advancedSearch: " + e.getMessage());
        }

        Map<String, String> params = Map.of(
                "page", String.valueOf(page),
                "limit", String.valueOf(limit),
                "advancedSearch", advancedSearchJson
        );

        JsonNode response = get(
                "core/machine/",
                defaultHeaders(token, lastRefreshToken, organizationId, "organization"),
                params,
                "Zapizi"
        );

        List<JsonNode> machines = new ArrayList<>();
        if (response != null && response.has("data") && response.get("data").isArray()) {
            response.get("data").forEach(machines::add);
        }
        return machines;
    }

    public JsonNode getReports(
            String token,
            String startDate,
            String endDate,
            List<Integer> organizationIds,
            List<Integer> locationIds,
            String tags,
            String search,
            int page,
            int limit,
            Integer organizationId,
            String refreshToken
    ) {
        String effectiveToken = (token != null && !token.isBlank()) ? token : this.lastToken;
        String effectiveRefresh = (refreshToken != null && !refreshToken.isBlank()) ? refreshToken : this.lastRefreshToken;
        if (effectiveToken == null || effectiveToken.isBlank()) {
            return null;
        }

        Map<String, Object> advancedSearch = new LinkedHashMap<>();
        advancedSearch.put("organizationIds", organizationIds);
        advancedSearch.put("locationIds", locationIds);
        advancedSearch.put("tags", tags != null ? tags : "");
        advancedSearch.put("search", search != null ? search : "");
        advancedSearch.put("startDate", startDate);
        advancedSearch.put("endDate", endDate);

        String advancedSearchJson;
        try {
            advancedSearchJson = objectMapper.writeValueAsString(advancedSearch);
        } catch (JsonProcessingException e) {
            throw new IntegrationException("Zapizi", "Erro ao serializar advancedSearch: " + e.getMessage());
        }

        Map<String, String> params = Map.of(
                "page", String.valueOf(page),
                "limit", String.valueOf(limit),
                "advancedSearch", advancedSearchJson
        );

        return get(
                "statement/statements/sales/reports",
                defaultHeaders(effectiveToken, effectiveRefresh, organizationId, "organization"),
                params,
                "Zapizi"
        );
    }

    public JsonNode getTelemetry(
            String token,
            String startDate,
            String endDate,
            Integer organizationId,
            String refreshToken
    ) {
        String effectiveToken = (token != null && !token.isBlank()) ? token : this.lastToken;
        String effectiveRefresh = (refreshToken != null && !refreshToken.isBlank()) ? refreshToken : this.lastRefreshToken;
        if (effectiveToken == null || effectiveToken.isBlank()) {
            return null;
        }

        Map<String, Object> advancedSearch = new LinkedHashMap<>();
        advancedSearch.put("startDate", startDate);
        advancedSearch.put("endDate", endDate);

        String advancedSearchJson;
        try {
            advancedSearchJson = objectMapper.writeValueAsString(advancedSearch);
        } catch (JsonProcessingException e) {
            throw new IntegrationException("Zapizi", "Erro ao serializar advancedSearch: " + e.getMessage());
        }

        Map<String, String> params = Map.of(
                "page", "0",
                "limit", "100",
                "advancedSearch", advancedSearchJson
        );

        return get(
                "core/machine-io/list/summarized",
                defaultHeaders(effectiveToken, effectiveRefresh, organizationId, "organization"),
                params,
                "Zapizi"
        );
    }

    public String getLastToken() {
        return lastToken;
    }

    public String getLastRefreshToken() {
        return lastRefreshToken;
    }

    public void setDefaultOrganizationId(Integer defaultOrganizationId) {
        this.defaultOrganizationId = defaultOrganizationId;
    }
}
