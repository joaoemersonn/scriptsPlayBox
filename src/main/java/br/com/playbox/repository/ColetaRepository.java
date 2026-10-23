package br.com.playbox.repository;

import br.com.playbox.entity.Coleta;
import br.com.playbox.entity.Maquina;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ColetaRepository extends JpaRepository<Coleta, Long> {

    List<Coleta> findAllByOrderByDataHoraColetaDesc();

    List<Coleta> findByStatusOrderByDataHoraColetaDesc(String status);

    List<Coleta> findByMaquinaOrderByDataHoraColetaDesc(Maquina maquina);

    List<Coleta> findByEstabelecimentoIdOrderByDataHoraColetaDesc(Long estabelecimentoId);

    // Encontrar a última coleta VÁLIDA (não cancelada) antes da dataHora fornecida para uma máquina
    Optional<Coleta> findFirstByMaquinaIdAndStatusNotAndDataHoraColetaBeforeOrderByDataHoraColetaDesc(Long maquinaId, String statusNot, LocalDateTime dataHora);

    // Encontrar a última coleta antes da dataHora fornecida para uma máquina
    Optional<Coleta> findFirstByMaquinaIdAndDataHoraColetaBeforeOrderByDataHoraColetaDesc(Long maquinaId, LocalDateTime dataHora);

    // Encontrar a última coleta VÁLIDA (não cancelada) registrada da máquina em geral
    Optional<Coleta> findFirstByMaquinaIdAndStatusNotOrderByDataHoraColetaDesc(Long maquinaId, String statusNot);

    // Encontrar a última coleta registrada da máquina em geral
    Optional<Coleta> findFirstByMaquinaIdOrderByDataHoraColetaDesc(Long maquinaId);
}
