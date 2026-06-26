package org.chibot.Config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChiConfig {

    private static final Logger log = LoggerFactory.getLogger(ChiConfig.class);
    private static final Path ENV_PATH = Paths.get(".env");

    private static volatile ChiConfig loaded;

    private final String token;
    private final String prefix;
    private final String guildId;
    private final String lavalinkUri;
    private final String lavalinkPassword;
    private final String youtubeApiKey;
    private final String youtubeRefreshToken;
    private final String ownerId;
    private final String deeplApiKey;

    private ChiConfig(String token, String prefix, String guildId,
                      String lavalinkUri, String lavalinkPassword,
                      String youtubeApiKey, String youtubeRefreshToken,
                      String ownerId,
                      String deeplApiKey) {

        this.token = token;
        this.prefix = prefix;
        this.guildId = guildId;
        this.lavalinkUri = lavalinkUri;
        this.lavalinkPassword = lavalinkPassword;
        this.youtubeApiKey = youtubeApiKey;
        this.youtubeRefreshToken = youtubeRefreshToken;
        this.ownerId = ownerId;
        this.deeplApiKey = deeplApiKey;
    }

    public static ChiConfig load() throws IOException {
        if (Files.notExists(ENV_PATH)) {
            createDefault();
            log.warn(".env nao encontrado. Um arquivo padrao foi criado em {}. " +
                    "Preencha o DISCORD_TOKEN e reinicie o bot.", ENV_PATH.toAbsolutePath());
        }

        Map<String, String> env = parse(ENV_PATH);

        String token = value(env, "DISCORD_TOKEN", "");
        String prefix = value(env, "PREFIX", "!");
        String guildId = value(env, "GUILD_ID", "");
        String lavalinkUri = value(env, "LAVALINK_URI", "ws://localhost:2333");
        String lavalinkPassword = value(env, "LAVALINK_PASSWORD", "youshallnotpass");
        String youtubeApiKey = value(env, "YOUTUBE_API_KEY", "");
        String youtubeRefreshToken = value(env, "YOUTUBE_REFRESH_TOKEN", "");
        String ownerId = value(env, "OWNER_ID", "");
        String deeplApiKey = value(env, "DEEPL_API_KEY", "");

        ChiConfig config = new ChiConfig(token, prefix, guildId, lavalinkUri, lavalinkPassword,
                youtubeApiKey, youtubeRefreshToken, ownerId,
                deeplApiKey);
        loaded = config;
        return config;
    }

    /** Config carregada no boot (pro help mostrar o prefixo), ou null antes do load. */
    public static ChiConfig get() {
        return loaded;
    }

    /**
     * Persiste o refresh token do YouTube no .env pra sobreviver a restarts —
     * chamado pelo YtOauth depois do device flow, pra nao pedir login a cada
     * restart. Preserva o resto do arquivo (demais chaves e comentarios).
     */
    public static synchronized void saveYoutubeRefreshToken(String refreshToken) throws IOException {
        upsert("YOUTUBE_REFRESH_TOKEN", refreshToken);
    }

    /**
     * Valor da configuracao: a variavel de ambiente do processo tem prioridade
     * (ex.: o que o docker compose injeta), senao o que estiver no .env, senao
     * o default.
     */
    private static String value(Map<String, String> env, String key, String def) {
        String fromProcess = System.getenv(key);
        if (fromProcess != null && !fromProcess.isBlank()) {
            return fromProcess;
        }
        String fromFile = env.get(key);
        if (fromFile != null && !fromFile.isBlank()) {
            return fromFile;
        }
        return def;
    }

    /** Le um .env simples: KEY=valor por linha, ignorando vazias e # comentarios. */
    private static Map<String, String> parse(Path path) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).trim();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String val = unquote(line.substring(eq + 1).trim());
            map.put(key, val);
        }
        return map;
    }

    /** Remove aspas simples/duplas em volta do valor, se houver. */
    private static String unquote(String val) {
        if (val.length() >= 2 &&
                ((val.startsWith("\"") && val.endsWith("\"")) ||
                 (val.startsWith("'") && val.endsWith("'")))) {
            return val.substring(1, val.length() - 1);
        }
        return val;
    }

    /** Atualiza (ou adiciona) uma chave no .env, preservando o resto do arquivo. */
    private static void upsert(String key, String val) throws IOException {
        List<String> lines = Files.exists(ENV_PATH)
                ? new ArrayList<>(Files.readAllLines(ENV_PATH, StandardCharsets.UTF_8))
                : new ArrayList<>();
        String newLine = key + "=" + val;
        boolean replaced = false;
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith(key + "=") || trimmed.startsWith("export " + key + "=")) {
                lines.set(i, newLine);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            lines.add(newLine);
        }
        Files.writeString(ENV_PATH, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private static void createDefault() throws IOException {
        String content = String.join("\n",
                "# ─── ChiBot ───────────────────────────────────────────────",
                "# Token do bot no Discord Developer Portal (obrigatorio).",
                "DISCORD_TOKEN=",
                "",
                "# Prefixo dos comandos de texto.",
                "PREFIX=!",
                "",
                "# Servidor (guild) onde os slash commands sao registrados na hora.",
                "# Vazio = registro global (pode levar ate ~1h pra propagar).",
                "GUILD_ID=",
                "",
                "# ID do dono do bot: unico que pode usar comandos restritos (ex.: manutencao).",
                "# Vazio = ninguem tem acesso a esses comandos.",
                "OWNER_ID=",
                "",
                "# ─── Musica (Lavalink) ────────────────────────────────────",
                "# No Docker da VPS use ws://lavalink:2333.",
                "LAVALINK_URI=ws://localhost:2333",
                "LAVALINK_PASSWORD=youshallnotpass",
                "",
                "# Chave da YouTube Data API v3 (opcional): melhora a busca por nome.",
                "# Sem ela, a busca cai no ytsearch do Lavalink.",
                "YOUTUBE_API_KEY=",
                "",
                "# Login do YouTube (opcional, mas necessario em IP de datacenter):",
                "# deixe vazio e o bot mostra no log o codigo do google.com/device e",
                "# depois salva o refresh token aqui sozinho (veja YtOauth).",
                "YOUTUBE_REFRESH_TOKEN=",
                "",
                "# ─── Tradução (DeepL) ─────────────────────────────────────",
                "# Chave da API da DeepL (conta gratuita termina em ':fx').",
                "# Vazio = tradução desligada (tudo em pt).",
                "DEEPL_API_KEY=",
                "");
        Files.writeString(ENV_PATH, content, StandardCharsets.UTF_8);
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

    /** ID do dono do bot (comandos restritos, ex.: manutencao). Vazio = nao configurado. */
    public String getOwnerId() {
        return ownerId;
    }

    /** Chave da API da DeepL (traducao por usuario). Vazia = traducao desligada. */
    public String getDeeplApiKey() {
        return deeplApiKey;
    }
}