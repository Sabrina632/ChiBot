package org.chibot.Translation;

import org.chibot.Database.LanguageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationServiceTest {

    /** Tradutor fake: conta chamadas e prefixa o idioma alvo. */
    static class FakeTranslator implements Translator {
        int calls = 0;
        String lastText;
        @Override
        public String translate(String text, String source, String target) {
            calls++;
            lastText = text;
            return "<" + target + ">" + text;
        }
    }

    private static LanguageRepository inMemory() {
        return new LanguageRepository("jdbc:sqlite::memory:");
    }

    @Test
    void portuguesEhNoOp() {
        FakeTranslator fake = new FakeTranslator();
        TranslationService svc = new TranslationService(inMemory(), fake);
        svc.setLanguage("u1", "pt");
        assertEquals("Olá", svc.translateForUser("u1", "Olá"));
        assertEquals(0, fake.calls);
    }

    @Test
    void traduzParaOutroIdioma() {
        FakeTranslator fake = new FakeTranslator();
        TranslationService svc = new TranslationService(inMemory(), fake);
        svc.setLanguage("u1", "en");
        assertEquals("<en>Olá", svc.translateForUser("u1", "Olá"));
        assertEquals(1, fake.calls);
    }

    @Test
    void cacheEmMemoriaEvitaSegundaChamada() {
        FakeTranslator fake = new FakeTranslator();
        TranslationService svc = new TranslationService(inMemory(), fake);
        svc.setLanguage("u1", "en");
        svc.translateForUser("u1", "Olá");
        svc.translateForUser("u1", "Olá");
        assertEquals(1, fake.calls);
    }

    @Test
    void cacheDoBancoSobreviveAoRestart(@TempDir Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("ChiLang.db");

        FakeTranslator fakeA = new FakeTranslator();
        LanguageRepository repoA = new LanguageRepository(url);
        TranslationService svcA = new TranslationService(repoA, fakeA);
        svcA.setLanguage("u1", "en");
        svcA.translateForUser("u1", "Olá");
        assertEquals(1, fakeA.calls);
        repoA.close();

        // "Reinício": novo serviço/tradutor sobre o mesmo arquivo. Não bate na API.
        FakeTranslator fakeB = new FakeTranslator();
        LanguageRepository repoB = new LanguageRepository(url);
        TranslationService svcB = new TranslationService(repoB, fakeB);
        assertEquals("<en>Olá", svcB.translateForUser("u1", "Olá"));
        assertEquals(0, fakeB.calls);
        repoB.close();
    }

    @Test
    void mascaraComandoAntesDeTraduzir() {
        FakeTranslator fake = new FakeTranslator();
        TranslationService svc = new TranslationService(inMemory(), fake);
        svc.setLanguage("u1", "en");
        String out = svc.translateForUser("u1", "Use `daily` agora");
        // O tradutor não viu "daily" (estava mascarado)...
        assertFalse(fake.lastText.contains("daily"));
        // ...mas o resultado final tem "daily" de volta.
        assertTrue(out.contains("daily"));
    }

    @Test
    void setLanguageValidaCodigo() {
        TranslationService svc = new TranslationService(inMemory(), new FakeTranslator());
        assertTrue(svc.setLanguage("u1", "en"));
        assertFalse(svc.setLanguage("u1", "xx"));
        assertTrue(svc.supportedLanguages().contains("ja"));
    }

    @Test
    void semTradutorDegrada() {
        TranslationService svc = new TranslationService(inMemory(), null);
        svc.setLanguage("u1", "en");
        assertEquals("Olá", svc.translateForUser("u1", "Olá"));
    }
}