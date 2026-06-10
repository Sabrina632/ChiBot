package org.chibot.Commands.Music;

import net.dv8tion.jda.api.EmbedBuilder;
import org.chibot.Commands.CommandContext;
import org.chibot.Music.MusicUi;

import java.util.List;

public class SkipCommand extends MusicCommand {

    @Override
    public String getName() {
        return "skip";
    }

    @Override
    public List<String> getAliases() {
        return List.of("pular", "s");
    }

    @Override
    public String getDescription() {
        return "Pula pra próxima musiquinha da fila~ (ﾉ´ヮ`)ﾉ*:･ﾟ✧";
    }

    @Override
    public void execute(CommandContext ctx) {
        if (!requirePlaying(ctx)) {
            return;
        }
        getManager(ctx).skip().ifPresentOrElse(
                next -> ctx.replyEmbeds(new EmbedBuilder()
                        .setColor(MusicUi.KAWAII_PINK)
                        .setTitle("ﾟ･✧ Pulei! ✧･ﾟ")
                        .setDescription("Agora vai: " + MusicUi.trackLine(next) + " ♡")
                        .build()),
                () -> ctx.reply("Pulei, mas a fila acabou~ era a última (´•ω•̥`)"));
    }
}