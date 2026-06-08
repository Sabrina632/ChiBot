package org.chibot.Database;

import org.chibot.Commands.PartyFinderCommands.PfListing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistencia local (SQLite) das listagens de Party Finder raspadas do
 * xivpf.com. Guarda sempre o ultimo snapshot completo, que serve pra dois fins:
 *
 * <ul>
 *   <li><b>Sobreviver a restart</b> — o cache em memoria do
 *       {@link org.chibot.Commands.PartyFinderCommands.PartyFinderService} se
 *       perde quando o bot reinicia; o banco nao.</li>
 *   <li><b>Fallback</b> — quando o xivpf.com cai ou trava, o {@code /pf} mostra
 *       o ultimo snapshot conhecido em vez de dar erro.</li>
 * </ul>
 *
 * <p>A classe e resiliente: se o banco nao abrir (driver ausente, disco cheio,
 * etc.) ela apenas loga e vira no-op, deixando o {@code /pf} funcionar so com o
 * scraping. Todos os metodos sao sincronizados — uma unica conexao SQLite.
 */
public class PfRepository {

    private static final Logger log = LoggerFactory.getLogger(PfRepository.class);
    private static final String DB_URL = "jdbc:sqlite:ChiData.db";

    private Connection conn;

    public PfRepository() {
        this(DB_URL);
    }

    /** Construtor com URL explicita (ex.: {@code jdbc:sqlite::memory:} nos testes). */
    public PfRepository(String dbUrl) {
        try {
            conn = DriverManager.getConnection(dbUrl);
            createSchema();
            log.info("Banco do Party Finder pronto ({}).", dbUrl);
        } catch (SQLException e) {
            conn = null;
            log.warn("Nao foi possivel abrir o banco do Party Finder; seguindo sem persistencia.", e);
        }
    }

    private boolean available() {
        return conn != null;
    }

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pf_listing (
                        id          TEXT PRIMARY KEY,
                        data_centre TEXT,
                        category    TEXT,
                        duty        TEXT,
                        description TEXT,
                        slots       TEXT,
                        filled      INTEGER,
                        total       INTEGER,
                        min_il      TEXT,
                        creator     TEXT,
                        world       TEXT,
                        expires     TEXT,
                        updated     TEXT,
                        comp        TEXT
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pf_meta (
                        key   TEXT PRIMARY KEY,
                        value TEXT
                    )
                    """);
        }
    }

    /**
     * Substitui o snapshot guardado pelas listagens recem-raspadas, em uma
     * transacao (tudo ou nada), e registra o momento do scraping.
     */
    public synchronized void saveSnapshot(List<PfListing> listings, Instant fetchedAt) {
        if (!available()) {
            return;
        }
        try {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM pf_listing");
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO pf_listing
                        (id, data_centre, category, duty, description, slots,
                         filled, total, min_il, creator, world, expires, updated, comp)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                for (PfListing l : listings) {
                    ps.setString(1, l.id());
                    ps.setString(2, l.dataCentre());
                    ps.setString(3, l.category());
                    ps.setString(4, l.duty());
                    ps.setString(5, l.description());
                    ps.setString(6, l.slots());
                    ps.setInt(7, l.filled());
                    ps.setInt(8, l.total());
                    ps.setString(9, l.minIL());
                    ps.setString(10, l.creator());
                    ps.setString(11, l.world());
                    ps.setString(12, l.expires());
                    ps.setString(13, l.updated());
                    ps.setString(14, l.comp());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            putMeta("fetched_at", Long.toString(fetchedAt.toEpochMilli()));
            conn.commit();
        } catch (SQLException e) {
            log.warn("Falha ao salvar o snapshot do Party Finder.", e);
            rollbackQuietly();
        } finally {
            restoreAutoCommit();
        }
    }

    /** Le o ultimo snapshot guardado (lista vazia se nao houver / banco indisponivel). */
    public synchronized List<PfListing> loadSnapshot() {
        List<PfListing> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT id, data_centre, category, duty, description, slots,
                            filled, total, min_il, creator, world, expires, updated, comp
                     FROM pf_listing
                     """)) {
            while (rs.next()) {
                out.add(new PfListing(
                        rs.getString("id"),
                        rs.getString("data_centre"),
                        rs.getString("category"),
                        rs.getString("duty"),
                        rs.getString("description"),
                        rs.getString("slots"),
                        rs.getInt("filled"),
                        rs.getInt("total"),
                        rs.getString("min_il"),
                        rs.getString("creator"),
                        rs.getString("world"),
                        rs.getString("expires"),
                        rs.getString("updated"),
                        rs.getString("comp")
                ));
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler o snapshot do Party Finder.", e);
        }
        return out;
    }

    /** Quando o snapshot guardado foi raspado, ou {@code Instant.EPOCH} se nunca. */
    public synchronized Instant lastFetchedAt() {
        if (!available()) {
            return Instant.EPOCH;
        }
        String v = getMeta("fetched_at");
        if (v == null) {
            return Instant.EPOCH;
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(v));
        } catch (NumberFormatException e) {
            return Instant.EPOCH;
        }
    }

    private void putMeta(String key, String value) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO pf_meta (key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    private String getMeta(String key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM pf_meta WHERE key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler meta '{}' do Party Finder.", key, e);
            return null;
        }
    }

    private void rollbackQuietly() {
        try {
            conn.rollback();
        } catch (SQLException ignored) {
            // ja estamos tratando uma falha; nada a fazer
        }
    }

    private void restoreAutoCommit() {
        try {
            conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // idem
        }
    }
}