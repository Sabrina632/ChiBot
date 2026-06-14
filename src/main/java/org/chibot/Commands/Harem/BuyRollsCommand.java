package org.chibot.Commands.Harem;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Database.HaremRepository;
import org.chibot.Harem.HaremEmojis;
import org.chibot.Harem.HaremService;

import java.util.List;

public class BuyRollsCommand implements ICommand {

    private static final int MAX_POR_COMPRA = 50;

    @Override
    public String getName() {
        return "buyrolls";
    }

    @Override
    public List<String> getAliases() {
        return List.of("comprarrolls", "br");
    }

    @Override
    public String getDescription() {
        return "Compra rolls extras com kakera (" + HaremService.CUSTO_ROLL_EXTRA + " 💎 cada)~ 🎲";
    }

    @Override
    public String getUsage() {
        return "buyrolls [quantidade]";
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
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.INTEGER, "quantidade",
                "Quantos rolls comprar (padrão: 1)", false)
                .setMinValue(1)
                .setMaxValue(MAX_POR_COMPRA));
    }

    @Override
    public void execute(CommandContext ctx) {
        HaremService service = HaremService.get();
        if (service == null) {
            ctx.reply("O sistema de harém ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }

        int quantidade = parseQuantidade(ctx);
        if (quantidade < 1 || quantidade > MAX_POR_COMPRA) {
            ctx.reply("Escolhe uma quantidade entre 1 e " + MAX_POR_COMPRA + "~ (・∀・)");
            return;
        }

        HaremRepository repo = service.getRepo();
        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();
        long custo = (long) quantidade * HaremService.CUSTO_ROLL_EXTRA;

        if (!repo.trySpendKakera(guildId, userId, custo)) {
            long saldo = repo.getPlayer(guildId, userId).kakera();
            String k = HaremEmojis.kakera();
            ctx.reply("Kakera insuficiente~ você tem " + k + " " + saldo + " e precisa de " + k + " " + custo
                    + ". Coleta o `daily` ou os " + k + " dos rolls! (｡•́︿•̀｡)");
            return;
        }
        repo.addBonusRolls(guildId, userId, quantidade);

        ctx.reply("🎲 Comprou **" + quantidade + "** roll(s) extra(s) por " + HaremEmojis.kakera() + " " + custo
                + "! Eles não expiram e são usados quando a cota da hora acabar~ (✿◠‿◠)");
    }

    private int parseQuantidade(CommandContext ctx) {
        String raw = ctx.getOption("quantidade");
        if (raw == null && !ctx.getArgs().isEmpty()) {
            raw = ctx.getArgs().get(0);
        }
        if (raw == null || raw.isBlank()) {
            return 1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}