package br.com.playbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;

@Component
public class ApiKeyInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyInterceptor.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public ApiKeyInterceptor(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String requiredKey = properties.getApiKey();
        if (requiredKey == null || requiredKey.isBlank()) {
            log.error("API Key não configurada no servidor.");
            sendUnauthorized(response, "Configuração de API Key não definida no servidor.");
            return false;
        }

        String clientKey = request.getHeader("X-API-KEY");
        if (clientKey == null || clientKey.isBlank()) {
            clientKey = request.getHeader("x-api-key");
        }

        if (clientKey == null || !clientKey.trim().equals(requiredKey.trim())) {
            log.warn("Acesso não autorizado bloqueado em {}. Header X-API-KEY ausente ou inválido.", request.getRequestURI());
            sendUnauthorized(response, "Chave de API inválida ou ausente. Forneça o header 'X-API-KEY'.");
            return false;
        }

        return true;
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), Map.of(
                "status", "UNAUTHORIZED",
                "message", message
        ));
    }
}

