package org.chibot.Commands.PartyFinderCommands;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Busca e parseia as listagens de Party Finder do xivpf.com.
 *
 * <p>O xivpf.com e crowdsourced (alimentado pelo plugin Remote Party Finder),
 * entao a cobertura e parcial: so aparece o PF de quem roda o plugin. O dono do
 * site pede intervalo minimo de ~5 min entre buscas, por isso o resultado fica
 * em cache por esse tempo. A classe e thread-safe (metodo sincronizado).
 */
public class PartyFinderService {

    private static final Logger log = LoggerFactory.getLogger(PartyFinderService.class);

    private static final String URL = "https://xivpf.com/listings";
    private static final String USER_AGENT =
            "ChiBot/1.0 (Discord bot; +https://github.com/Sabrina632/ChiBot)";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int TIMEOUT_MS = 20_000;

    private List<PfListing> cache = List.of();
    private Instant fetchedAt = Instant.EPOCH;

    /** Retorna as listagens, usando o cache se ainda estiver fresco (< 5 min). */
    public synchronized List<PfListing> getListings() throws IOException {
        boolean fresh = Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) < 0;
        if (fresh && !cache.isEmpty()) {
            return cache;
        }

        Document doc = Jsoup.connect(URL)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .get();

        cache = parse(doc);
        fetchedAt = Instant.now();
        log.info("Party Finder atualizado: {} listagem(ns) do xivpf.com.", cache.size());
        return cache;
    }

    /** Quando o cache foi preenchido pela ultima vez (Instant.EPOCH se nunca). */
    public synchronized Instant lastUpdated() {
        return fetchedAt;
    }

    private static List<PfListing> parse(Document doc) {
        List<PfListing> out = new ArrayList<>();
        for (Element el : doc.select("div.listing")) {
            String total = text(el.selectFirst(".party .total")); // "5/8"
            int filled = 0;
            int slots = 0;
            if (total != null && total.contains("/")) {
                String[] parts = total.split("/", 2);
                filled = parseInt(parts[0]);
                slots = parseInt(parts[1]);
            }

            out.add(new PfListing(
                    el.attr("data-id"),
                    el.attr("data-centre"),
                    el.attr("data-pf-category"),
                    text(el.selectFirst(".duty")),
                    text(el.selectFirst(".description")),
                    total,
                    filled,
                    slots,
                    text(el.selectFirst(".middle .stat .value")),
                    text(el.selectFirst(".item.creator .text")),
                    text(el.selectFirst(".item.world .text")),
                    text(el.selectFirst(".item.expires .text")),
                    text(el.selectFirst(".item.updated .text"))
            ));
        }
        return out;
    }

    private static String text(Element e) {
        return e == null ? null : e.text().trim();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}