package org.chibot.Harem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPInputStream;

/**
 * Dataset local de personagens de jogos, extraido dos dumps SQL do projeto
 * giant-bomb-wiki (a API do Giant Bomb nao emite mais chaves). O arquivo
 * {@code harem/game_characters.tsv.gz} e gerado por
 * {@code tools/extract_gb_characters.py} e embarcado no jar — sem rede,
 * sem chave, sempre disponivel.
 *
 * <p>Formato do TSV: {@code id \t name \t gender \t series \t image_url \t
 * notable}, um personagem por linha. O id vem positivo no arquivo e e
 * negativado aqui (namespace dos claims de jogos).
 */
public class GameCharacterDataset {

    static final String RESOURCE = "/harem/game_characters.tsv.gz";

    private static final Logger log = LoggerFactory.getLogger(GameCharacterDataset.class);

    private final List<GameCharacter> females = new ArrayList<>();
    private final List<GameCharacter> males = new ArrayList<>();

    /** Carrega o dataset embarcado; falha no boot se o recurso sumiu do jar. */
    public GameCharacterDataset() {
        this(loadResource());
    }

    /** Package-private pros testes: recebe a lista ja parseada. */
    GameCharacterDataset(List<GameCharacter> all) {
        for (GameCharacter ch : all) {
            (ch.isFemale() ? females : males).add(ch);
        }
    }

    public int size() {
        return females.size() + males.size();
    }

    public GameCharacter randomFemale(Random rng) {
        return females.get(rng.nextInt(females.size()));
    }

    public GameCharacter randomMale(Random rng) {
        return males.get(rng.nextInt(males.size()));
    }

    /** Sorteio misto, proporcional ao tamanho de cada lista. */
    public GameCharacter randomAny(Random rng) {
        int r = rng.nextInt(size());
        return r < females.size() ? females.get(r) : males.get(r - females.size());
    }

    private static List<GameCharacter> loadResource() {
        try (InputStream in = GameCharacterDataset.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Dataset " + RESOURCE + " ausente do classpath"
                        + " — rode tools/extract_gb_characters.py e recompile.");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(in), StandardCharsets.UTF_8))) {
                List<GameCharacter> all = parse(reader);
                if (all.isEmpty()) {
                    throw new IllegalStateException("Dataset " + RESOURCE + " vazio.");
                }
                return all;
            }
        } catch (IOException e) {
            throw new IllegalStateException("Falha lendo o dataset " + RESOURCE, e);
        }
    }

    /** Package-private pros testes: converte as linhas do TSV em personagens rolaveis. */
    static List<GameCharacter> parse(BufferedReader reader) {
        List<GameCharacter> out = new ArrayList<>();
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                String[] c = line.split("\t");
                if (c.length != 6 || c[0].isBlank() || c[1].isBlank()) {
                    if (!line.isBlank()) {
                        log.warn("Linha malformada no dataset de jogos ignorada: {}", line);
                    }
                    continue;
                }
                long gbId;
                try {
                    gbId = Long.parseLong(c[0]);
                } catch (NumberFormatException e) {
                    log.warn("Id invalido no dataset de jogos ignorado: {}", c[0]);
                    continue;
                }
                out.add(new GameCharacter(-gbId, c[1], c[2], c[3], c[4],
                        kakeraValue(gbId, "1".equals(c[5]))));
            }
        } catch (IOException e) {
            throw new IllegalStateException("Falha lendo o dataset de jogos", e);
        }
        return out;
    }

    /**
     * Valor em kakera do personagem, deterministico (mesmo id = mesmo valor,
     * sempre): um hash estavel do id vira 15..400, e personagem notavel
     * (descricao wiki longa) vale 3x, saturando em 1200. Mesma formula do
     * antigo GiantBombClient — claims antigos mantem os valores.
     */
    static int kakeraValue(long gbId, boolean notavel) {
        int base = 15 + (int) Math.floorMod(gbId * 2654435761L, 386);
        return notavel ? Math.min(1200, base * 3) : base;
    }
}
