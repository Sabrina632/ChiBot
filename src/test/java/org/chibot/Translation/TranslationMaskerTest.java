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
     * Simula duas manhas de tradutor que já quebraram o marcador em produção:
     * (1) DESCARTA os invisíveis da Área de Uso Privado (U+E000–U+F8FF) — virava
     * "0 Playing now! 1"; (2) mete ESPAÇO entre pontuações ASCII adjacentes —
     * "@@0@@" virava "@ @0 @@". Um marcador alfanumérico passa imune às duas.
     */
    private static String fakeTradutorAdversario(String masked) {
        String out = masked.replaceAll("[\\x{E000}-\\x{F8FF}]", "");
        out = out.replaceAll("(?<=\\p{Punct})(?=\\p{Punct})", " ");
        return out.replace("Tocando agora", "Playing now");
    }

    @Test
    void restauraMesmoQuandoTradutorMexeNosMarcadores() {
        String s = "ﾟ･✧ Tocando agora! ✧･ﾟ";
        TranslationMasker.Masked m = TranslationMasker.mask(s);
        String traduzido = fakeTradutorAdversario(m.text());
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
    void preservaKaomojiComEspacosInternos() {
        // Kaomoji com espaços e o 'ᵕ' (modifier letter, fora das faixas DECO) — o
        // espaço quebrava o run e o 'ᵕ' vazava, daí o tradutor o descartava.
        String s = "(´｡• ᵕ •｡`) ♡";
        assertEquals(s, roundTrip(s));
        assertFalse(TranslationMasker.mask(s).text().contains("ᵕ"));
    }

    @Test
    void marcadoresAdjacentesNaoFormamRunDeLetrasIguais() {
        // Kaomoji colado a um símbolo vira dois marcadores adjacentes; eles não podem
        // formar um run de letras iguais (tipo "ZZZZ"), senão o Translate mexe e sobra
        // lixo — foi o "(｡•̀ᴗ-)ZZ✧".
        String masked = TranslationMasker.mask("(｡•̀ᴗ-)✧").text();
        assertFalse(masked.matches(".*([A-Za-z])\\1\\1.*"),
                "marcadores formaram run de 3+ letras iguais: " + masked);
        assertEquals("(｡•̀ᴗ-)✧", roundTrip("(｡•̀ᴗ-)✧"));
    }

    @Test
    void naoTraduzKakera() {
        // "kakera" é termo do jogo; o Translate vira "camera". Tem que ficar intacto.
        String s = "Você coletou 50 kakera!";
        assertEquals(s, roundTrip(s));
        assertFalse(TranslationMasker.mask(s).text().toLowerCase().contains("kakera"));
    }

    @Test
    void restauraMarcadoresAninhados() {
        // Um trecho protegido pode cair dentro de outro (crase dentro de kaomoji):
        // o restore precisa desaninhar, não parar na primeira passada.
        String s = "(`x` ✧)";
        assertEquals(s, roundTrip(s));
    }

    @Test
    void naoTraduzReferenciaDeComando() {
        // "!help" é uma referência de comando — tem que ficar literal (senão o
        // tradutor faz "! Help"). Sem crase porque footer não renderiza markdown.
        String s = "usa !help pra ver os detalhes";
        assertEquals(s, roundTrip(s));
        assertFalse(TranslationMasker.mask(s).text().contains("!help"));
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
        String s = "Você tem 5 rolls";
        TranslationMasker.Masked m = TranslationMasker.mask(s);
        assertEquals(s, m.text());
        assertTrue(m.originals().isEmpty());
        // Parênteses comuns (sem emoticon) não são mascarados.
        String s2 = "isso (importante) aqui";
        assertEquals(s2, TranslationMasker.mask(s2).text());
    }
}