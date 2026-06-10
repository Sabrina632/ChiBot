package org.chibot.Commands.Music;

import dev.arbjerg.lavalink.client.player.Track;
import net.dv8tion.jda.api.EmbedBuilder;
import org.chibot.Commands.CommandContext;
import org.chibot.Music.GuildMusicManager;
import org.chibot.Music.MusicUi;

import java.util.List;

public class PlaylistCommand extends MusicCommand {

    private static final int MAX_SHOWN = 10;

    @Override
    public String getName() {
        return "playlist";
    }

    @Override
    public List<String> getAliases() {
        return List.of("queue", "fila", "q");
    }

    @Override
    public String getDescription() {
        return "Mostra o que tá tocando e a fila de musiquinhas~ (´｡• ᵕ •｡`)";
    }

    @Override
    public void execute(CommandContext ctx) {
        GuildMusicManager manager = getManager(ctx);
        var current = manager.getCurrentTrack();
        if (current.isEmpty()) {
            ctx.reply("Não tô tocando nada agora~ usa o play pra começar! (・∀・)");
            return;
        }

        EmbedBuilder embed = new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Fila de musiquinhas ✧･ﾟ")
                .addField("♪ Tocando agora", MusicUi.trackLine(current.get()), false);

        List<Track> queue = manager.getQueueSnapshot();
        if (queue.isEmpty()) {
            embed.setDescription("A fila tá vazia~ aproveita a música! ♡");
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < queue.size() && i < MAX_SHOWN; i++) {
                sb.append('`').append(i + 1).append(".` ")
                        .append(MusicUi.trackLine(queue.get(i))).append('\n');
            }
            if (queue.size() > MAX_SHOWN) {
                sb.append("...e mais **").append(queue.size() - MAX_SHOWN).append("** musiquinha(s)~ ♡");
            }
            embed.addField("☆ Na fila (" + queue.size() + ")", sb.toString(), false);
        }

        ctx.replyEmbeds(embed.build());
    }
}