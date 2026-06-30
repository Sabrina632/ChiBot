package org.chibot.Translation;

import org.chibot.Config.ChiConfig;
import org.json.JSONArray;
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
 * Implementação de {@link Translator} sobre a API da DeepL (endpoint REST v2). Roda
 * na thread do gateway do JDA, então é configurado pra falhar rápido: sem retries e
 * com timeout curto. Em caso de falha (rede, HTTP != 200), propaga a exceção — quem
 * decide degradar (e blindar o bot do spam de log) é o {@link ResilientTranslator},
 * que embrulha este aqui.
 */
public class DeepLTranslator implements Translator {

    private static final Logger log = LoggerFactory.getLogger(DeepLTranslator.class);

    /** Teto pra não segurar a thread do gateway se a DeepL sumir. */
    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    /**
     * DeepL exige variante de inglês como destino (EN sozinho é depreciado) e usa
     * ZH pro chinês; o resto é só o código em maiúsculo. {@code pt} é a fonte, então
     * nunca aparece como destino aqui. Códigos fora do mapa caem no maiúsculo direto.
     */
    private static final Map<String, String> TARGET = Map.of(
            "en", "EN-US",
            "zh", "ZH");

    private final HttpClient http;
    private final String endpoint;
    private final String authKey;

    public DeepLTranslator(HttpClient http, String endpoint, String authKey) {
        this.http = http;
        this.endpoint = endpoint;
        this.authKey = authKey;
    }

    /**
     * Monta o tradutor a partir do .env. Retorna {@code null} (tradução desligada)
     * se faltar a chave — aí o bot segue todo em português. Chaves de conta Free
     * terminam em ":fx" e batem no endpoint api-free; as Pro vão pro api.deepl.com.
     */
    public static DeepLTranslator fromConfig(ChiConfig config) {
        String key = config.getDeeplApiKey();
        if (key == null || key.isBlank()) {
            log.warn("DEEPL_API_KEY ausente no .env; tradução desligada (tudo em pt).");
            return null;
        }
        boolean free = key.endsWith(":fx");
        String endpoint = (free ? "https://api-free.deepl.com" : "https://api.deepl.com")
                + "/v2/translate";
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        log.info("DeepL pronto (conta {}).", free ? "Free" : "Pro");
        return new DeepLTranslator(http, endpoint, key);
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        String body = "text=" + enc(text)
                + "&source_lang=" + enc(sourceLang.toUpperCase())
                + "&target_lang=" + enc(target(targetLang));

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(TIMEOUT)
                .header("Authorization", "DeepL-Auth-Key " + authKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Falha ao chamar a DeepL", e);
        }
        if (response.statusCode() != 200) {
            throw new RuntimeException("DeepL respondeu HTTP " + response.statusCode());
        }

        JSONArray translations = new JSONObject(response.body()).getJSONArray("translations");
        return translations.getJSONObject(0).getString("text");
    }

    /** Código de destino no formato que a DeepL espera (ver {@link #TARGET}). */
    private static String target(String lang) {
        return TARGET.getOrDefault(lang.toLowerCase(), lang.toUpperCase());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}