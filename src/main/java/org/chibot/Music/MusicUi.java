package org.chibot.Music;

import dev.arbjerg.lavalink.client.player.Track;
import net.dv8tion.jda.api.EmbedBuilder;

import java.awt.Color;

/** Pedacinhos visuais compartilhados pelos comandos de musica. */
public final class MusicUi {

    public static final Color KAWAII_PINK = new Color(0xFFB6C1);

    private MusicUi() {
    }

    /** "3:07" ou "1:02:45" a partir de milissegundos. */
    public static String formatDuration(long millis) {
        long totalSeconds = millis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    /** Aplica a capa da track como imagem grande do embed (se houver). */
    public static EmbedBuilder withArtwork(EmbedBuilder embed, Track track) {
        String artwork = track.getInfo().getArtworkUrl();
        if (artwork != null) {
            embed.setImage(artwork);
        }
        return embed;
    }

    /** "[Titulo](url) `(3:07)`" pra usar em embeds. */
    public static String trackLine(Track track) {
        var info = track.getInfo();
        String duration = info.isStream() ? "ao vivo" : formatDuration(info.getLength());
        return "[" + info.getTitle() + "](" + info.getUri() + ") `(" + duration + ")`";
    }
}