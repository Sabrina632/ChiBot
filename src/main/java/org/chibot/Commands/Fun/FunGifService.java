package org.chibot.Commands.Fun;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Busca gifs de anime na API publica (sem chave) do nekos.best, usada pelos
 * comandos de roleplay (hug/kiss/pat/slap). Cada chamada devolve um gif
 * aleatorio da categoria pedida junto com o nome do anime de onde ele saiu.
 */
final class FunGifService {

    private static final String BASE = "https://nekos.best/api/v2/";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Url do gif + nome do anime (pode vir vazio). */
    record Gif(String url, String animeName) {
    }

    /**
     * Busca um gif da categoria ({@code hug}, {@code kiss}, {@code pat},
     * {@code slap}, ...) de forma assincrona pra nao travar a thread de eventos.
     */
    CompletableFuture<Gif> fetch(String category) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(BASE + category))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("nekos.best respondeu HTTP " + response.statusCode());
                    }
                    JSONArray results = new JSONObject(response.body()).optJSONArray("results");
                    if (results == null || results.isEmpty()) {
                        throw new RuntimeException("nekos.best nao devolveu nenhum gif");
                    }
                    JSONObject first = results.getJSONObject(0);
                    return new Gif(first.getString("url"), first.optString("anime_name", ""));
                });
    }
}