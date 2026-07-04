package org.chibot.Harem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiantBombClientTest {

    /**
     * Resposta tipica do endpoint /characters/: dois personagens completos,
     * um com a imagem placeholder do site, um sem nome e um sem jogo de origem.
     */
    private static final String JSON = """
            {
              "error": "OK",
              "status_code": 1,
              "results": [
                {
                  "id": 2977,
                  "name": "Tifa Lockhart",
                  "gender": 2,
                  "deck": "Lutadora e dona do bar Setimo Ceu.",
                  "image": { "medium_url": "https://www.giantbomb.com/a/uploads/scale_medium/tifa.jpg" },
                  "first_appeared_in_game": { "name": "Final Fantasy VII" }
                },
                {
                  "id": 1,
                  "name": "Mario",
                  "gender": 1,
                  "deck": "O encanador mais famoso dos videogames.",
                  "image": { "medium_url": "https://www.giantbomb.com/a/uploads/scale_medium/mario.jpg" },
                  "first_appeared_in_game": { "name": "Donkey Kong" }
                },
                {
                  "id": 50,
                  "name": "Sem Foto",
                  "gender": 0,
                  "deck": null,
                  "image": { "medium_url": "https://www.giantbomb.com/a/uploads/scale_medium/3026329-gb_default-16_9.png" },
                  "first_appeared_in_game": { "name": "Jogo Obscuro" }
                },
                {
                  "id": 51,
                  "name": "",
                  "gender": 2,
                  "deck": null,
                  "image": { "medium_url": "https://img/x.jpg" },
                  "first_appeared_in_game": { "name": "Jogo X" }
                },
                {
                  "id": 60,
                  "name": "Misterioso",
                  "gender": 0,
                  "deck": null,
                  "image": { "medium_url": "https://img/misterioso.jpg" },
                  "first_appeared_in_game": null
                }
              ]
            }""";

    @Test
    void parseConverteEDescartaInvalidos() throws IOException {
        List<GameCharacter> chars = GiantBombClient.parse(JSON);
        // Placeholder (gb_default) e sem nome caem fora; sobram 3.
        assertEquals(3, chars.size());

        GameCharacter tifa = chars.get(0);
        assertEquals(-2977, tifa.id());
        assertEquals("Tifa Lockhart", tifa.name());
        assertTrue(tifa.isFemale());
        assertFalse(tifa.isMale());
        assertEquals("Final Fantasy VII", tifa.game());
        assertEquals("https://www.giantbomb.com/a/uploads/scale_medium/tifa.jpg", tifa.imageUrl());

        GameCharacter mario = chars.get(1);
        assertEquals(-1, mario.id());
        assertTrue(mario.isMale());

        GameCharacter misterioso = chars.get(2);
        assertNull(misterioso.gender());
        assertFalse(misterioso.isFemale());
        assertEquals("Origem desconhecida", misterioso.game());
    }

    @Test
    void parseFalhaComStatusDeErro() {
        assertThrows(IOException.class, () -> GiantBombClient.parse(
                "{\"error\":\"Invalid API Key\",\"status_code\":100,\"results\":[]}"));
    }

    @Test
    void kakeraDeterministicoENaFaixa() {
        // Mesmo id, mesmo valor — sempre.
        assertEquals(GiantBombClient.kakeraValue(2977, true), GiantBombClient.kakeraValue(2977, true));
        assertEquals(GiantBombClient.kakeraValue(2977, false), GiantBombClient.kakeraValue(2977, false));
        for (long id = 1; id <= 500; id++) {
            int sem = GiantBombClient.kakeraValue(id, false);
            int com = GiantBombClient.kakeraValue(id, true);
            assertTrue(sem >= 15 && sem <= 400, "sem deck fora da faixa: " + sem);
            assertTrue(com >= 15 && com <= 1200, "com deck fora da faixa: " + com);
            assertTrue(com >= sem, "deck deveria valorizar o personagem");
        }
    }

    @Test
    void clienteSemChaveFicaIndisponivel() {
        assertFalse(new GiantBombClient("").isAvailable());
        assertFalse(new GiantBombClient(null).isAvailable());
        assertFalse(new GiantBombClient("   ").isAvailable());
        assertTrue(new GiantBombClient("abc123").isAvailable());
    }
}
