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
import java.util.stream.Collectors;

public class DivorceCommand implements ICommand {

    @Override
    public String getName() {
        return "divorce";
    }

    @Override
    public List<String> getAliases() {
        return List.of("divorciar");
    }

    @Override
    public String getDescription() {
        return "Se divorcia de um personagem do seu harém (devolve metade do valor em kakera)~ 💔";
    }

    @Override
    public String getUsage() {
        return "divorce <nome do personagem>";
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
        return List.of(new OptionData(OptionType.STRING, "personagem",
                "Nome do personagem de quem você quer se divorciar", true));
    }

    @Override
    public void execute(CommandContext ctx) {
        HaremService service = HaremService.get();
        if (service == null) {
            ctx.reply("O sistema de harém ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }

        String nome = ctx.getOption("personagem");
        if (nome == null && !ctx.getArgs().isEmpty()) {
            nome = String.join(" ", ctx.getArgs());
        }
        if (nome == null || nome.isBlank()) {
            ctx.reply("Me fala de quem você quer se divorciar~ ex.: `divorce Zero Two` (・∀・)");
            return;
        }

        HaremRepository repo = service.getRepo();
        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();

        List<HaremRepository.Claim> matches = repo.findClaims(guildId, userId, nome.trim());
        HaremRepository.Claim alvo = HaremUtils.escolher(matches, nome.trim());
        if (alvo == null) {
            if (matches.isEmpty()) {
                ctx.reply("Não achei ninguém com esse nome no seu harém~ (・_・;)");
            } else {
                ctx.reply("Achei mais de um, seja mais específico~ " + matches.stream()
                        .limit(5)
                        .map(c -> "**" + c.name() + "**")
                        .collect(Collectors.joining(", ")) + " (>_<)");
            }
            return;
        }

        if (!repo.removeClaim(guildId, alvo.charId())) {
            ctx.reply("Não consegui desfazer esse casamento agora~ (；△；)");
            return;
        }
        int devolucao = alvo.kakera() / 2;
        repo.addKakera(guildId, userId, devolucao);

        ctx.replyEmbeds(new EmbedBuilder()
                .setColor(MusicUi.KAWAII_PINK)
                .setTitle("ﾟ･✧ Divórcio ✧･ﾟ 💔")
                .setThumbnail(alvo.imageUrl())
                .setDescription("Você se divorciou de **" + alvo.name() + "**... que triste~ (｡•́︿•̀｡)\n"
                        + "Como consolo, ficou com " + HaremEmojis.kakera() + " **" + devolucao
                        + "** kakera (metade do valor).")
                .build());
    }
}