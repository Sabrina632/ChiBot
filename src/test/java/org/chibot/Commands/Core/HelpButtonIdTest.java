package org.chibot.Commands.Core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HelpButtonIdTest {

    @Test
    void homeRoundTrip() {
        HelpButtonId.Parsed p = HelpButtonId.decode(HelpButtonId.home("12345"));
        assertEquals("home", p.action());
        assertEquals("12345", p.invokerId());
        assertNull(p.category());
    }

    @Test
    void categoriaRoundTrip() {
        HelpButtonId.Parsed p = HelpButtonId.decode(HelpButtonId.cat("12345", "Harém"));
        assertEquals("cat", p.action());
        assertEquals("12345", p.invokerId());
        assertEquals("Harém", p.category());
    }

    @Test
    void categoriaComEspacoSobrevive() {
        // "Party Finder" tem espaço — não pode quebrar o parse.
        HelpButtonId.Parsed p = HelpButtonId.decode(HelpButtonId.cat("99", "Party Finder"));
        assertEquals("Party Finder", p.category());
    }

    @Test
    void idMalformadoOuDeOutroComponenteEhNull() {
        assertNull(HelpButtonId.decode(null));
        assertNull(HelpButtonId.decode("htrade:abc"));        // botão de outro sistema
        assertNull(HelpButtonId.decode("help:"));             // sem ação/invocador
        assertNull(HelpButtonId.decode("help:cat:99"));       // categoria faltando
        assertNull(HelpButtonId.decode("help:bogus:99"));     // ação desconhecida
    }
}