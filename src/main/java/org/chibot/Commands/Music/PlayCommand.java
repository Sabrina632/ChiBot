package org.chibot.Commands.Music;

import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Music.AudioLoader;
import org.chibot.Music.GuildMusicManager;
import org.chibot.Music.YtDlpResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PlayCommand extends MusicCommand {

    private static final Logger log = LoggerFactory.getLogger(PlayCommand.class);

    @Override
    public String getName() {
        return "play";
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

        boolean isUrl = query.startsWith("http://") || query.startsWith("https://");
        boolean isYoutube = isUrl && (query.contains("youtube.com") || query.contains("youtu.be"));

        // Links de outras fontes (SoundCloud, Bandcamp, MP3 direto...) vao
        // direto pro Lavalink, que resolve sozinho.
        if (isUrl && !isYoutube) {
            manager.loadAndPlay(query, new AudioLoader(ctx, manager));
            return;
        }

        // YouTube (link ou busca) passa pelo yt-dlp: o plugin do Lavalink sofre
        // bloqueio anti-bot em IP de datacenter; o yt-dlp extrai a URL direta
        // do audio e o Lavalink toca como stream HTTP comum.
        String identifier = isYoutube ? query : "ytsearch1:" + query;
        String finalQuery = query;
        CompletableFuture.runAsync(() -> {
            try {
                YtDlpResolver.Resolved resolved = YtDlpResolver.resolve(identifier);
                manager.loadAndPlay(resolved.streamUrl(),
                        new AudioLoader(ctx, manager, resolved.toMeta()));
            } catch (YtDlpResolver.YtDlpException e) {
                ctx.reply("Deu ruim no YouTube~ (；△；) " + e.getMessage());
            } catch (Exception e) {
                log.error("Erro inesperado resolvendo '{}'", finalQuery, e);
                ctx.reply("Ops, algo deu errado procurando essa música~ (；△；)");
            }
        });
    }
}
