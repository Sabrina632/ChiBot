package org.chibot.Commands.PartyFinderCommands;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cliente da API JSON do xivpf (porte do {@code scraper/client.py} +
 * {@code scraper/parser.py} + override de duty do {@code config.py} do
 * xivpf-tokenizer). Busca {@code https://xivpf.com/api/listings} e devolve as
 * listagens com o que o /strats precisa: id, duty (en), descricao (en) e DC.
 *
 * <p>Diferente do {@link PartyFinderService} (que raspa o HTML pro /pf), aqui a
 * descricao vem ja em ingles e limpa, que e o que o tokenizer espera.
 */
final class XivpfApiClient {

    private static final String API_URL = "https://xivpf.com/api/listings";
    private static final String USER_AGENT =
            "ChiBot/1.0 (Discord bot; +https://github.com/Sabrina632/ChiBot)";
    private static final int TIMEOUT_MS = 20_000;

    /** Listagens parseadas (todos os DC; o filtro de Aether fica no acumulo). */
    List<PfListing> fetchListings() throws IOException {
        String body = Jsoup.connect(API_URL)
                .ignoreContentType(true)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .maxBodySize(0)
                .execute()
                .body();
        return parseJson(body);
    }

    /** Parseia o corpo JSON da API (array de {@code {"listing": {...}}}). */
    List<PfListing> parseJson(String body) {
        JSONArray arr = new JSONArray(body);
        List<PfListing> out = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            PfListing l = parse(arr.getJSONObject(i));
            if (l != null) {
                out.add(l);
            }
        }
        return out;
    }

    /** Um item da API ({@code {"listing": {...}}}) -> PfListing minimo. */
    private static PfListing parse(JSONObject raw) {
        JSONObject lst = raw.optJSONObject("listing");
        if (lst == null) {
            return null;
        }

        String id = lst.has("id") ? String.valueOf(lst.get("id")) : null;
        String category = lst.optString("category", null);
        int minIL = lst.optInt("min_item_level", 0);

        String world = name(lst.optJSONObject("current_world"));
        String dc = world == null ? null : XivpfWorlds.WORLD_TO_DC.get(world);

        String descEn = localized(lst.optJSONObject("description"));

        String duty = null;
        JSONObject dutyInfo = lst.optJSONObject("duty_info");
        if (dutyInfo != null) {
            duty = normalizeDutyName(localized(dutyInfo.optJSONObject("name")));
        }
        if (duty == null) {
            duty = applyOverride(category, minIL, descEn);
        }

        // So id/dc/duty/description importam pro acumulo; o resto fica vazio.
        return new PfListing(id, dc, category, duty, descEn,
                null, 0, 0, null, null, world, null, null, null);
    }

    /** Pega "en" (fallback ja/de/fr) de um objeto de nomes localizados. */
    private static String localized(JSONObject names) {
        if (names == null) {
            return null;
        }
        for (String lang : new String[]{"en", "ja", "de", "fr"}) {
            String v = names.optString(lang, null);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String name(JSONObject worldObj) {
        return worldObj == null ? null : worldObj.optString("name", null);
    }

    // --- Override de duty (porte do config.py do xivpf-tokenizer) ----------

    /** Normaliza nomes que o xivpf devolve diferente do nosso canonico. */
    private static String normalizeDutyName(String name) {
        if (name == null) {
            return null;
        }
        if (name.toLowerCase(Locale.ROOT).contains("dancing mad")) {
            return "Dancing Mad (Ultimate)";
        }
        return name;
    }

    /**
     * Duties que a API ainda nao reconhece ({@code duty_info: null}) sao
     * deduzidas por palavras-chave na descricao. Hoje cobre o Dancing Mad
     * (Ultimate); novas fights entram aqui ate o xivpf atualizar a base deles.
     */
    private static String applyOverride(String category, int minIL, String descEn) {
        if (descEn == null || descEn.isBlank()) {
            return null;
        }
        if ("HighEndDuty".equals(category) && containsAny(descEn,
                "arrows", "arrow", "graven", "forsaken", "merry", "telepo",
                "toxic", "xolo", "kff", "nukemaru", "yarn", "x13", "kefkabin",
                "p1", "p2", "p3", "p4", "weapon farm")) {
            return "Dancing Mad (Ultimate)";
        }
        return null;
    }

    private static boolean containsAny(String text, String... keywords) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}