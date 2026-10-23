package br.com.playbox.service;

import br.com.playbox.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppProperties properties;
    private final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();

    public record SessionInfo(String username, Instant expiresAt) {}

    public AuthService(AppProperties properties) {
        this.properties = properties;
    }

    public String login(String username, String password) {
        if (username == null || password == null) {
            return null;
        }

        String expectedUser = properties.getAuth().getUsername();
        String expectedPass = properties.getAuth().getPassword();

        if (expectedUser != null && expectedUser.trim().equalsIgnoreCase(username.trim())
                && expectedPass != null && expectedPass.trim().equals(password.trim())) {
            String token = "pb_" + UUID.randomUUID().toString().replace("-", "");
            Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
            activeSessions.put(token, new SessionInfo(expectedUser, expiresAt));
            log.info("Login realizado com sucesso para o usuário '{}'. Sessão criada.", expectedUser);
            return token;
        }

        log.warn("Tentativa de login inválida para o usuário '{}'.", username);
        return null;
    }

    public boolean isValidToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        SessionInfo session = activeSessions.get(token.trim());
        if (session == null) {
            return false;
        }
        if (Instant.now().isAfter(session.expiresAt())) {
            activeSessions.remove(token.trim());
            return false;
        }
        return true;
    }

    public String getUsername(String token) {
        SessionInfo session = activeSessions.get(token != null ? token.trim() : "");
        return session != null ? session.username() : null;
    }

    public void logout(String token) {
        if (token != null) {
            SessionInfo removed = activeSessions.remove(token.trim());
            if (removed != null) {
                log.info("Sessão do usuário '{}' encerrada.", removed.username());
            }
        }
    }
}
