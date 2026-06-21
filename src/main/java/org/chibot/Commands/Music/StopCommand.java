package org.chibot.Commands.Music;

import org.chibot.Commands.CommandContext;

import java.util.List;

public class StopCommand extends MusicCommand {

    @Override
    public String getName() {
        return "stop";
    }

    @Override
    public String getCategory() {
        return "Música";
    }

    @Override
    public List<String> getAliases() {
        return List.of("parar", "leave", "sair");
    }

    @Override
    public String getDescription() {
        return "Para a música, limpa a fila e sai do canal de voz~ (｡•́︿•̀｡)";
    }

    @Override
    public void execute(CommandContext ctx) {
        getManager(ctx).stop();
        ctx.getJDA().getDirectAudioController().disconnect(ctx.getGuild());
        ctx.reply("Música parada e fila limpinha~ até a próxima! (´。• ᵕ •。`) ♡");
    }
}