package br.com.playbox.repository;

import br.com.playbox.entity.Maquina;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaquinaRepository extends JpaRepository<Maquina, Long> {

    Optional<Maquina> findByIntegracaoAndExternalId(String integracao, String externalId);

    List<Maquina> findByAtivoTrue();

    List<Maquina> findByEstabelecimentoId(Long estabelecimentoId);

    List<Maquina> findByIntegracao(String integracao);

    @Query("SELECT m FROM Maquina m LEFT JOIN m.estabelecimento e WHERE m.ativo = true " +
           "AND (e IS NULL OR e.ativo = true) " +
           "AND COALESCE(m.estoqueAtual, 0) < COALESCE(m.estoqueMinimoAlerta, 30) " +
           "ORDER BY COALESCE(m.estoqueAtual, 0) ASC")
    List<Maquina> findMaquinasComEstoqueBaixo();

    @Query("SELECT m FROM Maquina m LEFT JOIN m.estabelecimento e WHERE m.ativo = true " +
           "AND (:estabelecimentoId IS NULL OR e.id = :estabelecimentoId) " +
           "AND (:cidade IS NULL OR LOWER(e.cidade) = LOWER(:cidade)) " +
           "AND (:maquinaId IS NULL OR m.id = :maquinaId) " +
           "AND (:apenasEstoqueBaixo = false OR COALESCE(m.estoqueAtual, 0) < COALESCE(m.estoqueMinimoAlerta, 30)) " +
           "ORDER BY m.id ASC")
    List<Maquina> filtrarEstoque(
            @Param("estabelecimentoId") Long estabelecimentoId,
            @Param("cidade") String cidade,
            @Param("maquinaId") Long maquinaId,
            @Param("apenasEstoqueBaixo") boolean apenasEstoqueBaixo
    );
}
