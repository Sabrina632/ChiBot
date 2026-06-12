package org.chibot.Commands;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;

import java.util.ArrayList;
import java.util.List;

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
        if (deferred) {
            event.getHook().sendMessage(message).queue();
        } else {
            event.reply(message).queue();
        }
    }

    @Override
    public void replyEmbeds(MessageEmbed embed) {
        if (deferred) {
            event.getHook().sendMessageEmbeds(embed).queue();
        } else {
            event.replyEmbeds(embed).queue();
        }
    }

    @Override
    public void replyEmbeds(List<MessageEmbed> embeds) {
        if (deferred) {
            event.getHook().sendMessageEmbeds(embeds).queue();
        } else {
            event.replyEmbeds(embeds).queue();
        }
    }

    @Override
    public void replyEmbedWithButtons(String content, MessageEmbed embed, List<Button> buttons) {
        if (deferred) {
            var action = event.getHook().sendMessageEmbeds(embed);
            if (content != null && !content.isBlank()) {
                action.setContent(content);
            }
            if (!buttons.isEmpty()) {
                action.setComponents(ActionRow.of(buttons));
            }
            action.queue();
        } else {
            var action = event.replyEmbeds(embed);
            if (content != null && !content.isBlank()) {
                action.setContent(content);
            }
            if (!buttons.isEmpty()) {
                action.setComponents(ActionRow.of(buttons));
            }
            action.queue();
        }
    }
}