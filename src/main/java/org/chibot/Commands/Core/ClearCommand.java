package org.chibot.Commands.Core;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Commands.PrefixCommandContext;
import org.chibot.Music.MusicUi;
import org.chibot.Translation.TranslationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ClearCommand implements ICommand {

    private static final Logger log = LoggerFactory.getLogger(ClearCommand.class);

    /** Limite do delete em massa do Discord (por chamada). */
    private static final int MAX_AMOUNT = 100;

    @Override
    public String getName() {
        return "clear";
    }

    @Override
    public List<String> getAliases() {
        return List.of("limpar", "purge");
    }

    @Override
    public String getDescription() {
        return "Limpa as últimas mensagens do canal~ faxina fofa! ✧(≧◡≦)";
    }

    @Override
    public String getUsage() {
        return "clear <quantidade>";
    }

    @Override
    public String getCategory() {
        return "Moderação";
    }

    @Override
    public boolean isGuildOnly() {
        return true;
    }

    @Override
    public EnumSet<Permission> getRequiredPermissions() {
        return EnumSet.of(Permission.MESSAGE_MANAGE);
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.INTEGER, "quantidade",
                "Quantas mensagens apagar (1 a " + MAX_AMOUNT + ")", true)
                .setRequiredRange(1, MAX_AMOUNT));
    }

    @Override
    public void execute(CommandContext ctx) {
        Integer amount = parseAmount(ctx);
        if (amount == null) {
            ctx.reply("Me fala quantas mensagens apagar~ um numerozinho de 1 a "
                    + MAX_AMOUNT + "! (・∀・)");
            return;
        }
        if (amount < 1 || amount > MAX_AMOUNT) {
            ctx.reply("Só consigo apagar de 1 a " + MAX_AMOUNT + " mensagens por vez~ (>_<)");
            return;
        }

        GuildMessageChannel channel = (GuildMessageChannel) ctx.getChannel();
        if (!ctx.getGuild().getSelfMember().hasPermission(channel, Permission.MESSAGE_MANAGE)) {
            ctx.reply("Eu não tenho a permissão de gerenciar mensagens aqui~ (｡•́︿•̀｡)");
            return;
        }

        // No prefixo a mensagem do proprio !clear tambem some, mas nao conta na
        // quantidade. E o resultado vai direto no canal: responder ("reply") a
        // uma mensagem apagada daria erro.
        boolean isPrefix = ctx instanceof PrefixCommandContext;
        int fetch = isPrefix ? amount + 1 : amount;

        ctx.deferReply();
        channel.getIterableHistory().takeAsync(fetch).thenAccept(messages -> {
            int deleted = isPrefix ? Math.max(0, messages.size() - 1) : messages.size();
            List<CompletableFuture<Void>> futures = channel.purgeMessages(messages);
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .whenComplete((ok, err) -> {
                        if (err != null) {
                            log.warn("Purge incompleto no canal {}", channel.getId(), err);
                            String aviso = "Não consegui apagar tudo~ (；△；) "
                                    + "confere se eu tenho permissão e se as mensagens não são velhinhas demais...";
                            // No prefixo a mensagem original ja pode ter sido apagada,
                            // entao nada de reply: manda direto no canal. Como o
                            // sendMessage nao passa pelo context, traduz aqui na mao
                            // pro idioma de quem chamou (o slash ja traduz via ctx).
                            if (isPrefix) {
                                String userId = ctx.getAuthor().getId();
                                channel.sendMessage(TranslationService.forUser(userId, aviso)).queue();
                            } else {
                                ctx.reply(aviso);
                            }
                            return;
                        }
                        EmbedBuilder embed = new EmbedBuilder()
                                .setColor(MusicUi.KAWAII_PINK)
                                .setTitle("ﾟ･✧ Faxina feita! ✧･ﾟ")
                                .setDescription("Limpei **" + deleted + "** mensagen(s)~ "
                                        + "ficou tudo brilhando! ✧･ﾟ(≧◡≦)♡");
                        if (isPrefix) {
                            String userId = ctx.getAuthor().getId();
                            channel.sendMessageEmbeds(
                                    TranslationService.embedForUser(userId, embed.build())).queue();
                        } else {
                            ctx.replyEmbeds(embed.build());
                        }
                    });
        }).exceptionally(err -> {
            log.error("Falha lendo o historico do canal {}", channel.getId(), err);
            ctx.reply("Não consegui ler as mensagens do canal~ (・_・;)");
            return null;
        });
    }

    /** Quantidade da opcao do slash ou do primeiro argumento do prefixo (null se invalida). */
    private static Integer parseAmount(CommandContext ctx) {
        String raw = ctx.getOption("quantidade");
        if (raw == null && !ctx.getArgs().isEmpty()) {
            raw = ctx.getArgs().get(0);
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}