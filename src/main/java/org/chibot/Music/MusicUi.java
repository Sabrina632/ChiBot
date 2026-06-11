package org.chibot.Music;

import dev.arbjerg.lavalink.client.player.Track;
import net.dv8tion.jda.api.EmbedBuilder;

import java.awt.Color;
import java.util.List;

/** Pedacinhos visuais compartilhados pelos comandos de musica. */
public final class MusicUi {

    public static final Color KAWAII_PINK = new Color(0xFFB6C1);

    /** Quantas faixas da fila aparecem na lista dos embeds. */
    private static final int QUEUE_SHOWN = 10;

    /** Campo da fila tem limite de 1024 chars no Discord; paramos antes disso. */
    private static final int QUEUE_FIELD_BUDGET = 900;

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

    /**
     * Acrescenta no embed o "Tocando agora" (com a capa como thumbnail) e a
     * lista das proximas da fila. Compartilhado pelo comando playlist e pela
     * resposta de playlist adicionada.
     */
    public static EmbedBuilder withNowPlaying(EmbedBuilder embed, Track current, List<Track> queue) {
        embed.addField("♪ Tocando agora", trackLine(current), false);
        String artwork = current.getInfo().getArtworkUrl();
        if (artwork != null) {
            embed.setThumbnail(artwork);
        }
        if (queue.isEmpty()) {
            return embed;
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Track track : queue) {
            if (shown >= QUEUE_SHOWN) {
                break;
            }
            String line = "`" + (shown + 1) + ".` " + trackLine(track) + "\n";
            if (sb.length() + line.length() > QUEUE_FIELD_BUDGET) {
                break;
            }
            sb.append(line);
            shown++;
        }
        int rest = queue.size() - shown;
        if (rest > 0) {
            sb.append("...e mais **").append(rest).append("** musiquinha(s)~ ♡");
        }
        embed.addField("☆ Na fila (" + queue.size() + ")", sb.toString(), false);
        return embed;
    }
}