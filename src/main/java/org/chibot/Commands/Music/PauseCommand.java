package org.chibot.Commands.Music;

import org.chibot.Commands.CommandContext;

import java.util.List;

public class PauseCommand extends MusicCommand {

    @Override
    public String getName() {
        return "pause";
    }

    @Override
    public String getCategory() {
        return "Música";
    }

    @Override
    public List<String> getAliases() {
        return List.of("pausar");
    }

    @Override
    public String getDescription() {
        return "Dá uma pausadinha na música~ (˘ω˘)zzz";
    }

    @Override
    public void execute(CommandContext ctx) {
        if (!requirePlaying(ctx)) {
            return;
        }
        getManager(ctx).setPaused(true);
        ctx.reply("Pausei~ usa o resume quando quiser voltar! (˘ω˘) ♡");
    }
}