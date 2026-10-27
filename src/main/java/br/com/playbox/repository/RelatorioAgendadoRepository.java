package br.com.playbox.repository;

import br.com.playbox.entity.RelatorioAgendado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RelatorioAgendadoRepository extends JpaRepository<RelatorioAgendado, Long> {

    List<RelatorioAgendado> findByAtivoTrue();

    List<RelatorioAgendado> findAllByOrderByCreatedAtDesc();
}
