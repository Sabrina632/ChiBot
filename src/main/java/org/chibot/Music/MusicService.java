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
    private final Map<Long, GuildMusicManager> managers = new ConcurrentHashMap<>();

    private MusicService(ChiConfig config) {
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
                log.warn("Erro ao tocar '{}' no servidor {}: {}",
                        event.getTrack().getInfo().getTitle(), event.getGuildId(),
                        event.getException().getMessage()));
    }

    /** Interceptor que desvia os eventos de voz do JDA pro Lavalink. */
    public VoiceDispatchInterceptor getVoiceInterceptor() {
        return new JDAVoiceUpdateListener(client);
    }

    public GuildMusicManager getManager(long guildId) {
        return managers.computeIfAbsent(guildId, id -> new GuildMusicManager(id, client));
    }
}