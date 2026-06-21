package org.chibot.Translation;

import org.chibot.Config.ChiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.translate.TranslateClient;
import software.amazon.awssdk.services.translate.model.TranslateTextRequest;
import software.amazon.awssdk.services.translate.model.TranslateTextResponse;

import java.time.Duration;

/**
 * Implementação de {@link Translator} sobre o Amazon Translate. Roda na thread do
 * gateway do JDA, então é configurado pra falhar rápido: sem retries e com timeout
 * curto. Em caso de falha, propaga a exceção — quem decide degradar (e blindar o
 * bot do spam de log) é o {@link ResilientTranslator}, que embrulha este aqui.
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
                // Falha rápido: 1 tentativa, sem a tempestade de 4 retries do SDK,
                // e teto de 3s pra não segurar a thread do gateway se a AWS sumir.
                .overrideConfiguration(o -> o
                        .retryPolicy(RetryPolicy.none())
                        .apiCallTimeout(Duration.ofSeconds(3))
                        .apiCallAttemptTimeout(Duration.ofSeconds(3)))
                .build();
        log.info("Amazon Translate pronto (região {}).", region);
        return new AwsTranslator(client);
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        TranslateTextResponse resp = client.translateText(TranslateTextRequest.builder()
                .text(text)
                .sourceLanguageCode(sourceLang)
                .targetLanguageCode(targetLang)
                .build());
        return resp.translatedText();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}