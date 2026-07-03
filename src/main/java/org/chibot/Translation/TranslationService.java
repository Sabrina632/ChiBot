package org.chibot.Translation;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.chibot.Database.LanguageRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coração do sistema de tradução. Singleton (igual {@code HaremService}): olha o
 * idioma do autor, mascara o que não pode ser traduzido, consulta cache (memória →
 * banco → API) e traduz. Também traduz embeds. {@code pt} é no-op; sem tradutor,
 * tudo degrada pro original.
 */
public class TranslationService {

    /** Idioma nativo da Chi — não traduz. */
    private static final String IDIOMA_FONTE = "pt";

    /**
     * Versão do esquema de cache. Entra no hash, então ao subir esse número as
     * traduções antigas são ignoradas (e recalculadas). v2 limpou o lixo do marcador
     * invisível (U+E000 → "0 ... 1"); v3 limpa o do "@@" (que virava "@ @0 @@"),
     * agora resolvido com o marcador alfanumérico. v4 cobriu o grupo de parênteses
     * inteiro (kaomoji que perdiam o ᵕ); v5, os "ZZ" sobrando de marcadores colados;
     * v6 limpa os termos do glossário que já foram traduzidos errados (kakera ->
     * camera); v7, as referências de comando manjadas (ex.: "!help" -> "! Help") —
     * ver {@link TranslationMasker}.
     */
    private static final String CACHE_VERSION = "v7";

    /** Idiomas oferecidos no {@code !language}. */
    private static final Set<String> SUPORTADOS = Set.of(
            "pt", "en", "es", "ja", "fr", "de", "it", "ru", "ko", "zh");

    // Limites do Discord pra não estourar ao traduzir embeds.
    private static final int MAX_TITULO = 256;
    private static final int MAX_DESC = 4096;
    private static final int MAX_CAMPO_NOME = 256;
    private static final int MAX_CAMPO_VALOR = 1024;
    private static final int MAX_RODAPE = 2048;

    /** Limite de conteúdo de uma mensagem do Discord (a tradução pode expandir o texto). */
    private static final int MAX_MENSAGEM = 2000;

    /** Teto do cache em memória; ao passar disso ele é zerado (o banco reabastece). */
    private static final int MAX_MEM_CACHE = 10_000;

    private static volatile TranslationService instance;

    private final LanguageRepository repo;
    private final Translator translator;
    /** Cache em memória: chave "lang hash" → tradução final (já restaurada). */
    private final ConcurrentHashMap<String, String> memCache = new ConcurrentHashMap<>();

    public TranslationService(LanguageRepository repo, Translator translator) {
        this.repo = repo;
        this.translator = translator;
    }

    public static TranslationService init(LanguageRepository repo, Translator translator) {
        instance = new TranslationService(repo, translator);
        return instance;
    }

    /** Instância criada no boot, ou {@code null} antes do boot. */
    public static TranslationService get() {
        return instance;
    }

    // --------------------------------------------------------------- preferência

    public String getLanguage(String userId) {
        return repo.getLanguage(userId);
    }

    /** Salva o idioma do usuário. Retorna {@code false} se o código não for suportado. */
    public boolean setLanguage(String userId, String lang) {
        if (lang == null) {
            return false;
        }
        String code = lang.toLowerCase();
        if (!SUPORTADOS.contains(code)) {
            return false;
        }
        repo.setLanguage(userId, code);
        return true;
    }

    public Set<String> supportedLanguages() {
        return SUPORTADOS;
    }

    // ------------------------------------------------------------------ tradução

    public String translateForUser(String userId, String text) {
        return translate(text, getLanguage(userId));
    }

    /** Traduz {@code text} para {@code lang} (no-op se {@code pt}/sem tradutor/vazio). */
    public String translate(String text, String lang) {
        if (translator == null || text == null || text.isBlank() || IDIOMA_FONTE.equals(lang)) {
            return text;
        }
        return translateAll(List.of(text), lang).get(0);
    }

    /**
     * Traduz vários textos de uma vez, na ordem (nulls/vazios passam direto).
     * Cada texto tem cache individual (memória → banco); só os misses vão pra
     * API, num único lote — um embed inteiro custa no máximo uma chamada de
     * rede, em vez de uma por campo na thread de eventos do JDA.
     */
    public List<String> translateAll(List<String> texts, String lang) {
        if (translator == null || IDIOMA_FONTE.equals(lang) || texts.isEmpty()) {
            return texts;
        }
        String[] out = new String[texts.size()];
        List<Integer> misses = new ArrayList<>();
        List<String> missHashes = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            if (text == null || text.isBlank()) {
                out[i] = text;
                continue;
            }
            String hash = sha256(CACHE_VERSION + " " + text);
            String mem = memCache.get(lang + " " + hash);
            if (mem != null) {
                out[i] = mem;
                continue;
            }
            String cached = repo.getCachedTranslation(lang, hash);
            if (cached != null) {
                memPut(lang + " " + hash, cached);
                out[i] = cached;
                continue;
            }
            misses.add(i);
            missHashes.add(hash);
        }

        if (!misses.isEmpty()) {
            List<TranslationMasker.Masked> masked = new ArrayList<>(misses.size());
            List<String> maskedTexts = new ArrayList<>(misses.size());
            for (int idx : misses) {
                TranslationMasker.Masked m = TranslationMasker.mask(texts.get(idx));
                masked.add(m);
                maskedTexts.add(m.text());
            }
            List<String> translated = translator.translate(maskedTexts, IDIOMA_FONTE, lang);
            for (int j = 0; j < misses.size(); j++) {
                String restored = TranslationMasker.restore(translated.get(j), masked.get(j).originals());
                out[misses.get(j)] = restored;
                // Se a API devolveu o texto intacto (circuit breaker aberto ou
                // falha degradada), NÃO cacheia — senão o pt ficava gravado como
                // "tradução" daquele idioma pra sempre.
                if (!translated.get(j).equals(maskedTexts.get(j))) {
                    memPut(lang + " " + missHashes.get(j), restored);
                    repo.putCachedTranslation(lang, missHashes.get(j), restored);
                }
            }
        }
        return Arrays.asList(out);
    }

    private void memPut(String key, String value) {
        if (memCache.size() >= MAX_MEM_CACHE) {
            memCache.clear(); // o banco reabastece; melhor que crescer sem limite
        }
        memCache.put(key, value);
    }

    // -------------------------------------------------------------------- embeds

    public MessageEmbed translateEmbedForUser(String userId, MessageEmbed embed) {
        String lang = getLanguage(userId);
        if (translator == null || IDIOMA_FONTE.equals(lang) || embed == null) {
            return embed;
        }
        // Junta todos os textos do embed num único lote: [título, descrição,
        // nome/valor de cada campo..., rodapé]. translateAll passa nulls direto.
        List<MessageEmbed.Field> fields = embed.getFields();
        List<String> partes = new ArrayList<>(3 + fields.size() * 2);
        partes.add(embed.getTitle());
        partes.add(embed.getDescription());
        for (MessageEmbed.Field f : fields) {
            partes.add(f.getName());
            partes.add(f.getValue());
        }
        partes.add(embed.getFooter() == null ? null : embed.getFooter().getText());
        List<String> trad = translateAll(partes, lang);

        EmbedBuilder b = new EmbedBuilder(embed);
        if (embed.getTitle() != null) {
            b.setTitle(clamp(trad.get(0), MAX_TITULO), embed.getUrl());
        }
        if (embed.getDescription() != null) {
            b.setDescription(clamp(trad.get(1), MAX_DESC));
        }
        b.clearFields();
        for (int i = 0; i < fields.size(); i++) {
            String nome = trad.get(2 + i * 2);
            String valor = trad.get(3 + i * 2);
            b.addField(nome == null ? "" : clamp(nome, MAX_CAMPO_NOME),
                    valor == null ? "" : clamp(valor, MAX_CAMPO_VALOR),
                    fields.get(i).isInline());
        }
        if (embed.getFooter() != null && embed.getFooter().getText() != null) {
            b.setFooter(clamp(trad.get(partes.size() - 1), MAX_RODAPE),
                    embed.getFooter().getIconUrl());
        }
        return b.build();
    }

    public List<MessageEmbed> translateEmbedsForUser(String userId, List<MessageEmbed> embeds) {
        if (embeds == null || embeds.isEmpty()) {
            return embeds;
        }
        List<MessageEmbed> out = new ArrayList<>(embeds.size());
        for (MessageEmbed e : embeds) {
            out.add(translateEmbedForUser(userId, e));
        }
        return out;
    }

    // -------------------------------------------------- estáticos null-safe (contexts)

    public static String forUser(String userId, String text) {
        TranslationService s = instance;
        // Clampa no limite de mensagem: a tradução pode expandir o texto e o
        // Discord rejeita conteúdo acima de 2000 caracteres.
        return s == null ? text : clamp(s.translateForUser(userId, text), MAX_MENSAGEM);
    }

    public static MessageEmbed embedForUser(String userId, MessageEmbed embed) {
        TranslationService s = instance;
        return s == null ? embed : s.translateEmbedForUser(userId, embed);
    }

    public static List<MessageEmbed> embedsForUser(String userId, List<MessageEmbed> embeds) {
        TranslationService s = instance;
        return s == null ? embeds : s.translateEmbedsForUser(userId, embeds);
    }

    // ----------------------------------------------------------------- auxiliares

    private static String clamp(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        // Não corta no meio de um surrogate pair (emoji fora do BMP), senão a
        // string fica malformada e o Discord rejeita a mensagem.
        int cut = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, cut);
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 sempre existe; fallback improvável.
            return Integer.toHexString(text.hashCode());
        }
    }
}