package org.chibot.Commands.PartyFinderCommands;

import org.chibot.Database.PfRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Fonte das strats do {@code /strats}, igual ao xivpf-tokenizer: busca a API
 * JSON do xivpf, tokeniza a descricao (en) de cada PF do Aether e acumula a
 * contagem de cada termo por duty ao longo do tempo (cada PF conta uma vez,
 * via gate no {@link PfRepository}). O ranking so cresce conforme o bot roda.
 *
 * <p>Por educacao com o xivpf, so atualiza a cada {@link #CACHE_TTL}. Thread-safe
 * (metodos sincronizados).
 */
public class StratsService {

    private static final Logger log = LoggerFactory.getLogger(StratsService.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final String DATA_CENTER = "Aether";

    private final PfRepository repo = new PfRepository();
    private final XivpfApiClient client = new XivpfApiClient();

    private Instant lastIndexed = Instant.EPOCH;

    /**
     * Top strats (tokens acumulados) das duties cujo nome contem
     * {@code dutySubstring}. Atualiza o acumulo antes (best-effort): se a API
     * falhar, responde com o que ja houver no banco.
     */
    public synchronized List<PfRepository.TokenCount> topStrats(String dutySubstring, int limit) {
        refreshIfStale();
        return repo.topTokens(dutySubstring, limit);
    }

    private void refreshIfStale() {
        if (Duration.between(lastIndexed, Instant.now()).compareTo(CACHE_TTL) < 0) {
            return; // ainda fresco — nao bate na API
        }
        try {
            List<PfListing> listings = client.fetchListings();
            Instant now = Instant.now();
            repo.indexTokens(listings, DATA_CENTER, now);
            lastIndexed = now;
            log.info("Strats atualizadas: {} listagem(ns) da API do xivpf.", listings.size());
        } catch (IOException | RuntimeException e) {
            log.warn("Nao deu pra atualizar as strats pela API do xivpf; usando o acumulo atual.", e);
        }
    }
}