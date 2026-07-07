package org.chibot.Database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Persistência (PostgreSQL) da música. Guarda tres coisas, todas opcionais —
 * se o banco nao abrir, a musica continua tocando, so nao sobrevive a restart:
 *
 * <ul>
 *   <li><b>Volume por servidor</b> ({@code music_config}) — cada guild lembra o
 *       volume que escolheu.</li>
 *   <li><b>Fila persistente</b> ({@code music_session} + {@code music_queue}) — a
 *       faixa atual, as proximas e o canal de voz/texto, pro bot voltar a tocar
 *       de onde parou depois de um redeploy.</li>
 *   <li><b>Playlists salvas</b> ({@code music_playlist} + {@code music_playlist_track})
 *       — listas nomeadas que o usuario monta e reusa quando quiser.</li>
 * </ul>
 *
 * <p>As faixas sao guardadas no formato <em>encoded</em> do Lavalink (base64), que
 * o {@code node.decodeTracks(...)} reconstroi sem precisar resolver de novo o
 * link. Segue o mesmo espirito do {@link PfRepository}: degrada com log e vira
 * no-op se o banco falhar. Todos os metodos sao sincronizados e pegam conexões
 * do pool compartilhado (Db).
 */
public class MusicRepository {

    private static final Logger log = LoggerFactory.getLogger(MusicRepository.class);

    /** Uma faixa persistida: o encoded do Lavalink + o titulo (pra listar sem decodificar). */
    public record StoredTrack(String encoded, String title) {}

    /** Uma playlist salva (so o cabecalho, pra listagem). */
    public record SavedPlaylist(String name, int trackCount) {}

    private DataSource ds;

    public MusicRepository() {
        this(Db.dataSource());
    }

    /** Construtor com DataSource explícito (testes). Null = degrada pra no-op. */
    public MusicRepository(DataSource ds) {
        this.ds = ds;
        if (ds == null) {
            log.warn("Banco da musica não configurado; seguindo sem persistência.");
            return;
        }
        try {
            createSchema();
            log.info("Banco da musica pronto.");
        } catch (SQLException e) {
            this.ds = null;
            log.warn("Não foi possível preparar o banco da musica; seguindo sem persistência.", e);
        }
    }

    private boolean available() {
        return ds != null;
    }

    private void createSchema() throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS music_config (
                        guild_id TEXT    NOT NULL PRIMARY KEY,
                        volume   INTEGER NOT NULL DEFAULT 50
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS music_session (
                        guild_id         TEXT NOT NULL PRIMARY KEY,
                        voice_channel_id TEXT,
                        text_channel_id  TEXT
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS music_queue (
                        guild_id TEXT    NOT NULL,
                        position INTEGER NOT NULL,
                        encoded  TEXT    NOT NULL,
                        title    TEXT,
                        PRIMARY KEY (guild_id, position)
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS music_playlist (
                        guild_id   TEXT   NOT NULL,
                        owner_id   TEXT   NOT NULL,
                        name_lower TEXT   NOT NULL,
                        name       TEXT   NOT NULL,
                        created_at BIGINT NOT NULL,
                        PRIMARY KEY (guild_id, owner_id, name_lower)
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS music_playlist_track (
                        guild_id   TEXT    NOT NULL,
                        owner_id   TEXT    NOT NULL,
                        name_lower TEXT    NOT NULL,
                        position   INTEGER NOT NULL,
                        encoded    TEXT    NOT NULL,
                        title      TEXT,
                        PRIMARY KEY (guild_id, owner_id, name_lower, position)
                    )
                    """);
        }
    }

    // ----------------------------------------------------------------- volume

    /** Volume salvo do servidor, ou {@code def} se nunca foi configurado / banco indisponivel. */
    public synchronized int getVolume(String guildId, int def) {
        if (!available()) {
            return def;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT volume FROM music_config WHERE guild_id = ?")) {
            ps.setString(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : def;
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler o volume de {}.", guildId, e);
            return def;
        }
    }

    public synchronized void setVolume(String guildId, int volume) {
        if (!available()) {
            return;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                INSERT INTO music_config (guild_id, volume) VALUES (?, ?)
                ON CONFLICT(guild_id) DO UPDATE SET volume = excluded.volume
                """)) {
            ps.setString(1, guildId);
            ps.setInt(2, volume);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Falha ao salvar o volume de {}.", guildId, e);
        }
    }

    // --------------------------------------------------------------- sessao/fila

    /**
     * Salva a sessao de musica do servidor: o canal de voz/texto e a fila inteira
     * (posicao 0 = tocando agora, 1.. = proximas), numa transacao. Chamado a cada
     * mudanca de fila — barato e mantem o snapshot sempre atual pro restore.
     */
    public synchronized void saveSession(String guildId, String voiceChannelId,
                                         String textChannelId, List<StoredTrack> tracks) {
        if (!available()) {
            return;
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO music_session (guild_id, voice_channel_id, text_channel_id)
                    VALUES (?, ?, ?)
                    ON CONFLICT(guild_id) DO UPDATE SET
                        voice_channel_id = excluded.voice_channel_id,
                        text_channel_id  = excluded.text_channel_id
                    """);
                 PreparedStatement del = c.prepareStatement(
                         "DELETE FROM music_queue WHERE guild_id = ?");
                 PreparedStatement ins = c.prepareStatement(
                         "INSERT INTO music_queue (guild_id, position, encoded, title) VALUES (?,?,?,?)")) {
                ps.setString(1, guildId);
                ps.setString(2, voiceChannelId);
                ps.setString(3, textChannelId);
                ps.executeUpdate();

                del.setString(1, guildId);
                del.executeUpdate();

                int pos = 0;
                for (StoredTrack t : tracks) {
                    ins.setString(1, guildId);
                    ins.setInt(2, pos++);
                    ins.setString(3, t.encoded());
                    ins.setString(4, t.title());
                    ins.addBatch();
                }
                ins.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.warn("Falha ao salvar a sessao de musica de {}.", guildId, e);
        }
    }

    /** Servidores que tem uma fila salva (nao vazia) pra restaurar no boot. */
    public synchronized List<String> guildsWithQueue() {
        List<String> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT guild_id FROM music_queue")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("Falha ao listar as filas salvas.", e);
        }
        return out;
    }

    public synchronized String voiceChannelId(String guildId) {
        return sessionField(guildId, "voice_channel_id");
    }

    public synchronized String textChannelId(String guildId) {
        return sessionField(guildId, "text_channel_id");
    }

    private String sessionField(String guildId, String column) {
        if (!available()) {
            return null;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + column + " FROM music_session WHERE guild_id = ?")) {
            ps.setString(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler {} da sessao de {}.", column, guildId, e);
            return null;
        }
    }

    /** Fila salva do servidor em ordem (posicao 0 = tocando agora). Vazia se nada. */
    public synchronized List<StoredTrack> loadQueue(String guildId) {
        List<StoredTrack> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT encoded, title FROM music_queue WHERE guild_id = ? ORDER BY position")) {
            ps.setString(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new StoredTrack(rs.getString("encoded"), rs.getString("title")));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler a fila salva de {}.", guildId, e);
        }
        return out;
    }

    // ----------------------------------------------------------- playlists salvas

    /**
     * Salva (ou substitui) uma playlist nomeada do usuario com as faixas dadas.
     * Retorna false se o banco estiver indisponivel ou a lista vier vazia.
     */
    public synchronized boolean savePlaylist(String guildId, String ownerId, String name,
                                             List<StoredTrack> tracks, long epochMs) {
        if (!available() || tracks.isEmpty()) {
            return false;
        }
        String nameLower = name.toLowerCase(Locale.ROOT);
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO music_playlist (guild_id, owner_id, name_lower, name, created_at)
                    VALUES (?,?,?,?,?)
                    ON CONFLICT(guild_id, owner_id, name_lower)
                    DO UPDATE SET name = excluded.name, created_at = excluded.created_at
                    """);
                 PreparedStatement del = c.prepareStatement("""
                    DELETE FROM music_playlist_track
                    WHERE guild_id = ? AND owner_id = ? AND name_lower = ?
                    """);
                 PreparedStatement ins = c.prepareStatement("""
                    INSERT INTO music_playlist_track
                        (guild_id, owner_id, name_lower, position, encoded, title)
                    VALUES (?,?,?,?,?,?)
                    """)) {
                ps.setString(1, guildId);
                ps.setString(2, ownerId);
                ps.setString(3, nameLower);
                ps.setString(4, name);
                ps.setLong(5, epochMs);
                ps.executeUpdate();

                del.setString(1, guildId);
                del.setString(2, ownerId);
                del.setString(3, nameLower);
                del.executeUpdate();

                int pos = 0;
                for (StoredTrack t : tracks) {
                    ins.setString(1, guildId);
                    ins.setString(2, ownerId);
                    ins.setString(3, nameLower);
                    ins.setInt(4, pos++);
                    ins.setString(5, t.encoded());
                    ins.setString(6, t.title());
                    ins.addBatch();
                }
                ins.executeBatch();
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.warn("Falha ao salvar a playlist '{}' de {}/{}.", name, guildId, ownerId, e);
            return false;
        }
    }

    /** Faixas de uma playlist salva, em ordem. Vazia se nao existir / banco indisponivel. */
    public synchronized List<StoredTrack> loadPlaylist(String guildId, String ownerId, String nameLower) {
        List<StoredTrack> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                SELECT encoded, title FROM music_playlist_track
                WHERE guild_id = ? AND owner_id = ? AND name_lower = ?
                ORDER BY position
                """)) {
            ps.setString(1, guildId);
            ps.setString(2, ownerId);
            ps.setString(3, nameLower.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new StoredTrack(rs.getString("encoded"), rs.getString("title")));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler a playlist '{}' de {}/{}.", nameLower, guildId, ownerId, e);
        }
        return out;
    }

    /** Apaga uma playlist salva do usuario. Retorna true se existia algo pra apagar. */
    public synchronized boolean deletePlaylist(String guildId, String ownerId, String nameLower) {
        if (!available()) {
            return false;
        }
        String key = nameLower.toLowerCase(Locale.ROOT);
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("""
                    DELETE FROM music_playlist
                    WHERE guild_id = ? AND owner_id = ? AND name_lower = ?
                    """);
                 PreparedStatement psTrack = c.prepareStatement("""
                    DELETE FROM music_playlist_track
                    WHERE guild_id = ? AND owner_id = ? AND name_lower = ?
                    """)) {
                ps.setString(1, guildId);
                ps.setString(2, ownerId);
                ps.setString(3, key);
                int removed = ps.executeUpdate();

                psTrack.setString(1, guildId);
                psTrack.setString(2, ownerId);
                psTrack.setString(3, key);
                psTrack.executeUpdate();

                c.commit();
                return removed > 0;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.warn("Falha ao apagar a playlist '{}' de {}/{}.", nameLower, guildId, ownerId, e);
            return false;
        }
    }

    /** Playlists salvas do usuario nesse servidor, com a contagem de faixas. */
    public synchronized List<SavedPlaylist> listPlaylists(String guildId, String ownerId) {
        List<SavedPlaylist> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                SELECT p.name AS name, COUNT(t.position) AS n
                FROM music_playlist p
                LEFT JOIN music_playlist_track t
                    ON t.guild_id = p.guild_id AND t.owner_id = p.owner_id
                    AND t.name_lower = p.name_lower
                WHERE p.guild_id = ? AND p.owner_id = ?
                GROUP BY p.name_lower, p.name, p.created_at
                ORDER BY p.created_at DESC
                """)) {
            ps.setString(1, guildId);
            ps.setString(2, ownerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SavedPlaylist(rs.getString("name"), rs.getInt("n")));
                }
            }
        } catch (SQLException e) {
            log.warn("Falha ao listar as playlists de {}/{}.", guildId, ownerId, e);
        }
        return out;
    }

    /** O pool é global (Db); aqui só descarta a referência. Mantido por compatibilidade. */
    public synchronized void close() {
        ds = null;
    }
}
