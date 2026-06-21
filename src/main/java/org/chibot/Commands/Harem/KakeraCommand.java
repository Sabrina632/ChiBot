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
        // Emojis customizados do emoji.gg (igual badge/profile). Caem no unicode se
        // ainda não subiram pra aplicação. Veja ARTE_DECOR em HaremEmojis.
        String eGanhar = HaremEmojis.custom("kak_ganhar", "💰");
        String eDaily = HaremEmojis.custom("kak_daily", "🌅");
        String eDivorce = HaremEmojis.custom("kak_divorce", "💔");
        String eTrade = HaremEmojis.custom("kak_trade", "🤝");
        String eGastar = HaremEmojis.custom("kak_gastar", "🛒");
        String eBuyrolls = HaremEmojis.custom("kak_buyrolls", "🎲");
        String eTorre = HaremEmojis.custom("kak_torre", "🏰");
        String eMax = HaremEmojis.custom("kak_max", "👑");

        String torre = nivel < HaremService.TORRE_MAX
                ? HaremEmojis.torre(nivel) + " nível **" + nivel + "/" + HaremService.TORRE_MAX
                        + "** · próximo nível custa " + k + " **" + HaremService.custoTorre(nivel + 1) + "**"
                : HaremEmojis.torre(nivel) + " nível **máximo** alcançado~ " + eMax;

        ctx.replyEmbeds(new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Kakera de " + ctx.getAuthor().getEffectiveName() + " ✧･ﾟ")
                .setThumbnail(ctx.getAuthor().getEffectiveAvatarUrl())
                .setDescription("Você tem " + k + " **" + player.kakera() + "** kakera!")
                .addField(eGanhar + " Como ganhar",
                        eDaily + " `daily` — coleta a cada 20h\n"
                        + k + " Clica no kakera dos personagens **já casados** (no roll)\n"
                        + eDivorce + " `divorce` — recupera metade do valor do personagem\n"
                        + eTrade + " `trade` — troca personagens com outra pessoa", false)
                .addField(eGastar + " Onde gastar",
                        eBuyrolls + " `buyrolls` — rolls extras (" + k + " " + HaremService.CUSTO_ROLL_EXTRA + " cada)\n"
                        + eTorre + " `tower` — sobe a torre: +rolls/hora, +" + HaremService.SAQUE_POR_NIVEL
                        + "% de saque e +" + HaremService.DAILY_POR_NIVEL + " no daily por nível", false)
                .addField(eTorre + " Sua torre", torre, false)
                .setFooter("Veja seus tempos com `timers` · seu perfil com `profile`")
                .build());
    }
}