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

    @Query("SELECT a FROM AluguelRepasse a WHERE a.pago = false AND a.estabelecimento.ativo = true ORDER BY a.dataVencimento ASC")
    List<AluguelRepasse> findByPagoFalseOrderByDataVencimentoAsc();

    List<AluguelRepasse> findByPagoTrueOrderByDataPagamentoDesc();

    @Query("SELECT a FROM AluguelRepasse a WHERE a.pago = false AND a.estabelecimento.ativo = true AND a.dataVencimento <= :dataLimite ORDER BY a.dataVencimento ASC")
    List<AluguelRepasse> findVencendoAte(@Param("dataLimite") LocalDate dataLimite);

    List<AluguelRepasse> findByMesAno(String mesAno);

    @Query("SELECT a FROM AluguelRepasse a WHERE a.mesAno = :mesAno ORDER BY a.dataVencimento ASC")
    List<AluguelRepasse> findByMesAnoOrderByDataVencimentoAsc(@Param("mesAno") String mesAno);

    @Query("SELECT DISTINCT a.mesAno FROM AluguelRepasse a ORDER BY a.mesAno DESC")
    List<String> findDistinctMesAno();

    @Query("SELECT a FROM AluguelRepasse a ORDER BY a.mesAno DESC, a.dataVencimento ASC")
    List<AluguelRepasse> findAllOrdered();

    @Query("SELECT a FROM AluguelRepasse a WHERE a.estabelecimento.id = :estabelecimentoId ORDER BY a.mesAno DESC, a.dataVencimento DESC")
    List<AluguelRepasse> findByEstabelecimentoIdOrderByMesAnoDesc(@Param("estabelecimentoId") Long estabelecimentoId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM AluguelRepasse a WHERE a.estabelecimento.id = :estabelecimentoId")
    void deleteByEstabelecimentoId(@Param("estabelecimentoId") Long estabelecimentoId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM AluguelRepasse a WHERE a.estabelecimento.id = :estabelecimentoId AND a.pago = false")
    void deleteByEstabelecimentoIdAndPagoFalse(@Param("estabelecimentoId") Long estabelecimentoId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM AluguelRepasse a WHERE a.pago = false AND a.estabelecimento.ativo = false")
    void deleteByEstabelecimentoAtivoFalseAndPagoFalse();
}
