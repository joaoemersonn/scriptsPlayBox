package br.com.playbox.service;

import br.com.playbox.config.AppProperties;
import br.com.playbox.entity.UserSession;
import br.com.playbox.repository.UserSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final AppProperties properties;
    private final UserSessionRepository userSessionRepository;
    private final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();

    public record SessionInfo(String username, Instant expiresAt) {}

    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(AppProperties properties, UserSessionRepository userSessionRepository) {
        this.properties = properties;
        this.userSessionRepository = userSessionRepository;
    }

    public AuthService(AppProperties properties) {
        this(properties, null);
    }

    @Transactional
    public String login(String username, String password) {
        if (username == null || password == null) {
            return null;
        }

        String expectedUser = properties.getAuth().getUsername();
        String expectedPass = properties.getAuth().getPassword();

        if (expectedUser != null && expectedUser.trim().equalsIgnoreCase(username.trim())
                && expectedPass != null && expectedPass.trim().equals(password.trim())) {
            String token = "pb_" + UUID.randomUUID().toString().replace("-", "");
            // Expiração longa (365 dias) para lembrar o login
            Instant expiresAt = Instant.now().plus(365, ChronoUnit.DAYS);
            Instant createdAt = Instant.now();

            // Salva na memória
            activeSessions.put(token, new SessionInfo(expectedUser, expiresAt));

            // Salva no banco SQLite para persistir entre reinicializações do servidor
            try {
                userSessionRepository.save(new UserSession(token, expectedUser, expiresAt, createdAt));
            } catch (Exception e) {
                log.warn("Falha ao persistir sessão no banco SQLite: {}", e.getMessage());
            }

            log.info("Login realizado com sucesso para o usuário '{}'. Sessão persistente criada.", expectedUser);
            return token;
        }

        log.warn("Tentativa de login inválida para o usuário '{}'.", username);
        return null;
    }

    public boolean isValidToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String cleanToken = token.trim();

        // 1. Tenta recuperar do cache em memória
        SessionInfo session = activeSessions.get(cleanToken);
        if (session != null) {
            if (Instant.now().isAfter(session.expiresAt())) {
                activeSessions.remove(cleanToken);
                try {
                    userSessionRepository.deleteById(cleanToken);
                } catch (Exception ignored) {}
                return false;
            }
            return true;
        }

        // 2. Se o servidor foi reiniciado, busca no banco SQLite
        try {
            Optional<UserSession> dbSession = userSessionRepository.findByTokenAndExpiresAtAfter(cleanToken, Instant.now());
            if (dbSession.isPresent()) {
                UserSession us = dbSession.get();
                activeSessions.put(cleanToken, new SessionInfo(us.getUsername(), us.getExpiresAt()));
                log.info("Sessão persistida restaurada com sucesso do SQLite para usuário '{}'.", us.getUsername());
                return true;
            }
        } catch (Exception e) {
            log.warn("Erro ao consultar sessão no banco SQLite: {}", e.getMessage());
        }

        return false;
    }

    public String getUsername(String token) {
        if (token == null || token.isBlank()) return null;
        String cleanToken = token.trim();
        SessionInfo session = activeSessions.get(cleanToken);
        if (session != null) return session.username();

        try {
            return userSessionRepository.findById(cleanToken)
                    .map(UserSession::getUsername)
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    @Transactional
    public void logout(String token) {
        if (token != null && !token.isBlank()) {
            String cleanToken = token.trim();
            SessionInfo removed = activeSessions.remove(cleanToken);
            try {
                userSessionRepository.deleteById(cleanToken);
            } catch (Exception ignored) {}
            if (removed != null) {
                log.info("Sessão do usuário '{}' encerrada no banco e na memória.", removed.username());
            }
        }
    }
}
