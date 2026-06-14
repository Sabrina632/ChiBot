package org.chibot.Commands.Harem;

import net.dv8tion.jda.api.EmbedBuilder;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Database.HaremRepository;
import org.chibot.Harem.HaremEmojis;
import org.chibot.Harem.HaremService;
import org.chibot.Music.MusicUi;

import java.util.List;

public class TimersCommand implements ICommand {

    @Override
    public String getName() {
        return "timers";
    }

    @Override
    public List<String> getAliases() {
        return List.of("tu", "tempos");
    }

    @Override
    public String getDescription() {
        return "Mostra seus rolls restantes, cooldown de casamento e kakera~ ⏰";
    }

    @Override
    public String getUsage() {
        return "timers";
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
        int rolls = service.rollsRestantes(guildId, userId);
        int maxRolls = HaremService.ROLLS_POR_HORA + player.towerLevel();
        long resetRolls = (agora / 3_600_000L + 1) * 3_600_000L;
        long proximoClaim = service.proximoClaimMs(guildId, userId);
        long proximoDaily = player.lastDailyMs() + HaremService.INTERVALO_DAILY.toMillis();
        List<String> desejos = service.getRepo().listWishes(guildId, userId);

        String claimTexto = agora >= proximoClaim
                ? "Disponível agora! Vai lá rolar~ 💗"
                : "De novo " + HaremService.relativo(proximoClaim);
        String dailyTexto = agora >= proximoDaily
                ? "Disponível! Usa `daily`~ 🌅"
                : "De novo " + HaremService.relativo(proximoDaily);

        ctx.replyEmbeds(new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Timers de " + ctx.getAuthor().getEffectiveName() + " ✧･ﾟ")
                .setThumbnail(ctx.getAuthor().getEffectiveAvatarUrl())
                .addField("🎲 Rolls", rolls + "/" + maxRolls
                        + (player.bonusRolls() > 0 ? " (" + player.bonusRolls() + " de bônus)" : "")
                        + " · reseta " + HaremService.relativo(resetRolls), true)
                .addField("💍 Casamento", claimTexto, true)
                .addField("🌅 Daily", dailyTexto, true)
                .addField(HaremEmojis.kakera() + " Kakera", String.valueOf(player.kakera()), true)
                .addField("🏰 Torre", HaremService.TORRE_EMOJIS[player.towerLevel()]
                        + " nível " + player.towerLevel() + "/" + HaremService.TORRE_MAX, true)
                .addField("✨ Desejos", desejos.size() + "/" + HaremService.MAX_DESEJOS, true)
                .build());
    }
}