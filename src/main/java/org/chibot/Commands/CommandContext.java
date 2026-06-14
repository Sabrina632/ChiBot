package org.chibot.Commands;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;

import java.util.List;
import java.util.function.Consumer;

/**
 * Abstracao que cobre tanto comandos por prefixo (mensagem) quanto slash commands (interacao),
 * pra que o mesmo {@link ICommand} funcione dos dois jeitos sem duplicar logica.
 */
public interface CommandContext {

    JDA getJDA();

    /** Quem chamou o comando. */
    User getAuthor();

    /** Membro do servidor (null em DM). */
    Member getMember();

    /** Servidor de onde veio (null em DM). */
    Guild getGuild();

    /** Canal onde o comando foi usado. */
    MessageChannel getChannel();

    boolean isFromGuild();

    /**
     * Argumentos posicionais. No prefixo vem do texto depois do comando;
     * no slash vem das opcoes na ordem em que foram declaradas.
     */
    List<String> getArgs();

    /** Valor de uma opcao pelo nome (null se nao foi informada). Util pros slash commands. */
    String getOption(String name);

    /**
     * Avisa o Discord que a resposta vem em seguida (pode demorar um pouco).
     * No slash isso e obrigatorio pra interacoes que respondem de forma assincrona;
     * no prefixo apenas mostra o "digitando...".
     */
    void deferReply();

    /** Responde com texto. */
    void reply(String message);

    /** Responde com um embed. */
    void replyEmbeds(MessageEmbed embed);

    /** Responde com varios embeds de uma vez (o Discord aceita ate 10). */
    void replyEmbeds(List<MessageEmbed> embeds);

    /**
     * Responde com um embed acompanhado de botoes, com um texto opcional acima
     * do embed (ex.: mencoes de wishlist). Lista de botoes vazia = so o embed.
     */
    void replyEmbedWithButtons(String content, MessageEmbed embed, List<Button> buttons);

    /**
     * Posta um embed (com texto opcional acima) e entrega a mensagem enviada num
     * callback — util pra quem precisa do ID da mensagem depois (ex.: registrar
     * um roll que vai ser casado por reacao).
     */
    void replyEmbedAndThen(String content, MessageEmbed embed, Consumer<Message> onSent);
}