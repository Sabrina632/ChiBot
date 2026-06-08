package org.chibot.Commands;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.chibot.Config.ChiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

public class CommandListener extends ListenerAdapter {

    private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

    private final ChiConfig config;
    private final CommandManager manager;

    public CommandListener(ChiConfig config, CommandManager manager) {
        this.config = config;
        this.manager = manager;
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isWebhookMessage()) {
            return;
        }

        String prefix = config.getPrefix();
        String content = event.getMessage().getContentRaw();
        if (!content.startsWith(prefix)) {
            return;
        }

        String withoutPrefix = content.substring(prefix.length()).trim();
        if (withoutPrefix.isEmpty()) {
            return;
        }

        String[] parts = withoutPrefix.split("\\s+");
        String commandName = parts[0].toLowerCase();
        List<String> args = Arrays.asList(parts).subList(1, parts.length);

        ICommand command = manager.get(commandName);
        if (command == null) {
            return;
        }

        dispatch(command, new PrefixCommandContext(event, args), commandName);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String commandName = event.getName().toLowerCase();
        ICommand command = manager.get(commandName);
        if (command == null) {
            event.reply("Esse comando não existe mais~ (・_・;)").setEphemeral(true).queue();
            return;
        }

        dispatch(command, new SlashCommandContext(event), commandName);
    }

    /** Roda as verificacoes comuns (servidor/permissoes) e executa o comando. */
    private void dispatch(ICommand command, CommandContext ctx, String commandName) {
        if (command.isGuildOnly() && !ctx.isFromGuild()) {
            ctx.reply("Esse comando só funciona dentro de um servidor~ (>_<)");
            return;
        }

        if (ctx.isFromGuild() && !command.getRequiredPermissions().isEmpty()) {
            Member member = ctx.getMember();
            if (member == null || !member.hasPermission(command.getRequiredPermissions())) {
                ctx.reply("Você não tem permissão pra usar isso~ (｡•́︿•̀｡)");
                return;
            }
        }

        try {
            command.execute(ctx);
        } catch (Exception e) {
            log.error("Erro ao executar o comando '{}'", commandName, e);
            ctx.reply("Ops, algo deu errado~ (；△；)");
        }
    }
}