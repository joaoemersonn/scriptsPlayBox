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
                if (!columns.contains("valor_cash")) {
                    log.info("[DB-MIGRATION] Adicionando coluna 'valor_cash' na tabela transacoes...");
                    stmt.execute("ALTER TABLE transacoes ADD COLUMN valor_cash REAL DEFAULT 0.0");
                }
                if (!columns.contains("valor_pix")) {
                    log.info("[DB-MIGRATION] Adicionando coluna 'valor_pix' na tabela transacoes...");
                    stmt.execute("ALTER TABLE transacoes ADD COLUMN valor_pix REAL DEFAULT 0.0");
                }
                if (!columns.contains("valor_credito")) {
                    log.info("[DB-MIGRATION] Adicionando coluna 'valor_credito' na tabela transacoes...");
                    stmt.execute("ALTER TABLE transacoes ADD COLUMN valor_credito REAL DEFAULT 0.0");
                }
                if (!columns.contains("valor_debito")) {
                    log.info("[DB-MIGRATION] Adicionando coluna 'valor_debito' na tabela transacoes...");
                    stmt.execute("ALTER TABLE transacoes ADD COLUMN valor_debito REAL DEFAULT 0.0");
                }

                // Sanitização de nulos para garantir cálculos exatos de caixa e coletas
                stmt.execute("UPDATE transacoes SET valor_cash = 0.0 WHERE valor_cash IS NULL");
                stmt.execute("UPDATE transacoes SET valor_pix = 0.0 WHERE valor_pix IS NULL");
                stmt.execute("UPDATE transacoes SET valor_credito = 0.0 WHERE valor_credito IS NULL");
                stmt.execute("UPDATE transacoes SET valor_debito = 0.0 WHERE valor_debito IS NULL");
                stmt.execute("UPDATE transacoes SET valor_estorno = 0.0 WHERE valor_estorno IS NULL");
                stmt.execute("UPDATE transacoes SET quantidade_premios = 0.0 WHERE quantidade_premios IS NULL");
                stmt.execute("UPDATE transacoes SET teste = 0 WHERE teste IS NULL");
                stmt.execute("UPDATE transacoes SET valor_teste = 0.0 WHERE valor_teste IS NULL");
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

            // 5. Tabela relatorios_agendados (agendamentos personalizados de relatórios e alertas WhatsApp)
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS relatorios_agendados (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    nome TEXT NOT NULL,
                    tipo TEXT NOT NULL,
                    frequencia TEXT NOT NULL,
                    hora TEXT,
                    intervalo_minutos INTEGER,
                    dia_do_mes INTEGER DEFAULT 1,
                    cron_expression TEXT,
                    tipo_exibicao TEXT DEFAULT 'DETALHADO_ESTABELECIMENTO',
                    whatsapp_chat_id TEXT,
                    ativo INTEGER DEFAULT 1,
                    ultimo_disparo TIMESTAMP,
                    ultimo_status TEXT,
                    ultima_mensagem TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);

            boolean hasRelatorios = false;
            try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM relatorios_agendados")) {
                if (rs.next() && rs.getInt(1) > 0) {
                    hasRelatorios = true;
                }
            }

            if (!hasRelatorios) {
                log.info("[DB-MIGRATION] Populando relatórios agendados padrões...");
                stmt.execute("""
                    INSERT INTO relatorios_agendados (nome, tipo, frequencia, hora, intervalo_minutos, dia_do_mes, cron_expression, tipo_exibicao, ativo, created_at)
                    VALUES 
                    ('Relatório Diário 24h (Fechamento Ontem)', 'FATURAMENTO_24H', 'DIARIO', '00:00', NULL, 1, '0 0 0 * * *', 'DETALHADO_ESTABELECIMENTO', 1, CURRENT_TIMESTAMP),
                    ('Relatório Parcial 12h (Meio-dia)', 'FATURAMENTO_12H', 'DIARIO', '12:00', NULL, 1, '0 0 12 * * *', 'DETALHADO_ESTABELECIMENTO', 1, CURRENT_TIMESTAMP),
                    ('Relatório Mensal Consolidado', 'FATURAMENTO_MES', 'MENSAL', '08:00', NULL, 1, '0 0 8 1 * *', 'DETALHADO_ESTABELECIMENTO', 1, CURRENT_TIMESTAMP),
                    ('Alerta Diário Estoque Baixo (Reabastecimento)', 'ALERTA_ESTOQUE', 'DIARIO', '18:20', NULL, 1, '0 20 18 * * *', 'DETALHADO_ESTABELECIMENTO', 1, CURRENT_TIMESTAMP)
                """);
            }

            log.info("[DB-MIGRATION] Migração de esquema verificada com sucesso.");
        } catch (Exception e) {
            log.warn("[DB-MIGRATION] Aviso durante migração de esquema: {}", e.getMessage());
        }
    }
}
