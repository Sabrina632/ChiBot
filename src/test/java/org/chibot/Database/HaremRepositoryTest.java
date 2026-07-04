package org.chibot.Database;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HaremRepositoryTest {

    private static final String GUILD = "g1";
    private static final String ANA = "u-ana";
    private static final String BIA = "u-bia";

    /** Banco em memoria — a mesma conexao vive enquanto o repo viver. */
    private static HaremRepository inMemory() {
        return new HaremRepository("jdbc:sqlite::memory:");
    }

    private static HaremRepository.Claim claim(long charId, String name, int kakera, String owner) {
        return new HaremRepository.Claim(charId, name, "Serie X", "https://img/x.png",
                kakera, owner, "Dona " + owner);
    }

    @Test
    void rollsRespeitamCotaEJanelaDaHora() {
        HaremRepository repo = inMemory();

        assertEquals(2, repo.tryUseRoll(GUILD, ANA, 100, 3));
        assertEquals(1, repo.tryUseRoll(GUILD, ANA, 100, 3));
        assertEquals(0, repo.tryUseRoll(GUILD, ANA, 100, 3));
        assertEquals(-1, repo.tryUseRoll(GUILD, ANA, 100, 3));

        // Outra hora reseta a cota; outro jogador tem cota propria.
        assertEquals(2, repo.tryUseRoll(GUILD, ANA, 101, 3));
        assertEquals(2, repo.tryUseRoll(GUILD, BIA, 100, 3));
    }

    @Test
    void soUmaPessoaCasaComCadaPersonagem() {
        HaremRepository repo = inMemory();

        assertTrue(repo.tryClaim(GUILD, claim(42, "Zero Two", 500, ANA), 1000));
        assertFalse(repo.tryClaim(GUILD, claim(42, "Zero Two", 500, BIA), 2000));

        HaremRepository.Claim dona = repo.findOwner(GUILD, 42);
        assertEquals(ANA, dona.ownerId());

        // Em outro servidor o personagem continua livre.
        assertNull(repo.findOwner("g2", 42));
        assertTrue(repo.tryClaim("g2", claim(42, "Zero Two", 500, BIA), 3000));
    }

    @Test
    void haremListaPorValorEDivorcioLibera() {
        HaremRepository repo = inMemory();
        repo.tryClaim(GUILD, claim(1, "Rem", 300, ANA), 1000);
        repo.tryClaim(GUILD, claim(2, "Megumin", 700, ANA), 1000);
        repo.tryClaim(GUILD, claim(3, "Aqua", 100, BIA), 1000);

        List<HaremRepository.Claim> harem = repo.listHarem(GUILD, ANA);
        assertEquals(2, harem.size());
        assertEquals("Megumin", harem.get(0).name());

        List<HaremRepository.Claim> busca = repo.findClaims(GUILD, ANA, "rem");
        assertEquals(1, busca.size());
        assertTrue(repo.removeClaim(GUILD, busca.get(0).charId()));
        assertNull(repo.findOwner(GUILD, 1));
        assertEquals(1, repo.listHarem(GUILD, ANA).size());
    }

    @Test
    void kakeraEClaimDoJogadorPersistem() {
        HaremRepository repo = inMemory();

        repo.addKakera(GUILD, ANA, 120);
        repo.addKakera(GUILD, ANA, 30);
        repo.setLastClaim(GUILD, ANA, 999_999L);

        HaremRepository.Player player = repo.getPlayer(GUILD, ANA);
        assertEquals(150, player.kakera());
        assertEquals(999_999L, player.lastClaimMs());

        // Jogador desconhecido vem zerado.
        assertEquals(0, repo.getPlayer(GUILD, BIA).kakera());
    }

    @Test
    void rollsBonusSaoUsadosQuandoACotaDaHoraAcaba() {
        HaremRepository repo = inMemory();
        repo.addBonusRolls(GUILD, ANA, 2);

        assertEquals(2, repo.tryUseRoll(GUILD, ANA, 100, 1)); // 0 da hora + 2 bonus
        assertEquals(1, repo.tryUseRoll(GUILD, ANA, 100, 1)); // consome 1 bonus
        assertEquals(0, repo.tryUseRoll(GUILD, ANA, 100, 1)); // consome o ultimo
        assertEquals(-1, repo.tryUseRoll(GUILD, ANA, 100, 1));

        // Hora nova devolve so a cota da hora (os bonus ja eram).
        assertEquals(0, repo.tryUseRoll(GUILD, ANA, 101, 1));
    }

    @Test
    void gastoDeKakeraEAtomicoESoComSaldo() {
        HaremRepository repo = inMemory();
        repo.addKakera(GUILD, ANA, 100);

        assertTrue(repo.trySpendKakera(GUILD, ANA, 60));
        assertFalse(repo.trySpendKakera(GUILD, ANA, 60)); // sobrou 40
        assertEquals(40, repo.getPlayer(GUILD, ANA).kakera());
    }

    @Test
    void dailyRespeitaCooldown() {
        HaremRepository repo = inMemory();

        assertTrue(repo.tryDaily(GUILD, ANA, 1000, 1000 - 1, 250));
        assertEquals(250, repo.getPlayer(GUILD, ANA).kakera());
        assertEquals(1000, repo.getPlayer(GUILD, ANA).lastDailyMs());

        // Antes do corte (cutoff < last_daily) nao credita de novo.
        assertFalse(repo.tryDaily(GUILD, ANA, 2000, 500, 250));
        assertEquals(250, repo.getPlayer(GUILD, ANA).kakera());

        // Depois do corte, pode.
        assertTrue(repo.tryDaily(GUILD, ANA, 99_999, 5000, 250));
        assertEquals(500, repo.getPlayer(GUILD, ANA).kakera());
    }

    @Test
    void torreSobeUmNivelPorVezESoComSaldo() {
        HaremRepository repo = inMemory();
        repo.addKakera(GUILD, ANA, 500);

        assertFalse(repo.tryUpgradeTower(GUILD, ANA, 2, 400)); // nao pode pular nivel
        assertTrue(repo.tryUpgradeTower(GUILD, ANA, 1, 200));
        assertEquals(1, repo.getPlayer(GUILD, ANA).towerLevel());
        assertEquals(300, repo.getPlayer(GUILD, ANA).kakera());

        assertFalse(repo.tryUpgradeTower(GUILD, ANA, 2, 400)); // saldo insuficiente
        assertEquals(1, repo.getPlayer(GUILD, ANA).towerLevel());
    }

    @Test
    void trocaEAtomicaEValidaOsDonos() {
        HaremRepository repo = inMemory();
        repo.tryClaim(GUILD, claim(1, "Rem", 300, ANA), 1000);
        repo.tryClaim(GUILD, claim(2, "Megumin", 700, BIA), 1000);

        // Troca valida: donos conferem.
        assertTrue(repo.tradeClaims(GUILD, 1, ANA, BIA, "Bia", 2, BIA, ANA, "Ana"));
        assertEquals(BIA, repo.findOwner(GUILD, 1).ownerId());
        assertEquals(ANA, repo.findOwner(GUILD, 2).ownerId());
        assertEquals("Bia", repo.findOwner(GUILD, 1).ownerName());

        // Proposta velha (donos desatualizados) nao move nada.
        assertFalse(repo.tradeClaims(GUILD, 1, ANA, BIA, "Bia", 2, BIA, ANA, "Ana"));
        assertEquals(BIA, repo.findOwner(GUILD, 1).ownerId());
    }

    @Test
    void perfilGuardaCorBioEFavorito() {
        HaremRepository repo = inMemory();

        // Sem nada salvo, vem o padrao.
        HaremRepository.Profile vazio = repo.getProfile(GUILD, ANA);
        assertEquals(-1, vazio.color());
        assertNull(vazio.bio());
        assertEquals(0, vazio.favCharId());

        repo.setProfileColor(GUILD, ANA, 0xFF66AA);
        repo.setProfileBio(GUILD, ANA, "oi, sou a Ana~");
        repo.setProfileFav(GUILD, ANA, 42);

        HaremRepository.Profile perfil = repo.getProfile(GUILD, ANA);
        assertEquals(0xFF66AA, perfil.color());
        assertEquals("oi, sou a Ana~", perfil.bio());
        assertEquals(42, perfil.favCharId());
    }

    @Test
    void statsERankConsideramOValorDoHarem() {
        HaremRepository repo = inMemory();
        repo.tryClaim(GUILD, claim(1, "Rem", 300, ANA), 5000);
        repo.tryClaim(GUILD, claim(2, "Megumin", 700, ANA), 9000);
        repo.tryClaim(GUILD, claim(3, "Aqua", 5000, BIA), 1000);

        HaremRepository.HaremStats stats = repo.haremStats(GUILD, ANA);
        assertEquals(2, stats.count());
        assertEquals(1000, stats.valorTotal());
        assertEquals(5000, stats.primeiroClaimMs());

        assertEquals(1, repo.haremRank(GUILD, BIA)); // 5000 > 1000
        assertEquals(2, repo.haremRank(GUILD, ANA));
        assertEquals(0, repo.haremRank(GUILD, "u-sem-harem"));
    }

    @Test
    void desejosTemLimiteEAvisamQuemDeseja() {
        HaremRepository repo = inMemory();

        assertEquals(HaremRepository.WishResult.OK, repo.addWish(GUILD, ANA, "zero two", 2));
        assertEquals(HaremRepository.WishResult.DUPLICADO, repo.addWish(GUILD, ANA, "zero two", 2));
        assertEquals(HaremRepository.WishResult.OK, repo.addWish(GUILD, ANA, "rem", 2));
        assertEquals(HaremRepository.WishResult.CHEIO, repo.addWish(GUILD, ANA, "emilia", 2));

        repo.addWish(GUILD, BIA, "zero two", 2);
        assertEquals(2, repo.findWishers(GUILD, "zero two").size());
        assertTrue(repo.findWishers(GUILD, "emilia").isEmpty());

        assertTrue(repo.removeWish(GUILD, ANA, "rem"));
        assertFalse(repo.removeWish(GUILD, ANA, "rem"));
        assertEquals(List.of("zero two"), repo.listWishes(GUILD, ANA));
    }

    @Test
    void rollsDeJogoTemCotaPropria() {
        HaremRepository repo = inMemory();

        // Esgota a cota de anime; a de jogos continua intacta (e vice-versa).
        assertEquals(0, repo.tryUseRoll(GUILD, ANA, 100, 1));
        assertEquals(-1, repo.tryUseRoll(GUILD, ANA, 100, 1));

        assertEquals(1, repo.tryUseGameRoll(GUILD, ANA, 100, 2));
        assertEquals(0, repo.tryUseGameRoll(GUILD, ANA, 100, 2));
        assertEquals(-1, repo.tryUseGameRoll(GUILD, ANA, 100, 2));

        // Outra hora reseta a cota de jogos; outro jogador tem cota propria.
        assertEquals(1, repo.tryUseGameRoll(GUILD, ANA, 101, 2));
        assertEquals(1, repo.tryUseGameRoll(GUILD, BIA, 100, 2));
    }

    @Test
    void cooldownDeClaimDeJogoEIndependente() {
        HaremRepository repo = inMemory();

        repo.setLastClaim(GUILD, ANA, 111);
        repo.setLastGameClaim(GUILD, ANA, 222);

        HaremRepository.Player p = repo.getPlayer(GUILD, ANA);
        assertEquals(111, p.lastClaimMs());
        assertEquals(222, p.gameLastClaimMs());
    }

    @Test
    void claimDeIdNegativoConviveComPositivo() {
        HaremRepository repo = inMemory();

        // Personagem de jogo (id negativo) e de anime (positivo) nao colidem.
        assertTrue(repo.tryClaim(GUILD, claim(42, "Zero Two", 500, ANA), 1000));
        assertTrue(repo.tryClaim(GUILD, claim(-42, "Tifa Lockhart", 400, BIA), 1000));

        assertEquals(ANA, repo.findOwner(GUILD, 42).ownerId());
        assertEquals(BIA, repo.findOwner(GUILD, -42).ownerId());
        assertEquals(1, repo.listHarem(GUILD, BIA).size());

        // Troca entre um personagem de anime e um de jogo (id negativo) funciona.
        assertTrue(repo.tradeClaims(GUILD,
                42, ANA, BIA, "Dona " + BIA,
                -42, BIA, ANA, "Dona " + ANA));
        assertEquals(BIA, repo.findOwner(GUILD, 42).ownerId());
        assertEquals(ANA, repo.findOwner(GUILD, -42).ownerId());
    }
}