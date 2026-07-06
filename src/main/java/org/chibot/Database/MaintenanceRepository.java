package org.chibot.Database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Persistência (PostgreSQL) do estado global do bot. Hoje guarda só uma
 * coisa: se o modo manutenção está ligado, pro
 * {@link org.chibot.Commands.Admin.MaintenanceCommand} lembrar disso depois
 * de um restart (ex.: o dono liga a manutenção, reinicia o bot pra fazer
 * deploy, e ele volta ainda pausado em vez de atender todo mundo no meio da
 * mexida).
 *
 * <p>A tabela é um chave/valor genérico ({@code bot_state}) de propósito, pra
 * abrigar outros flags globais no futuro sem mudar o schema. Segue o mesmo
 * espírito do {@link MusicRepository}: se o banco não abrir, loga e degrada —
 * a manutenção volta a ser só em memória (não sobrevive a restart), mas nada
 * quebra. Todos os métodos são sincronizados e pegam conexões do pool
 * compartilhado (Db).
 */
public class MaintenanceRepository {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceRepository.class);
    private static final String KEY_MAINTENANCE = "maintenance_active";

    private DataSource ds;

    public MaintenanceRepository() {
        this(Db.dataSource());
    }

    /** Construtor com DataSource explícito (testes). Null = degrada pra no-op. */
    public MaintenanceRepository(DataSource ds) {
        this.ds = ds;
        if (ds == null) {
            log.warn("Banco de estado não configurado; manutenção não vai persistir.");
            return;
        }
        try {
            createSchema();
            log.info("Banco de estado pronto.");
        } catch (SQLException e) {
            this.ds = null;
            log.warn("Não foi possível preparar o banco de estado; manutenção não vai persistir.", e);
        }
    }

    private boolean available() {
        return ds != null;
    }

    private void createSchema() throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bot_state (
                        key   TEXT NOT NULL PRIMARY KEY,
                        value TEXT
                    )
                    """);
        }
    }

    // ------------------------------------------------------------- manutenção

    /** Se o modo manutenção ficou ligado no último desligamento. False se banco indisponível. */
    public synchronized boolean isMaintenanceActive() {
        if (!available()) {
            return false;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT value FROM bot_state WHERE key = ?")) {
            ps.setString(1, KEY_MAINTENANCE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "true".equals(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler o estado da manutenção.", e);
            return false;
        }
    }

    public synchronized void setMaintenanceActive(boolean active) {
        if (!available()) {
            return;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                INSERT INTO bot_state (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """)) {
            ps.setString(1, KEY_MAINTENANCE);
            ps.setString(2, Boolean.toString(active));
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Falha ao salvar o estado da manutenção.", e);
        }
    }

    /** O pool é global (Db); aqui só descarta a referência. Mantido por compatibilidade. */
    public synchronized void close() {
        ds = null;
    }
}
