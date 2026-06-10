package org.chibot.Commands.Music;

import org.chibot.Commands.CommandContext;

import java.util.List;

public class ResumeCommand extends MusicCommand {

    @Override
    public String getName() {
        return "resume";
    }

    @Override
    public List<String> getAliases() {
        return List.of("continuar", "unpause");
    }

    @Override
    public String getDescription() {
        return "Continua a música de onde parou~ ♪(´▽｀)";
    }

    @Override
    public void execute(CommandContext ctx) {
        if (!requirePlaying(ctx)) {
            return;
        }
        getManager(ctx).setPaused(false);
        ctx.reply("Voltamos~ bora dançar de novo! ♪(ﾉ´ヮ`)ﾉ*:･ﾟ✧");
    }
}