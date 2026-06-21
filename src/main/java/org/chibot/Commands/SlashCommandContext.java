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
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import org.chibot.Translation.TranslationService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Contexto de um comando chamado via slash (ex.: {@code /ping}). */
public class SlashCommandContext implements CommandContext {

    private final SlashCommandInteractionEvent event;
    private boolean deferred = false;

    public SlashCommandContext(SlashCommandInteractionEvent event) {
        this.event = event;
    }

    @Override
    public JDA getJDA() {
        return event.getJDA();
    }

    @Override
    public User getAuthor() {
        return event.getUser();
    }

    @Override
    public Member getMember() {
        return event.getMember();
    }

    @Override
    public Guild getGuild() {
        return event.getGuild();
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
        List<String> args = new ArrayList<>();
        for (OptionMapping option : event.getOptions()) {
            args.add(option.getAsString());
        }
        return args;
    }

    @Override
    public String getOption(String name) {
        OptionMapping option = event.getOption(name);
        return option == null ? null : option.getAsString();
    }

    @Override
    public void deferReply() {
        if (!deferred) {
            event.deferReply().queue();
            deferred = true;
        }
    }

    @Override
    public void reply(String message) {
        String userId = event.getUser().getId();
        String out = TranslationService.forUser(userId, message);
        if (deferred) {
            event.getHook().sendMessage(out).queue();
        } else {
            event.reply(out).queue();
        }
    }

    @Override
    public void replyEmbeds(MessageEmbed embed) {
        String userId = event.getUser().getId();
        MessageEmbed out = TranslationService.embedForUser(userId, embed);
        if (deferred) {
            event.getHook().sendMessageEmbeds(out).queue();
        } else {
            event.replyEmbeds(out).queue();
        }
    }

    @Override
    public void replyEmbeds(List<MessageEmbed> embeds) {
        String userId = event.getUser().getId();
        List<MessageEmbed> out = TranslationService.embedsForUser(userId, embeds);
        if (deferred) {
            event.getHook().sendMessageEmbeds(out).queue();
        } else {
            event.replyEmbeds(out).queue();
        }
    }

    @Override
    public void replyEmbedWithButtons(String content, MessageEmbed embed, List<Button> buttons) {
        String userId = event.getUser().getId();
        MessageEmbed outEmbed = TranslationService.embedForUser(userId, embed);
        String outContent = content == null || content.isBlank()
                ? content : TranslationService.forUser(userId, content);
        if (deferred) {
            var action = event.getHook().sendMessageEmbeds(outEmbed);
            if (outContent != null && !outContent.isBlank()) {
                action.setContent(outContent);
            }
            if (!buttons.isEmpty()) {
                action.setComponents(ActionRow.partitionOf(buttons));
            }
            action.queue();
        } else {
            var action = event.replyEmbeds(outEmbed);
            if (outContent != null && !outContent.isBlank()) {
                action.setContent(outContent);
            }
            if (!buttons.isEmpty()) {
                action.setComponents(ActionRow.partitionOf(buttons));
            }
            action.queue();
        }
    }

    @Override
    public void replyEmbedAndThen(String content, MessageEmbed embed, Consumer<Message> onSent) {
        String userId = event.getUser().getId();
        MessageEmbed outEmbed = TranslationService.embedForUser(userId, embed);
        String outContent = content == null || content.isBlank()
                ? content : TranslationService.forUser(userId, content);
        if (deferred) {
            var action = event.getHook().sendMessageEmbeds(outEmbed);
            if (outContent != null && !outContent.isBlank()) {
                action.setContent(outContent);
            }
            action.queue(onSent);
        } else {
            var action = event.replyEmbeds(outEmbed);
            if (outContent != null && !outContent.isBlank()) {
                action.setContent(outContent);
            }
            action.queue(hook -> hook.retrieveOriginal().queue(onSent));
        }
    }
}