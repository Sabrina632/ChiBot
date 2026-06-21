package org.chibot.Translation;

import org.chibot.Config.ChiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.translate.TranslateClient;
import software.amazon.awssdk.services.translate.model.TranslateTextRequest;
import software.amazon.awssdk.services.translate.model.TranslateTextResponse;

/**
 * Implementação de {@link Translator} sobre o Amazon Translate. Se uma tradução
 * falhar (rede/credencial/limite), loga e devolve o texto original — a Chi nunca
 * quebra por causa disso.
 */
public class AwsTranslator implements Translator {

    private static final Logger log = LoggerFactory.getLogger(AwsTranslator.class);

    private final TranslateClient client;

    public AwsTranslator(TranslateClient client) {
        this.client = client;
    }

    /**
     * Monta o tradutor a partir do .env. Retorna {@code null} (tradução desligada)
     * se faltar qualquer uma das credenciais — aí o bot segue todo em português.
     */
    public static AwsTranslator fromConfig(ChiConfig config) {
        String key = config.getAwsAccessKeyId();
        String secret = config.getAwsSecretAccessKey();
        String region = config.getAwsRegion();
        if (blank(key) || blank(secret) || blank(region)) {
            log.warn("Credenciais da AWS ausentes no .env; tradução desligada (tudo em pt).");
            return null;
        }
        TranslateClient client = TranslateClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(key, secret)))
                .build();
        log.info("Amazon Translate pronto (região {}).", region);
        return new AwsTranslator(client);
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        try {
            TranslateTextResponse resp = client.translateText(TranslateTextRequest.builder()
                    .text(text)
                    .sourceLanguageCode(sourceLang)
                    .targetLanguageCode(targetLang)
                    .build());
            return resp.translatedText();
        } catch (Exception e) {
            log.warn("Falha ao traduzir para {} — devolvendo original.", targetLang, e);
            return text;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}