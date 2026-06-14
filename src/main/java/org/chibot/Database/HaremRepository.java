package org.chibot.Database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Persistencia local (SQLite) do sistema de waifu/husbando. Guarda os
 * casamentos (um dono por personagem por servidor), o estado de cada jogador
 * (kakera, cooldown de claim e rolls da hora) e as listas de desejos.
 *
 * <p>Segue o mesmo espirito do {@link PfRepository}: se o banco nao abrir, a
 * classe loga e degrada — rolls continuam funcionando, mas claims falham.
 * Todos os metodos sao sincronizados (uma unica conexao SQLite).
 */
public class HaremRepository {

    private static final Logger log = LoggerFactory.getLogger(HaremRepository.class);
    private static final String DEFAULT_DB_PATH = "ChiData.db";

    /** Estado de um jogador num servidor. */
    public record Player(long kakera, long lastClaimMs, int rollsUsed, long rollsHour,
                         long lastDailyMs, int bonusRolls, int towerLevel) {}

    /** Um personagem casado num servidor. */
    public record Claim(long charId, String name, String series, String imageUrl,
                        int kakera, String ownerId, String ownerName) {}

    /** Resultado de {@link #addWish}. */
    public enum WishResult { OK, DUPLICADO, CHEIO }

    /** Personalizacao do perfil de um jogador ({@code color} -1 = padrao). */
    public record Profile(int color, String bio, long favCharId) {}

    /** Estatisticas do harem de um jogador num servidor. */
    public record HaremStats(int count, long valorTotal, long primeiroClaimMs) {}

    private Connection conn;

    public HaremRepository() {
        this(defaultDbUrl());
    }

    /** Construtor com URL explicita (ex.: {@code jdbc:sqlite::memory:} nos testes). */
    public HaremRepository(String dbUrl) {
        try {
            conn = DriverManager.getConnection(dbUrl);
            createSchema();
            log.info("Banco do harem pronto ({}).", dbUrl);
        } catch (SQLException e) {
            conn = null;
            log.warn("Nao foi possivel abrir o banco do harem; claims nao vao persistir.", e);
        }
    }

    private static String defaultDbUrl() {
        String path = System.getenv("CHIBOT_DB_PATH");
        if (path == null || path.isBlank()) {
            path = DEFAULT_DB_PATH;
        }
        return "jdbc:sqlite:" + path;
    }

    private boolean available() {
        return conn != null;
    }

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS harem_claim (
                        guild_id   TEXT    NOT NULL,
                        char_id    INTEGER NOT NULL,
                        name       TEXT    NOT NULL,
                        series     TEXT,
                        image_url  TEXT,
                        kakera     INTEGER NOT NULL,
                        owner_id   TEXT    NOT NULL,
                        owner_name TEXT,
                        claimed_at INTEGER NOT NULL,
                        PRIMARY KEY (guild_id, char_id)
                    )
                    """);
            st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_harem_claim_owner ON harem_claim(guild_id, owner_id)");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS harem_player (
                        guild_id    TEXT    NOT NULL,
                        user_id     TEXT    NOT NULL,
                        kakera      INTEGER NOT NULL DEFAULT 0,
                        last_claim  INTEGER NOT NULL DEFAULT 0,
                        rolls_used  INTEGER NOT NULL DEFAULT 0,
                        rolls_hour  INTEGER NOT NULL DEFAULT 0,
                        last_daily  INTEGER NOT NULL DEFAULT 0,
                        bonus_rolls INTEGER NOT NULL DEFAULT 0,
                        tower_level INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (guild_id, user_id)
                    )
                    """);
            // Migracao de bancos criados antes das colunas novas (o ALTER falha
            // com "duplicate column" em bancos novos e e ignorado de proposito).
            addColumnIfMissing(st, "last_daily INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(st, "bonus_rolls INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(st, "tower_level INTEGER NOT NULL DEFAULT 0");
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS harem_wish (
                        guild_id   TEXT NOT NULL,
                        user_id    TEXT NOT NULL,
                        name_lower TEXT NOT NULL,
                        PRIMARY KEY (guild_id, user_id, name_lower)
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS harem_profile (
                        guild_id    TEXT    NOT NULL,
                        user_id     TEXT    NOT NULL,
                        color       INTEGER NOT NULL DEFAULT -1,
                        bio         TEXT,
                        fav_char_id INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (guild_id, user_id)
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS harem_badge (
                        guild_id  TEXT    NOT NULL,
                        user_id   TEXT    NOT NULL,
                        badge_id  TEXT    NOT NULL,
                        acquired  INTEGER NOT NULL DEFAULT 0,
                        equipped  INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (guild_id, user_id, badge_id)
                    )
                    """);
        }
    }

    /**
     * Consome um roll do jogador: primeiro da cota da hora atual, depois dos
     * rolls bonus (comprados com kakera). Retorna quantos rolls sobram depois
     * desse, ou -1 se acabou tudo. Sem banco, libera o roll (so nao conta).
     */
    public synchronized int tryUseRoll(String guildId, String userId, long hour, int maxRolls) {
        if (!available()) {
            return maxRolls - 1;
        }
        try {
            ensurePlayer(guildId, userId);
            int used = 0;
            int bonus = 0;
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT rolls_used, rolls_hour, bonus_rolls
                    FROM harem_player WHERE guild_id = ? AND user_id = ?
                    """)) {
                ps.setString(1, guildId);
                ps.setString(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        bonus = rs.getInt("bonus_rolls");
                        if (rs.getLong("rolls_hour") == hour) {
                            used = rs.getInt("rolls_used");
                        }
                    }
                }
            }
            if (used < maxRolls) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE harem_player SET rolls_used = ?, rolls_hour = ? WHERE guild_id = ? AND user_id = ?")) {
                    ps.setInt(1, used + 1);
                    ps.setLong(2, hour);
                    ps.setString(3, guildId);
                    ps.setString(4, userId);
                    ps.executeUpdate();
                }
                return maxRolls - used - 1 + bonus;
            }
            if (bonus > 0) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE harem_player SET bonus_rolls = bonus_rolls - 1 WHERE guild_id = ? AND user_id = ?")) {
                    ps.setString(1, guildId);
                    ps.setString(2, userId);
                    ps.executeUpdate();
                }
                return bonus - 1;
            }
            return -1;
        } catch (SQLException e) {
            log.warn("Falha ao consumir roll de {}/{}.", guildId, userId, e);
            return maxRolls - 1;
        }
    }

    /** Estado do jogador (tudo zerado se nunca jogou / banco indisponivel). */
    public synchronized Player getPlayer(String guildId, String userId) {
        if (!available()) {
            return new Player(0, 0, 0, 0, 0, 0, 0);
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT kakera, last_claim, rolls_used, rolls_hour, last_daily, bonus_rolls, tower_level
                FROM harem_player WHERE guild_id = ? AND user_id = ?
                """)) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Player(rs.getLong("kakera"), rs.getLong("last_claim"),
                            rs.getInt("rolls_used"), rs.getLong("rolls_hour"),
                            rs.getLong("last_daily"), rs.getInt("bonus_rolls"),
                            rs.getInt("tower_level"));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler o jogador {}/{}.", guildId, userId, e);
        }
        return new Player(0, 0, 0, 0, 0, 0, 0);
    }

    public synchronized void setLastClaim(String guildId, String userId, long epochMs) {
        if (!available()) {
            return;
        }
        try {
            ensurePlayer(guildId, userId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE harem_player SET last_claim = ? WHERE guild_id = ? AND user_id = ?")) {
                ps.setLong(1, epochMs);
                ps.setString(2, guildId);
                ps.setString(3, userId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("Falha ao registrar o claim de {}/{}.", guildId, userId, e);
        }
    }

    public synchronized void addKakera(String guildId, String userId, long delta) {
        if (!available()) {
            return;
        }
        try {
            ensurePlayer(guildId, userId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE harem_player SET kakera = kakera + ? WHERE guild_id = ? AND user_id = ?")) {
                ps.setLong(1, delta);
                ps.setString(2, guildId);
                ps.setString(3, userId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("Falha ao creditar kakera pra {}/{}.", guildId, userId, e);
        }
    }

    /** Quem casou com o personagem nesse servidor, ou null se esta livre. */
    public synchronized Claim findOwner(String guildId, long charId) {
        if (!available()) {
            return null;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT char_id, name, series, image_url, kakera, owner_id, owner_name
                FROM harem_claim WHERE guild_id = ? AND char_id = ?
                """)) {
            ps.setString(1, guildId);
            ps.setLong(2, charId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readClaim(rs) : null;
            }
        } catch (SQLException e) {
            log.warn("Falha ao buscar o dono do personagem {} em {}.", charId, guildId, e);
            return null;
        }
    }

    /**
     * Tenta casar o personagem com o jogador, de forma atomica: se outra pessoa
     * casou no meio tempo, retorna false (o INSERT OR IGNORE nao insere nada).
     */
    public synchronized boolean tryClaim(String guildId, Claim claim, long epochMs) {
        if (!available()) {
            return false;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT OR IGNORE INTO harem_claim
                    (guild_id, char_id, name, series, image_url, kakera, owner_id, owner_name, claimed_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, guildId);
            ps.setLong(2, claim.charId());
            ps.setString(3, claim.name());
            ps.setString(4, claim.series());
            ps.setString(5, claim.imageUrl());
            ps.setInt(6, claim.kakera());
            ps.setString(7, claim.ownerId());
            ps.setString(8, claim.ownerName());
            ps.setLong(9, epochMs);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("Falha ao salvar o claim de {} em {}.", claim.name(), guildId, e);
            return false;
        }
    }

    /** Harem do jogador, do personagem mais valioso pro menos. */
    public synchronized List<Claim> listHarem(String guildId, String userId) {
        List<Claim> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT char_id, name, series, image_url, kakera, owner_id, owner_name
                FROM harem_claim WHERE guild_id = ? AND owner_id = ?
                ORDER BY kakera DESC, name
                """)) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readClaim(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao listar o harem de {}/{}.", guildId, userId, e);
        }
        return out;
    }

    /** Personagens do jogador cujo nome contem o termo (case-insensitive). */
    public synchronized List<Claim> findClaims(String guildId, String userId, String query) {
        List<Claim> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT char_id, name, series, image_url, kakera, owner_id, owner_name
                FROM harem_claim
                WHERE guild_id = ? AND owner_id = ? AND LOWER(name) LIKE ?
                ORDER BY kakera DESC
                """)) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            ps.setString(3, "%" + query.toLowerCase(Locale.ROOT) + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readClaim(rs));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao procurar '{}' no harem de {}/{}.", query, guildId, userId, e);
        }
        return out;
    }

    public synchronized boolean removeClaim(String guildId, long charId) {
        if (!available()) {
            return false;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM harem_claim WHERE guild_id = ? AND char_id = ?")) {
            ps.setString(1, guildId);
            ps.setLong(2, charId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("Falha ao remover o claim {} de {}.", charId, guildId, e);
            return false;
        }
    }

    public synchronized WishResult addWish(String guildId, String userId, String nameLower, int maxWishes) {
        if (!available()) {
            return WishResult.CHEIO;
        }
        try {
            if (listWishes(guildId, userId).size() >= maxWishes) {
                return WishResult.CHEIO;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO harem_wish (guild_id, user_id, name_lower) VALUES (?,?,?)")) {
                ps.setString(1, guildId);
                ps.setString(2, userId);
                ps.setString(3, nameLower);
                return ps.executeUpdate() > 0 ? WishResult.OK : WishResult.DUPLICADO;
            }
        } catch (SQLException e) {
            log.warn("Falha ao adicionar o desejo '{}' de {}/{}.", nameLower, guildId, userId, e);
            return WishResult.CHEIO;
        }
    }

    public synchronized boolean removeWish(String guildId, String userId, String nameLower) {
        if (!available()) {
            return false;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM harem_wish WHERE guild_id = ? AND user_id = ? AND name_lower = ?")) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            ps.setString(3, nameLower);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("Falha ao remover o desejo '{}' de {}/{}.", nameLower, guildId, userId, e);
            return false;
        }
    }

    public synchronized List<String> listWishes(String guildId, String userId) {
        List<String> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name_lower FROM harem_wish WHERE guild_id = ? AND user_id = ? ORDER BY name_lower")) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao listar os desejos de {}/{}.", guildId, userId, e);
        }
        return out;
    }

    /** IDs dos usuarios do servidor que desejam esse personagem (nome exato, lowercase). */
    public synchronized List<String> findWishers(String guildId, String nameLower) {
        List<String> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id FROM harem_wish WHERE guild_id = ? AND name_lower = ?")) {
            ps.setString(1, guildId);
            ps.setString(2, nameLower);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao buscar quem deseja '{}' em {}.", nameLower, guildId, e);
        }
        return out;
    }

    /**
     * Debita kakera do jogador, atomico: so desconta se o saldo cobre o valor.
     * Retorna false se nao tinha kakera suficiente.
     */
    public synchronized boolean trySpendKakera(String guildId, String userId, long amount) {
        if (!available()) {
            return false;
        }
        try {
            ensurePlayer(guildId, userId);
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE harem_player SET kakera = kakera - ?
                    WHERE guild_id = ? AND user_id = ? AND kakera >= ?
                    """)) {
                ps.setLong(1, amount);
                ps.setString(2, guildId);
                ps.setString(3, userId);
                ps.setLong(4, amount);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.warn("Falha ao debitar kakera de {}/{}.", guildId, userId, e);
            return false;
        }
    }

    public synchronized void addBonusRolls(String guildId, String userId, int rolls) {
        if (!available()) {
            return;
        }
        try {
            ensurePlayer(guildId, userId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE harem_player SET bonus_rolls = bonus_rolls + ? WHERE guild_id = ? AND user_id = ?")) {
                ps.setInt(1, rolls);
                ps.setString(2, guildId);
                ps.setString(3, userId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("Falha ao creditar rolls bonus pra {}/{}.", guildId, userId, e);
        }
    }

    /**
     * Coleta o daily, atomico: so credita se o ultimo daily foi antes do corte
     * (agora - intervalo). Retorna false se ainda esta em cooldown.
     */
    public synchronized boolean tryDaily(String guildId, String userId, long nowMs, long cutoffMs, long reward) {
        if (!available()) {
            return false;
        }
        try {
            ensurePlayer(guildId, userId);
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE harem_player SET last_daily = ?, kakera = kakera + ?
                    WHERE guild_id = ? AND user_id = ? AND last_daily <= ?
                    """)) {
                ps.setLong(1, nowMs);
                ps.setLong(2, reward);
                ps.setString(3, guildId);
                ps.setString(4, userId);
                ps.setLong(5, cutoffMs);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.warn("Falha ao coletar o daily de {}/{}.", guildId, userId, e);
            return false;
        }
    }

    /**
     * Sobe a torre de kakera pro nivel informado, atomico: so passa se o saldo
     * cobre o custo e o jogador esta exatamente no nivel anterior.
     */
    public synchronized boolean tryUpgradeTower(String guildId, String userId, int newLevel, long cost) {
        if (!available()) {
            return false;
        }
        try {
            ensurePlayer(guildId, userId);
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE harem_player SET tower_level = ?, kakera = kakera - ?
                    WHERE guild_id = ? AND user_id = ? AND kakera >= ? AND tower_level = ?
                    """)) {
                ps.setInt(1, newLevel);
                ps.setLong(2, cost);
                ps.setString(3, guildId);
                ps.setString(4, userId);
                ps.setLong(5, cost);
                ps.setInt(6, newLevel - 1);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.warn("Falha ao subir a torre de {}/{}.", guildId, userId, e);
            return false;
        }
    }

    /**
     * Troca dois personagens de dono numa transacao: A vai pro dono de B e
     * vice-versa. Se qualquer um dos dois mudou de dono desde a proposta,
     * nada acontece e retorna false.
     */
    public synchronized boolean tradeClaims(String guildId,
                                            long charA, String fromA, String toA, String toAName,
                                            long charB, String fromB, String toB, String toBName) {
        if (!available()) {
            return false;
        }
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE harem_claim SET owner_id = ?, owner_name = ?
                    WHERE guild_id = ? AND char_id = ? AND owner_id = ?
                    """)) {
                ps.setString(1, toA);
                ps.setString(2, toAName);
                ps.setString(3, guildId);
                ps.setLong(4, charA);
                ps.setString(5, fromA);
                int movedA = ps.executeUpdate();

                ps.setString(1, toB);
                ps.setString(2, toBName);
                ps.setString(3, guildId);
                ps.setLong(4, charB);
                ps.setString(5, fromB);
                int movedB = ps.executeUpdate();

                if (movedA == 1 && movedB == 1) {
                    conn.commit();
                    return true;
                }
                conn.rollback();
                return false;
            }
        } catch (SQLException e) {
            log.warn("Falha ao trocar os personagens {} e {} em {}.", charA, charB, guildId, e);
            try {
                conn.rollback();
            } catch (SQLException ignored) {
                // ja estamos tratando uma falha; nada a fazer
            }
            return false;
        } finally {
            try {
                conn.setAutoCommit(true);
            } catch (SQLException ignored) {
                // idem
            }
        }
    }

    /** Personalizacao do perfil (cor/bio/favorito padrao se nunca mexeu / banco indisponivel). */
    public synchronized Profile getProfile(String guildId, String userId) {
        if (!available()) {
            return new Profile(-1, null, 0);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT color, bio, fav_char_id FROM harem_profile WHERE guild_id = ? AND user_id = ?")) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Profile(rs.getInt("color"), rs.getString("bio"), rs.getLong("fav_char_id"));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler o perfil de {}/{}.", guildId, userId, e);
        }
        return new Profile(-1, null, 0);
    }

    public synchronized void setProfileColor(String guildId, String userId, int color) {
        setProfileField(guildId, userId, "color", ps -> ps.setInt(1, color));
    }

    public synchronized void setProfileBio(String guildId, String userId, String bio) {
        setProfileField(guildId, userId, "bio", ps -> ps.setString(1, bio));
    }

    public synchronized void setProfileFav(String guildId, String userId, long charId) {
        setProfileField(guildId, userId, "fav_char_id", ps -> ps.setLong(1, charId));
    }

    private interface ParamSetter {
        void set(PreparedStatement ps) throws SQLException;
    }

    private void setProfileField(String guildId, String userId, String column, ParamSetter setter) {
        if (!available()) {
            return;
        }
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR IGNORE INTO harem_profile (guild_id, user_id) VALUES (?, ?)")) {
                ps.setString(1, guildId);
                ps.setString(2, userId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE harem_profile SET " + column + " = ? WHERE guild_id = ? AND user_id = ?")) {
                setter.set(ps);
                ps.setString(2, guildId);
                ps.setString(3, userId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("Falha ao salvar {} do perfil de {}/{}.", column, guildId, userId, e);
        }
    }

    /** Quantidade, valor total e data do primeiro casamento do jogador. */
    public synchronized HaremStats haremStats(String guildId, String userId) {
        if (!available()) {
            return new HaremStats(0, 0, 0);
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) AS n, COALESCE(SUM(kakera), 0) AS total, COALESCE(MIN(claimed_at), 0) AS primeiro
                FROM harem_claim WHERE guild_id = ? AND owner_id = ?
                """)) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new HaremStats(rs.getInt("n"), rs.getLong("total"), rs.getLong("primeiro"));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler as estatisticas do harem de {}/{}.", guildId, userId, e);
        }
        return new HaremStats(0, 0, 0);
    }

    /**
     * Posicao do jogador no ranking do servidor por valor total do harem
     * (1 = maior harem). Retorna 0 se o jogador nao tem personagens.
     */
    public synchronized int haremRank(String guildId, String userId) {
        if (!available()) {
            return 0;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT COUNT(*) + 1 FROM (
                    SELECT owner_id, SUM(kakera) AS s FROM harem_claim
                    WHERE guild_id = ? GROUP BY owner_id
                ) WHERE s > (
                    SELECT COALESCE(SUM(kakera), -1) FROM harem_claim
                    WHERE guild_id = ? AND owner_id = ?
                )
                """)) {
            ps.setString(1, guildId);
            ps.setString(2, guildId);
            ps.setString(3, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && haremStats(guildId, userId).count() > 0) {
                    return rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao calcular o rank de {}/{}.", guildId, userId, e);
        }
        return 0;
    }

    /** Nomes (em minusculas) dos personagens no harem do jogador — pros badges de personagem. */
    public synchronized Set<String> claimNamesLower(String guildId, String userId) {
        Set<String> out = new LinkedHashSet<>();
        if (!available()) {
            return out;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT LOWER(name) FROM harem_claim WHERE guild_id = ? AND owner_id = ?")) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao listar os nomes do harem de {}/{}.", guildId, userId, e);
        }
        return out;
    }

    // ------------------------------------------------------------------ badges

    /** IDs dos badges que o jogador possui nesse servidor. */
    public synchronized Set<String> ownedBadges(String guildId, String userId) {
        Set<String> out = new LinkedHashSet<>();
        if (!available()) {
            return out;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT badge_id FROM harem_badge WHERE guild_id = ? AND user_id = ? ORDER BY acquired")) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao listar os badges de {}/{}.", guildId, userId, e);
        }
        return out;
    }

    /** IDs dos badges equipados (exibidos no perfil), na ordem em que foram ganhos. */
    public synchronized List<String> equippedBadges(String guildId, String userId) {
        List<String> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT badge_id FROM harem_badge
                WHERE guild_id = ? AND user_id = ? AND equipped = 1 ORDER BY acquired
                """)) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao listar os badges equipados de {}/{}.", guildId, userId, e);
        }
        return out;
    }

    /** Quantos badges o jogador exibe no perfil agora. */
    public synchronized int equippedBadgeCount(String guildId, String userId) {
        if (!available()) {
            return 0;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM harem_badge WHERE guild_id = ? AND user_id = ? AND equipped = 1")) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.warn("Falha ao contar os badges equipados de {}/{}.", guildId, userId, e);
            return 0;
        }
    }

    /** Concede um badge (conquista). Retorna true so se ele ainda nao tinha. */
    public synchronized boolean grantBadge(String guildId, String userId, String badgeId, long epochMs) {
        if (!available()) {
            return false;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO harem_badge (guild_id, user_id, badge_id, acquired) VALUES (?,?,?,?)")) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            ps.setString(3, badgeId);
            ps.setLong(4, epochMs);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("Falha ao conceder o badge '{}' pra {}/{}.", badgeId, guildId, userId, e);
            return false;
        }
    }

    /**
     * Compra um badge da loja: debita o kakera e concede o badge, atomico.
     * Retorna false se ja possuia o badge ou se faltou kakera.
     */
    public synchronized boolean buyBadge(String guildId, String userId, String badgeId, long preco, long epochMs) {
        if (!available()) {
            return false;
        }
        try {
            ensurePlayer(guildId, userId);
            if (ownedBadges(guildId, userId).contains(badgeId)) {
                return false;
            }
            if (!trySpendKakera(guildId, userId, preco)) {
                return false;
            }
            grantBadge(guildId, userId, badgeId, epochMs);
            return true;
        } catch (SQLException e) {
            log.warn("Falha ao comprar o badge '{}' pra {}/{}.", badgeId, guildId, userId, e);
            return false;
        }
    }

    /** Equipa/desequipa um badge (so se o jogador o possui). Retorna true se mudou algo. */
    public synchronized boolean setBadgeEquipped(String guildId, String userId, String badgeId, boolean equipped) {
        if (!available()) {
            return false;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE harem_badge SET equipped = ? WHERE guild_id = ? AND user_id = ? AND badge_id = ?")) {
            ps.setInt(1, equipped ? 1 : 0);
            ps.setString(2, guildId);
            ps.setString(3, userId);
            ps.setString(4, badgeId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.warn("Falha ao equipar o badge '{}' de {}/{}.", badgeId, guildId, userId, e);
            return false;
        }
    }

    private Claim readClaim(ResultSet rs) throws SQLException {
        return new Claim(
                rs.getLong("char_id"),
                rs.getString("name"),
                rs.getString("series"),
                rs.getString("image_url"),
                rs.getInt("kakera"),
                rs.getString("owner_id"),
                rs.getString("owner_name"));
    }

    private static void addColumnIfMissing(Statement st, String columnDef) {
        try {
            st.executeUpdate("ALTER TABLE harem_player ADD COLUMN " + columnDef);
        } catch (SQLException ignored) {
            // coluna ja existe (bancos novos ja nascem com ela no CREATE TABLE)
        }
    }

    private void ensurePlayer(String guildId, String userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO harem_player (guild_id, user_id) VALUES (?, ?)")) {
            ps.setString(1, guildId);
            ps.setString(2, userId);
            ps.executeUpdate();
        }
    }
}