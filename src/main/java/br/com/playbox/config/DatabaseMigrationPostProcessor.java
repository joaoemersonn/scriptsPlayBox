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

            // 1. Tabela user_sessions (persistência de sessão de login)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS user_sessions (
                    token VARCHAR(64) PRIMARY KEY,
                    username VARCHAR(100) NOT NULL,
                    expires_at TIMESTAMP NOT NULL,
                    created_at TIMESTAMP NOT NULL
                )
            """);

            // 2. Tabela configuracoes_sistema (configurações dinâmicas sem restart)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS configuracoes_sistema (
                    chave VARCHAR(100) PRIMARY KEY,
                    valor TEXT,
                    descricao VARCHAR(255),
                    updated_at TIMESTAMP
                )
            """);

            // 3. Verificar colunas na tabela transacoes
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

            // 4. Tabela historico_vinculos_maquina (histórico de vigência e movimentação de máquinas)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS historico_vinculos_maquina (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    maquina_id INTEGER NOT NULL,
                    estabelecimento_id INTEGER NOT NULL,
                    data_inicio TIMESTAMP NOT NULL,
                    data_fim TIMESTAMP,
                    motivo TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_hist_maq ON historico_vinculos_maquina(maquina_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_hist_est ON historico_vinculos_maquina(estabelecimento_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_hist_datas ON historico_vinculos_maquina(data_inicio, data_fim)");

            // Inicializar histórico para máquinas que já possuem estabelecimento e ainda não têm registro
            boolean maquinasExists = false;
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='maquinas'")) {
                if (rs.next()) maquinasExists = true;
            }

            if (maquinasExists) {
                stmt.execute("""
                    INSERT INTO historico_vinculos_maquina (maquina_id, estabelecimento_id, data_inicio, created_at, motivo)
                    SELECT m.id, m.estabelecimento_id, '2026-01-01 00:00:00', CURRENT_TIMESTAMP, 'Vínculo inicial cadastrado'
                    FROM maquinas m
                    WHERE m.estabelecimento_id IS NOT NULL
                      AND NOT EXISTS (
                          SELECT 1 FROM historico_vinculos_maquina h WHERE h.maquina_id = m.id
                      )
                """);
            }

            log.info("[DB-MIGRATION] Migração de esquema verificada com sucesso.");
        } catch (Exception e) {
            log.warn("[DB-MIGRATION] Aviso durante migração de esquema: {}", e.getMessage());
        }
    }
}
