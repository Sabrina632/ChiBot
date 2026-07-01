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
import java.util.ArrayList;
import java.util.List;
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

    /** Máximo de parâmetros {@code text} por requisição (limite da API v2). */
    private static final int MAX_LOTE = 50;

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        return translate(List.of(text), sourceLang, targetLang).get(0);
    }

    /**
     * Lote de verdade: todos os textos vão numa única requisição (vários {@code text=}),
     * e a DeepL devolve as traduções na mesma ordem. Um embed inteiro vira uma
     * chamada só, em vez de uma por campo.
     */
    @Override
    public List<String> translate(List<String> texts, String sourceLang, String targetLang) {
        List<String> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += MAX_LOTE) {
            out.addAll(sendBatch(texts.subList(i, Math.min(i + MAX_LOTE, texts.size())),
                    sourceLang, targetLang));
        }
        return out;
    }

    private List<String> sendBatch(List<String> texts, String sourceLang, String targetLang) {
        StringBuilder body = new StringBuilder();
        for (String text : texts) {
            body.append("text=").append(enc(text)).append('&');
        }
        body.append("source_lang=").append(enc(sourceLang.toUpperCase()))
                .append("&target_lang=").append(enc(target(targetLang)));

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(TIMEOUT)
                .header("Authorization", "DeepL-Auth-Key " + authKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
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
        if (translations.length() != texts.size()) {
            throw new RuntimeException("DeepL devolveu " + translations.length()
                    + " tradução(ões) pra " + texts.size() + " texto(s)");
        }
        List<String> out = new ArrayList<>(texts.size());
        for (int i = 0; i < translations.length(); i++) {
            out.add(translations.getJSONObject(i).getString("text"));
        }
        return out;
    }

    /** Código de destino no formato que a DeepL espera (ver {@link #TARGET}). */
    private static String target(String lang) {
        return TARGET.getOrDefault(lang.toLowerCase(), lang.toUpperCase());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}