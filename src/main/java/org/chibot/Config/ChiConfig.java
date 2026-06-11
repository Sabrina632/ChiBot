package org.chibot.Config;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ChiConfig {

    private static final Logger log = LoggerFactory.getLogger(ChiConfig.class);
    private static final Path CONFIG_PATH = Paths.get("ChiConfig.json");

    private final String token;
    private final String prefix;
    private final String guildId;
    private final String lavalinkUri;
    private final String lavalinkPassword;
    private final String youtubeApiKey;
    private final String youtubeRefreshToken;

    private ChiConfig(String token, String prefix, String guildId,
                      String lavalinkUri, String lavalinkPassword,
                      String youtubeApiKey, String youtubeRefreshToken) {
        this.token = token;
        this.prefix = prefix;
        this.guildId = guildId;
        this.lavalinkUri = lavalinkUri;
        this.lavalinkPassword = lavalinkPassword;
        this.youtubeApiKey = youtubeApiKey;
        this.youtubeRefreshToken = youtubeRefreshToken;
    }

    public static ChiConfig load() throws IOException {
        if (Files.notExists(CONFIG_PATH)) {
            createDefault();
            log.warn("ChiConfig.json nao encontrado. Um arquivo padrao foi criado em {}. " +
                    "Preencha o token e reinicie o bot.", CONFIG_PATH.toAbsolutePath());
        }

        String content = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(content);

        String token = json.optString("Token", "");
        String prefix = json.optString("Prefix", "!");
        String guildId = json.optString("GuildId", "");
        String lavalinkUri = json.optString("LavalinkUri", "ws://localhost:2333");
        String lavalinkPassword = json.optString("LavalinkPassword", "youshallnotpass");
        String youtubeApiKey = json.optString("YoutubeApiKey", "");
        String youtubeRefreshToken = json.optString("YoutubeRefreshToken", "");

        return new ChiConfig(token, prefix, guildId, lavalinkUri, lavalinkPassword,
                youtubeApiKey, youtubeRefreshToken);
    }

    private static void createDefault() throws IOException {
        JSONObject json = new JSONObject();
        json.put("Token", "");
        json.put("Prefix", "!");
        json.put("GuildId", "");
        // Servidor Lavalink (musica). No Docker da VPS use ws://lavalink:2333.
        json.put("LavalinkUri", "ws://localhost:2333");
        json.put("LavalinkPassword", "youshallnotpass");
        // Chave da YouTube Data API v3 (opcional): melhora a busca por nome.
        // Sem ela, a busca cai no ytsearch do Lavalink.
        json.put("YoutubeApiKey", "");
        // Login do YouTube (opcional, mas necessario em IP de datacenter):
        // deixe vazio e o bot mostra no log o codigo do google.com/device e
        // depois o refresh token pra colar aqui (veja YtOauth).
        json.put("YoutubeRefreshToken", "");
        Files.writeString(CONFIG_PATH, json.toString(4), StandardCharsets.UTF_8);
    }

    public boolean isTokenPresent() {
        return token != null && !token.isBlank();
    }

    public String getToken() {
        return token;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getGuildId() {
        return guildId;
    }

    public String getLavalinkUri() {
        return lavalinkUri;
    }

    public String getLavalinkPassword() {
        return lavalinkPassword;
    }

    public String getYoutubeApiKey() {
        return youtubeApiKey;
    }

    public String getYoutubeRefreshToken() {
        return youtubeRefreshToken;
    }
}