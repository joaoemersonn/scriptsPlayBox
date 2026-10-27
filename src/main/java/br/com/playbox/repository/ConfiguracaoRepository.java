package br.com.playbox.repository;

import br.com.playbox.entity.ConfiguracaoSistema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ConfiguracaoRepository extends JpaRepository<ConfiguracaoSistema, String> {
}
