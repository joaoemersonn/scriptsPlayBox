package br.com.playbox.repository;

import br.com.playbox.entity.HistoricoVinculoMaquina;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface HistoricoVinculoMaquinaRepository extends JpaRepository<HistoricoVinculoMaquina, Long> {

    List<HistoricoVinculoMaquina> findByMaquinaIdOrderByDataInicioDesc(Long maquinaId);

    List<HistoricoVinculoMaquina> findByEstabelecimentoIdOrderByDataInicioDesc(Long estabelecimentoId);

    Optional<HistoricoVinculoMaquina> findFirstByMaquinaIdAndDataFimIsNullOrderByDataInicioDesc(Long maquinaId);

    @Query("SELECT h FROM HistoricoVinculoMaquina h WHERE h.maquina.id = :maquinaId AND h.dataInicio <= :timestamp AND (h.dataFim IS NULL OR h.dataFim > :timestamp) ORDER BY h.dataInicio DESC")
    List<HistoricoVinculoMaquina> findVigenteNoMomento(@Param("maquinaId") Long maquinaId, @Param("timestamp") LocalDateTime timestamp);
}
