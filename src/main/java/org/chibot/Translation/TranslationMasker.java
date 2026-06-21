package org.chibot.Translation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Esconde, antes da tradução, os trechos que a API não deve mexer e restaura
 * depois. Cada trecho protegido vira um marcador {@code @@índice@@} — um símbolo
 * ASCII comum, que o Amazon Translate preserva. (Marcadores invisíveis da Área de
 * Uso Privado NÃO servem: o Translate os descarta, e o índice nu vaza no texto.)
 *
 * <p>Protege, nesta ordem: spans de crase ({@code `comando`}), menções/emojis do
 * Discord, URLs e emoticons/emoji (kaomoji). A detecção de emoticon é heurística:
 * uma sequência de caracteres "decorativos" (com pontuação de kaomoji em volta).
 */
public final class TranslationMasker {

    private TranslationMasker() {}

    /** Texto com os trechos protegidos trocados por marcadores, e a lista dos originais. */
    public record Masked(String text, List<String> originals) {}

    // Delimitador do marcador. "@@" é ASCII comum (o Translate preserva) e está fora
    // de todos os padrões de máscara abaixo, então o marcador nunca é re-mascarado.
    private static final String OPEN = "@@";
    private static final String CLOSE = "@@";

    // Caracteres "decorativos" típicos de kaomoji/emoji (não devem ser traduzidos).
    private static final String DECO =
            "\\u00B4\\u02C6-\\u02DF\\u0300-\\u036F"           // acentos soltos, modificadores, combinantes
            + "\\u0391-\\u03C9"                              // gregas usadas em kaomoji (ω, etc.)
            + "\\u2010-\\u2027\\u2030-\\u205E"               // travessões, aspas curvas, reticências, etc.
            + "\\u2190-\\u21FF\\u2200-\\u22FF\\u2300-\\u23FF" // setas, operadores matemáticos, técnicos
            + "\\u2460-\\u24FF\\u25A0-\\u27BF"               // fechados, geométricos, dingbats/símbolos
            + "\\u2900-\\u2BFF"                              // setas suplementares, símbolos diversos
            + "\\u3000-\\u303F\\u3040-\\u30FF\\u31F0-\\u31FF\\uFF00-\\uFFEF" // CJK punct, kana, halfwidth
            + "\\x{1F000}-\\x{1FAFF}";                       // emoji fora do BMP

    // Pontuação ASCII que costuma compor kaomoji (só mascarada junto se houver DECO).
    private static final String KAOMOJI_PUNCT = "()\\[\\]{}<>|/\\\\^~*;:._=+\\-'\"!?";

    private static final Pattern BACKTICK = Pattern.compile("`[^`]+`");
    private static final Pattern DISCORD = Pattern.compile("<a?:\\w+:\\d+>|<@[!&]?\\d+>|<#\\d+>");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    // Sequência de pontuação-de-kaomoji/decoração; o filtro "tem DECO?" é aplicado depois.
    private static final Pattern EMOTICON_RUN =
            Pattern.compile("[" + KAOMOJI_PUNCT + DECO + "]+");
    private static final Pattern HAS_DECO = Pattern.compile("[" + DECO + "]");
    // \\s* tolera o tradutor inserir espaços ao redor do índice (ex.: "@@ 0 @@").
    private static final Pattern PLACEHOLDER = Pattern.compile("@@\\s*(\\d+)\\s*@@");

    public static Masked mask(String text) {
        if (text == null || text.isEmpty()) {
            return new Masked(text, List.of());
        }
        List<String> originals = new ArrayList<>();
        String out = text;
        out = maskPattern(out, BACKTICK, originals, false);
        out = maskPattern(out, DISCORD, originals, false);
        out = maskPattern(out, URL, originals, false);
        out = maskPattern(out, EMOTICON_RUN, originals, true);
        return new Masked(out, originals);
    }

    /**
     * Troca cada match por um marcador. Se {@code requireDeco}, só mascara matches
     * que contenham ao menos um caractere decorativo (pra não pegar "(texto)" comum).
     */
    private static String maskPattern(String text, Pattern pattern, List<String> originals,
                                      boolean requireDeco) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String match = m.group();
            if (requireDeco && !HAS_DECO.matcher(match).find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(match));
                continue;
            }
            String token = OPEN + originals.size() + CLOSE;
            originals.add(match);
            m.appendReplacement(sb, Matcher.quoteReplacement(token));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String restore(String maskedText, List<String> originals) {
        if (maskedText == null || originals.isEmpty()) {
            return maskedText;
        }
        Matcher m = PLACEHOLDER.matcher(maskedText);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            String original = idx >= 0 && idx < originals.size() ? originals.get(idx) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(original));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}