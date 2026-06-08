package org.chibot.Commands.PartyFinderCommands;

import org.chibot.Database.PfRepository;
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
    // O acumulo de strats so considera o Aether (recorte do bot).
    private static final String STRATS_DATA_CENTER = "Aether";

    private final PfRepository repo = new PfRepository();

    private List<PfListing> cache;
    private Instant fetchedAt;

    public PartyFinderService() {
        // Esquenta o cache com o ultimo snapshot do banco, pra que o /pf ja tenha
        // o que mostrar logo apos um restart (mesmo antes do primeiro scraping).
        cache = repo.loadSnapshot();
        fetchedAt = repo.lastFetchedAt();
    }

    /**
     * Retorna as listagens, usando o cache se ainda estiver fresco (&lt; 5 min).
     * Se o xivpf.com falhar, cai pro ultimo snapshot guardado (banco/cache) em
     * vez de propagar o erro — so propaga se nao houver nada pra mostrar.
     */
    public synchronized List<PfListing> getListings() throws IOException {
        boolean fresh = Duration.between(fetchedAt, Instant.now()).compareTo(CACHE_TTL) < 0;
        if (fresh && !cache.isEmpty()) {
            return cache;
        }

        try {
            Document doc = Jsoup.connect(URL)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .get();

            cache = parse(doc);
            fetchedAt = Instant.now();
            repo.saveSnapshot(cache, fetchedAt);
            // Acumula os tokens de strat das listagens novas (so Aether), pro /strats.
            repo.indexTokens(cache, STRATS_DATA_CENTER, fetchedAt);
            log.info("Party Finder atualizado: {} listagem(ns) do xivpf.com.", cache.size());
            return cache;
        } catch (IOException e) {
            if (!cache.isEmpty()) {
                log.warn("xivpf.com indisponivel; usando o ultimo snapshot ({} listagem(ns)).",
                        cache.size(), e);
                return cache;
            }
            throw e; // nada em cache/banco — deixa o comando avisar o usuario
        }
    }

    /** Quando o cache foi preenchido pela ultima vez (Instant.EPOCH se nunca). */
    public synchronized Instant lastUpdated() {
        return fetchedAt;
    }

    /**
     * Top strats (tokens acumulados) das duties cujo nome contem
     * {@code dutySubstring}. Tenta atualizar o acumulo antes (best-effort): se o
     * xivpf.com falhar, responde com o que ja houver no banco.
     */
    public List<PfRepository.TokenCount> topStrats(String dutySubstring, int limit) {
        try {
            getListings(); // refresca o cache e acumula tokens novos
        } catch (IOException e) {
            log.warn("Nao deu pra atualizar antes do /strats; usando o acumulo atual.", e);
        }
        return repo.topTokens(dutySubstring, limit);
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
                    text(el.selectFirst(".item.updated .text")),
                    parseComp(el)
            ));
        }
        return out;
    }

    /**
     * Le os slots da party na ordem e monta uma lista de tokens separados por
     * virgula. Slot preenchido vira o codigo do job (ex.: "WAR", "WHM"); vaga
     * aberta vira "-" + role ("-T", "-H", "-D", ou "-*" pra varias roles).
     * Ex.: "WAR,WHM,NIN,-D,-D,-*".
     */
    private static String parseComp(Element listing) {
        List<String> tokens = new ArrayList<>();
        for (Element slot : listing.select(".party .slot")) {
            if (slot.hasClass("filled")) {
                String job = slot.attr("title").trim(); // ex.: "WAR"
                tokens.add(job.isEmpty() ? "-" + roleOf(slot) : job);
            } else {
                tokens.add("-" + roleOf(slot)); // vaga aberta
            }
        }
        return String.join(",", tokens);
    }

    /** Role de um slot a partir das classes: T/H/D, ou '*' se aceitar varias. */
    private static char roleOf(Element slot) {
        boolean tank = slot.hasClass("tank");
        boolean healer = slot.hasClass("healer");
        boolean dps = slot.hasClass("dps");
        int n = (tank ? 1 : 0) + (healer ? 1 : 0) + (dps ? 1 : 0);
        if (n == 1) {
            return tank ? 'T' : healer ? 'H' : 'D';
        }
        return '*';
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