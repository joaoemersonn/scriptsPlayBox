package br.com.playbox.repository;

import br.com.playbox.entity.Maquina;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MaquinaRepository extends JpaRepository<Maquina, Long> {

    Optional<Maquina> findByIntegracaoAndExternalId(String integracao, String externalId);

    List<Maquina> findByAtivoTrue();

    List<Maquina> findByEstabelecimentoId(Long estabelecimentoId);
}
