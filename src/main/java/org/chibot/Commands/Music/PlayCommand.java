package org.chibot.Commands.Music;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;

import java.util.List;

public class PlayCommand extends MusicCommand {

    @Override
    public String getName() {
        return "play";
    }

    @Override
    public String getCategory() {
        return "Música";
    }

    @Override
    public List<String> getAliases() {
        return List.of("p", "tocar");
    }

    @Override
    public String getDescription() {
        return "Toca uma musiquinha~ aceita link ou busca no YouTube ♪(´▽｀)";
    }

    @Override
    public String getUsage() {
        return "play <link ou busca>";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.STRING, "musica",
                "Link (YouTube, SoundCloud...) ou termo de busca", true));
    }

    @Override
    public void execute(CommandContext ctx) {
        String query = ctx.getOption("musica");
        if (query == null || query.isBlank()) {
            query = String.join(" ", ctx.getArgs());
        }
        if (query.isBlank()) {
            ctx.reply("Me fala o que tocar~ um link ou o nome da música! (・∀・)");
            return;
        }
        loadAndPlayQuery(ctx, query);
    }
}