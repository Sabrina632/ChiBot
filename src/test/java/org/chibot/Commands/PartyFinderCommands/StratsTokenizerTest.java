package org.chibot.Commands.PartyFinderCommands;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StratsTokenizerTest {

    @Test
    void extraiStratsEIgnoraRuido() {
        List<String> tokens = StratsTokenizer.tokenize("Hector strat, prog only, must know mechanics");

        assertTrue(tokens.contains("hector"), "deveria pegar a strat 'hector'");
        assertTrue(tokens.contains("strat"));
        // ruido de PF e stopwords ficam de fora
        assertFalse(tokens.contains("prog"));
        assertFalse(tokens.contains("must"));
        assertFalse(tokens.contains("know"));
        assertFalse(tokens.contains("mechanics"));
    }

    @Test
    void geraBigramas() {
        List<String> tokens = StratsTokenizer.tokenize("uptime strat please");
        assertTrue(tokens.contains("uptime"));
        assertTrue(tokens.contains("strat"));
        assertTrue(tokens.contains("uptime strat"), "deveria gerar o bigrama");
    }

    @Test
    void urlViraTokenDeServico() {
        List<String> tokens = StratsTokenizer.tokenize("plano aqui https://raidplan.io/plan/p8JvSSs1");
        boolean temRaidplan = tokens.stream().anyMatch(t -> t.startsWith("raidplan"));
        assertTrue(temRaidplan, "URL do raidplan deveria virar token 'raidplan ...'");
        assertFalse(tokens.contains("https"));
    }

    @Test
    void descartaNumerosPurosCjkETokensCurtos() {
        List<String> tokens = StratsTokenizer.tokenize("p1 p2 123 ぬけまる ok hectorbin");
        assertFalse(tokens.contains("123"));
        assertFalse(tokens.contains("ぬけまる"));
        assertFalse(tokens.contains("ok")); // < 3 chars
        assertTrue(tokens.contains("hectorbin"));
    }
}