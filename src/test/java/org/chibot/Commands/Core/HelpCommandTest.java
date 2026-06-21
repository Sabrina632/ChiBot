package org.chibot.Commands.Core;

import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HelpCommandTest {

    /** Comando fake só com nome e categoria (o resto vem dos defaults da interface). */
    private static ICommand cmd(String nome, String categoria) {
        return new ICommand() {
            @Override
            public String getName() {
                return nome;
            }

            @Override
            public String getCategory() {
                return categoria;
            }

            @Override
            public void execute(CommandContext ctx) {
            }
        };
    }

    @Test
    void agrupaNaOrdemFixaComExtrasNoFim() {
        List<ICommand> comandos = List.of(
                cmd("ping", "Core"),
                cmd("waifu", "Harém"),
                cmd("play", "Música"),
                cmd("foo", "Geral"));   // categoria fora da ordem fixa → vai pro fim

        List<HelpCommand.CategoryGroup> grupos = HelpCommand.agruparPorCategoria(comandos);

        // Música e Harém vêm antes de Core (ordem fixa); Geral (extra) por último.
        assertEquals(List.of("Música", "Harém", "Core", "Geral"),
                grupos.stream().map(HelpCommand.CategoryGroup::categoria).toList());
    }

    @Test
    void categoriaSemComandoNaoAparece() {
        List<HelpCommand.CategoryGroup> grupos =
                HelpCommand.agruparPorCategoria(List.of(cmd("play", "Música")));
        assertEquals(List.of("Música"), grupos.stream()
                .map(HelpCommand.CategoryGroup::categoria).toList());
    }

    @Test
    void comandosDaCategoriaSaemOrdenadosPorNome() {
        List<ICommand> comandos = List.of(
                cmd("waifu", "Harém"), cmd("badge", "Harém"), cmd("roll", "Harém"));
        HelpCommand.CategoryGroup harem = HelpCommand.agruparPorCategoria(comandos).get(0);
        assertEquals(List.of("badge", "roll", "waifu"),
                harem.comandos().stream().map(ICommand::getName).toList());
    }
}