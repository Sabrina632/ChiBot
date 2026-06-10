package org.chibot.Music;

/**
 * Metadados de um video resolvido pelo yt-dlp, anexados na {@code Track} via
 * userData. Sem isso a fila mostraria a URL crua do googlevideo, ja que o
 * Lavalink toca o stream HTTP sem saber titulo/duracao.
 *
 * Campos publicos + construtor vazio porque o lavalink-client (de)serializa
 * com Jackson.
 */
public class YtMeta {

    public String title;
    public String author;
    public long durationMs;
    public String pageUrl;
    public String thumbnail;

    public YtMeta() {
    }

    public YtMeta(String title, String author, long durationMs, String pageUrl, String thumbnail) {
        this.title = title;
        this.author = author;
        this.durationMs = durationMs;
        this.pageUrl = pageUrl;
        this.thumbnail = thumbnail;
    }
}
