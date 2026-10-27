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

    long countByTimestampBetween(LocalDateTime start, LocalDateTime end);

    @Query("""
        SELECT COALESCE(SUM(
            CASE
                WHEN t.teste = true AND (t.valorTeste IS NULL OR t.valorTeste <= 0.0) THEN 0.0
                WHEN t.teste = true AND t.valorTeste > 0.0 THEN (CASE WHEN t.valorCash > t.valorTeste THEN (t.valorCash - t.valorTeste) ELSE 0.0 END)
                ELSE COALESCE(t.valorCash, 0.0)
            END
        ), 0.0)
        FROM Transacao t
        WHERE t.maquina.id = :maquinaId AND t.timestamp >= :inicio AND t.timestamp <= :fim
    """)
    Double sumValorCashByMaquinaAndPeriod(
            @Param("maquinaId") Long maquinaId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fim") LocalDateTime fim
    );

    @Query("""
        SELECT COALESCE(SUM(
            CASE
                WHEN t.teste = true AND (t.valorTeste IS NULL OR t.valorTeste <= 0.0) THEN 0.0
                WHEN t.teste = true AND t.valorTeste > 0.0 THEN (CASE WHEN t.valorCash > t.valorTeste THEN (t.valorCash - t.valorTeste) ELSE 0.0 END)
                ELSE COALESCE(t.valorCash, 0.0)
            END
        ), 0.0)
        FROM Transacao t
        WHERE t.maquina.id = :maquinaId AND t.timestamp <= :fim
    """)
    Double sumValorCashByMaquinaUntil(
            @Param("maquinaId") Long maquinaId,
            @Param("fim") LocalDateTime fim
    );

    @Query("""
        SELECT t FROM Transacao t
        LEFT JOIN t.maquina m
        LEFT JOIN t.estabelecimento e
        WHERE t.timestamp >= :inicio AND t.timestamp <= :fim
          AND (:estabelecimentoId IS NULL OR e.id = :estabelecimentoId)
          AND (:cidade IS NULL OR LOWER(e.cidade) = LOWER(:cidade))
          AND (:maquinaId IS NULL OR m.id = :maquinaId)
          AND (:integracao IS NULL OR UPPER(t.integracao) = UPPER(:integracao))
          AND (:teste IS NULL OR (:teste = true AND t.teste = true) OR (:teste = false AND (t.teste IS NULL OR t.teste = false)))
        ORDER BY t.timestamp DESC
    """)
    List<Transacao> findFiltered(
            @Param("inicio") LocalDateTime inicio,
            @Param("fim") LocalDateTime fim,
            @Param("estabelecimentoId") Long estabelecimentoId,
            @Param("cidade") String cidade,
            @Param("maquinaId") Long maquinaId,
            @Param("integracao") String integracao,
            @Param("teste") Boolean teste
    );

    default List<Transacao> findFiltered(
            LocalDateTime inicio,
            LocalDateTime fim,
            Long estabelecimentoId,
            String cidade,
            Long maquinaId,
            String integracao
    ) {
        return findFiltered(inicio, fim, estabelecimentoId, cidade, maquinaId, integracao, null);
    }

    @Query("""
        SELECT t FROM Transacao t
        LEFT JOIN t.maquina m
        LEFT JOIN t.estabelecimento e
        WHERE t.timestamp >= :inicio AND t.timestamp <= :fim
          AND (:maquinaId IS NULL OR m.id = :maquinaId)
          AND (:estabelecimentoId IS NULL OR e.id = :estabelecimentoId)
        ORDER BY t.timestamp ASC
    """)
    List<Transacao> findByPeriodAndFilters(
            @Param("inicio") LocalDateTime inicio,
            @Param("fim") LocalDateTime fim,
            @Param("maquinaId") Long maquinaId,
            @Param("estabelecimentoId") Long estabelecimentoId
    );
}
