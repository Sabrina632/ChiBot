package org.chibot.Harem;

import org.chibot.Config.ChiConfig;
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
 * Cliente da API REST do Giant Bomb (giantbomb.com/api), de onde vem os
 * personagens de jogos do harem. Cada chamada busca uma pagina de personagens
 * ordenados por id crescente — os ids baixos sao os classicos cadastrados
 * primeiro no site (Mario, Link...), entao sortear o offset dentro dos
 * primeiros {@code MAX_OFFSET} mantem os rolls reconheciveis.
 *
 * <p>Sem {@code GIANTBOMB_API_KEY} configurada, {@link #isAvailable()} retorna
 * false e os comandos de roll de jogos respondem um aviso amigavel.
 */
public class GiantBombClient {

    private static final String ENDPOINT = "https://www.giantbomb.com/api/characters/";
    private static final String FIELDS = "id,name,gender,image,first_appeared_in_game,deck";

    public static final int PER_PAGE = 100;
    /** Offsets sorteaveis: ~8000 primeiros personagens cadastrados no site. */
    public static final int MAX_OFFSET = 7900;

    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public GiantBombClient() {
        this(apiKeyFromConfig());
    }

    /** Construtor com chave explicita (testes). */
    GiantBombClient(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    private static String apiKeyFromConfig() {
        ChiConfig config = ChiConfig.get();
        if (config != null) {
            return config.getGiantBombApiKey();
        }
        String fromEnv = System.getenv("GIANTBOMB_API_KEY");
        return fromEnv == null ? "" : fromEnv;
    }

    /** false = sem chave configurada (rolls de jogos desligados). */
    public boolean isAvailable() {
        return !apiKey.isBlank();
    }

    /** Busca uma pagina de personagens a partir do offset (descarta os sem nome/imagem). */
    public List<GameCharacter> fetchPage(int offset) throws IOException, InterruptedException {
        String url = ENDPOINT + "?api_key=" + apiKey + "&format=json&limit=" + PER_PAGE
                + "&offset=" + offset + "&sort=id:asc&field_list=" + FIELDS;

        // O Giant Bomb bloqueia o User-Agent padrao do JDK — precisa ser custom.
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "ChiBot/1.0 (bot de Discord)")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Giant Bomb respondeu HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    /** Package-private pros testes: converte o JSON da API em personagens rolaveis. */
    static List<GameCharacter> parse(String json) throws IOException {
        JSONObject root = new JSONObject(json);
        if (root.optInt("status_code", -1) != 1) {
            throw new IOException("Giant Bomb retornou erro: " + root.optString("error", "desconhecido"));
        }
        List<GameCharacter> out = new ArrayList<>();
        JSONArray results = root.optJSONArray("results");
        if (results == null) {
            return out;
        }
        for (int i = 0; i < results.length(); i++) {
            JSONObject c = results.getJSONObject(i);
            String name = c.optString("name", "");
            JSONObject image = c.optJSONObject("image");
            String imageUrl = image == null ? "" : image.optString("medium_url", "");
            // "gb_default" e a imagem placeholder do site — personagem sem arte real.
            if (name.isBlank() || imageUrl.isBlank() || imageUrl.contains("gb_default")) {
                continue;
            }
            long gbId = c.getLong("id");
            boolean temDeck = !c.isNull("deck") && !c.optString("deck", "").isBlank();
            out.add(new GameCharacter(
                    -gbId,
                    name,
                    genderOf(c.optInt("gender", 0)),
                    gameOf(c),
                    imageUrl,
                    kakeraValue(gbId, temDeck)));
        }
        return out;
    }

    /** Giant Bomb codifica genero como int: 1 = masculino, 2 = feminino, 0 = desconhecido. */
    private static String genderOf(int gender) {
        return switch (gender) {
            case 1 -> "Male";
            case 2 -> "Female";
            default -> null;
        };
    }

    private static String gameOf(JSONObject character) {
        JSONObject game = character.optJSONObject("first_appeared_in_game");
        String name = game == null ? "" : game.optString("name", "");
        return name.isBlank() ? "Origem desconhecida" : name;
    }

    /**
     * Valor em kakera do personagem, deterministico (mesmo id = mesmo valor,
     * sempre): o Giant Bomb nao tem contagem de favoritos como o AniList, entao
     * um hash estavel do id vira 15..400, e quem tem descricao ({@code deck} —
     * sinal de personagem notavel) vale 3x, saturando em 1200.
     */
    static int kakeraValue(long gbId, boolean temDeck) {
        int base = 15 + (int) Math.floorMod(gbId * 2654435761L, 386);
        return temDeck ? Math.min(1200, base * 3) : base;
    }
}
