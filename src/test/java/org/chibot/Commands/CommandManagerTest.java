package org.chibot.Commands;

import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandManagerTest {

    @Test
    void atalhosDoHaremViramSlashCommands() {
        CommandManager manager = new CommandManager();
        Map<String, SlashCommandData> porNome = manager.buildSlashCommands().stream()
                .collect(Collectors.toMap(SlashCommandData::getName, d -> d));

        // Cada atalho slash existe junto do comando principal e roteia pro
        // mesmo comando (o mapa de aliases do manager ja resolve).
        Map<String, String> atalhos = Map.of(
                "wa", "waifu",
                "ha", "husbando",
                "mx", "roll",
                "wg", "gamewaifu",
                "hg", "gamehusbando",
                "gr", "gameroll");
        for (Map.Entry<String, String> e : atalhos.entrySet()) {
            SlashCommandData atalho = porNome.get(e.getKey());
            SlashCommandData principal = porNome.get(e.getValue());
            assertNotNull(atalho, "atalho /" + e.getKey() + " nao registrado");
            assertNotNull(principal, "comando /" + e.getValue() + " nao registrado");
            assertSame(manager.get(e.getValue()), manager.get(e.getKey()),
                    "atalho " + e.getKey() + " deveria rotear pro mesmo comando");
            assertEquals(principal.getOptions().size(), atalho.getOptions().size(),
                    "atalho /" + e.getKey() + " deveria ter as mesmas opcoes do principal");
        }
    }

    @Test
    void descricaoDosAtalhosRespeitaLimiteDoDiscord() {
        List<SlashCommandData> slash = new CommandManager().buildSlashCommands();
        for (SlashCommandData data : slash) {
            int len = data.getDescription().length();
            assertTrue(len >= 1 && len <= 100,
                    "/" + data.getName() + " com descricao de " + len + " chars");
        }
    }

    @Test
    void aliasComumNaoViraSlash() {
        // "w" e alias so de prefixo (nao esta em getSlashAliases) — nao pode
        // aparecer no registro de slash commands.
        List<String> nomes = new CommandManager().buildSlashCommands().stream()
                .map(SlashCommandData::getName)
                .toList();
        assertTrue(!nomes.contains("w"), "alias de prefixo 'w' nao deveria virar slash");
        assertEquals(nomes.size(), nomes.stream().distinct().count(),
                "nomes de slash duplicados no registro");
    }
}
