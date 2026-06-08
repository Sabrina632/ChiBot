package org.chibot.Commands;

import net.dv8tion.jda.api.EmbedBuilder;

import java.awt.Color;
import java.time.Instant;

public class PingCommand implements ICommand {

    private static final Color KAWAII_PINK = new Color(0xFFB6C1);

    @Override
    public String getName() {
        return "ping";
    }

    @Override
    public String getDescription() {
        return "Mostra a latência do bot de um jeitinho fofo~ (｡•ᴗ•｡)♡";
    }

    @Override
    public String getCategory() {
        return "Utilidades";
    }

    @Override
    public void execute(CommandContext ctx) {
        long gatewayPing = ctx.getJDA().getGatewayPing();

        ctx.deferReply();
        ctx.getJDA().getRestPing().queue(restPing -> {
            EmbedBuilder embed = new EmbedBuilder()
                    .setColor(KAWAII_PINK)
                    .setTitle("ﾟ･✧ Pong! ✧･ﾟ")
                    .setDescription("Tô aqui pra você~ (≧◡≦) ♡")
                    .addField("☆ Gateway", "`" + gatewayPing + "ms` (灬º‿º灬)♡", true)
                    .addField("☆ API", "`" + restPing + "ms` ✨", true)
                    .setFooter("feito com muito amor~ ♡", ctx.getJDA().getSelfUser().getEffectiveAvatarUrl())
                    .setTimestamp(Instant.now());

            ctx.replyEmbeds(embed.build());
        });
    }
}