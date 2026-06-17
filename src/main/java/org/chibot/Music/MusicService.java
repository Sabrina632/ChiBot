package org.chibot.Music;

import dev.arbjerg.lavalink.client.Helpers;
import dev.arbjerg.lavalink.client.LavalinkClient;
import dev.arbjerg.lavalink.client.LavalinkNode;
import dev.arbjerg.lavalink.client.NodeOptions;
import dev.arbjerg.lavalink.client.event.ReadyEvent;
import dev.arbjerg.lavalink.client.event.TrackEndEvent;
import dev.arbjerg.lavalink.client.event.TrackExceptionEvent;
import dev.arbjerg.lavalink.client.event.TrackStartEvent;
import dev.arbjerg.lavalink.libraries.jda.JDAVoiceUpdateListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.hooks.VoiceDispatchInterceptor;
import org.chibot.Config.ChiConfig;
import org.chibot.Database.MusicRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final MusicRepository repo = new MusicRepository();
    private final Map<Long, GuildMusicManager> managers = new ConcurrentHashMap<>();

    /** JDA, setado pelo ChiBot quando o bot fica pronto (precisa pra reconectar nos canais). */
    private volatile JDA jda;
    /** Garante que o restore das filas salvas rode uma unica vez. */
    private final AtomicBoolean restored = new AtomicBoolean(false);

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
        client.on(ReadyEvent.class).subscribe(event -> {
            log.info("Node Lavalink '{}' conectado.", event.getNode().getName());
            // O node ficou pronto: tenta retomar as filas que sobreviveram ao
            // restart (so roda de fato quando o JDA tambem ja estiver pronto).
            tryRestore(event.getNode());
        });

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
        return managers.computeIfAbsent(guildId, id -> new GuildMusicManager(id, client, ytOauth, repo));
    }

    public MusicRepository getRepo() {
        return repo;
    }

    /**
     * Informado pelo ChiBot quando o bot fica pronto. Como o restore precisa do
     * JDA (pra reconectar nos canais) e de um node disponivel, ele dispara aqui
     * e tambem no {@link ReadyEvent} do node — o que acontecer por ultimo vence.
     */
    public void setJda(JDA jda) {
        this.jda = jda;
        tryRestore(firstAvailableNode());
    }

    private LavalinkNode firstAvailableNode() {
        for (LavalinkNode node : client.getNodes()) {
            if (node.getAvailable()) {
                return node;
            }
        }
        return null;
    }

    /** Restaura as filas salvas se (e quando) JDA e um node disponivel existirem — uma vez so. */
    private void tryRestore(LavalinkNode node) {
        if (jda == null || node == null || !node.getAvailable()) {
            return;
        }
        if (!restored.compareAndSet(false, true)) {
            return;
        }
        try {
            restoreQueues(node);
        } catch (Exception e) {
            log.warn("Falha ao restaurar as filas de musica salvas.", e);
        }
    }

    /**
     * Pra cada servidor com fila salva, reconecta no canal de voz onde o bot
     * estava (se ele ainda existe e tem gente) e retoma a faixa atual + a fila.
     * A retomada comeca a faixa do inicio (a posicao exata nao e guardada).
     */
    private void restoreQueues(LavalinkNode node) {
        for (String guildId : repo.guildsWithQueue()) {
            List<MusicRepository.StoredTrack> stored = repo.loadQueue(guildId);
            if (stored.isEmpty()) {
                continue;
            }
            String voiceId = repo.voiceChannelId(guildId);
            Guild guild = guildId == null ? null : jda.getGuildById(guildId);
            if (voiceId == null || guild == null) {
                continue;
            }
            VoiceChannel voice = guild.getVoiceChannelById(voiceId);
            if (voice == null) {
                continue;
            }
            // So volta a tocar se ainda tem gente (humano) no canal.
            boolean temGente = voice.getMembers().stream().anyMatch(m -> !m.getUser().isBot());
            if (!temGente) {
                continue;
            }

            List<String> encoded = stored.stream().map(MusicRepository.StoredTrack::encoded).toList();
            String textId = repo.textChannelId(guildId);
            node.decodeTracks(encoded).subscribe(tracks -> {
                jda.getDirectAudioController().connect(voice);
                GuildMusicManager manager = getManager(guild.getIdLong());
                manager.rememberChannels(voiceId, textId);
                manager.restoreQueue(tracks);
                log.info("Fila de musica restaurada em {} ({} faixa(s)).", guild.getName(), tracks.size());
            }, err -> log.warn("Falha ao decodificar a fila salva de {}.", guildId, err));
        }
    }
}