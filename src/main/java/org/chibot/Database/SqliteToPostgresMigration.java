package org.chibot.Database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Migração única dos bancos SQLite legados (ChiData/ChiMusic/ChiState/ChiLang)
 * pro PostgreSQL. Roda no boot: se o marcador {@code sqlite-import} já existe
 * no destino, não faz nada (custo de um SELECT). Senão, copia cada tabela com
 * {@code INSERT ... ON CONFLICT DO NOTHING} — reexecutar após falha parcial é
 * seguro. Os arquivos .db NÃO são apagados (ficam de backup no volume).
 */
public final class SqliteToPostgresMigration {

    private static final Logger log = LoggerFactory.getLogger(SqliteToPostgresMigration.class);
    private static final String MARKER = "sqlite-import";

    private SqliteToPostgresMigration() {
    }

    /** Arquivo legado → tabelas que moravam nele (mesmos defaults/env de antes). */
    private static Map<Path, List<String>> legacyFiles() {
        Map<Path, List<String>> map = new LinkedHashMap<>();
        Path main = resolve("CHIBOT_DB_PATH", "ChiData.db", null);
        map.put(main, List.of(
                "harem_claim", "harem_player", "harem_wish", "harem_profile",
                "harem_badge", "harem_meta",
                "pf_listing", "pf_meta", "pf_indexed_listing", "pf_duty_token"));
        map.put(resolve("CHIBOT_MUSIC_DB_PATH", "ChiMusic.db", main), List.of(
                "music_config", "music_session", "music_queue",
                "music_playlist", "music_playlist_track"));
        map.put(resolve("CHIBOT_STATE_DB_PATH", "ChiState.db", main), List.of("bot_state"));
        map.put(resolve("CHIBOT_LANG_DB_PATH", "ChiLang.db", main), List.of(
                "user_language", "translation_cache"));
        return map;
    }

    /** Mesma resolução de caminho dos repositórios antigos: env própria > ao lado do principal > cwd. */
    private static Path resolve(String envKey, String defaultFile, Path mainDb) {
        String explicit = System.getenv(envKey);
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit);
        }
        if (mainDb != null && mainDb.getParent() != null) {
            return mainDb.getParent().resolve(defaultFile);
        }
        return Paths.get(defaultFile);
    }

    /** Entrada de produção: garante schema no destino, checa o marcador e copia o que houver. */
    public static void run(DataSource target) {
        if (target == null) {
            return;
        }
        // Constrói os repositórios só pelo efeito colateral: cada um cria as
        // próprias tabelas no destino (idempotente).
        new MaintenanceRepository(target);
        new LanguageRepository(target);
        new PfRepository(target);
        new MusicRepository(target);
        new HaremRepository(target);

        try (Connection pg = target.getConnection()) {
            ensureMarkerTable(pg);
            if (markerPresent(pg)) {
                return;
            }
            long total = 0;
            for (Map.Entry<Path, List<String>> e : legacyFiles().entrySet()) {
                if (!Files.exists(e.getKey())) {
                    log.info("Migração: {} não existe; nada a importar dele.", e.getKey());
                    continue;
                }
                try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + e.getKey())) {
                    total += copyDatabase(sqlite, pg, e.getValue());
                }
            }
            writeMarker(pg);
            log.info("Migração SQLite → PostgreSQL concluída: {} linha(s) importada(s). "
                    + "Os arquivos .db antigos ficaram no volume como backup.", total);
        } catch (SQLException e) {
            log.warn("Migração de dados do SQLite falhou; tento de novo no próximo boot.", e);
        }
    }

    /**
     * Copia as tabelas listadas de um SQLite aberto pro PostgreSQL, uma
     * transação por tabela, com ON CONFLICT DO NOTHING (reexecução segura).
     * Tabela ausente na origem é pulada (bancos velhos podem não ter todas).
     * Retorna o total de linhas inseridas. Visível pra teste.
     */
    static long copyDatabase(Connection sqlite, Connection pg, List<String> tables) throws SQLException {
        long total = 0;
        for (String table : tables) {
            if (!sqliteHasTable(sqlite, table)) {
                continue;
            }
            total += copyTable(sqlite, pg, table);
        }
        return total;
    }

    private static boolean sqliteHasTable(Connection sqlite, String table) throws SQLException {
        try (PreparedStatement ps = sqlite.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static long copyTable(Connection sqlite, Connection pg, String table) throws SQLException {
        long count = 0;
        try (Statement src = sqlite.createStatement();
             ResultSet rs = src.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            StringBuilder names = new StringBuilder();
            StringBuilder marks = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                if (i > 1) {
                    names.append(", ");
                    marks.append(", ");
                }
                names.append(meta.getColumnName(i));
                marks.append('?');
            }
            String sql = "INSERT INTO " + table + " (" + names + ") VALUES (" + marks
                    + ") ON CONFLICT DO NOTHING";
            boolean oldAutoCommit = pg.getAutoCommit();
            pg.setAutoCommit(false);
            try (PreparedStatement ins = pg.prepareStatement(sql)) {
                while (rs.next()) {
                    for (int i = 1; i <= cols; i++) {
                        ins.setObject(i, rs.getObject(i));
                    }
                    count += ins.executeUpdate();
                }
                pg.commit();
            } catch (SQLException e) {
                pg.rollback();
                throw e;
            } finally {
                pg.setAutoCommit(oldAutoCommit);
            }
        }
        log.info("Migração: {} — {} linha(s).", table, count);
        return count;
    }

    private static void ensureMarkerTable(Connection pg) throws SQLException {
        try (Statement st = pg.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS data_migration (
                        name    TEXT NOT NULL PRIMARY KEY,
                        done_at BIGINT NOT NULL
                    )
                    """);
        }
    }

    private static boolean markerPresent(Connection pg) throws SQLException {
        try (PreparedStatement ps = pg.prepareStatement(
                "SELECT 1 FROM data_migration WHERE name = ?")) {
            ps.setString(1, MARKER);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void writeMarker(Connection pg) throws SQLException {
        try (PreparedStatement ps = pg.prepareStatement(
                "INSERT INTO data_migration (name, done_at) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, MARKER);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}
