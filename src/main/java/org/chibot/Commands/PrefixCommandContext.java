package org.chibot.Commands;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.chibot.Translation.TranslationService;

import java.util.List;
import java.util.function.Consumer;

/** Contexto de um comando chamado por prefixo (ex.: {@code !ping}). */
public class PrefixCommandContext implements CommandContext {

    private final MessageReceivedEvent event;
    private final List<String> args;

    public PrefixCommandContext(MessageReceivedEvent event, List<String> args) {
        this.event = event;
        this.args = args;
    }

    @Override
    public JDA getJDA() {
        return event.getJDA();
    }

    @Override
    public User getAuthor() {
        return event.getAuthor();
    }

    @Override
    public Member getMember() {
        return event.getMember();
    }

    @Override
    public Guild getGuild() {
        return event.isFromGuild() ? event.getGuild() : null;
    }

    @Override
    public MessageChannel getChannel() {
        return event.getChannel();
    }

    @Override
    public boolean isFromGuild() {
        return event.isFromGuild();
    }

    @Override
    public List<String> getArgs() {
        return args;
    }

    @Override
    public String getOption(String name) {
        // Comandos por prefixo nao tem opcoes nomeadas; use getArgs().
        return null;
    }

    @Override
    public void deferReply() {
        event.getChannel().sendTyping().queue();
    }

    @Override
    public void reply(String message) {
        String userId = event.getAuthor().getId();
        event.getMessage().reply(TranslationService.forUser(userId, message)).queue();
    }

    @Override
    public void replyEmbeds(MessageEmbed embed) {
        String userId = event.getAuthor().getId();
        event.getMessage().replyEmbeds(TranslationService.embedForUser(userId, embed)).queue();
    }

    @Override
    public void replyEmbeds(List<MessageEmbed> embeds) {
        String userId = event.getAuthor().getId();
        event.getMessage().replyEmbeds(TranslationService.embedsForUser(userId, embeds)).queue();
    }

    @Override
    public void replyEmbedWithButtons(String content, MessageEmbed embed, List<Button> buttons) {
        String userId = event.getAuthor().getId();
        var action = event.getMessage().replyEmbeds(TranslationService.embedForUser(userId, embed));
        if (content != null && !content.isBlank()) {
            action.setContent(TranslationService.forUser(userId, content));
        }
        if (!buttons.isEmpty()) {
            action.setComponents(ActionRow.partitionOf(buttons));
        }
        action.queue();
    }

    @Override
    public void replyEmbedAndThen(String content, MessageEmbed embed, Consumer<Message> onSent) {
        String userId = event.getAuthor().getId();
        var action = event.getMessage().replyEmbeds(TranslationService.embedForUser(userId, embed));
        if (content != null && !content.isBlank()) {
            action.setContent(TranslationService.forUser(userId, content));
        }
        action.queue(onSent);
    }
}