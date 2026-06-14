package org.chibot.Music;

import org.chibot.Config.ChiConfig;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

/**
 * OAuth do YouTube gerenciado pelo bot (modo "client-provided token" do
 * youtube-source): o bot troca o refresh token por access tokens e cada track
 * do YouTube leva o token no userData ({"oauth-token": ...}) na hora do play.
 * Assim o node toca como conta logada — contorna o bloqueio de IP de
 * datacenter — sem precisar configurar OAuth no application.yml do Lavalink.
 *
 * Usa o client publico de TV do YouTube (o mesmo embutido no youtube-source
 * e no yt-dlp); e o unico aceito pelo device flow interno do YouTube.
 *
 * Sem YOUTUBE_REFRESH_TOKEN no .env, o bot dispara o device flow no boot: o
 * log mostra o codigo pra autorizar em google.com/device e, depois da
 * autorizacao, salva o refresh token sozinho no .env.
 */
public final class YtOauth {

    private static final Logger log = LoggerFactory.getLogger(YtOauth.class);

    private static final String CLIENT_ID =
            "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com";
    private static final String CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT";
    private static final String SCOPES =
            "http://gdata.youtube.com https://www.googleapis.com/auth/youtube";
    private static final String DEVICE_CODE_URL = "https://www.youtube.com/o/oauth2/device/code";
    private static final String TOKEN_URL = "https://www.youtube.com/o/oauth2/token";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private String refreshToken;
    private String accessToken;
    private long validUntil;   // ate quando o accessToken vale
    private long nextAttempt;  // throttle de retry depois de uma falha

    public YtOauth(String refreshToken) {
        this.refreshToken =
                refreshToken == null || refreshToken.isBlank() ? null : refreshToken;
    }

    /**
     * Access token valido (renova sozinho perto de expirar), ou null se o
     * login nao foi feito/configurado — nesse caso a track vai sem token e o
     * node tenta tocar deslogado, como antes.
     */
    public synchronized String accessToken() {
        if (refreshToken == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (accessToken != null && now < validUntil) {
            return accessToken;
        }
        if (now < nextAttempt) {
            return null;
        }
        try {
            JSONObject res = post(TOKEN_URL, new JSONObject()
                    .put("client_id", CLIENT_ID)
                    .put("client_secret", CLIENT_SECRET)
                    .put("refresh_token", refreshToken)
                    .put("grant_type", "refresh_token"));
            if (!res.has("access_token")) {
                throw new IllegalStateException(res.optString("error", "resposta sem access_token"));
            }
            accessToken = res.getString("access_token");
            validUntil = now + res.getLong("expires_in") * 1000 - 60_000;
            log.info("Access token do YouTube renovado.");
        } catch (Exception e) {
            accessToken = null;
            nextAttempt = now + 15_000;
            log.warn("Falha renovando o access token do YouTube: {}", e.toString());
        }
        return accessToken;
    }

    /** Sem refresh token: roda o device flow em background (uma vez, no boot). */
    public void startDeviceFlowIfNeeded() {
        if (refreshToken != null) {
            return;
        }
        Thread thread = new Thread(this::deviceFlow, "yt-oauth-device-flow");
        thread.setDaemon(true);
        thread.start();
    }

    private void deviceFlow() {
        try {
            JSONObject init = post(DEVICE_CODE_URL, new JSONObject()
                    .put("client_id", CLIENT_ID)
                    .put("scope", SCOPES)
                    .put("device_id", UUID.randomUUID().toString().replace("-", ""))
                    .put("device_model", "ytlr::"));
            String deviceCode = init.getString("device_code");
            long intervalMs = init.optLong("interval", 5) * 1000;
            long deadline = System.currentTimeMillis() + init.optLong("expires_in", 1800) * 1000;

            log.warn("==== LOGIN DO YOUTUBE ====");
            log.warn("Acesse {} e digite o codigo: {}",
                    init.getString("verification_url"), init.getString("user_code"));
            log.warn("Use uma conta Google DESCARTAVEL (ha risco de bloqueio). Aguardando...");

            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(intervalMs);
                JSONObject res = post(TOKEN_URL, new JSONObject()
                        .put("client_id", CLIENT_ID)
                        .put("client_secret", CLIENT_SECRET)
                        .put("code", deviceCode)
                        .put("grant_type", "http://oauth.net/grant_type/device/1.0"));
                String error = res.optString("error", "");
                if (error.equals("authorization_pending") || error.equals("slow_down")) {
                    continue;
                }
                if (!error.isEmpty()) {
                    log.error("Device flow do YouTube falhou: {}", error);
                    return;
                }
                synchronized (this) {
                    refreshToken = res.getString("refresh_token");
                    accessToken = res.getString("access_token");
                    validUntil = System.currentTimeMillis()
                            + res.getLong("expires_in") * 1000 - 60_000;
                }
                log.info("YouTube autorizado!");
                persistRefreshToken(res.getString("refresh_token"));
                return;
            }
            log.warn("Device flow do YouTube expirou sem autorizacao; reinicie o bot pra tentar de novo.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Device flow do YouTube falhou.", e);
        }
    }

    /**
     * Salva o token no .env pra sobreviver a restarts. Se nao der (ex.: bind
     * mount sem permissao de escrita pro usuario do container), imprime o token
     * pro usuario salvar manualmente.
     */
    private void persistRefreshToken(String token) {
        try {
            ChiConfig.saveYoutubeRefreshToken(token);
            log.info("Refresh token salvo no .env — nao vai pedir login de novo.");
        } catch (Exception e) {
            log.warn("Nao consegui salvar o refresh token no .env ({}). "
                    + "Salve manualmente pra nao logar a cada restart:", e.toString());
            log.warn("YOUTUBE_REFRESH_TOKEN={}", token);
        }
    }

    /** POST com corpo JSON; devolve o corpo parseado mesmo em status != 200 (erros vem como JSON). */
    private JSONObject post(String url, JSONObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        return new JSONObject(response.body());
    }
}