package org.chibot.Commands.PartyFinderCommands;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokeniza a descricao de um Party Finder pra revelar as strats mais usadas.
 * Porte (em Java) da abordagem do projeto xivpf-tokenizer:
 *
 * <ul>
 *   <li>minusculas; URLs sao trocadas por um token de servico
 *       (ex.: {@code raidplan}, {@code toolbox}) + id do plano quando houver;</li>
 *   <li>remove pontuacao, stopwords ({@link #STOP_WORDS}), tokens curtos
 *       (&lt; 3), numeros puros e caracteres CJK;</li>
 *   <li>gera unigramas e bigramas (ex.: "uptime strat").</li>
 * </ul>
 */
public final class StratsTokenizer {

    private StratsTokenizer() {}

    private static final int MIN_TOKEN_LEN = 3;

    // Stopwords: ingles comum + ruido tipico de PF (porte do _STOP_WORDS do repo).
    private static final Set<String> STOP_WORDS = Set.of(
            // ingles comum (>= 3 letras; os curtos ja caem pelo MIN_TOKEN_LEN)
            "the", "and", "but", "for", "with", "about", "against", "between",
            "into", "through", "during", "before", "after", "above", "below",
            "from", "down", "out", "off", "over", "under", "again", "further",
            "then", "once", "here", "there", "when", "where", "why", "how",
            "all", "any", "both", "each", "few", "more", "most", "other", "some",
            "such", "nor", "not", "only", "own", "same", "than", "too", "very",
            "can", "will", "just", "don", "should", "now", "are", "was", "were",
            "been", "being", "have", "has", "had", "having", "does", "did",
            "doing", "this", "that", "these", "those", "what", "which", "who",
            "whom", "your", "yours", "you", "they", "them", "their", "theirs",
            "his", "her", "hers", "its", "our", "ours", "him", "she", "dont",
            "youre", "thats", "also", "etc", "yes", "none",
            // ruido de PF (porte do _STOP_WORDS do repo)
            "https", "http", "www", "com", "net", "org", "docs", "google",
            "lf", "lf1m", "lf2m", "lf3m", "lf4m", "lf5m", "lf6m",
            "dc", "oce", "ocg", "pf", "party", "finder", "item", "ilvl", "ilv",
            "prog", "reclear", "clear", "farm", "per", "job", "jobs",
            "one", "player", "players", "ppj", "ppl",
            "must", "please", "know", "knowing", "mech", "mechs", "mechanic",
            "mechanics", "fill", "spot", "spots", "exp", "experienced", "join",
            "run", "runs", "week", "weekly", "new", "old", "alt", "main",
            "use", "using", "want", "need", "raid", "boss", "fresh", "enrage",
            "kefka", "bin"
    );

    // Hosts de servicos conhecidos -> token canonico (porte do _URL_SERVICE_NAMES).
    private static final Map<String, String> URL_SERVICE_NAMES = Map.ofEntries(
            Map.entry("raidplan.io", "raidplan"),
            Map.entry("pastebin.com", "pastebin"),
            Map.entry("kefkabin.com", "kefkabin"),
            Map.entry("ff14.toolboxgaming.space", "toolbox"),
            Map.entry("toolboxgaming.space", "toolbox"),
            Map.entry("docs.google.com", "google docs"),
            Map.entry("cdn.discordapp.com", "discord"),
            Map.entry("imgur.com", "imgur"),
            Map.entry("i.imgur.com", "imgur")
    );

    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern URL_PARTS = Pattern.compile("https?://([^/\\s]+)(.*)");
    private static final Pattern CJK = Pattern.compile(
            "[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}\\p{IsHangul}]");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * Diz se um token e puro ruido (todas as palavras sao stopwords), pra
     * filtrar tambem o que ja foi acumulado no banco na hora de exibir — ex.:
     * "one", "player", "one player", "per job".
     */
    public static boolean isNoise(String token) {
        if (token == null || token.isBlank()) {
            return true;
        }
        for (String w : token.split(" ")) {
            if (!STOP_WORDS.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** Tokens distintos da descricao (unigramas + bigramas + servicos de URL). */
    public static List<String> tokenize(String description) {
        List<String> tokens = new ArrayList<>();
        if (description == null || description.isBlank()) {
            return tokens;
        }

        // 1) tokens de URL (preserva o id do plano com a caixa original)
        List<String> urlTokens = new ArrayList<>();
        Matcher m = URL_PATTERN.matcher(description);
        while (m.find()) {
            String t = urlToken(m.group());
            if (t != null) {
                urlTokens.add(t);
            }
        }

        // 2) tokeniza o restante
        String normalized = normalize(description);
        List<String> filtered = new ArrayList<>();
        for (String w : normalized.split("\\s+")) {
            if (w.length() < MIN_TOKEN_LEN) {
                continue;
            }
            if (STOP_WORDS.contains(w) || DIGITS.matcher(w).matches() || CJK.matcher(w).find()) {
                continue;
            }
            filtered.add(w);
        }

        // unigramas + bigramas consecutivos
        tokens.addAll(filtered);
        for (int i = 0; i < filtered.size() - 1; i++) {
            tokens.add(filtered.get(i) + " " + filtered.get(i + 1));
        }
        tokens.addAll(urlTokens);

        // distintos por PF (presenca): cada token conta uma vez por descricao
        return new ArrayList<>(new LinkedHashSet<>(tokens));
    }

    /** Token combinado pra uma URL: "raidplan <id>", so o servico, ou null. */
    private static String urlToken(String url) {
        Matcher m = URL_PARTS.matcher(url);
        if (!m.find()) {
            return null;
        }
        String host = m.group(1).toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        String service = URL_SERVICE_NAMES.get(host);
        if (service == null) {
            return null;
        }
        String path = m.group(2).split("[?#]", 2)[0];
        String planId = null;
        for (String seg : path.split("/")) {
            if (seg.length() >= 4) {
                planId = seg.replaceAll("[^\\w\\-]", "");
            }
        }
        return (planId != null && !planId.isBlank()) ? service + " " + planId : service;
    }

    /** Tira URLs (tratadas a parte) e normaliza pontuacao/espacos. */
    private static String normalize(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        t = URL_PATTERN.matcher(t).replaceAll(" ");
        t = t.replaceAll("[\\[\\](){}|/\\\\]", " ");
        t = t.replaceAll("[^\\w\\s\\-]", " ");
        return t.replaceAll("\\s+", " ").trim();
    }
}