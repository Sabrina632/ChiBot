package org.chibot.Commands.PartyFinderCommands;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class XivpfApiClientTest {

    private static final XivpfApiClient client = new XivpfApiClient();

    @Test
    void parseiaCamposEResolveDcPorMundo() {
        // formato real da API: array de { "listing": {...} }
        String json = """
                [
                  {"listing": {
                    "id": 60119,
                    "category": "HighEndDuty",
                    "min_item_level": 730,
                    "current_world": {"id": 65, "name": "Faerie"},
                    "duty_info": {"name": {"en": "Futures Rewritten (Ultimate)", "ja": "x"}},
                    "description": {"en": "C41 fmbg lesbin", "ja": "y"}
                  }}
                ]
                """;

        List<PfListing> out = client.parseJson(json);
        assertEquals(1, out.size());
        PfListing l = out.get(0);
        assertEquals("60119", l.id());
        assertEquals("Aether", l.dataCentre(), "Faerie deveria virar Aether");
        assertEquals("Futures Rewritten (Ultimate)", l.duty());
        assertEquals("C41 fmbg lesbin", l.description());
    }

    @Test
    void aplicaOverrideQuandoDutyInfoNull() {
        // fight nova (duty_info null) deduzida por palavra-chave na descricao
        String json = """
                [
                  {"listing": {
                    "id": 1,
                    "category": "HighEndDuty",
                    "min_item_level": 0,
                    "current_world": {"name": "Gilgamesh"},
                    "duty_info": null,
                    "description": {"en": "p3 nukemaru farm graven"}
                  }}
                ]
                """;

        PfListing l = client.parseJson(json).get(0);
        assertEquals("Dancing Mad (Ultimate)", l.duty());
        assertEquals("Aether", l.dataCentre());
    }

    @Test
    void mundoDesconhecidoFicaSemDc() {
        String json = """
                [
                  {"listing": {
                    "id": 2,
                    "current_world": {"name": "Mundo Inexistente"},
                    "duty_info": {"name": {"en": "The Omega Protocol (Ultimate)"}},
                    "description": {"en": "reclear"}
                  }}
                ]
                """;

        PfListing l = client.parseJson(json).get(0);
        assertNotNull(l.duty());
        assertEquals(null, l.dataCentre(), "mundo fora do mapa nao resolve DC");
    }
}