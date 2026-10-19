package br.com.playbox.repository;

import br.com.playbox.entity.Transacao;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransacaoRepository extends JpaRepository<Transacao, Long> {

    Optional<Transacao> findByIntegracaoAndExternalTxId(String integracao, String externalTxId);

    boolean existsByIntegracaoAndExternalTxId(String integracao, String externalTxId);

    List<Transacao> findByTimestampBetween(LocalDateTime start, LocalDateTime end);

    @Query("SELECT t FROM Transacao t WHERE t.estabelecimento.id = :estabelecimentoId AND t.timestamp BETWEEN :start AND :end")
    List<Transacao> findByEstabelecimentoAndPeriod(
            @Param("estabelecimentoId") Long estabelecimentoId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );
}
