package org.chibot.Commands.Core;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.chibot.Translation.TranslationService;

import java.util.List;

/**
 * Escuta os botões do {@code !help} (IDs {@code help:...}). Stateless: reconstrói o
 * embed a partir do {@link HelpCommand} na hora, então funciona mesmo depois de
 * reiniciar. Só o invocador navega; outra pessoa recebe um aviso efêmero.
 */
public class HelpButtons extends ListenerAdapter {

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        HelpButtonId.Parsed p = HelpButtonId.decode(event.getComponentId());
        if (p == null) {
            return; // não é botão do help — deixa pros outros listeners
        }

        String clicador = event.getUser().getId();
        if (!clicador.equals(p.invokerId())) {
            event.reply("esse menuzinho é de outra pessoa~ abre o seu com `help`! (・∀・)")
                    .setEphemeral(true).queue();
            return;
        }

        String prefix = HelpCommand.prefix();
        MessageEmbed embed = "cat".equals(p.action())
                ? HelpCommand.buildCategoryEmbed(p.category(), prefix)
                : null;

        List<Button> botoes;
        if (embed != null) {
            botoes = List.of(HelpCommand.buildBackButton(clicador));
        } else {
            // home, ou categoria que sumiu entre versões → volta pro overview.
            embed = HelpCommand.buildOverviewEmbed(event.getJDA(), prefix);
            botoes = HelpCommand.buildOverviewButtons(clicador);
        }

        MessageEmbed traduzido = TranslationService.embedForUser(clicador, embed);
        event.editMessageEmbeds(traduzido)
                .setComponents(ActionRow.partitionOf(botoes))
                .queue();
    }
}