package org.chibot.Commands.Music;

import dev.arbjerg.lavalink.client.player.Track;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
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
        return "Mostra a fila~ ou usa o add pra colocar até 100 musiquinhas! (´｡• ᵕ •｡`)";
    }

    @Override
    public String getUsage() {
        return "playlist [add <link ou busca>]";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.STRING, "add",
                "Link ou busca pra adicionar na fila (playlists entram até 100 músicas)", false));
    }

    @Override
    public void execute(CommandContext ctx) {
        String addQuery = resolveAddQuery(ctx);
        if (addQuery != null) {
            if (addQuery.isBlank()) {
                ctx.reply("Me fala o que adicionar~ um link ou o nome da música! (・∀・)");
                return;
            }
            loadAndPlayQuery(ctx, addQuery);
            return;
        }

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

    /**
     * O que adicionar na fila: no slash vem da opcao {@code add}; no prefixo,
     * de {@code !playlist add <resto>}. Null = ninguem pediu add (mostra a fila).
     */
    private static String resolveAddQuery(CommandContext ctx) {
        String option = ctx.getOption("add");
        if (option != null) {
            return option;
        }
        List<String> args = ctx.getArgs();
        if (!args.isEmpty() && args.get(0).equalsIgnoreCase("add")) {
            return String.join(" ", args.subList(1, args.size()));
        }
        return null;
    }
}