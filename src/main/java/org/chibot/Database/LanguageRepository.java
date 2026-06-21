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
 * Persistência local (SQLite) do sistema de tradução, num arquivo SEPARADO
 * ({@code ChiLang.db}) dos demais bancos. Guarda duas coisas:
 *
 * <ul>
 *   <li><b>Preferência de idioma por usuário</b> ({@code user_language}) — o idioma
 *       que cada pessoa escolheu com {@code !language}.</li>
 *   <li><b>Cache de traduções</b> ({@code translation_cache}) — cada frase já
 *       traduzida, por idioma, pra não bater na API de novo (sobrevive a restart).</li>
 * </ul>
 *
 * <p>Segue o mesmo espírito dos outros repositórios: degrada com log e vira no-op
 * se o banco não abrir. Métodos sincronizados (uma única conexão SQLite).
 */
public class LanguageRepository {

    private static final Logger log = LoggerFactory.getLogger(LanguageRepository.class);
    private static final String DEFAULT_DB_FILE = "ChiLang.db";
    private static final String IDIOMA_PADRAO = "pt";

    private Connection conn;

    public LanguageRepository() {
        this(defaultDbUrl());
    }

    /** Construtor com URL explícita (ex.: {@code jdbc:sqlite::memory:} nos testes). */
    public LanguageRepository(String dbUrl) {
        try {
            ensureParentDir(dbUrl);
            conn = DriverManager.getConnection(dbUrl);
            createSchema();
            log.info("Banco de idiomas pronto ({}).", dbUrl);
        } catch (SQLException e) {
            conn = null;
            log.warn("Não foi possível abrir o banco de idiomas; tradução vira só em memória.", e);
        }
    }

    /**
     * Fica num arquivo separado dos outros bancos. Por padrão ao lado do banco
     * principal (mesmo diretório/volume no Docker); {@code CHIBOT_LANG_DB_PATH}
     * sobrescreve.
     */
    private static String defaultDbUrl() {
        String explicit = System.getenv("CHIBOT_LANG_DB_PATH");
        if (explicit != null && !explicit.isBlank()) {
            return "jdbc:sqlite:" + explicit;
        }
        String mainDb = System.getenv("CHIBOT_DB_PATH");
        if (mainDb != null && !mainDb.isBlank()) {
            java.nio.file.Path parent = java.nio.file.Paths.get(mainDb).getParent();
            java.nio.file.Path langPath = parent != null
                    ? parent.resolve(DEFAULT_DB_FILE)
                    : java.nio.file.Paths.get(DEFAULT_DB_FILE);
            return "jdbc:sqlite:" + langPath;
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
                    CREATE TABLE IF NOT EXISTS user_language (
                        user_id TEXT NOT NULL PRIMARY KEY,
                        lang    TEXT NOT NULL
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS translation_cache (
                        lang        TEXT NOT NULL,
                        source_hash TEXT NOT NULL,
                        translated  TEXT NOT NULL,
                        PRIMARY KEY (lang, source_hash)
                    )
                    """);
        }
    }

    // -------------------------------------------------------------- preferência

    /** Idioma escolhido pelo usuário, ou {@code pt} se nunca configurou / banco indisponível. */
    public synchronized String getLanguage(String userId) {
        if (!available()) {
            return IDIOMA_PADRAO;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT lang FROM user_language WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : IDIOMA_PADRAO;
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler o idioma de {}.", userId, e);
            return IDIOMA_PADRAO;
        }
    }

    public synchronized void setLanguage(String userId, String lang) {
        if (!available()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO user_language (user_id, lang) VALUES (?, ?)
                ON CONFLICT(user_id) DO UPDATE SET lang = excluded.lang
                """)) {
            ps.setString(1, userId);
            ps.setString(2, lang);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Falha ao salvar o idioma de {}.", userId, e);
        }
    }

    // ------------------------------------------------------------------- cache

    /** Tradução já guardada pra (idioma, hash), ou {@code null}. */
    public synchronized String getCachedTranslation(String lang, String sourceHash) {
        if (!available()) {
            return null;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT translated FROM translation_cache WHERE lang = ? AND source_hash = ?")) {
            ps.setString(1, lang);
            ps.setString(2, sourceHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler o cache de tradução ({}, {}).", lang, sourceHash, e);
            return null;
        }
    }

    public synchronized void putCachedTranslation(String lang, String sourceHash, String translated) {
        if (!available()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO translation_cache (lang, source_hash, translated) VALUES (?, ?, ?)
                ON CONFLICT(lang, source_hash) DO UPDATE SET translated = excluded.translated
                """)) {
            ps.setString(1, lang);
            ps.setString(2, sourceHash);
            ps.setString(3, translated);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Falha ao gravar o cache de tradução ({}, {}).", lang, sourceHash, e);
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