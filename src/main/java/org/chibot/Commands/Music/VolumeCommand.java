package org.chibot.Commands.Music;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Music.GuildMusicManager;

import java.util.List;

/**
 * Ajusta o volume da musica nesse servidor. O valor fica salvo no banco, entao
 * vale pras proximas musicas e sobrevive a restart.
 */
public class VolumeCommand extends MusicCommand {

    @Override
    public String getName() {
        return "volume";
    }

    @Override
    public String getCategory() {
        return "Música";
    }

    @Override
    public List<String> getAliases() {
        return List.of("vol");
    }

    @Override
    public String getDescription() {
        return "Mostra ou muda o volume da musiquinha (0 a 200)~ ♪(´▽｀)";
    }

    @Override
    public String getUsage() {
        return "volume [0-200]";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.INTEGER, "nivel",
                "Volume de 0 a 200 (padrão é 50)", false)
                .setRequiredRange(0, GuildMusicManager.MAX_VOLUME));
    }

    @Override
    public void execute(CommandContext ctx) {
        GuildMusicManager manager = getManager(ctx);
        String raw = rawLevel(ctx);
        if (raw == null) {
            ctx.reply("O volume tá em **" + manager.getVolume() + "**~ usa `volume <0-200>` pra mudar! (・∀・)");
            return;
        }

        int nivel;
        try {
            nivel = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            ctx.reply("Me fala um número de 0 a 200~ (・_・;)");
            return;
        }
        if (nivel < 0 || nivel > GuildMusicManager.MAX_VOLUME) {
            ctx.reply("O volume vai de **0** a **" + GuildMusicManager.MAX_VOLUME + "**~ (>_<)");
            return;
        }

        manager.setVolume(nivel);
        ctx.reply("Volume ajustado pra **" + nivel + "**! "
                + (manager.isPlaying() ? "♪(´▽｀)" : "vou lembrar pra próxima musiquinha~ ♡"));
    }

    /** Valor cru do volume: opcao {@code nivel} no slash, ou o primeiro arg no prefixo. */
    private static String rawLevel(CommandContext ctx) {
        String option = ctx.getOption("nivel");
        if (option != null) {
            return option;
        }
        List<String> args = ctx.getArgs();
        return args.isEmpty() ? null : args.get(0);
    }
}