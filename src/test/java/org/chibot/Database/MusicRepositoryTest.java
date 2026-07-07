package org.chibot.Database;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicRepositoryTest {

    private static final String GUILD = "g1";
    private static final String ANA = "u-ana";
    private static final String BIA = "u-bia";

    /** Banco Postgres embarcado de teste. */
    private static MusicRepository inMemory() {
        return new MusicRepository(PgTestDb.database("music_basico"));
    }

    private static MusicRepository.StoredTrack track(String id) {
        return new MusicRepository.StoredTrack("encoded-" + id, "Titulo " + id);
    }

    @Test
    void volumePadraoAteSerConfigurado() {
        MusicRepository repo = inMemory();

        assertEquals(50, repo.getVolume(GUILD, 50));
        repo.setVolume(GUILD, 80);
        assertEquals(80, repo.getVolume(GUILD, 50));

        // Outro servidor tem volume proprio.
        assertEquals(50, repo.getVolume("g2", 50));
    }

    @Test
    void sessaoGuardaFilaEmOrdemComCanais() {
        MusicRepository repo = inMemory();

        repo.saveSession(GUILD, "voz1", "texto1", List.of(track("a"), track("b"), track("c")));

        assertEquals(List.of(GUILD), repo.guildsWithQueue());
        assertEquals("voz1", repo.voiceChannelId(GUILD));
        assertEquals("texto1", repo.textChannelId(GUILD));

        List<MusicRepository.StoredTrack> fila = repo.loadQueue(GUILD);
        assertEquals(3, fila.size());
        assertEquals("encoded-a", fila.get(0).encoded());
        assertEquals("encoded-c", fila.get(2).encoded());
    }

    @Test
    void salvarSessaoSubstituiAFilaAnterior() {
        MusicRepository repo = inMemory();

        repo.saveSession(GUILD, "voz1", "texto1", List.of(track("a"), track("b")));
        repo.saveSession(GUILD, "voz2", "texto2", List.of(track("x")));

        List<MusicRepository.StoredTrack> fila = repo.loadQueue(GUILD);
        assertEquals(1, fila.size());
        assertEquals("encoded-x", fila.get(0).encoded());
        assertEquals("voz2", repo.voiceChannelId(GUILD));

        // Fila vazia some da listagem de restore.
        repo.saveSession(GUILD, "voz2", "texto2", List.of());
        assertTrue(repo.guildsWithQueue().isEmpty());
    }

    @Test
    void playlistSalvaCarregaEApaga() {
        MusicRepository repo = inMemory();

        assertTrue(repo.savePlaylist(GUILD, ANA, "Treino", List.of(track("1"), track("2")), 1000));

        List<MusicRepository.StoredTrack> faixas = repo.loadPlaylist(GUILD, ANA, "treino");
        assertEquals(2, faixas.size());
        assertEquals("encoded-1", faixas.get(0).encoded());

        List<MusicRepository.SavedPlaylist> lista = repo.listPlaylists(GUILD, ANA);
        assertEquals(1, lista.size());
        assertEquals("Treino", lista.get(0).name());
        assertEquals(2, lista.get(0).trackCount());

        // Salvar com o mesmo nome (case-insensitive) substitui as faixas.
        assertTrue(repo.savePlaylist(GUILD, ANA, "treino", List.of(track("9")), 2000));
        assertEquals(1, repo.loadPlaylist(GUILD, ANA, "treino").size());
        assertEquals(1, repo.listPlaylists(GUILD, ANA).size());

        // Playlist e por dono: a Bia nao ve a da Ana.
        assertTrue(repo.listPlaylists(GUILD, BIA).isEmpty());

        assertTrue(repo.deletePlaylist(GUILD, ANA, "Treino"));
        assertFalse(repo.deletePlaylist(GUILD, ANA, "Treino"));
        assertTrue(repo.loadPlaylist(GUILD, ANA, "treino").isEmpty());
    }

    @Test
    void salvarPlaylistVaziaFalha() {
        MusicRepository repo = inMemory();
        assertFalse(repo.savePlaylist(GUILD, ANA, "Vazia", List.of(), 1000));
    }

    @Test
    void semBancoDegradaSemQuebrar() {
        // Banco indisponivel: tudo vira no-op seguro.
        MusicRepository repo = new MusicRepository(new BrokenDataSource());

        repo.setVolume(GUILD, 70);
        assertEquals(50, repo.getVolume(GUILD, 50));
        repo.saveSession(GUILD, "voz", "texto", List.of(track("a")));
        assertTrue(repo.guildsWithQueue().isEmpty());
        assertNull(repo.voiceChannelId(GUILD));
        assertFalse(repo.savePlaylist(GUILD, ANA, "X", List.of(track("a")), 1000));
        assertTrue(repo.listPlaylists(GUILD, ANA).isEmpty());
    }
}