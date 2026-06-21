package org.chibot.Database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Persistência local (SQLite) do estado global do bot, num arquivo SEPARADO
 * ({@code ChiState.db}) do harém/PF e da música. Hoje guarda só uma coisa: se o
 * modo manutenção está ligado, pro {@link org.chibot.Commands.Admin.MaintenanceCommand}
 * lembrar disso depois de um restart (ex.: o dono liga a manutenção, reinicia o
 * bot pra fazer deploy, e ele volta ainda pausado em vez de atender todo mundo
 * no meio da mexida).
 *
 * <p>A tabela é um chave/valor genérico ({@code bot_state}) de propósito, pra
 * abrigar outros flags globais no futuro sem mudar o schema. Segue o mesmo
 * espírito do {@link MusicRepository}: se o banco não abrir, loga e degrada —
 * a manutenção volta a ser só em memória (não sobrevive a restart), mas nada
 * quebra. Todos os métodos são sincronizados (uma única conexão SQLite).
 */
public class MaintenanceRepository {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceRepository.class);
    private static final String DEFAULT_DB_FILE = "ChiState.db";
    private static final String KEY_MAINTENANCE = "maintenance_active";

    private Connection conn;

    public MaintenanceRepository() {
        this(defaultDbUrl());
    }

    /** Construtor com URL explícita (ex.: {@code jdbc:sqlite::memory:} nos testes). */
    public MaintenanceRepository(String dbUrl) {
        try {
            ensureParentDir(dbUrl);
            conn = DriverManager.getConnection(dbUrl);
            createSchema();
            log.info("Banco de estado pronto ({}).", dbUrl);
        } catch (SQLException e) {
            conn = null;
            log.warn("Não foi possível abrir o banco de estado; manutenção não vai persistir.", e);
        }
    }

    /**
     * Fica num arquivo separado do banco principal (harém/PF) e da música. Por
     * padrão ao lado do banco principal (mesmo diretório/volume no Docker);
     * {@code CHIBOT_STATE_DB_PATH} sobrescreve.
     */
    private static String defaultDbUrl() {
        String explicit = System.getenv("CHIBOT_STATE_DB_PATH");
        if (explicit != null && !explicit.isBlank()) {
            return "jdbc:sqlite:" + explicit;
        }
        String mainDb = System.getenv("CHIBOT_DB_PATH");
        if (mainDb != null && !mainDb.isBlank()) {
            java.nio.file.Path parent = java.nio.file.Paths.get(mainDb).getParent();
            java.nio.file.Path statePath = parent != null
                    ? parent.resolve(DEFAULT_DB_FILE)
                    : java.nio.file.Paths.get(DEFAULT_DB_FILE);
            return "jdbc:sqlite:" + statePath;
        }
        return "jdbc:sqlite:" + DEFAULT_DB_FILE;
    }

    private static void ensureParentDir(String dbUrl) {
        String prefix = "jdbc:sqlite:";
        if (!dbUrl.startsWith(prefix)) {
            return;
        }
        String path = dbUrl.substring(prefix.length());
        if (path.isBlank() || path.startsWith(":")) {
            return; // :memory:, etc.
        }
        try {
            java.nio.file.Path parent = java.nio.file.Paths.get(path).getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
        } catch (Exception e) {
            log.warn("Não foi possível criar o diretório do banco para '{}'.", path, e);
        }
    }

    private boolean available() {
        return conn != null;
    }

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
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
        try (PreparedStatement ps = conn.prepareStatement(
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
        try (PreparedStatement ps = conn.prepareStatement("""
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

    /** Fecha a conexão com o banco. Só usado em teste / shutdown explícito. */
    public synchronized void close() {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // fechando; nada a fazer
        } finally {
            conn = null;
        }
    }
}