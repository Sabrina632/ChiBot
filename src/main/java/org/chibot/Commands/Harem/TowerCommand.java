package org.chibot.Commands.Harem;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Database.HaremRepository;
import org.chibot.Harem.HaremEmojis;
import org.chibot.Harem.HaremService;
import org.chibot.Music.MusicUi;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public class TowerCommand implements ICommand {

    private static final Set<String> SUBIR = Set.of("up", "subir", "upgrade");

    @Override
    public String getName() {
        return "tower";
    }

    @Override
    public List<String> getAliases() {
        return List.of("torre", "badges");
    }

    @Override
    public String getDescription() {
        return "Torre de kakera: gasta kakera pra ganhar mais rolls e saques maiores~ 🏰";
    }

    @Override
    public String getUsage() {
        return "tower | tower up";
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
        return List.of(new OptionData(OptionType.STRING, "acao",
                "O que fazer na torre (vazio = ver seu progresso)", false)
                .addChoice("subir", "subir"));
    }

    @Override
    public void execute(CommandContext ctx) {
        HaremService service = HaremService.get();
        if (service == null) {
            ctx.reply("O sistema de harém ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }

        String acao = ctx.getOption("acao");
        if (acao == null && !ctx.getArgs().isEmpty()
                && SUBIR.contains(ctx.getArgs().get(0).toLowerCase(Locale.ROOT))) {
            acao = "subir";
        }

        if ("subir".equals(acao)) {
            subir(ctx, service);
        } else {
            status(ctx, service);
        }
    }

    private void status(CommandContext ctx, HaremService service) {
        HaremRepository.Player player = service.getRepo()
                .getPlayer(ctx.getGuild().getId(), ctx.getAuthor().getId());
        int nivel = player.towerLevel();

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Torre de Kakera ✧･ﾟ 🏰")
                .setDescription("Nível atual: " + HaremService.TORRE_EMOJIS[nivel]
                        + " **" + nivel + "/" + HaremService.TORRE_MAX + "**")
                .addField("🎲 Rolls extras", "+" + nivel + " por hora", true)
                .addField(HaremEmojis.kakera() + " Bônus de saque",
                        "+" + (HaremService.SAQUE_POR_NIVEL * nivel) + "%", true)
                .addField("🌅 Bônus no daily", "+" + (HaremService.DAILY_POR_NIVEL * nivel) + " kakera", true);
        if (nivel < HaremService.TORRE_MAX) {
            eb.addField("⬆️ Próximo nível " + HaremService.TORRE_EMOJIS[nivel + 1],
                    "Custa " + HaremEmojis.kakera() + " **" + HaremService.custoTorre(nivel + 1)
                            + "** — usa `tower up` pra subir! (você tem " + HaremEmojis.kakera() + " "
                            + player.kakera() + ")", false);
        } else {
            eb.addField("👑 Topo da torre!", "Você chegou no máximo, parabéns~ ✧", false);
        }
        ctx.replyEmbeds(eb.build());
    }

    private void subir(CommandContext ctx, HaremService service) {
        HaremRepository repo = service.getRepo();
        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();

        HaremRepository.Player player = repo.getPlayer(guildId, userId);
        int nivel = player.towerLevel();
        if (nivel >= HaremService.TORRE_MAX) {
            ctx.reply("Você já está no topo da torre " + HaremService.TORRE_EMOJIS[nivel] + "~ 👑");
            return;
        }

        int novoNivel = nivel + 1;
        long custo = HaremService.custoTorre(novoNivel);
        if (!repo.tryUpgradeTower(guildId, userId, novoNivel, custo)) {
            String k = HaremEmojis.kakera();
            ctx.reply("Kakera insuficiente~ subir pro nível " + novoNivel + " custa " + k + " " + custo
                    + " e você tem " + k + " " + player.kakera() + ". (｡•́︿•̀｡)");
            return;
        }

        ctx.replyEmbeds(new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Torre subiu! ✧･ﾟ " + HaremService.TORRE_EMOJIS[novoNivel])
                .setDescription("Você alcançou o nível **" + novoNivel + "/" + HaremService.TORRE_MAX
                        + "** da torre por " + HaremEmojis.kakera() + " " + custo + "!\n"
                        + "Agora: **+" + novoNivel + "** roll(s)/hora · **+"
                        + (HaremService.SAQUE_POR_NIVEL * novoNivel) + "%** de saque · **+"
                        + (HaremService.DAILY_POR_NIVEL * novoNivel) + "** no daily~ (✿◠‿◠)")
                .build());
    }
}