package org.chibot.Harem;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameCharacterDatasetTest {

    /** Duas linhas validas, uma malformada (colunas de menos) e uma com id nao numerico. */
    private static final String TSV = """
            177\tMario\tMale\tSuper Mario\thttps://img/mario.jpg\t1
            2977\tTifa Lockhart\tFemale\tFinal Fantasy\thttps://img/tifa.jpg\t0
            linha quebrada sem tabs
            abc\tRuim\tMale\tX\thttps://img/x.jpg\t0
            """;

    private static List<GameCharacter> parse() {
        return GameCharacterDataset.parse(new BufferedReader(new StringReader(TSV)));
    }

    @Test
    void parseConverteLinhasEDescartaMalformadas() {
        List<GameCharacter> chars = parse();
        assertEquals(2, chars.size());

        GameCharacter mario = chars.get(0);
        assertEquals(-177, mario.id());
        assertEquals("Mario", mario.name());
        assertTrue(mario.isMale());
        assertEquals("Super Mario", mario.game());
        assertEquals("https://img/mario.jpg", mario.imageUrl());
        assertEquals(GameCharacterDataset.kakeraValue(177, true), mario.kakera());

        GameCharacter tifa = chars.get(1);
        assertEquals(-2977, tifa.id());
        assertTrue(tifa.isFemale());
        assertEquals(GameCharacterDataset.kakeraValue(2977, false), tifa.kakera());
    }

    @Test
    void kakeraDeterministicoENaFaixa() {
        assertEquals(GameCharacterDataset.kakeraValue(2977, true),
                GameCharacterDataset.kakeraValue(2977, true));
        for (long id = 1; id <= 500; id++) {
            int comum = GameCharacterDataset.kakeraValue(id, false);
            int notavel = GameCharacterDataset.kakeraValue(id, true);
            assertTrue(comum >= 15 && comum <= 400, "comum fora da faixa: " + comum);
            assertTrue(notavel >= 15 && notavel <= 1200, "notavel fora da faixa: " + notavel);
            assertTrue(notavel >= comum, "notavel deveria valorizar o personagem");
        }
    }

    @Test
    void sorteioRespeitaGenero() {
        GameCharacterDataset dataset = new GameCharacterDataset(parse());
        Random rng = new Random(42);
        for (int i = 0; i < 20; i++) {
            assertTrue(dataset.randomFemale(rng).isFemale());
            assertTrue(dataset.randomMale(rng).isMale());
            GameCharacter qualquer = dataset.randomAny(rng);
            assertTrue(qualquer.isFemale() || qualquer.isMale());
        }
    }

    @Test
    void datasetRealCarregaDoClasspath() {
        GameCharacterDataset dataset = new GameCharacterDataset();
        // O dataset extraido dos dumps tem ~7200 personagens, todos com genero.
        assertTrue(dataset.size() >= 7000, "dataset pequeno demais: " + dataset.size());
        Random rng = new Random(7);
        for (int i = 0; i < 50; i++) {
            GameCharacter ch = dataset.randomAny(rng);
            assertTrue(ch.id() < 0, "id deveria ser negativo: " + ch.id());
            assertTrue(ch.isFemale() || ch.isMale(), "sem genero: " + ch.name());
            assertTrue(ch.kakera() >= 15 && ch.kakera() <= 1200);
        }
    }
}
