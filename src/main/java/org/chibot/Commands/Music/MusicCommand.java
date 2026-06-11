package org.chibot.Commands.Music;

import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Music.AudioLoader;
import org.chibot.Music.GuildMusicManager;
import org.chibot.Music.MusicService;
import org.chibot.Music.YtSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Base dos comandos de musica: todos sao guild-only, ficam na mesma categoria
 * e precisam dos mesmos atalhos (manager do servidor, canal de voz do autor).
 */
public abstract class MusicCommand implements ICommand {

    private static final Logger log = LoggerFactory.getLogger(MusicCommand.class);

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

    /**
     * Fluxo completo de tocar: exige o autor num canal de voz, conecta o bot
     * se preciso e manda o link/busca pro Lavalink. Usado pelo play e pelo
     * playlist add.
     */
    protected void loadAndPlayQuery(CommandContext ctx, String query) {
        AudioChannelUnion authorChannel = requireAuthorVoiceChannel(ctx);
        if (authorChannel == null) {
            return;
        }

        // Conecta no canal do autor se ainda nao estiver em nenhum. Tem que ser pelo
        // DirectAudioController: o AudioManager normal do JDA ignoraria o Lavalink.
        GuildVoiceState selfState = ctx.getGuild().getSelfMember().getVoiceState();
        if (selfState == null || selfState.getChannel() == null) {
            ctx.getJDA().getDirectAudioController().connect(authorChannel);
        }

        ctx.deferReply();
        GuildMusicManager manager = getManager(ctx);

        // Links (YouTube, SoundCloud, MP3 direto...) vao direto pro Lavalink,
        // que resolve sozinho.
        if (query.startsWith("http://") || query.startsWith("https://")) {
            manager.loadAndPlay(query, new AudioLoader(ctx, manager));
            return;
        }

        // Busca por nome: a Data API e uma chamada de rede, entao fora da
        // thread de eventos do JDA.
        CompletableFuture.runAsync(() -> {
            try {
                manager.loadAndPlay(resolveIdentifier(query), new AudioLoader(ctx, manager));
            } catch (Exception e) {
                log.error("Erro inesperado resolvendo '{}'", query, e);
                ctx.reply("Ops, algo deu errado procurando essa música~ (；△；)");
            }
        });
    }

    /**
     * Busca por nome tenta primeiro a Data API (mais rapida e precisa, se
     * tiver chave configurada); se nao achar ou falhar, cai no ytsearch do
     * proprio Lavalink.
     */
    private static String resolveIdentifier(String query) {
        YtSearch search = MusicService.get().getYtSearch();
        if (search != null) {
            String url = search.findVideoUrl(query);
            if (url != null) {
                return url;
            }
        }
        return "ytsearch:" + query;
    }
}