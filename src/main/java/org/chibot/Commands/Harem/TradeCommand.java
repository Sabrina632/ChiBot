package org.chibot.Commands.Harem;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Database.HaremRepository;
import org.chibot.Harem.HaremService;

import java.util.List;
import java.util.regex.Pattern;

public class TradeCommand implements ICommand {

    /** Separador entre o personagem oferecido e o pedido no modo prefixo. */
    private static final Pattern SEPARADOR = Pattern.compile(",|\\s+(?i:por)\\s+");

    @Override
    public String getName() {
        return "trade";
    }

    @Override
    public List<String> getAliases() {
        return List.of("trocar");
    }

    @Override
    public String getDescription() {
        return "Propõe trocar um personagem seu por um de outra pessoa~ 🤝";
    }

    @Override
    public String getUsage() {
        return "trade <@usuario> <seu personagem> por <personagem dele>";
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
                new OptionData(OptionType.USER, "usuario", "Com quem você quer trocar", true),
                new OptionData(OptionType.STRING, "meu", "Personagem seu que você oferece", true),
                new OptionData(OptionType.STRING, "dele", "Personagem da outra pessoa que você quer", true));
    }

    @Override
    public void execute(CommandContext ctx) {
        HaremService service = HaremService.get();
        if (service == null) {
            ctx.reply("O sistema de harém ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }

        String alvoId = ctx.getOption("usuario");
        String meuNome = ctx.getOption("meu");
        String deleNome = ctx.getOption("dele");

        // Prefixo: trade @fulana Rem por Megumin   (ou "Rem, Megumin")
        if (alvoId == null && !ctx.getArgs().isEmpty()) {
            alvoId = HaremUtils.resolveUserId(ctx.getArgs().get(0));
            String resto = String.join(" ", ctx.getArgs().subList(1, ctx.getArgs().size()));
            String[] nomes = SEPARADOR.split(resto, 2);
            if (nomes.length == 2) {
                meuNome = nomes[0].trim();
                deleNome = nomes[1].trim();
            }
        }

        if (alvoId == null || meuNome == null || meuNome.isBlank()
                || deleNome == null || deleNome.isBlank()) {
            ctx.reply("Assim: `trade @pessoa <seu personagem> por <personagem dela>`~ (・∀・)");
            return;
        }
        if (alvoId.equals(ctx.getAuthor().getId())) {
            ctx.reply("Trocar consigo mesmo não vale, bobinho~ (・∀・)");
            return;
        }

        HaremRepository repo = service.getRepo();
        String guildId = ctx.getGuild().getId();

        HaremRepository.Claim meu = HaremUtils.escolher(
                repo.findClaims(guildId, ctx.getAuthor().getId(), meuNome), meuNome);
        if (meu == null) {
            ctx.reply("Não achei **" + meuNome + "** no *seu* harém (ou achei mais de um)~ (・_・;)");
            return;
        }

        HaremRepository.Claim dele = HaremUtils.escolher(
                repo.findClaims(guildId, alvoId, deleNome), deleNome);
        if (dele == null) {
            ctx.reply("Não achei **" + deleNome + "** no harém de <@" + alvoId
                    + "> (ou achei mais de um)~ (・_・;)");
            return;
        }

        service.proporTroca(ctx, meu, dele);
    }
}