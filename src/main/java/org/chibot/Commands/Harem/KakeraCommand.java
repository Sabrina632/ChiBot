package org.chibot.Commands.Harem;

import net.dv8tion.jda.api.EmbedBuilder;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Database.HaremRepository;
import org.chibot.Harem.HaremEmojis;
import org.chibot.Harem.HaremService;
import org.chibot.Music.MusicUi;

import java.util.List;

/** Mostra o saldo de kakera e como ganhar/gastar — no estilo do {@code $kakera} do Mudae. */
public class KakeraCommand implements ICommand {

    @Override
    public String getName() {
        return "kakera";
    }

    @Override
    public List<String> getAliases() {
        return List.of("ka", "kk");
    }

    @Override
    public String getDescription() {
        return "Mostra seu saldo de kakera e como ganhar/gastar~ 💎";
    }

    @Override
    public String getUsage() {
        return "kakera";
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
        HaremRepository.Player player = service.getRepo().getPlayer(guildId, userId);

        int nivel = player.towerLevel();
        String k = HaremEmojis.kakera();
        String torre = nivel < HaremService.TORRE_MAX
                ? HaremEmojis.torre(nivel) + " nível **" + nivel + "/" + HaremService.TORRE_MAX
                        + "** · próximo nível custa " + k + " **" + HaremService.custoTorre(nivel + 1) + "**"
                : HaremEmojis.torre(nivel) + " nível **máximo** alcançado~ 👑";

        ctx.replyEmbeds(new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Kakera de " + ctx.getAuthor().getEffectiveName() + " ✧･ﾟ")
                .setThumbnail(ctx.getAuthor().getEffectiveAvatarUrl())
                .setDescription("Você tem " + k + " **" + player.kakera() + "** kakera!")
                .addField("💰 Como ganhar",
                        "🌅 `daily` — coleta a cada 20h\n"
                        + k + " Clica no kakera dos personagens **já casados** (no roll)\n"
                        + "💔 `divorce` — recupera metade do valor do personagem\n"
                        + "🤝 `trade` — troca personagens com outra pessoa", false)
                .addField("🛒 Onde gastar",
                        "🎲 `buyrolls` — rolls extras (" + k + " " + HaremService.CUSTO_ROLL_EXTRA + " cada)\n"
                        + "🏰 `tower` — sobe a torre: +rolls/hora, +" + HaremService.SAQUE_POR_NIVEL
                        + "% de saque e +" + HaremService.DAILY_POR_NIVEL + " no daily por nível", false)
                .addField("🏰 Sua torre", torre, false)
                .setFooter("Veja seus tempos com `timers` · seu perfil com `profile`")
                .build());
    }
}