package org.chibot.Database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LanguageRepositoryTest {

    private static LanguageRepository inMemory(String nome) {
        return new LanguageRepository(PgTestDb.database(nome));
    }

    @Test
    void idiomaPadraoEhPortugues() {
        LanguageRepository repo = inMemory("lang_basico");
        assertEquals("pt", repo.getLanguage("u1"));
    }

    @Test
    void salvaELeIdiomaPorUsuario() {
        LanguageRepository repo = inMemory("lang_por_usuario");
        repo.setLanguage("u1", "en");
        assertEquals("en", repo.getLanguage("u1"));
        // Outro usuário continua no padrão.
        assertEquals("pt", repo.getLanguage("u2"));
    }

    @Test
    void cacheGuardaERecupera() {
        LanguageRepository repo = inMemory("lang_cache");
        assertNull(repo.getCachedTranslation("en", "hash1"));
        repo.putCachedTranslation("en", "hash1", "Roll used~");
        assertEquals("Roll used~", repo.getCachedTranslation("en", "hash1"));
        // Idioma diferente, mesmo hash = entrada diferente.
        assertNull(repo.getCachedTranslation("es", "hash1"));
    }

    @Test
    void preferenciaECacheSobrevivemAoRestart() {
        LanguageRepository antes = new LanguageRepository(PgTestDb.database("lang_persist"));
        antes.setLanguage("u1", "ja");
        antes.putCachedTranslation("ja", "h", "ロール");
        antes.close();

        LanguageRepository depois = new LanguageRepository(PgTestDb.database("lang_persist"));
        assertEquals("ja", depois.getLanguage("u1"));
        assertEquals("ロール", depois.getCachedTranslation("ja", "h"));
        depois.close();
    }

    @Test
    void semBancoDegradaSemQuebrar() {
        LanguageRepository repo = new LanguageRepository(new BrokenDataSource());
        repo.setLanguage("u1", "en");
        assertEquals("pt", repo.getLanguage("u1"));
        repo.putCachedTranslation("en", "h", "x");
        assertNull(repo.getCachedTranslation("en", "h"));
    }
}