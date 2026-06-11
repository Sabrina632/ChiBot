package org.chibot.Music;

import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Busca por nome usando a YouTube Data API v3 (so a chave de API; busca
 * publica nao precisa de OAuth). E mais rapida e precisa que o ytsearch do
 * Lavalink, mas so encontra o video — quem toca continua sendo o Lavalink
 * (a Data API nao da acesso ao audio).
 *
 * Qualquer falha (quota estourada, rede, chave invalida) devolve null e o
 * chamador cai no ytsearch como antes.
 */
public final class YtSearch {

    private static final Logger log = LoggerFactory.getLogger(YtSearch.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    public YtSearch(String apiKey) {
        this.apiKey = apiKey;
    }

    /** Devolve a URL do primeiro resultado (watch?v=...) ou null se nao achou/falhou. */
    public String findVideoUrl(String query) {
        String videoId = searchFirstId(query, "video", "videoId");
        return videoId == null ? null : "https://www.youtube.com/watch?v=" + videoId;
    }

    /** Devolve a URL da primeira playlist encontrada (playlist?list=...) ou null. */
    public String findPlaylistUrl(String query) {
        String playlistId = searchFirstId(query, "playlist", "playlistId");
        return playlistId == null ? null : "https://www.youtube.com/playlist?list=" + playlistId;
    }

    /** Primeiro resultado da busca do tipo pedido; idField e o campo do id (videoId/playlistId). */
    private String searchFirstId(String query, String type, String idField) {
        String url = "https://www.googleapis.com/youtube/v3/search"
                + "?part=snippet&type=" + type + "&maxResults=1"
                + "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&key=" + apiKey;

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Data API respondeu {} pra busca '{}': {}",
                        response.statusCode(), query, snippet(response.body()));
                return null;
            }
            JSONArray items = new JSONObject(response.body()).optJSONArray("items");
            if (items == null || items.isEmpty()) {
                return null;
            }
            String id = items.getJSONObject(0)
                    .optJSONObject("id", new JSONObject())
                    .optString(idField, "");
            return id.isBlank() ? null : id;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Falha na busca da Data API pra '{}': {}", query, e.toString());
            return null;
        }
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }
}