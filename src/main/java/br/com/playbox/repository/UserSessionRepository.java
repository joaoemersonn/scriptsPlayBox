package br.com.playbox.repository;

import br.com.playbox.entity.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    Optional<UserSession> findByTokenAndExpiresAtAfter(String token, Instant now);

    void deleteByExpiresAtBefore(Instant now);
}
