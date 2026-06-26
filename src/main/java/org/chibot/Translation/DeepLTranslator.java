package org.chibot.Translation;

import org.chibot.Config.ChiConfig;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Implementação de {@link Translator} sobre a API da DeepL. Roda na thread do
 * gateway do JDA, então é configurado pra falhar rápido: timeout curto e sem
 * retries. Em caso de falha, propaga a exceção — quem decide degradar (e blindar
 * o bot do spam de log) é o {@link ResilientTranslator}, que embrulha este aqui.
 */
public class DeepLTranslator implements Translator {

    private static final Logger log = LoggerFactory.getLogger(DeepLTranslator.class);

    // Endpoints da DeepL. A chave da conta gratuita termina em ":fx" e usa o host
    // api-free; a paga usa o api (sem o sufixo).
    private static final String FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate";
    private static final String PRO_ENDPOINT = "https://api.deepl.com/v2/translate";

    /**
     * Códigos de idioma alvo da DeepL. Para a maioria basta o código em maiúsculo,
     * mas a DeepL pede a variante regional pra alguns (EN e PT) e não aceita mais o
     * "EN"/"PT" puro como alvo. Quem não estiver no mapa é só passado em maiúsculo.
     */
    private static final Map<String, String> TARGET_LANG = Map.of(
            "en", "EN-US",
            "pt", "PT-BR");

    private final HttpClient http;
    private final String apiKey;
    private final String endpoint;

    public DeepLTranslator(HttpClient http, String apiKey, String endpoint) {
        this.http = http;
        this.apiKey = apiKey;
        this.endpoint = endpoint;
    }

    /**
     * Monta o tradutor a partir do .env. Retorna {@code null} (tradução desligada)
     * se faltar a chave da DeepL — aí o bot segue todo em português.
     */
    public static DeepLTranslator fromConfig(ChiConfig config) {
        String key = config.getDeeplApiKey();
        if (blank(key)) {
            log.warn("Chave da DeepL ausente no .env; tradução desligada (tudo em pt).");
            return null;
        }
        // Teto de 3s pra não segurar a thread do gateway se a DeepL sumir.
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        String endpoint = key.trim().endsWith(":fx") ? FREE_ENDPOINT : PRO_ENDPOINT;
        log.info("DeepL pronto ({}).", endpoint.equals(FREE_ENDPOINT) ? "conta gratuita" : "conta paga");
        return new DeepLTranslator(http, key.trim(), endpoint);
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        String body = "text=" + enc(text)
                + "&source_lang=" + enc(sourceLang.toUpperCase())
                + "&target_lang=" + enc(targetLang(targetLang));

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "DeepL-Auth-Key " + apiKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp;
        try {
            resp = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Falha ao chamar a DeepL", e);
        }
        if (resp.statusCode() != 200) {
            throw new RuntimeException("DeepL respondeu HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return new JSONObject(resp.body())
                .getJSONArray("translations")
                .getJSONObject(0)
                .getString("text");
    }

    /** Código alvo da DeepL: variante regional pra EN/PT, maiúsculo pro resto. */
    private static String targetLang(String lang) {
        String code = lang.toLowerCase();
        return TARGET_LANG.getOrDefault(code, code.toUpperCase());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}