package org.chibot.Music;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Resolve videos do YouTube com o yt-dlp: extrai a URL direta do audio
 * (googlevideo.com) pro Lavalink tocar como stream HTTP comum, sem passar
 * pelo plugin do YouTube (que sofre bloqueio anti-bot em IP de datacenter).
 *
 * Se existir um cookies.txt (exportado do navegador) em {@link #COOKIES_PATH},
 * ele e usado — e o jeito mais confiavel de fugir do "confirm you're not a bot".
 */
public final class YtDlpResolver {

    /** Resultado da extracao: metadados + URL direta do stream de audio. */
    public record Resolved(String title, String author, long durationMs,
                           String pageUrl, String thumbnail, String streamUrl) {

        public YtMeta toMeta() {
            return new YtMeta(title, author, durationMs, pageUrl, thumbnail);
        }
    }

    public static class YtDlpException extends Exception {
        public YtDlpException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(YtDlpResolver.class);
    private static final int TIMEOUT_SECONDS = 30;

    // No container, data/ e o volume persistente (mesmo lugar do ChiData.db).
    private static final Path COOKIES_PATH = Paths.get(
            System.getenv().getOrDefault("YTDLP_COOKIES", "data/yt-cookies.txt"));

    // URL do bgutil-ytdlp-pot-provider: gera os poTokens que satisfazem a
    // verificacao anti-bot do YouTube sem precisar de conta/cookies.
    private static final String POT_PROVIDER = System.getenv("YTDLP_POT_PROVIDER");

    private YtDlpResolver() {
    }

    /**
     * Roda o yt-dlp e devolve metadados + URL do audio. Bloqueia ate o
     * processo terminar (chame fora da thread de eventos do JDA).
     *
     * @param identifier URL do YouTube ou {@code ytsearch1:termo de busca}
     */
    public static Resolved resolve(String identifier) throws YtDlpException {
        // O yt-dlp regrava os cookies atualizados no proprio arquivo ao terminar.
        // Se o arquivo for de outro dono (ex.: copiado via "docker cp" como root),
        // isso da PermissionError e derruba a extracao. Por isso passamos uma
        // copia temporaria descartavel: o yt-dlp escreve nela, o original fica
        // intacto e a permissao dele deixa de importar.
        Path cookiesCopy = null;
        if (Files.exists(COOKIES_PATH)) {
            try {
                cookiesCopy = Files.createTempFile("yt-cookies", ".txt");
                Files.copy(COOKIES_PATH, cookiesCopy,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                log.warn("Nao consegui copiar o cookies; seguindo sem ele.", e);
                cookiesCopy = null;
            }
        }
        try {
            return runYtDlp(identifier, cookiesCopy);
        } finally {
            if (cookiesCopy != null) {
                try {
                    Files.deleteIfExists(cookiesCopy);
                } catch (IOException e) {
                    log.debug("Nao consegui apagar a copia temporaria do cookies.", e);
                }
            }
        }
    }

    private static Resolved runYtDlp(String identifier, Path cookies) throws YtDlpException {
        List<String> cmd = new ArrayList<>(List.of(
                "yt-dlp",
                "--no-playlist",
                "--no-warnings",
                "--quiet",
                "-f", "bestaudio[acodec=opus]/bestaudio/best",
                "--print", "%(title)s",
                "--print", "%(uploader)s",
                "--print", "%(duration)s",
                "--print", "%(webpage_url)s",
                "--print", "%(thumbnail)s",
                "--print", "%(url)s",
                // web_embedded/tv_simply contornam restricao de idade sem login
                // quando o video permite embed ("default" mantem os clients
                // normais). Restricao com embed bloqueado so com cookies.txt.
                "--extractor-args", "youtube:player_client=default,web_embedded,tv_simply",
                // Baixa (e cacheia) o script EJS que resolve os desafios de
                // assinatura no deno; sem ele o YouTube so entrega storyboards.
                "--remote-components", "ejs:github"));
        if (POT_PROVIDER != null && !POT_PROVIDER.isBlank()) {
            cmd.add("--extractor-args");
            cmd.add("youtubepot-bgutilhttp:base_url=" + POT_PROVIDER);
        }
        if (cookies != null) {
            cmd.add("--cookies");
            cmd.add(cookies.toString());
        }
        cmd.add("--");
        cmd.add(identifier);

        log.info("Rodando yt-dlp: {}", String.join(" ", cmd));

        Process process;
        try {
            process = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            log.error("Falha ao iniciar o yt-dlp", e);
            throw new YtDlpException("yt-dlp não está instalado ou não iniciou.");
        }

        List<String> stdout;
        String stderr;
        try {
            // stderr em outra thread: ler os dois em sequencia na mesma thread
            // pode travar se um dos buffers do processo encher.
            var stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return String.join(" | ", readLines(process.getErrorStream()));
                } catch (IOException e) {
                    return "";
                }
            });
            stdout = readLines(process.getInputStream());
            stderr = stderrFuture.join();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new YtDlpException("yt-dlp demorou demais (mais de "
                        + TIMEOUT_SECONDS + "s) e foi cancelado.");
            }
        } catch (IOException e) {
            process.destroyForcibly();
            log.error("Falha lendo a saida do yt-dlp", e);
            throw new YtDlpException("falha lendo a saída do yt-dlp.");
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new YtDlpException("extração interrompida.");
        }

        if (process.exitValue() != 0) {
            log.warn("yt-dlp saiu com codigo {} pra '{}': {}",
                    process.exitValue(), identifier, stderr);
            throw new YtDlpException(friendlyError(stderr));
        }
        if (stdout.size() < 6) {
            log.warn("Saida inesperada do yt-dlp pra '{}': {}", identifier, stdout);
            throw new YtDlpException("o yt-dlp não devolveu os dados esperados.");
        }

        // Mesma ordem dos --print acima.
        String title = stdout.get(0);
        String author = stdout.get(1);
        long durationMs = parseDurationMs(stdout.get(2));
        String pageUrl = stdout.get(3);
        String thumbnail = emptyToNull(stdout.get(4));
        String streamUrl = stdout.get(5);
        return new Resolved(title, author, durationMs, pageUrl, thumbnail, streamUrl);
    }

    private static List<String> readLines(java.io.InputStream stream) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /** "245" (segundos) -> ms; "NA" (live/desconhecido) -> 0. */
    private static long parseDurationMs(String duration) {
        try {
            return (long) (Double.parseDouble(duration) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() || "NA".equals(value) ? null : value;
    }

    /** Traduz os erros mais comuns do yt-dlp pra algo que da pra mostrar no Discord. */
    private static String friendlyError(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return "o yt-dlp falhou sem detalhes.";
        }
        String lower = stderr.toLowerCase();
        // "Sign in to confirm your age" tambem contem "sign in to confirm",
        // entao a restricao de idade precisa ser checada antes do anti-bot.
        if (lower.contains("confirm your age") || lower.contains("age-restricted")) {
            return "vídeo com restrição de idade — esse só com cookies.txt de conta logada.";
        }
        if (lower.contains("sign in to confirm") || lower.contains("not a bot")) {
            return "o YouTube pediu verificação anti-bot — precisa de um cookies.txt (veja data/yt-cookies.txt).";
        }
        if (lower.contains("video unavailable")) {
            return "vídeo indisponível (removido, privado ou bloqueado na região).";
        }
        if (lower.contains("no video results") || lower.contains("did not get any data")) {
            return "não achei resultados pra essa busca.";
        }
        // Mostra so o final do stderr pra nao estourar o limite do Discord.
        return stderr.length() > 180 ? "..." + stderr.substring(stderr.length() - 180) : stderr;
    }
}
