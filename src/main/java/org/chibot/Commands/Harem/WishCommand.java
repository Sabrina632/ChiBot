package org.chibot.Commands.Harem;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Database.HaremRepository;
import org.chibot.Harem.HaremService;
import org.chibot.Music.MusicUi;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public class WishCommand implements ICommand {

    private static final Set<String> REMOVER = Set.of("remover", "remove", "rm", "tirar");

    @Override
    public String getName() {
        return "wish";
    }

    @Override
    public List<String> getAliases() {
        return List.of("desejo", "wishlist");
    }

    @Override
    public String getDescription() {
        return "Lista de desejos: te aviso quando o personagem aparecer num roll~ ✨";
    }

    @Override
    public String getUsage() {
        return "wish [nome] | wish remover <nome> | wish (lista)";
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
        return List.of(
                new OptionData(OptionType.STRING, "personagem",
                        "Nome exato do personagem (vazio = mostra sua lista)", false),
                new OptionData(OptionType.STRING, "acao", "O que fazer com o personagem", false)
                        .addChoice("adicionar", "adicionar")
                        .addChoice("remover", "remover"));
    }

    @Override
    public void execute(CommandContext ctx) {
        HaremService service = HaremService.get();
        if (service == null) {
            ctx.reply("O sistema de harém ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }

        String acao = ctx.getOption("acao");
        String nome = ctx.getOption("personagem");
        List<String> args = ctx.getArgs();
        if (nome == null && !args.isEmpty()) {
            // Prefixo: "wish remover <nome>" remove; qualquer outra coisa adiciona.
            if (REMOVER.contains(args.get(0).toLowerCase(Locale.ROOT)) && args.size() > 1) {
                acao = "remover";
                nome = String.join(" ", args.subList(1, args.size()));
            } else {
                nome = String.join(" ", args);
            }
        }

        HaremRepository repo = service.getRepo();
        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();

        if (nome == null || nome.isBlank()) {
            listar(ctx, repo, guildId, userId);
            return;
        }

        String nomeLower = nome.trim().toLowerCase(Locale.ROOT);
        if ("remover".equals(acao)) {
            if (repo.removeWish(guildId, userId, nomeLower)) {
                ctx.reply("Tirei **" + nome.trim() + "** da sua lista de desejos~ (・∀・)");
            } else {
                ctx.reply("Esse personagem não tava na sua lista~ (・_・;)");
            }
            return;
        }

        switch (repo.addWish(guildId, userId, nomeLower, HaremService.MAX_DESEJOS)) {
            case OK -> ctx.reply("✨ Adicionei **" + nome.trim()
                    + "** na sua lista de desejos! Te aviso quando aparecer~ (´｡• ᵕ •｡`)"
                    + "\n*(o nome precisa ser exatamente igual ao do roll)*");
            case DUPLICADO -> ctx.reply("Esse personagem já tá na sua lista, ansioso(a)~ (・∀・)");
            case CHEIO -> ctx.reply("Sua lista de desejos tá cheia! Máximo de "
                    + HaremService.MAX_DESEJOS + "~ usa `wish remover <nome>` pra abrir espaço (>_<)");
        }
    }

    private void listar(CommandContext ctx, HaremRepository repo, String guildId, String userId) {
        List<String> desejos = repo.listWishes(guildId, userId);
        if (desejos.isEmpty()) {
            ctx.reply("Sua lista de desejos tá vazia~ usa `wish <nome>` pra adicionar! (・∀・)");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String d : desejos) {
            sb.append("✨ ").append(d).append('\n');
        }
        ctx.replyEmbeds(new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Desejos de " + ctx.getAuthor().getEffectiveName() + " ✧･ﾟ")
                .setDescription(sb.toString())
                .setFooter(desejos.size() + "/" + HaremService.MAX_DESEJOS + " desejos")
                .build());
    }
}