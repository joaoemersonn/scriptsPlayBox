package br.com.playbox.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

@Component
public class DatabaseMigrationPostProcessor implements BeanPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationPostProcessor.class);

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof DataSource ds) {
            migrate(ds);
        }
        return bean;
    }

    private void migrate(DataSource ds) {
        log.info("[DB-MIGRATION] Verificando integridade e colunas das tabelas SQLite...");
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. Verificar tabela transacoes
            boolean transacoesExists = false;
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='transacoes'")) {
                if (rs.next()) {
                    transacoesExists = true;
                }
            }

            if (transacoesExists) {
                Set<String> columns = new HashSet<>();
                try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(transacoes)")) {
                    while (rs.next()) {
                        columns.add(rs.getString("name").toLowerCase());
                    }
                }

                if (!columns.contains("teste")) {
                    log.info("[DB-MIGRATION] Adicionando coluna 'teste' na tabela transacoes...");
                    stmt.execute("ALTER TABLE transacoes ADD COLUMN teste INTEGER DEFAULT 0");
                }
                if (!columns.contains("valor_teste")) {
                    log.info("[DB-MIGRATION] Adicionando coluna 'valor_teste' na tabela transacoes...");
                    stmt.execute("ALTER TABLE transacoes ADD COLUMN valor_teste REAL DEFAULT 0.0");
                }
                if (!columns.contains("motivo_teste")) {
                    log.info("[DB-MIGRATION] Adicionando coluna 'motivo_teste' na tabela transacoes...");
                    stmt.execute("ALTER TABLE transacoes ADD COLUMN motivo_teste TEXT");
                }
            }

            log.info("[DB-MIGRATION] Migração de esquema verificada com sucesso.");
        } catch (Exception e) {
            log.warn("[DB-MIGRATION] Aviso durante migração de esquema: {}", e.getMessage());
        }
    }
}
