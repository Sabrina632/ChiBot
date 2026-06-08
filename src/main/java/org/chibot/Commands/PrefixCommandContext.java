package org.chibot.Commands;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.util.List;

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
        event.getMessage().reply(message).queue();
    }

    @Override
    public void replyEmbeds(MessageEmbed embed) {
        event.getMessage().replyEmbeds(embed).queue();
    }
}