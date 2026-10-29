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

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM Transacao t WHERE t.integracao = :integracao AND t.externalTxId = :externalTxId")
    void deleteByIntegracaoAndExternalTxId(@Param("integracao") String integracao, @Param("externalTxId") String externalTxId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM Transacao t WHERE t.integracao = :integracao AND t.maquina.id = :maquinaId AND t.timestamp >= :inicio AND t.timestamp <= :fim AND UPPER(t.tipoPagamento) = 'CONSOLIDADO'")
    void deleteLegacyConsolidadoByPeriod(
            @Param("integracao") String integracao,
            @Param("maquinaId") Long maquinaId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fim") LocalDateTime fim
    );

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM Transacao t WHERE UPPER(COALESCE(t.tipoPagamento, '')) = 'CONSOLIDADO'")
    int deleteAllLegacyConsolidados();

    @Query("SELECT COUNT(t) FROM Transacao t WHERE t.timestamp >= :start AND t.timestamp <= :end AND UPPER(COALESCE(t.tipoPagamento, '')) = 'CONSOLIDADO'")
    long countConsolidadosBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    List<Transacao> findByTimestampBetween(LocalDateTime start, LocalDateTime end);

    @Query("SELECT t FROM Transacao t WHERE t.maquina.id = :maquinaId AND t.timestamp >= :dataHora")
    List<Transacao> findByMaquinaIdAndTimestampGreaterThanEqual(
            @Param("maquinaId") Long maquinaId,
            @Param("dataHora") LocalDateTime dataHora
    );

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
                WHEN t.teste = true AND t.valorTeste > 0.0 THEN (
                    CASE 
                        WHEN COALESCE(t.valorCash, CASE WHEN UPPER(COALESCE(t.tipoPagamento, '')) IN ('DINHEIRO', 'ESPECIE', 'CASH') THEN t.valor ELSE 0.0 END) > t.valorTeste 
                        THEN (COALESCE(t.valorCash, CASE WHEN UPPER(COALESCE(t.tipoPagamento, '')) IN ('DINHEIRO', 'ESPECIE', 'CASH') THEN t.valor ELSE 0.0 END) - t.valorTeste) 
                        ELSE 0.0 
                    END
                )
                ELSE COALESCE(t.valorCash, CASE WHEN UPPER(COALESCE(t.tipoPagamento, '')) IN ('DINHEIRO', 'ESPECIE', 'CASH') THEN t.valor ELSE 0.0 END, 0.0)
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
                WHEN t.teste = true AND t.valorTeste > 0.0 THEN (
                    CASE 
                        WHEN COALESCE(t.valorCash, CASE WHEN UPPER(COALESCE(t.tipoPagamento, '')) IN ('DINHEIRO', 'ESPECIE', 'CASH') THEN t.valor ELSE 0.0 END) > t.valorTeste 
                        THEN (COALESCE(t.valorCash, CASE WHEN UPPER(COALESCE(t.tipoPagamento, '')) IN ('DINHEIRO', 'ESPECIE', 'CASH') THEN t.valor ELSE 0.0 END) - t.valorTeste) 
                        ELSE 0.0 
                    END
                )
                ELSE COALESCE(t.valorCash, CASE WHEN UPPER(COALESCE(t.tipoPagamento, '')) IN ('DINHEIRO', 'ESPECIE', 'CASH') THEN t.valor ELSE 0.0 END, 0.0)
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
        SELECT COALESCE(SUM(
            CASE
                WHEN t.teste = true AND (t.valorTeste IS NULL OR t.valorTeste <= 0.0) THEN 0.0
                WHEN t.teste = true AND t.valorTeste > 0.0 THEN (
                    CASE 
                        WHEN COALESCE(t.valor, 0.0) > t.valorTeste 
                        THEN (COALESCE(t.valor, 0.0) - t.valorTeste) 
                        ELSE 0.0 
                    END
                )
                ELSE COALESCE(t.valor, 0.0)
            END
        ), 0.0)
        FROM Transacao t
        WHERE t.maquina.id = :maquinaId AND t.timestamp >= :inicio AND t.timestamp <= :fim
    """)
    Double sumValorTotalByMaquinaAndPeriod(
            @Param("maquinaId") Long maquinaId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fim") LocalDateTime fim
    );

    @Query("""
        SELECT COALESCE(SUM(
            CASE
                WHEN t.teste = true AND (t.valorTeste IS NULL OR t.valorTeste <= 0.0) THEN 0.0
                WHEN t.teste = true AND t.valorTeste > 0.0 THEN (
                    CASE 
                        WHEN COALESCE(t.valor, 0.0) > t.valorTeste 
                        THEN (COALESCE(t.valor, 0.0) - t.valorTeste) 
                        ELSE 0.0 
                    END
                )
                ELSE COALESCE(t.valor, 0.0)
            END
        ), 0.0)
        FROM Transacao t
        WHERE t.maquina.id = :maquinaId AND t.timestamp <= :fim
    """)
    Double sumValorTotalByMaquinaUntil(
            @Param("maquinaId") Long maquinaId,
            @Param("fim") LocalDateTime fim
    );

    @Query("""
        SELECT COALESCE(SUM(
            CASE
                WHEN t.teste = true THEN 0.0
                ELSE COALESCE(t.quantidadePremios, 0.0)
            END
        ), 0.0)
        FROM Transacao t
        WHERE t.maquina.id = :maquinaId AND t.timestamp >= :inicio AND t.timestamp <= :fim
    """)
    Double sumQuantidadePremiosByMaquinaAndPeriod(
            @Param("maquinaId") Long maquinaId,
            @Param("inicio") LocalDateTime inicio,
            @Param("fim") LocalDateTime fim
    );

    @Query("""
        SELECT COALESCE(SUM(
            CASE
                WHEN t.teste = true THEN 0.0
                ELSE COALESCE(t.quantidadePremios, 0.0)
            END
        ), 0.0)
        FROM Transacao t
        WHERE t.maquina.id = :maquinaId AND t.timestamp <= :fim
    """)
    Double sumQuantidadePremiosByMaquinaUntil(
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
          AND UPPER(COALESCE(t.tipoPagamento, '')) != 'CONSOLIDADO'
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
          AND UPPER(COALESCE(t.tipoPagamento, '')) != 'CONSOLIDADO'
        ORDER BY t.timestamp ASC
    """)
    List<Transacao> findByPeriodAndFilters(
            @Param("inicio") LocalDateTime inicio,
            @Param("fim") LocalDateTime fim,
            @Param("maquinaId") Long maquinaId,
            @Param("estabelecimentoId") Long estabelecimentoId
    );
}
