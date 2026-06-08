package org.chibot.Database;

import org.chibot.Commands.PartyFinderCommands.PfListing;
import org.chibot.Commands.PartyFinderCommands.StratsTokenizer;
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
    private static final String DEFAULT_DB_PATH = "ChiData.db";

    private Connection conn;

    public PfRepository() {
        this(defaultDbUrl());
    }

    /** Construtor com URL explicita (ex.: {@code jdbc:sqlite::memory:} nos testes). */
    public PfRepository(String dbUrl) {
        try {
            ensureParentDir(dbUrl);
            conn = DriverManager.getConnection(dbUrl);
            createSchema();
            log.info("Banco do Party Finder pronto ({}).", dbUrl);
        } catch (SQLException e) {
            conn = null;
            log.warn("Nao foi possivel abrir o banco do Party Finder; seguindo sem persistencia.", e);
        }
    }

    /**
     * Caminho do banco: env {@code CHIBOT_DB_PATH} (usado na VPS/Docker pra
     * apontar pra um diretorio com volume) ou {@code ChiData.db} no diretorio
     * atual (dev local).
     */
    private static String defaultDbUrl() {
        String path = System.getenv("CHIBOT_DB_PATH");
        if (path == null || path.isBlank()) {
            path = DEFAULT_DB_PATH;
        }
        return "jdbc:sqlite:" + path;
    }

    /** Cria o diretorio do arquivo do banco se ainda nao existir (ignora {@code :memory:}). */
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
            log.warn("Nao foi possivel criar o diretorio do banco para '{}'.", path, e);
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
            // Acumulo de strats (tokens) ao longo do tempo. Cada PF e tokenizado
            // uma vez (gate em pf_indexed_listing) e suas contagens somam em
            // pf_duty_token, que cresce a cada scraping.
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pf_indexed_listing (
                        id TEXT PRIMARY KEY
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS pf_duty_token (
                        duty       TEXT NOT NULL,
                        token      TEXT NOT NULL,
                        count      INTEGER NOT NULL DEFAULT 0,
                        first_seen TEXT,
                        last_seen  TEXT,
                        PRIMARY KEY (duty, token)
                    )
                    """);
            st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_pf_duty_token ON pf_duty_token(duty, count DESC)");
        }
    }

    /** Uma contagem de token (strat) pra uma duty. */
    public record TokenCount(String token, int count) {}

    /**
     * Tokeniza as descricoes das listagens ainda nao indexadas e acumula as
     * contagens por duty. O gate {@code pf_indexed_listing} garante que cada PF
     * conte uma unica vez, mesmo que apareca em varios scrapings. So indexa
     * listagens do data center informado (ex.: Aether) com duty conhecida.
     */
    public synchronized void indexTokens(List<PfListing> listings, String dataCenter, Instant when) {
        if (!available()) {
            return;
        }
        String ts = when.toString();
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement gate = conn.prepareStatement(
                         "INSERT OR IGNORE INTO pf_indexed_listing (id) VALUES (?)");
                 PreparedStatement upsert = conn.prepareStatement("""
                         INSERT INTO pf_duty_token (duty, token, count, first_seen, last_seen)
                         VALUES (?, ?, 1, ?, ?)
                         ON CONFLICT(duty, token)
                         DO UPDATE SET count = count + 1, last_seen = excluded.last_seen
                         """)) {
                for (PfListing l : listings) {
                    if (l.id() == null || l.duty() == null
                            || dataCenter != null && !dataCenter.equalsIgnoreCase(l.dataCentre())) {
                        continue;
                    }
                    gate.setString(1, l.id());
                    if (gate.executeUpdate() == 0) {
                        continue; // ja indexado antes
                    }
                    for (String token : StratsTokenizer.tokenize(l.description())) {
                        upsert.setString(1, l.duty());
                        upsert.setString(2, token);
                        upsert.setString(3, ts);
                        upsert.setString(4, ts);
                        upsert.executeUpdate();
                    }
                }
            }
            conn.commit();
        } catch (SQLException e) {
            log.warn("Falha ao indexar tokens de strat do Party Finder.", e);
            rollbackQuietly();
        } finally {
            restoreAutoCommit();
        }
    }

    /**
     * Top strats (tokens) acumuladas pras duties cujo nome contem
     * {@code dutySubstring} (case-insensitive), somando entre elas e ordenando
     * pela contagem. Lista vazia se nao houver dados / banco indisponivel.
     */
    public synchronized List<TokenCount> topTokens(String dutySubstring, int limit) {
        List<TokenCount> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT token, SUM(count) AS c FROM pf_duty_token
                WHERE LOWER(duty) LIKE ?
                GROUP BY token
                ORDER BY c DESC
                LIMIT ?
                """)) {
            ps.setString(1, "%" + dutySubstring.toLowerCase(java.util.Locale.ROOT) + "%");
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TokenCount(rs.getString("token"), rs.getInt("c")));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler as top strats do Party Finder.", e);
        }
        return out;
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