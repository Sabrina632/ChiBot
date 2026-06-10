package org.chibot.Commands.Music;

import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Music.GuildMusicManager;
import org.chibot.Music.MusicService;

/**
 * Base dos comandos de musica: todos sao guild-only, ficam na mesma categoria
 * e precisam dos mesmos atalhos (manager do servidor, canal de voz do autor).
 */
public abstract class MusicCommand implements ICommand {

    @Override
    public String getCategory() {
        return "Música";
    }

    @Override
    public boolean isGuildOnly() {
        return true;
    }

    protected GuildMusicManager getManager(CommandContext ctx) {
        return MusicService.get().getManager(ctx.getGuild().getIdLong());
    }

    /** Canal de voz onde o autor esta, ou null (ja respondendo o aviso fofo). */
    protected AudioChannelUnion requireAuthorVoiceChannel(CommandContext ctx) {
        Member member = ctx.getMember();
        GuildVoiceState voiceState = member == null ? null : member.getVoiceState();
        if (voiceState == null || voiceState.getChannel() == null) {
            ctx.reply("Você precisa estar num canal de voz pra isso~ (>_<)");
            return null;
        }
        return voiceState.getChannel();
    }

    /** True se o bot esta tocando algo nesse servidor (senao ja avisa o usuario). */
    protected boolean requirePlaying(CommandContext ctx) {
        if (getManager(ctx).isPlaying()) {
            return true;
        }
        ctx.reply("Não tô tocando nada agora~ usa o play primeiro? (｡•́︿•̀｡)");
        return false;
    }
}