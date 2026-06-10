package org.chibot.Commands.Music;

import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Music.AudioLoader;
import org.chibot.Music.GuildMusicManager;

import java.util.List;

public class PlayCommand extends MusicCommand {

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

        // Sem esquema de URL = busca no YouTube.
        String identifier = query.startsWith("http://") || query.startsWith("https://")
                ? query
                : "ytsearch:" + query;

        ctx.deferReply();
        GuildMusicManager manager = getManager(ctx);
        manager.loadAndPlay(identifier, new AudioLoader(ctx, manager));
    }
}
