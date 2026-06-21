package org.chibot.Translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationMaskerTest {

    private static String roundTrip(String s) {
        TranslationMasker.Masked m = TranslationMasker.mask(s);
        return TranslationMasker.restore(m.text(), m.originals());
    }

    /**
     * Simula o Amazon Translate: traduz a parte visível e DESCARTA os caracteres
     * invisíveis da Área de Uso Privado (U+E000–U+F8FF). Foi exatamente isso que
     * quebrou em produção ("0 Playing now! 1"): o marcador sumia e sobrava o índice.
     */
    private static String fakeAmazonTranslate(String masked) {
        String semInvisiveis = masked.replaceAll("[\\x{E000}-\\x{F8FF}]", "");
        return semInvisiveis.replace("Tocando agora", "Playing now");
    }

    @Test
    void restauraMesmoQuandoTradutorDescartaCharsInvisiveis() {
        String s = "ﾟ･✧ Tocando agora! ✧･ﾟ";
        TranslationMasker.Masked m = TranslationMasker.mask(s);
        String traduzido = fakeAmazonTranslate(m.text());
        assertEquals("ﾟ･✧ Playing now! ✧･ﾟ",
                TranslationMasker.restore(traduzido, m.originals()));
    }

    @Test
    void preservaEmoticonKawaii() {
        String s = "Roll usado~ volte logo! (˘ω˘) ♡";
        assertEquals(s, roundTrip(s));
        // O emoticon não fica visível pro tradutor.
        assertFalse(TranslationMasker.mask(s).text().contains("˘ω˘"));
    }

    @Test
    void preservaSpanDeCrase() {
        String s = "Use `daily` pra coletar";
        assertEquals(s, roundTrip(s));
        assertFalse(TranslationMasker.mask(s).text().contains("daily"));
    }

    @Test
    void preservaMencaoEEmojiDoDiscord() {
        String s = "oi <@123456> e <:pepe:789>";
        assertEquals(s, roundTrip(s));
        assertFalse(TranslationMasker.mask(s).text().contains("<@123456>"));
        assertFalse(TranslationMasker.mask(s).text().contains("<:pepe:789>"));
    }

    @Test
    void preservaUrl() {
        String s = "veja https://exemplo.com/a?b=1 aqui";
        assertEquals(s, roundTrip(s));
        assertFalse(TranslationMasker.mask(s).text().contains("https://exemplo.com/a?b=1"));
    }

    @Test
    void preservaEmojiForaDoBmp() {
        String s = "parabéns 🎉✨";
        assertEquals(s, roundTrip(s));
    }

    @Test
    void textoSemNadaProtegidoNaoMuda() {
        String s = "Você tem 5 kakera";
        TranslationMasker.Masked m = TranslationMasker.mask(s);
        assertEquals(s, m.text());
        assertTrue(m.originals().isEmpty());
        // Parênteses comuns (sem emoticon) não são mascarados.
        String s2 = "isso (importante) aqui";
        assertEquals(s2, TranslationMasker.mask(s2).text());
    }
}