package org.chibot.Commands.Harem;

import net.dv8tion.jda.api.EmbedBuilder;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Database.HaremRepository;
import org.chibot.Harem.HaremEmojis;
import org.chibot.Harem.HaremService;
import org.chibot.Music.MusicUi;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class DailyCommand implements ICommand {

    @Override
    public String getName() {
        return "daily";
    }

    @Override
    public List<String> getAliases() {
        return List.of("diario", "dk");
    }

    @Override
    public String getDescription() {
        return "Coleta seu kakera diário (a cada 20h)~ 💎";
    }

    @Override
    public String getUsage() {
        return "daily";
    }

    @Override
    public String getCategory() {
        return "Harém";
    }

    @Override
    public boolean isGuildOnly() {
        return true;
    }

    @Override
    public void execute(CommandContext ctx) {
        HaremService service = HaremService.get();
        if (service == null) {
            ctx.reply("O sistema de harém ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }

        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();
        long agora = System.currentTimeMillis();

        HaremRepository.Player player = service.getRepo().getPlayer(guildId, userId);
        long cutoff = agora - HaremService.INTERVALO_DAILY.toMillis();
        int recompensa = HaremService.DAILY_BASE
                + ThreadLocalRandom.current().nextInt(HaremService.DAILY_SORTE + 1)
                + HaremService.DAILY_POR_NIVEL * player.towerLevel();

        if (!service.getRepo().tryDaily(guildId, userId, agora, cutoff, recompensa)) {
            ctx.reply("Você já coletou seu daily~ pode pegar de novo "
                    + HaremService.relativo(player.lastDailyMs() + HaremService.INTERVALO_DAILY.toMillis())
                    + "! (・∀・)");
            return;
        }

        ctx.replyEmbeds(new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Daily! ✧･ﾟ " + HaremEmojis.kakera())
                .setDescription("Você ganhou " + HaremEmojis.kakera() + " **" + recompensa + "** kakera!"
                        + (player.towerLevel() > 0
                                ? " (com bônus da torre " + HaremEmojis.torre(player.towerLevel()) + ")"
                                : "")
                        + "\nAgora você tem " + HaremEmojis.kakera() + " **"
                        + (player.kakera() + recompensa) + "** no total~ (´｡• ᵕ •｡`)")
                .setFooter("Volta em 20 horas pra coletar de novo!")
                .build());
    }
}