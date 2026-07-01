package org.chibot.Music;

import dev.arbjerg.lavalink.client.AbstractAudioLoadResultHandler;
import dev.arbjerg.lavalink.client.player.LoadFailed;
import dev.arbjerg.lavalink.client.player.PlaylistLoaded;
import dev.arbjerg.lavalink.client.player.SearchResult;
import dev.arbjerg.lavalink.client.player.Track;
import dev.arbjerg.lavalink.client.player.TrackLoaded;
import net.dv8tion.jda.api.EmbedBuilder;
import org.chibot.Commands.CommandContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Recebe o resultado do {@code link.loadItem(...)} do Lavalink e responde
 * o usuario, enfileirando o que foi encontrado no {@link GuildMusicManager}.
 */
public class AudioLoader extends AbstractAudioLoadResultHandler {

    private static final Logger log = LoggerFactory.getLogger(AudioLoader.class);

    /** Maximo de faixas que uma playlist pode colocar na fila de uma vez. */
    private static final int MAX_PLAYLIST_TRACKS = 100;

    private final CommandContext ctx;
    private final GuildMusicManager manager;

    public AudioLoader(CommandContext ctx, GuildMusicManager manager) {
        this.ctx = ctx;
        this.manager = manager;
    }

    @Override
    public void ontrackLoaded(@NotNull TrackLoaded result) {
        enqueueAndReply(result.getTrack());
    }

    @Override
    public void onPlaylistLoaded(@NotNull PlaylistLoaded result) {
        List<Track> tracks = result.getTracks();
        if (tracks.isEmpty()) {
            noMatches();
            return;
        }
        boolean truncated = tracks.size() > MAX_PLAYLIST_TRACKS;
        if (truncated) {
            tracks = tracks.subList(0, MAX_PLAYLIST_TRACKS);
        }
        manager.enqueuePlaylist(tracks);
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Playlist na fila! ✧･ﾟ")
                .setDescription("Adicionei **" + tracks.size() + "** musiquinhas de *"
                        + result.getInfo().getName() + "*~ (≧◡≦) ♡"
                        + (truncated ? "\n(a playlist tinha mais, mas o limite é "
                        + MAX_PLAYLIST_TRACKS + " por vez~)" : ""));
        // Se a fila estava parada, o player ainda pode nao ter confirmado a
        // primeira faixa (e assincrono); ela e a que esta comecando agora.
        Track current = manager.getCurrentTrack().orElse(tracks.get(0));
        MusicUi.withNowPlaying(embed, current, manager.getQueueSnapshot());
        ctx.replyEmbeds(embed.build());
    }

    @Override
    public void onSearchResultLoaded(@NotNull SearchResult result) {
        List<Track> tracks = result.getTracks();
        if (tracks.isEmpty()) {
            noMatches();
            return;
        }
        enqueueAndReply(tracks.get(0));
    }

    @Override
    public void noMatches() {
        ctx.reply("Não achei nada com isso~ tenta outra busca? (・_・;)");
    }

    @Override
    public void loadFailed(@NotNull LoadFailed result) {
        ctx.reply("Deu ruim pra carregar essa música~ (；△；) `" + result.getException().getMessage() + "`");
    }

    /** Erro fora do fluxo normal (ex.: node fora do ar/reiniciando). */
    public void onLoadError(Throwable error) {
        log.error("Falha falando com o Lavalink", error);
        ctx.reply("Não consegui falar com o servidor de música~ (´；ω；`) tenta de novo em instantes!");
    }

    private void enqueueAndReply(Track track) {
        boolean queued = manager.enqueue(track);
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle(queued ? "ﾟ･✧ Entrou na fila! ✧･ﾟ" : "ﾟ･✧ Tocando agora! ✧･ﾟ")
                .setDescription(MusicUi.trackLine(track) + (queued ? "\nposição na fila: `"
                        + manager.getQueueSnapshot().size() + "` ♡" : "\nbora dançar~ ♪(´▽｀)"));
        ctx.replyEmbeds(MusicUi.withArtwork(embed, track).build());
    }
}