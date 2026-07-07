package org.chibot.Database;

import org.chibot.Commands.PartyFinderCommands.PfListing;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PfRepositoryTest {

    private static PfListing sample(String id, String duty) {
        return new PfListing(id, "Aether", "HighEndDuty", duty, "BiS only",
                "5/8", 5, 8, "705", "Chi Bot @ Faerie", "Faerie",
                "in 30 minutes", "now", "WAR,WHM,NIN,-D,-D,-D");
    }

    @Test
    void salvaERecuperaSnapshot() {
        PfRepository repo = new PfRepository(PgTestDb.database("pf_basico"));
        Instant now = Instant.now();

        repo.saveSnapshot(List.of(sample("a", "FRU (Ultimate)"), sample("b", "M5S (Savage)")), now);

        List<PfListing> loaded = repo.loadSnapshot();
        assertEquals(2, loaded.size());
        assertEquals(now.toEpochMilli(), repo.lastFetchedAt().toEpochMilli());

        PfListing a = loaded.stream().filter(l -> l.id().equals("a")).findFirst().orElseThrow();
        assertEquals("FRU (Ultimate)", a.duty());
        assertEquals(5, a.filled());
        assertEquals(8, a.total());
        assertEquals("WAR,WHM,NIN,-D,-D,-D", a.comp());
    }

    @Test
    void snapshotNovoSubstituiOAntigo() {
        PfRepository repo = new PfRepository(PgTestDb.database("pf_substitui"));

        repo.saveSnapshot(List.of(sample("a", "FRU (Ultimate)"), sample("b", "M5S (Savage)")), Instant.now());
        repo.saveSnapshot(List.of(sample("c", "TOP (Ultimate)")), Instant.now());

        List<PfListing> loaded = repo.loadSnapshot();
        assertEquals(1, loaded.size());
        assertEquals("c", loaded.get(0).id());
    }

    @Test
    void semSnapshotRetornaVazio() {
        PfRepository repo = new PfRepository(PgTestDb.database("pf_vazio"));
        assertTrue(repo.loadSnapshot().isEmpty());
        assertEquals(Instant.EPOCH, repo.lastFetchedAt());
    }

    private static PfListing withDesc(String id, String duty, String dc, String desc) {
        return new PfListing(id, dc, "HighEndDuty", duty, desc,
                "5/8", 5, 8, "705", "Chi Bot @ Faerie", "Faerie",
                "in 30 minutes", "now", "WAR,WHM,NIN,-D,-D,-D");
    }

    @Test
    void acumulaTokensEContaCadaPfUmaVez() {
        PfRepository repo = new PfRepository(PgTestDb.database("pf_acumula"));
        Instant now = Instant.now();

        List<PfListing> snapshot = List.of(
                withDesc("a", "Futures Rewritten (Ultimate)", "Aether", "Hector strat"),
                withDesc("b", "Futures Rewritten (Ultimate)", "Aether", "hector reclear"));
        repo.indexTokens(snapshot, "Aether", now);
        // reindexar o mesmo snapshot nao deve contar de novo (gate por id)
        repo.indexTokens(snapshot, "Aether", now);

        List<PfRepository.TokenCount> top = repo.topTokens("Futures Rewritten", 10);
        PfRepository.TokenCount hector = top.stream()
                .filter(t -> t.token().equals("hector")).findFirst().orElseThrow();
        assertEquals(2, hector.count(), "hector aparece em 2 PF, contados uma vez cada");
    }

    @Test
    void indexacaoRespeitaDataCenter() {
        PfRepository repo = new PfRepository(PgTestDb.database("pf_datacenter"));
        repo.indexTokens(List.of(
                withDesc("a", "Futures Rewritten (Ultimate)", "Aether", "hector"),
                withDesc("b", "Futures Rewritten (Ultimate)", "Primal", "hector")),
                "Aether", Instant.now());

        List<PfRepository.TokenCount> top = repo.topTokens("Futures Rewritten", 10);
        assertEquals(1, top.stream().filter(t -> t.token().equals("hector"))
                .findFirst().orElseThrow().count(), "so o PF do Aether conta");
    }

    @Test
    void semBancoDegradaSemQuebrar() {
        PfRepository repo = new PfRepository(new BrokenDataSource());

        repo.saveSnapshot(List.of(sample("a", "FRU (Ultimate)")), Instant.now());
        assertTrue(repo.loadSnapshot().isEmpty());
        assertEquals(Instant.EPOCH, repo.lastFetchedAt());

        repo.indexTokens(List.of(withDesc("a", "FRU (Ultimate)", "Aether", "hector")), "Aether", Instant.now());
        assertTrue(repo.topTokens("FRU", 10).isEmpty());
    }
}
