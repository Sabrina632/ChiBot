package org.chibot.Translation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Esconde, antes da tradução, os trechos que a API não deve mexer e restaura
 * depois. Cada trecho protegido vira um marcador {@code Qíndice X} (sem espaço,
 * ex.: {@code Q0X}) — um token ALFANUMÉRICO, que o Amazon Translate trata como um
 * código e preserva colado. Histórico das tentativas que NÃO sobreviveram:
 * invisíveis (U+E000: o Translate descarta); símbolo dobrado ({@code @@}: vira
 * "@ @"); e delimitador igual dos dois lados ({@code ZZ}: dois marcadores colados
 * formavam "ZZZZ", que o Translate mexe). Aberto ≠ fechado evita o run igual.
 *
 * <p>Protege, nesta ordem: spans de crase ({@code `comando`}), menções/emojis do
 * Discord, URLs e emoticons/emoji (kaomoji). A detecção de emoticon é heurística:
 * uma sequência de caracteres "decorativos" (com pontuação de kaomoji em volta).
 */
public final class TranslationMasker {

    private TranslationMasker() {}

    /** Texto com os trechos protegidos trocados por marcadores, e a lista dos originais. */
    public record Masked(String text, List<String> originals) {}

    // Delimitadores do marcador: alfanuméricos (o Translate mantém colados ao índice)
    // e fora de todos os padrões de máscara abaixo (nunca re-mascarados). Abertura e
    // fechamento DIFERENTES: assim dois marcadores adjacentes ("Q0XQ1X") não formam um
    // run de letras iguais — que era o que o Translate bagunçava no "ZZ".
    private static final String OPEN = "Q";
    private static final String CLOSE = "X";

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

    // Termos do jogo/bot que o Translate erra (ex.: "kakera" vira "camera"). Ficam
    // intactos, como nome próprio. Pra proteger mais um, é só somar com "|" aqui.
    private static final Pattern GLOSSARY =
            Pattern.compile("\\b(?:kakeras?)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern BACKTICK = Pattern.compile("`[^`]+`");
    private static final Pattern DISCORD = Pattern.compile("<a?:\\w+:\\d+>|<@[!&]?\\d+>|<#\\d+>");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    // Kaomoji entre parênteses: pega o grupo inteiro — espaços e caracteres "exóticos"
    // (modifier letters tipo ᵕ/ᴗ, crases de bochecha) inclusos — como UMA unidade. Sem
    // isso o espaço interno quebrava o run e esses caracteres vazavam pro tradutor.
    // O filtro "tem DECO?" evita pegar parêntese comum tipo "(importante)".
    private static final Pattern KAOMOJI_PAREN = Pattern.compile("\\([^()]*\\)");
    // Sequência de pontuação-de-kaomoji/decoração; o filtro "tem DECO?" é aplicado depois.
    private static final Pattern EMOTICON_RUN =
            Pattern.compile("[" + KAOMOJI_PUNCT + DECO + "]+");
    private static final Pattern HAS_DECO = Pattern.compile("[" + DECO + "]");
    // Marcador alfanumérico colado; CASE_INSENSITIVE caso o Translate troque a caixa.
    private static final Pattern PLACEHOLDER = Pattern.compile("q(\\d+)x", Pattern.CASE_INSENSITIVE);

    public static Masked mask(String text) {
        if (text == null || text.isEmpty()) {
            return new Masked(text, List.of());
        }
        List<String> originals = new ArrayList<>();
        String out = text;
        // Glossário primeiro: enquanto os vizinhos ainda são originais (símbolos =
        // fronteira de palavra), o \b casa direito. Se o marcador acabar dentro de
        // outra máscara depois, o restore desaninha.
        out = maskPattern(out, GLOSSARY, originals, false);
        out = maskPattern(out, BACKTICK, originals, false);
        out = maskPattern(out, DISCORD, originals, false);
        out = maskPattern(out, URL, originals, false);
        out = maskPattern(out, KAOMOJI_PAREN, originals, true);
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
        // Repete porque um original pode conter outro marcador (máscaras aninhadas:
        // ex.: crase dentro de kaomoji). Um original só referencia índices menores que
        // o seu, então no máximo originals.size() passadas; o "não mudou" corta antes.
        String text = maskedText;
        for (int pass = 0; pass <= originals.size(); pass++) {
            Matcher m = PLACEHOLDER.matcher(text);
            StringBuilder sb = new StringBuilder();
            boolean achou = false;
            while (m.find()) {
                achou = true;
                int idx = Integer.parseInt(m.group(1));
                String original = idx >= 0 && idx < originals.size() ? originals.get(idx) : m.group();
                m.appendReplacement(sb, Matcher.quoteReplacement(original));
            }
            if (!achou) {
                break;
            }
            m.appendTail(sb);
            String next = sb.toString();
            if (next.equals(text)) {
                break; // só sobraram marcadores órfãos (índice inválido); evita loop
            }
            text = next;
        }
        return text;
    }
}