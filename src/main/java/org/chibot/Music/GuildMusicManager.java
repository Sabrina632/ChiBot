package org.chibot.Music;

import dev.arbjerg.lavalink.client.LavalinkClient;
import dev.arbjerg.lavalink.client.Link;
import dev.arbjerg.lavalink.client.player.LavalinkPlayer;
import dev.arbjerg.lavalink.client.player.Track;
import dev.arbjerg.lavalink.protocol.v4.Message.EmittedEvent.TrackEndEvent.AudioTrackEndReason;
import org.chibot.Database.MusicRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Estado da musica de um servidor: a fila de proximas faixas e os atalhos
 * pro player que vive no Lavalink (via {@link Link}).
 *
 * <p>A fila, a faixa atual, o volume e o canal de voz/texto sao persistidos no
 * {@link MusicRepository} a cada mudanca, pro bot voltar de onde parou depois
 * de um redeploy (ver {@code MusicService#restoreQueues}).
 */
public class GuildMusicManager {

    public static final int DEFAULT_VOLUME = 50;
    public static final int MAX_VOLUME = 200;

    private final long guildId;
    private final LavalinkClient client;
    private final YtOauth oauth;
    private final MusicRepository repo;
    private final Queue<Track> queue = new ConcurrentLinkedQueue<>();

    /** Faixa tocando agora — mantida em campo (alem do player do Lavalink) pra persistir a fila. */
    private volatile Track current;
    /** Volume do servidor, carregado do banco na primeira vez (-1 = ainda nao carregado). */
    private volatile int volume = -1;
    /** Onde o bot esta tocando, pra reconectar no restore. */
    private volatile String voiceChannelId;
    private volatile String textChannelId;

    GuildMusicManager(long guildId, LavalinkClient client, YtOauth oauth, MusicRepository repo) {
        this.guildId = guildId;
        this.client = client;
        this.oauth = oauth;
        this.repo = repo;
    }

    private Link getLink() {
        return client.getOrCreateLink(guildId);
    }

    /** Pede pro Lavalink resolver um link/busca e entrega o resultado pro handler. */
    public void loadAndPlay(String identifier, AudioLoader loader) {
        // Sem o segundo argumento, erros como "Node is not available" (node
        // fora/reiniciando) estouram como onErrorDropped e o usuario fica sem
        // resposta nenhuma no Discord.
        getLink().loadItem(identifier).subscribe(loader, loader::onLoadError);
    }

    /** Player em cache (null/empty se nunca tocou nada nesse servidor). */
    public Optional<LavalinkPlayer> getPlayer() {
        return Optional.ofNullable(getLink().getCachedPlayer());
    }

    /** Faixa tocando agora, se houver. */
    public Optional<Track> getCurrentTrack() {
        return getPlayer().map(LavalinkPlayer::getTrack);
    }

    public boolean isPlaying() {
        return getCurrentTrack().isPresent();
    }

    /** Copia da fila (so leitura, pro comando de playlist). */
    public List<Track> getQueueSnapshot() {
        return List.copyOf(queue);
    }

    /** Volume atual do servidor (carregado do banco na primeira chamada). */
    public int getVolume() {
        if (volume < 0) {
            volume = repo.getVolume(String.valueOf(guildId), DEFAULT_VOLUME);
        }
        return volume;
    }

    /** Define o volume (0..{@link #MAX_VOLUME}), aplica no player atual e persiste. */
    public void setVolume(int value) {
        volume = Math.max(0, Math.min(MAX_VOLUME, value));
        repo.setVolume(String.valueOf(guildId), volume);
        getPlayer().ifPresent(player -> player.setVolume(volume).subscribe());
    }

    /** Lembra onde o bot esta tocando, pro restore reconectar no canal certo. */
    public void rememberChannels(String voiceChannelId, String textChannelId) {
        this.voiceChannelId = voiceChannelId;
        this.textChannelId = textChannelId;
    }

    /** Toca ja se nada estiver tocando; senao entra na fila. Retorna true se entrou na fila. */
    public boolean enqueue(Track track) {
        boolean queued;
        if (isPlaying()) {
            queue.offer(track);
            queued = true;
        } else {
            startTrack(track);
            queued = false;
        }
        persist();
        return queued;
    }

    /** Enfileira a playlist inteira e comeca a tocar se estiver parado. */
    public void enqueuePlaylist(List<Track> tracks) {
        queue.addAll(tracks);
        if (!isPlaying()) {
            Track next = queue.poll();
            if (next != null) {
                startTrack(next);
            }
        }
        persist();
    }

    /**
     * Reconstroi a fila a partir do snapshot salvo (posicao 0 = tocando agora):
     * comeca a faixa atual e enfileira o resto. Usado pelo restore no boot.
     */
    public void restoreQueue(List<Track> tracks) {
        if (tracks.isEmpty()) {
            return;
        }
        queue.clear();
        for (int i = 1; i < tracks.size(); i++) {
            queue.offer(tracks.get(i));
        }
        startTrack(tracks.get(0));
    }

    /**
     * Decodifica faixas no formato encoded (vindas de uma playlist salva) e as
     * enfileira. O decode e uma chamada REST ao Lavalink (so base64, nao resolve
     * o link de novo). Entrega as faixas carregadas no callback de sucesso.
     */
    public void enqueueEncoded(List<String> encoded, Consumer<List<Track>> onLoaded, Consumer<Throwable> onError) {
        getLink().getNode().decodeTracks(encoded).subscribe(tracks -> {
            enqueuePlaylist(tracks);
            onLoaded.accept(tracks);
        }, onError);
    }

    /** Pula pra proxima da fila. Retorna a nova faixa, ou empty se a fila acabou (e o player para). */
    public Optional<Track> skip() {
        Track next = queue.poll();
        if (next != null) {
            startTrack(next);
            persist();
            return Optional.of(next);
        }
        getPlayer().ifPresent(player -> player.setTrack(null).subscribe());
        current = null;
        persist();
        return Optional.empty();
    }

    /** Para tudo: limpa a fila e zera o player. */
    public void stop() {
        queue.clear();
        getPlayer().ifPresent(player -> player.setPaused(false).setTrack(null).subscribe());
        current = null;
        persist();
    }

    public void setPaused(boolean paused) {
        getPlayer().ifPresent(player -> player.setPaused(paused).subscribe());
    }

    /** Chamado pelo MusicService quando o Lavalink avisa que a faixa terminou. */
    void onTrackEnd(AudioTrackEndReason endReason) {
        if (!endReason.getMayStartNext()) {
            // REPLACED/STOPPED: foi a gente que trocou/parou — ja persistimos la.
            return;
        }
        Track next = queue.poll();
        if (next != null) {
            startTrack(next);
        } else {
            current = null;
        }
        persist();
    }

    private void startTrack(Track track) {
        current = track;
        // Modo "client-provided token" do youtube-source: a track leva o
        // access token da conta logada no userData e o node streama como
        // usuario autenticado (contorna o bloqueio de IP de datacenter).
        if ("youtube".equals(track.getInfo().getSourceName())) {
            String token = oauth.accessToken();
            if (token != null) {
                track.setUserData(Map.of("oauth-token", token));
            }
        }
        getLink().createOrUpdatePlayer()
                .setTrack(track)
                .setVolume(getVolume())
                .subscribe();
    }

    /** Salva o snapshot atual da fila (faixa atual + proximas) e os canais no banco. */
    private void persist() {
        List<MusicRepository.StoredTrack> tracks = new ArrayList<>();
        Track c = current;
        if (c != null) {
            tracks.add(toStored(c));
        }
        for (Track t : queue) {
            tracks.add(toStored(t));
        }
        repo.saveSession(String.valueOf(guildId), voiceChannelId, textChannelId, tracks);
    }

    private static MusicRepository.StoredTrack toStored(Track track) {
        return new MusicRepository.StoredTrack(track.getEncoded(), track.getInfo().getTitle());
    }
}
