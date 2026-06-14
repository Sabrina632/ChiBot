package org.chibot.Harem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente da API GraphQL publica do AniList (sem chave), de onde vem os
 * personagens do sistema de waifu/husbando. Cada chamada busca uma pagina de
 * personagens ordenados por favoritos; sorteando a pagina, o roll fica
 * aleatorio dentro do top {@code MAX_PAGE * PER_PAGE} mais populares.
 */
public class AniListClient {

    private static final String ENDPOINT = "https://graphql.anilist.co";

    public static final int PER_PAGE = 50;
    /** Paginas sorteaveis: top ~8000 personagens mais favoritados do AniList. */
    public static final int MAX_PAGE = 160;

    private static final String QUERY = """
            query ($page: Int, $perPage: Int) {
              Page(page: $page, perPage: $perPage) {
                characters(sort: FAVOURITES_DESC) {
                  id
                  name { full }
                  gender
                  favourites
                  image { large }
                  media(sort: POPULARITY_DESC, perPage: 1) {
                    nodes { title { romaji english } }
                  }
                }
              }
            }""";

    private static final String SEARCH_QUERY = """
            query ($q: String) {
              Character(search: $q) {
                id
                name { full }
                gender
                favourites
                image { large }
                media(sort: POPULARITY_DESC, perPage: 1) {
                  nodes { title { romaji english } }
                }
              }
            }""";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Busca uma pagina de personagens populares (descarta os sem nome ou sem imagem). */
    public List<AnimeCharacter> fetchPage(int page) throws IOException, InterruptedException {
        JSONObject body = new JSONObject()
                .put("query", QUERY)
                .put("variables", new JSONObject().put("page", page).put("perPage", PER_PAGE));

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("AniList respondeu HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    /**
     * Busca um unico personagem pelo nome (o match mais relevante do AniList).
     * Retorna null se nao achar ou se vier sem nome/imagem. Usado pra montar os
     * emojis dos badges de personagem.
     */
    public AnimeCharacter searchCharacter(String query) throws IOException, InterruptedException {
        JSONObject body = new JSONObject()
                .put("query", SEARCH_QUERY)
                .put("variables", new JSONObject().put("q", query));

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("AniList respondeu HTTP " + response.statusCode());
        }
        JSONObject data = new JSONObject(response.body()).optJSONObject("data");
        JSONObject c = data == null ? null : data.optJSONObject("Character");
        if (c == null) {
            return null;
        }
        String name = c.getJSONObject("name").optString("full", "");
        JSONObject image = c.optJSONObject("image");
        String imageUrl = image == null ? "" : image.optString("large", "");
        if (name.isBlank() || imageUrl.isBlank()) {
            return null;
        }
        int favourites = c.optInt("favourites", 0);
        return new AnimeCharacter(c.getLong("id"), name,
                c.isNull("gender") ? null : c.optString("gender", null),
                seriesOf(c), imageUrl, favourites, kakeraValue(favourites));
    }

    private List<AnimeCharacter> parse(String json) {
        List<AnimeCharacter> out = new ArrayList<>();
        JSONArray characters = new JSONObject(json)
                .getJSONObject("data")
                .getJSONObject("Page")
                .getJSONArray("characters");
        for (int i = 0; i < characters.length(); i++) {
            JSONObject c = characters.getJSONObject(i);
            String name = c.getJSONObject("name").optString("full", "");
            JSONObject image = c.optJSONObject("image");
            String imageUrl = image == null ? "" : image.optString("large", "");
            if (name.isBlank() || imageUrl.isBlank()) {
                continue;
            }
            int favourites = c.optInt("favourites", 0);
            out.add(new AnimeCharacter(
                    c.getLong("id"),
                    name,
                    c.isNull("gender") ? null : c.optString("gender", null),
                    seriesOf(c),
                    imageUrl,
                    favourites,
                    kakeraValue(favourites)));
        }
        return out;
    }

    private static String seriesOf(JSONObject character) {
        JSONObject media = character.optJSONObject("media");
        JSONArray nodes = media == null ? null : media.optJSONArray("nodes");
        if (nodes == null || nodes.isEmpty()) {
            return "Origem desconhecida";
        }
        JSONObject title = nodes.getJSONObject(0).optJSONObject("title");
        if (title == null) {
            return "Origem desconhecida";
        }
        String romaji = title.optString("romaji", "");
        if (!romaji.isBlank()) {
            return romaji;
        }
        String english = title.optString("english", "");
        return english.isBlank() ? "Origem desconhecida" : english;
    }

    /** Valor em kakera derivado da popularidade (favoritos no AniList), entre 15 e 1200. */
    static int kakeraValue(int favourites) {
        return (int) Math.max(15, Math.min(1200, Math.round(25 + favourites / 45.0)));
    }
}