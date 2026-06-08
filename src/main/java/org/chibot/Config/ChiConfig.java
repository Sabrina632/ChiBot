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

    private ChiConfig(String token, String prefix, String guildId) {
        this.token = token;
        this.prefix = prefix;
        this.guildId = guildId;
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

        return new ChiConfig(token, prefix, guildId);
    }

    private static void createDefault() throws IOException {
        JSONObject json = new JSONObject();
        json.put("Token", "");
        json.put("Prefix", "!");
        json.put("GuildId", "");
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
}