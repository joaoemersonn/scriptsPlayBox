package br.com.playbox.repository;

import br.com.playbox.entity.Estabelecimento;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EstabelecimentoRepository extends JpaRepository<Estabelecimento, Long> {

    Optional<Estabelecimento> findByNomeIgnoreCase(String nome);

    java.util.List<Estabelecimento> findByAtivoTrue();
}
