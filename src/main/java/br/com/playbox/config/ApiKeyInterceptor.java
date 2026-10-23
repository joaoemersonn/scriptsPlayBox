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
    private final br.com.playbox.service.AuthService authService;

    @org.springframework.beans.factory.annotation.Autowired
    public ApiKeyInterceptor(AppProperties properties, ObjectMapper objectMapper, br.com.playbox.service.AuthService authService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.authService = authService;
    }

    public ApiKeyInterceptor(AppProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, new br.com.playbox.service.AuthService(properties));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String path = request.getRequestURI();
        if (path.startsWith("/api/auth/login")) {
            return true;
        }

        // 1. Validar Bearer Session Token (Web Frontend)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = authHeader.substring(7).trim();
            if (authService.isValidToken(token)) {
                return true;
            }
        }

        // 2. Validar X-API-KEY (Integrações, Crons, Webhooks)
        String requiredKey = properties.getApiKey();
        String clientKey = request.getHeader("X-API-KEY");
        if (clientKey == null || clientKey.isBlank()) {
            clientKey = request.getHeader("x-api-key");
        }

        if (clientKey != null && requiredKey != null && clientKey.trim().equals(requiredKey.trim())) {
            return true;
        }

        log.warn("Acesso não autorizado bloqueado em {}. Header X-API-KEY ou Bearer token ausente/inválido.", path);
        sendUnauthorized(response, "Acesso não autorizado. Efetue login ou forneça uma chave de API válida.");
        return false;
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

