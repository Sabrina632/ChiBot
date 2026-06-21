package org.chibot.Translation;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.chibot.Database.LanguageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    /** Idioma nativo da Chi — não traduz. */
    private static final String IDIOMA_FONTE = "pt";

    /**
     * Versão do esquema de cache. Entra no hash, então ao subir esse número as
     * traduções antigas são ignoradas (e recalculadas). v2 limpou o lixo do marcador
     * invisível (U+E000 → "0 ... 1"); v3 limpa o do "@@" (que virava "@ @0 @@"),
     * agora resolvido com o marcador alfanumérico. v4 cobriu o grupo de parênteses
     * inteiro (kaomoji que perdiam o ᵕ); v5, os "ZZ" sobrando de marcadores colados;
     * v6 limpa os termos do glossário que já foram traduzidos errados (kakera ->
     * camera) — ver {@link TranslationMasker}.
     */
    private static final String CACHE_VERSION = "v6";

    /** Idiomas oferecidos no {@code !language}. */
    private static final Set<String> SUPORTADOS = Set.of(
            "pt", "en", "es", "ja", "fr", "de", "it", "ru", "ko", "zh");

    // Limites do Discord pra não estourar ao traduzir embeds.
    private static final int MAX_TITULO = 256;
    private static final int MAX_DESC = 4096;
    private static final int MAX_CAMPO_NOME = 256;
    private static final int MAX_CAMPO_VALOR = 1024;

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
        String hash = sha256(CACHE_VERSION + " " + text);
        String memKey = lang + " " + hash;

        String mem = memCache.get(memKey);
        if (mem != null) {
            return mem;
        }
        String cached = repo.getCachedTranslation(lang, hash);
        if (cached != null) {
            memCache.put(memKey, cached);
            return cached;
        }

        TranslationMasker.Masked masked = TranslationMasker.mask(text);
        String translated = translator.translate(masked.text(), IDIOMA_FONTE, lang);
        String restored = TranslationMasker.restore(translated, masked.originals());

        memCache.put(memKey, restored);
        repo.putCachedTranslation(lang, hash, restored);
        return restored;
    }

    // -------------------------------------------------------------------- embeds

    public MessageEmbed translateEmbedForUser(String userId, MessageEmbed embed) {
        String lang = getLanguage(userId);
        if (translator == null || IDIOMA_FONTE.equals(lang) || embed == null) {
            return embed;
        }
        EmbedBuilder b = new EmbedBuilder(embed);
        if (embed.getTitle() != null) {
            b.setTitle(clamp(translate(embed.getTitle(), lang), MAX_TITULO), embed.getUrl());
        }
        if (embed.getDescription() != null) {
            b.setDescription(clamp(translate(embed.getDescription(), lang), MAX_DESC));
        }
        b.clearFields();
        for (MessageEmbed.Field f : embed.getFields()) {
            String nome = f.getName() == null ? "" : clamp(translate(f.getName(), lang), MAX_CAMPO_NOME);
            String valor = f.getValue() == null ? "" : clamp(translate(f.getValue(), lang), MAX_CAMPO_VALOR);
            b.addField(nome, valor, f.isInline());
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
        return s == null ? text : s.translateForUser(userId, text);
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
        return s.substring(0, max);
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