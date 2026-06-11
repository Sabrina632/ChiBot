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
                "Nome ou link de uma playlist do YouTube (entram até 100 músicas)", false));
    }

    @Override
    public void execute(CommandContext ctx) {
        String addQuery = resolveAddQuery(ctx);
        if (addQuery != null) {
            if (addQuery.isBlank()) {
                ctx.reply("Me fala o que adicionar~ o nome ou o link de uma playlist! (・∀・)");
                return;
            }
            loadAndPlayPlaylistQuery(ctx, addQuery);
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
                .setTitle("ﾟ･✧ Fila de musiquinhas ✧･ﾟ");

        List<Track> queue = manager.getQueueSnapshot();
        if (queue.isEmpty()) {
            embed.setDescription("A fila tá vazia~ aproveita a música! ♡");
        }
        MusicUi.withNowPlaying(embed, current.get(), queue);

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