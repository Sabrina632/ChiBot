package org.chibot.Commands;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

public interface ICommand {

    /** Nome principal que dispara o comando (sem o prefixo). */
    String getName();

    /** Acao executada quando o comando e chamado (por prefixo ou slash). */
    void execute(CommandContext ctx);

    /** Nomes alternativos que tambem disparam o comando (apenas no prefixo). */
    default List<String> getAliases() {
        return Collections.emptyList();
    }

    /** Texto curto exibido em um comando de ajuda. */
    default String getDescription() {
        return "Sem descricao.";
    }

    /** Como usar o comando, ex.: "ban <@usuario> [motivo]". */
    default String getUsage() {
        return getName();
    }

    /** Categoria para agrupar comandos na ajuda. */
    default String getCategory() {
        return "Geral";
    }

    /** Se true, o comando so pode ser usado dentro de um servidor (nao em DM). */
    default boolean isGuildOnly() {
        return false;
    }

    /** Permissoes que o autor precisa ter para executar o comando. */
    default EnumSet<Permission> getRequiredPermissions() {
        return EnumSet.noneOf(Permission.class);
    }

    /** Opcoes do slash command (parametros). Vazio = comando sem parametros. */
    default List<OptionData> getOptions() {
        return Collections.emptyList();
    }

    /** Se true, o comando tambem e registrado como slash command (/). */
    default boolean isSlashEnabled() {
        return true;
    }

    /**
     * Aliases que TAMBEM viram slash commands proprios (ex.: /wa alem de
     * /waifu), aparecendo como atalhos no perfil do bot — estilo Mudae.
     * Devem estar em {@link #getAliases()} pro roteamento resolver. Vazio =
     * so o nome principal vira slash.
     */
    default List<String> getSlashAliases() {
        return Collections.emptyList();
    }
}