package br.com.playbox.repository;

import br.com.playbox.entity.MeioPagamento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MeioPagamentoRepository extends JpaRepository<MeioPagamento, Long> {

    List<MeioPagamento> findByEstabelecimentoId(Long estabelecimentoId);

    List<MeioPagamento> findByEstabelecimentoIdAndTipoIgnoreCase(Long estabelecimentoId, String tipo);
}
