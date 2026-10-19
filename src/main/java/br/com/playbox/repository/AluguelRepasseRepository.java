package br.com.playbox.repository;

import br.com.playbox.entity.AluguelRepasse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AluguelRepasseRepository extends JpaRepository<AluguelRepasse, Long> {

    Optional<AluguelRepasse> findByEstabelecimentoIdAndMesAno(Long estabelecimentoId, String mesAno);

    List<AluguelRepasse> findByEstabelecimentoIdOrderByDataVencimentoDesc(Long estabelecimentoId);

    List<AluguelRepasse> findByPagoFalseOrderByDataVencimentoAsc();

    List<AluguelRepasse> findByPagoTrueOrderByDataPagamentoDesc();

    @Query("SELECT a FROM AluguelRepasse a WHERE a.pago = false AND a.dataVencimento <= :dataLimite ORDER BY a.dataVencimento ASC")
    List<AluguelRepasse> findVencendoAte(@Param("dataLimite") LocalDate dataLimite);

    List<AluguelRepasse> findByMesAno(String mesAno);
}
