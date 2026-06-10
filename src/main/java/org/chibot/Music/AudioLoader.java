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

import java.util.List;

/**
 * Recebe o resultado do {@code link.loadItem(...)} do Lavalink e responde
 * o usuario, enfileirando o que foi encontrado no {@link GuildMusicManager}.
 */
public class AudioLoader extends AbstractAudioLoadResultHandler {

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
        manager.enqueuePlaylist(tracks);
        ctx.replyEmbeds(new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Playlist na fila! ✧･ﾟ")
                .setDescription("Adicionei **" + tracks.size() + "** musiquinhas de *"
                        + result.getInfo().getName() + "*~ (≧◡≦) ♡")
                .build());
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

    private void enqueueAndReply(Track track) {
        boolean queued = manager.enqueue(track);
        EmbedBuilder embed = new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle(queued ? "ﾟ･✧ Entrou na fila! ✧･ﾟ" : "ﾟ･✧ Tocando agora! ✧･ﾟ")
                .setDescription(MusicUi.trackLine(track) + (queued ? "\nposição na fila: `"
                        + manager.getQueueSnapshot().size() + "` ♡" : "\nbora dançar~ ♪(´▽｀)"));
        String artwork = track.getInfo().getArtworkUrl();
        if (artwork != null) {
            embed.setThumbnail(artwork);
        }
        ctx.replyEmbeds(embed.build());
    }
}