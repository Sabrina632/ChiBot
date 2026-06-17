package org.chibot.Commands.Fun;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Music.MusicUi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Base dos comandos de roleplay (abracar, beijar, fazer carinho, dar tapa).
 * Todos seguem o mesmo fluxo: marca alguem, a Chi busca um gif de anime e
 * responde com um embed fofo. Cada subclasse so precisa dizer qual a categoria
 * do gif, o emoji, o titulo e a frase da acao.
 */
abstract class RoleplayAction implements ICommand {

    private static final Logger log = LoggerFactory.getLogger(RoleplayAction.class);
    private static final FunGifService GIFS = new FunGifService();

    /** Categoria do gif na API (ex.: {@code hug}). */
    protected abstract String action();

    /** Emoji que acompanha o titulo e a frase. */
    protected abstract String emoji();

    /** Titulo do embed (ex.: "Abraço!"). */
    protected abstract String title();

    /** Frase quando alguem age sobre outra pessoa. */
    protected abstract String describe(String author, String target);

    /** Frase quando a pessoa marca a si mesma. */
    protected abstract String describeSelf(String author);

    @Override
    public String getCategory() {
        return "Diversão";
    }

    @Override
    public String getUsage() {
        return getName() + " <@usuario>";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.USER, "usuario",
                "Quem vai receber~", true));
    }

    @Override
    public void execute(CommandContext ctx) {
        String targetId = resolveTargetId(ctx);
        if (targetId == null) {
            ctx.reply("Me fala em quem~ marca a pessoa! Ex.: `" + getUsage() + "` (・∀・)");
            return;
        }

        String author = ctx.getAuthor().getAsMention();
        boolean self = targetId.equals(ctx.getAuthor().getId());
        String description = self ? describeSelf(author) : describe(author, "<@" + targetId + ">");

        ctx.deferReply();
        GIFS.fetch(action()).whenComplete((gif, err) -> {
            if (err != null || gif == null) {
                log.error("Falha ao buscar gif de '{}'", action(), err);
                ctx.reply("Não consegui achar um gif agora~ tenta de novo? (；△；)");
                return;
            }
            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(MusicUi.KAWAII_PINK)
                    .setTitle(emoji() + " " + title())
                    .setDescription(description)
                    .setImage(gif.url());
            if (!gif.animeName().isBlank()) {
                embed.setFooter("via " + gif.animeName() + " • nekos.best");
            }
            ctx.replyEmbeds(embed.build());
        });
    }

    /** ID do alvo: opcao "usuario" do slash ou primeiro argumento do prefixo (mencao ou ID). */
    private static String resolveTargetId(CommandContext ctx) {
        String raw = ctx.getOption("usuario");
        if (raw == null && !ctx.getArgs().isEmpty()) {
            raw = ctx.getArgs().get(0);
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim().replaceAll("[<@!>]", "");
        return id.matches("\\d{15,21}") ? id : null;
    }
}