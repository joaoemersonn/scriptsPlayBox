package br.com.playbox.repository;

import br.com.playbox.entity.MovimentacaoEstoque;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MovimentacaoEstoqueRepository extends JpaRepository<MovimentacaoEstoque, Long> {

    Optional<MovimentacaoEstoque> findByExternalRef(String externalRef);

    List<MovimentacaoEstoque> findByMaquinaIdOrderByDataMovimentacaoDesc(Long maquinaId);

    List<MovimentacaoEstoque> findByMaquinaIdOrderByDataMovimentacaoDesc(Long maquinaId, Pageable pageable);

    List<MovimentacaoEstoque> findByMaquinaIdAndDataMovimentacaoBetweenOrderByDataMovimentacaoDesc(
            Long maquinaId, LocalDateTime start, LocalDateTime end
    );

    List<MovimentacaoEstoque> findTop100ByOrderByDataMovimentacaoDesc();
}
