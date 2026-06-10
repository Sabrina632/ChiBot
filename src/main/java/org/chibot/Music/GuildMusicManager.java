package org.chibot.Music;

import dev.arbjerg.lavalink.client.LavalinkClient;
import dev.arbjerg.lavalink.client.Link;
import dev.arbjerg.lavalink.client.player.LavalinkPlayer;
import dev.arbjerg.lavalink.client.player.Track;
import dev.arbjerg.lavalink.protocol.v4.Message.EmittedEvent.TrackEndEvent.AudioTrackEndReason;

import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Estado da musica de um servidor: a fila de proximas faixas e os atalhos
 * pro player que vive no Lavalink (via {@link Link}).
 */
public class GuildMusicManager {

    private static final int DEFAULT_VOLUME = 50;

    private final long guildId;
    private final LavalinkClient client;
    private final Queue<Track> queue = new ConcurrentLinkedQueue<>();

    GuildMusicManager(long guildId, LavalinkClient client) {
        this.guildId = guildId;
        this.client = client;
    }

    private Link getLink() {
        return client.getOrCreateLink(guildId);
    }

    /** Pede pro Lavalink resolver um link/busca e entrega o resultado pro handler. */
    public void loadAndPlay(String identifier, AudioLoader loader) {
        getLink().loadItem(identifier).subscribe(loader);
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

    /** Toca ja se nada estiver tocando; senao entra na fila. Retorna true se entrou na fila. */
    public boolean enqueue(Track track) {
        if (isPlaying()) {
            queue.offer(track);
            return true;
        }
        startTrack(track);
        return false;
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
    }

    /** Pula pra proxima da fila. Retorna a nova faixa, ou empty se a fila acabou (e o player para). */
    public Optional<Track> skip() {
        Track next = queue.poll();
        if (next != null) {
            startTrack(next);
            return Optional.of(next);
        }
        getPlayer().ifPresent(player -> player.setTrack(null).subscribe());
        return Optional.empty();
    }

    /** Para tudo: limpa a fila e zera o player. */
    public void stop() {
        queue.clear();
        getPlayer().ifPresent(player -> player.setPaused(false).setTrack(null).subscribe());
    }

    public void setPaused(boolean paused) {
        getPlayer().ifPresent(player -> player.setPaused(paused).subscribe());
    }

    /** Chamado pelo MusicService quando o Lavalink avisa que a faixa terminou. */
    void onTrackEnd(AudioTrackEndReason endReason) {
        if (!endReason.getMayStartNext()) {
            return;
        }
        Track next = queue.poll();
        if (next != null) {
            startTrack(next);
        }
    }

    private void startTrack(Track track) {
        getLink().createOrUpdatePlayer()
                .setTrack(track)
                .setVolume(DEFAULT_VOLUME)
                .subscribe();
    }
}