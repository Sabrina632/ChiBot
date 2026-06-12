package org.chibot.Commands.Harem;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Config.ChiConfig;
import org.chibot.Database.HaremRepository;
import org.chibot.Harem.HaremService;
import org.chibot.Music.MusicUi;

import java.util.ArrayList;
import java.util.List;

public class HaremCommand implements ICommand {

    private static final int POR_PAGINA = 20;
    private static final int MAX_EMBEDS = 10;

    @Override
    public String getName() {
        return "harem";
    }

    @Override
    public List<String> getAliases() {
        return List.of("mm", "meuharem");
    }

    @Override
    public String getDescription() {
        return "Mostra seu harém (ou o de outra pessoa)~ ♡(˃͈ ˂͈ )";
    }

    @Override
    public String getUsage() {
        return "harem [@usuario]";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.USER, "usuario",
                "De quem ver o harém (vazio = o seu)", false));
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

        String alvoId = ctx.getOption("usuario");
        if (alvoId == null && !ctx.getArgs().isEmpty()) {
            alvoId = HaremUtils.resolveUserId(ctx.getArgs().get(0));
            if (alvoId == null) {
                ctx.reply("Não entendi quem é~ marca a pessoa ou manda o ID! (・∀・)");
                return;
            }
        }

        if (alvoId == null || alvoId.equals(ctx.getAuthor().getId())) {
            render(ctx, service, ctx.getAuthor().getId(), ctx.getAuthor().getEffectiveName(), true);
            return;
        }

        // Harem de outra pessoa: resolve o nome de exibicao antes de montar.
        String finalAlvoId = alvoId;
        ctx.deferReply();
        ctx.getJDA().retrieveUserById(alvoId).queue(
                user -> render(ctx, service, finalAlvoId, user.getEffectiveName(), false),
                err -> ctx.reply("Não achei esse usuário em lugar nenhum~ (・_・;)"));
    }

    private void render(CommandContext ctx, HaremService service,
                        String donoId, String donoNome, boolean proprio) {
        List<HaremRepository.Claim> harem =
                service.getRepo().listHarem(ctx.getGuild().getId(), donoId);
        if (harem.isEmpty()) {
            String prefix = ChiConfig.get() == null ? "!" : ChiConfig.get().getPrefix();
            ctx.reply(proprio
                    ? "Seu harém tá vazio~ usa `" + prefix + "w` pra rolar uma waifu! (・∀・)"
                    : "O harém de **" + donoNome + "** tá vazio~ (・_・;)");
            return;
        }

        long valorTotal = harem.stream().mapToLong(HaremRepository.Claim::kakera).sum();
        List<MessageEmbed> paginas = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int exibidos = 0;
        for (HaremRepository.Claim claim : harem) {
            if (paginas.size() == MAX_EMBEDS) {
                break;
            }
            sb.append("💎`").append(claim.kakera()).append("` **").append(claim.name())
                    .append("** · ").append(claim.series()).append('\n');
            exibidos++;
            if (exibidos % POR_PAGINA == 0 || exibidos == harem.size()) {
                paginas.add(pagina(donoNome, sb.toString(), paginas.isEmpty(), harem, valorTotal));
                sb.setLength(0);
            }
        }
        ctx.replyEmbeds(paginas);
    }

    private MessageEmbed pagina(String donoNome, String descricao, boolean primeira,
                                List<HaremRepository.Claim> harem, long valorTotal) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setDescription(descricao);
        if (primeira) {
            eb.setTitle("ﾟ･✧ Harém de " + donoNome + " ✧･ﾟ")
                    .setThumbnail(harem.get(0).imageUrl());
        }
        eb.setFooter(harem.size() + " personagem(ns) · valor total: " + valorTotal + " kakera 💎");
        return eb.build();
    }
}