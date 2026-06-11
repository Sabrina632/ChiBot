package org.chibot.Music;

import dev.arbjerg.lavalink.client.Helpers;
import dev.arbjerg.lavalink.client.LavalinkClient;
import dev.arbjerg.lavalink.client.NodeOptions;
import dev.arbjerg.lavalink.client.event.ReadyEvent;
import dev.arbjerg.lavalink.client.event.TrackEndEvent;
import dev.arbjerg.lavalink.client.event.TrackExceptionEvent;
import dev.arbjerg.lavalink.client.event.TrackStartEvent;
import dev.arbjerg.lavalink.libraries.jda.JDAVoiceUpdateListener;
import net.dv8tion.jda.api.hooks.VoiceDispatchInterceptor;
import org.chibot.Config.ChiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ponto central da musica: segura o {@link LavalinkClient} (conexao com o servidor
 * Lavalink, que e quem de fato toca o audio) e um {@link GuildMusicManager} por servidor.
 *
 * E um singleton inicializado em ChiBot.start() porque os comandos sao instanciados
 * por reflexao com construtor vazio e precisam de um jeito de chegar ate aqui.
 */
public final class MusicService {

    private static final Logger log = LoggerFactory.getLogger(MusicService.class);
    private static MusicService instance;

    private final LavalinkClient client;
    private final YtSearch ytSearch;
    private final YtOauth ytOauth;
    private final Map<Long, GuildMusicManager> managers = new ConcurrentHashMap<>();

    private MusicService(ChiConfig config) {
        String apiKey = config.getYoutubeApiKey();
        ytSearch = apiKey == null || apiKey.isBlank() ? null : new YtSearch(apiKey);
        ytOauth = new YtOauth(config.getYoutubeRefreshToken());
        ytOauth.startDeviceFlowIfNeeded();
        client = new LavalinkClient(Helpers.getUserIdFromToken(config.getToken()));
        client.addNode(new NodeOptions.Builder()
                .setName("principal")
                .setServerUri(config.getLavalinkUri())
                .setPassword(config.getLavalinkPassword())
                .build());
        subscribeEvents();
    }

    /** Cria o servico (uma unica vez, antes de construir o JDA). */
    public static synchronized MusicService init(ChiConfig config) {
        if (instance == null) {
            instance = new MusicService(config);
        }
        return instance;
    }

    public static MusicService get() {
        if (instance == null) {
            throw new IllegalStateException("MusicService nao foi inicializado ainda.");
        }
        return instance;
    }

    private void subscribeEvents() {
        client.on(ReadyEvent.class).subscribe(event ->
                log.info("Node Lavalink '{}' conectado.", event.getNode().getName()));

        client.on(TrackStartEvent.class).subscribe(event ->
                log.info("Tocando '{}' no servidor {}.",
                        event.getTrack().getInfo().getTitle(), event.getGuildId()));

        client.on(TrackEndEvent.class).subscribe(event -> {
            GuildMusicManager manager = managers.get(event.getGuildId());
            if (manager != null) {
                manager.onTrackEnd(event.getEndReason());
            }
        });

        client.on(TrackExceptionEvent.class).subscribe(event ->
                // A mensagem do Lavalink e generica ("Something broke...");
                // a causa e quem diz o motivo real (403, sign-in, etc).
                log.warn("Erro ao tocar '{}' no servidor {} [{}]: {} — causa: {}",
                        event.getTrack().getInfo().getTitle(), event.getGuildId(),
                        event.getException().getSeverity(),
                        event.getException().getMessage(),
                        event.getException().getCause()));
    }

    /** Busca via YouTube Data API, ou null se nao tiver chave configurada. */
    public YtSearch getYtSearch() {
        return ytSearch;
    }

    /** Interceptor que desvia os eventos de voz do JDA pro Lavalink. */
    public VoiceDispatchInterceptor getVoiceInterceptor() {
        return new JDAVoiceUpdateListener(client);
    }

    public GuildMusicManager getManager(long guildId) {
        return managers.computeIfAbsent(guildId, id -> new GuildMusicManager(id, client, ytOauth));
    }
}