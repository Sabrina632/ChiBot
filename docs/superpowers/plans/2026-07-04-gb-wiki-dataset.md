# Dataset local do giant-bomb-wiki — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Substituir o `GiantBombClient` (API morta, sem keys novas) por um dataset local de ~7.200 personagens de jogos extraído dos dumps SQL do repo giant-bomb-wiki, embarcado no jar.

**Architecture:** Um script Python offline (`tools/extract_gb_characters.py`) baixa os dumps do GitHub, filtra/classifica os personagens e gera `src/main/resources/harem/game_characters.tsv.gz` (commitado no git). No bot, a classe nova `GameCharacterDataset` carrega esse recurso do classpath pra listas em memória e o `HaremService` sorteia direto delas — os pools de rede, o lock e o `GiantBombClient` somem.

**Tech Stack:** Java 17 + Gradle + JUnit 6 (bot); Python 3 stdlib (extração, roda só na máquina de dev).

**Spec:** `docs/superpowers/specs/2026-07-04-gb-wiki-dataset-design.md`

## Global Constraints

- Java 17; **nenhuma dependência nova** no `build.gradle` (Python usa só stdlib).
- Comentários/javadoc em português, no estilo dos arquivos vizinhos (sem acentos nos comentários Java é o padrão do projeto — veja `GiantBombClient.java`).
- Mensagens de commit em português **com acentuação correta** e **sem** rodapé `Co-Authored-By`.
- Shell: Windows PowerShell; testes com `.\gradlew.bat`.
- Formato do TSV (uma linha por personagem, campos separados por TAB, sem header):
  `id` (positivo, do Giant Bomb) · `name` · `gender` (`Female`|`Male`) · `series` · `image_url` · `notable` (`0`|`1`).
- Fórmula do kakera (inalterada): `base = 15 + floorMod(id * 2654435761L, 386)`; notável = `min(1200, base * 3)`.
- Ids no bot ficam **negativos** (`-idDoGiantBomb`) — namespace dos claims de jogos.

## Estado do working tree (atenção)

- `src/main/java/org/chibot/Harem/GameCharacter.java` tem uma mudança **não commitada** (record → classe final, comportamento idêntico). Ela entra no commit da Task 3.
- `resp.json` na raiz é lixo de exploração (página de erro HTML) — deletado na Task 4.

---

### Task 1: Script de extração + dataset gerado

**Files:**
- Create: `tools/extract_gb_characters.py`
- Create (gerado pelo script): `src/main/resources/harem/game_characters.tsv.gz`

**Interfaces:**
- Consumes: dumps `gb_api_db_init/*.sql.gz` do GitHub (baixados pelo script, com cache em `tools/.gb_dumps/`).
- Produces: `src/main/resources/harem/game_characters.tsv.gz` no formato dos Global Constraints — é o contrato consumido pela Task 2.

- [ ] **Step 1: Escrever o script completo**

Criar `tools/extract_gb_characters.py` com este conteúdo:

```python
#!/usr/bin/env python3
"""Extrai o dataset de personagens de jogos dos dumps SQL do giant-bomb-wiki.

Baixa os dumps de gb_api_db_init/ do repo Giant-Bomb-Dot-Com/giant-bomb-wiki
(com cache local em tools/.gb_dumps/), filtra personagens notaveis (descricao
wiki >= 1000 chars, nao deletados, com imagem real), resolve a franquia como
"serie", classifica o genero por cascata (rotulo do dump -> pronomes da
descricao -> palavras de genero -> fallback masculino) e grava
src/main/resources/harem/game_characters.tsv.gz.

Uso: python tools/extract_gb_characters.py
"""
import gzip
import html
import re
import sys
import urllib.request
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
SAIDA = RAIZ / "src" / "main" / "resources" / "harem" / "game_characters.tsv.gz"
CACHE = Path(__file__).resolve().parent / ".gb_dumps"
BASE = ("https://raw.githubusercontent.com/Giant-Bomb-Dot-Com/"
        "giant-bomb-wiki/main/gb_api_db_init/")
DUMPS = ("14_character.sql.gz", "18_image.sql.gz",
         "08_franchise.sql.gz", "19_relations.sql.gz")

MIN_DESC = 1000      # corte de notabilidade (entra no pool)
NOTAVEL_DESC = 5000  # corte do bonus de kakera (flag notable)
BARRA = chr(92)      # backslash (escape do MySQL)

# Ordem real das colunas no dump de wiki_character (18 colunas: as 17 do
# schema + mw_formatted_description inserida apos description pela migracao).
C_ID, C_IMAGE_ID, C_GENDER, C_NAME, C_DESC, C_DELETED = 0, 1, 3, 7, 12, 17

MASC = re.compile(r"\b(he|him|his|himself)\b", re.I)
FEM = re.compile(r"\b(she|her|hers|herself)\b", re.I)
MWORD = re.compile(
    r"\b(mr|mister|sir|lord|king|prince|father|dad|brother|son|boy|man|male"
    r"|guy|duke|emperor|baron|monk|priest|god)\b", re.I)
FWORD = re.compile(
    r"\b(mrs|ms|miss|lady|queen|princess|mother|mom|sister|daughter|girl"
    r"|woman|female|duchess|empress|baroness|nun|priestess|goddess|maiden"
    r"|witch)\b", re.I)
TAG = re.compile(r"<[^>]+>")


def baixar(nome):
    """Baixa um dump pro cache (se ainda nao estiver la) e devolve o texto."""
    CACHE.mkdir(exist_ok=True)
    destino = CACHE / nome
    if not destino.exists():
        print(f"baixando {nome}...")
        urllib.request.urlretrieve(BASE + nome, destino)
    with gzip.open(destino, "rt", encoding="utf-8", errors="replace") as f:
        return f.read()


def tuplas(sql, tabela):
    """Itera as tuplas dos INSERT INTO `tabela` VALUES do dump.

    Parser minimo de tuplas do mysqldump: respeita aspas simples com escape
    por backslash; valores nao citados viram string crua ('NULL' inclusive).
    """
    padrao = re.compile(
        r"INSERT INTO `" + tabela + r"` VALUES\n(.*?);\n", re.S)
    for bloco in padrao.finditer(sql):
        corpo = bloco.group(1)
        i, n = 0, len(corpo)
        while i < n:
            if corpo[i] != "(":
                i += 1
                continue
            i += 1
            linha, cru = [], []
            while i < n:
                c = corpo[i]
                if c == "'":
                    i += 1
                    buf = []
                    while True:
                        c = corpo[i]
                        if c == BARRA:
                            buf.append(corpo[i + 1])
                            i += 2
                        elif c == "'":
                            i += 1
                            break
                        else:
                            buf.append(c)
                            i += 1
                    linha.append("".join(buf))
                    cru = None
                elif c == ",":
                    if cru is not None:
                        linha.append("".join(cru).strip())
                    cru = []
                    i += 1
                elif c == ")":
                    if cru is not None:
                        linha.append("".join(cru).strip())
                    i += 1
                    break
                else:
                    if cru is None:
                        cru = []
                    cru.append(c)
                    i += 1
            yield linha


def limpar(campo):
    """Sanitiza um campo pro TSV: sem HTML entities, TABs ou quebras."""
    campo = html.unescape(campo)
    return re.sub(r"[\t\r\n]+", " ", campo).strip()


def genero(rotulo, descricao_sem_html, nome):
    """Cascata de genero: rotulo -> pronomes -> palavras -> Male."""
    if rotulo == "1":
        return "Male"
    if rotulo == "2":
        return "Female"
    m = len(MASC.findall(descricao_sem_html))
    f = len(FEM.findall(descricao_sem_html))
    if m != f:
        return "Male" if m > f else "Female"
    pista = nome + " " + descricao_sem_html[:400]
    if len(FWORD.findall(pista)) > len(MWORD.findall(pista)):
        return "Female"
    return "Male"


def main():
    imagens = {}  # image_id -> url
    for linha in tuplas(baixar("18_image.sql.gz"), "image"):
        if len(linha) >= 4:
            imagens[linha[0]] = linha[3]

    franquias = {}  # franchise_id -> nome (indice 4 no dump de wiki_franchise)
    for linha in tuplas(baixar("08_franchise.sql.gz"), "wiki_franchise"):
        if len(linha) >= 5:
            franquias[linha[0]] = linha[4]

    # personagem -> franquia de menor id (wiki_assoc_character_franchise:
    # colunas id, character_id, franchise_id, description)
    franquia_de = {}
    relations = baixar("19_relations.sql.gz")
    for linha in tuplas(relations, "wiki_assoc_character_franchise"):
        if len(linha) >= 3 and linha[2] in franquias:
            atual = franquia_de.get(linha[1])
            if atual is None or int(linha[2]) < int(atual):
                franquia_de[linha[1]] = linha[2]

    saida = []
    fem = masc = notaveis = 0
    for r in tuplas(baixar("14_character.sql.gz"), "wiki_character"):
        if len(r) != 18 or r[C_DELETED] == "1":
            continue
        # Os cortes de notabilidade valem sobre o HTML cru da descricao —
        # foi assim que os numeros do spec (7197/1936/5261) foram medidos.
        descricao_html = r[C_DESC] or ""
        if len(descricao_html) < MIN_DESC:
            continue
        url = imagens.get(r[C_IMAGE_ID], "")
        if not url or "default" in url.lower():
            continue
        nome = limpar(r[C_NAME])
        if not nome:
            continue
        g = genero(r[C_GENDER], TAG.sub(" ", descricao_html), nome)
        fid = franquia_de.get(r[C_ID])
        serie = limpar(franquias[fid]) if fid else "Origem desconhecida"
        notavel = "1" if len(descricao_html) >= NOTAVEL_DESC else "0"
        url = url.replace("/original/", "/scale_medium/")
        saida.append((int(r[C_ID]), nome, g, serie, limpar(url), notavel))
        fem += g == "Female"
        masc += g == "Male"
        notaveis += notavel == "1"

    saida.sort()
    SAIDA.parent.mkdir(parents=True, exist_ok=True)
    # mtime=0 deixa o .gz deterministico (mesmo input -> mesmo arquivo no git)
    with open(SAIDA, "wb") as f:
        with gzip.GzipFile(fileobj=f, mode="wb", mtime=0) as gz:
            for linha in saida:
                gz.write(("\t".join(str(c) for c in linha) + "\n")
                         .encode("utf-8"))

    print(f"{len(saida)} personagens -> {SAIDA}")
    print(f"waifus: {fem} | husbandos: {masc} | notaveis (kakera x3): {notaveis}")
    conhecidos = {177: "Mario", 337: "King Bowser Koopa"}
    for cid, esperado in conhecidos.items():
        achado = next((l for l in saida if l[0] == cid), None)
        print(f"spot-check {esperado}: {achado}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

- [ ] **Step 2: Rodar o script e conferir o resumo**

Run: `python tools/extract_gb_characters.py`

Expected (números exatos medidos na exploração — qualquer desvio grande é bug):
```
~7197 personagens -> ...\src\main\resources\harem\game_characters.tsv.gz
waifus: ~1936 | husbandos: ~5261 | notaveis (kakera x3): ~1411
spot-check Mario: (177, 'Mario', 'Male', 'Mario', 'https://www.giantbomb.com/a/uploads/scale_medium/15/153607/2895175-mario%2013.png', '1')
spot-check King Bowser Koopa: (337, 'King Bowser Koopa', 'Male', 'Mario', ..., '1')
```
Conferir também: `Mario` classificado `Male` (cascata de pronomes — o rótulo dele no dump é 0) e série `Mario` (a franquia id 1; se vier "Origem desconhecida" pro Mario, o join de franquia está quebrado — verificado na exploração que Mario e Bowser apontam pra ela).

- [ ] **Step 3: Conferir o dataset gerado**

Run (PowerShell):
```powershell
Get-Item src/main/resources/harem/game_characters.tsv.gz | Select-Object Length
```
Expected: algumas centenas de KB (falha se passar de ~2 MB — algo entrou errado no filtro).

- [ ] **Step 4: Adicionar `tools/.gb_dumps/` ao `.gitignore`**

Acrescentar ao `.gitignore` na raiz:
```
# Cache dos dumps do giant-bomb-wiki (tools/extract_gb_characters.py)
tools/.gb_dumps/
```

- [ ] **Step 5: Commit**

```powershell
git add tools/extract_gb_characters.py src/main/resources/harem/game_characters.tsv.gz .gitignore
git commit -m "Harém: script de extração e dataset local dos dumps do giant-bomb-wiki"
```

---

### Task 2: GameCharacterDataset (TDD)

**Files:**
- Create: `src/main/java/org/chibot/Harem/GameCharacterDataset.java`
- Test: `src/test/java/org/chibot/Harem/GameCharacterDatasetTest.java`

**Interfaces:**
- Consumes: `harem/game_characters.tsv.gz` no classpath (Task 1) e a classe `GameCharacter` existente (`new GameCharacter(long id, String name, String gender, String game, String imageUrl, int kakera)`, com `isFemale()`/`isMale()`).
- Produces (usado na Task 3):
  - `public GameCharacterDataset()` — carrega o recurso do classpath; lança `IllegalStateException` se ausente/vazio/corrompido.
  - `public GameCharacter randomFemale(java.util.Random rng)`
  - `public GameCharacter randomMale(java.util.Random rng)`
  - `public GameCharacter randomAny(java.util.Random rng)` — proporcional ao tamanho das listas.
  - `static int kakeraValue(long gbId, boolean notavel)` — package-private, mesma fórmula do `GiantBombClient`.
  - `static List<GameCharacter> parse(BufferedReader in)` — package-private (testes).

- [ ] **Step 1: Escrever os testes (falhando)**

Criar `src/test/java/org/chibot/Harem/GameCharacterDatasetTest.java`:

```java
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
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `.\gradlew.bat test --tests "org.chibot.Harem.GameCharacterDatasetTest"`
Expected: FAIL de compilação — `GameCharacterDataset` não existe.

- [ ] **Step 3: Implementar a classe**

Criar `src/main/java/org/chibot/Harem/GameCharacterDataset.java`:

```java
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
```

- [ ] **Step 4: Rodar e ver passar**

Run: `.\gradlew.bat test --tests "org.chibot.Harem.GameCharacterDatasetTest"`
Expected: BUILD SUCCESSFUL, 4 testes passando (o `datasetRealCarregaDoClasspath` prova que o `.tsv.gz` da Task 1 é lido de verdade).

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/org/chibot/Harem/GameCharacterDataset.java src/test/java/org/chibot/Harem/GameCharacterDatasetTest.java
git commit -m "Harém: GameCharacterDataset carrega o dataset local de personagens de jogos"
```

---

### Task 3: HaremService sorteia do dataset; GiantBombClient morre

**Files:**
- Modify: `src/main/java/org/chibot/Harem/HaremService.java` (campo `giantBomb` ~linha 81, pools de jogos ~88-93, `rollGame` ~161-184, `pickGameCharacter`/`pollGamePool`/`refillGames` ~308-358)
- Delete: `src/main/java/org/chibot/Harem/GiantBombClient.java`
- Delete: `src/test/java/org/chibot/Harem/GiantBombClientTest.java`
- Commit inclui também: `src/main/java/org/chibot/Harem/GameCharacter.java` (refactor record → classe já no working tree)

**Interfaces:**
- Consumes: `new GameCharacterDataset()`, `randomFemale(Random)`, `randomMale(Random)`, `randomAny(Random)` (Task 2).
- Produces: nada novo — os comandos `gamewaifu`/`gamehusbando`/`gameroll` continuam chamando `rollGame(CommandContext, Genero)` sem mudança de assinatura.

- [ ] **Step 1: Trocar o campo do cliente pelo dataset**

Em `HaremService.java`, substituir:

```java
    private final GiantBombClient giantBomb = new GiantBombClient();
```
por:
```java
    private final GameCharacterDataset gameDataset = new GameCharacterDataset();
```

- [ ] **Step 2: Remover os pools e o lock de jogos**

Apagar os campos:

```java
    private final ArrayDeque<GameCharacter> gameWaifus = new ArrayDeque<>();
    private final ArrayDeque<GameCharacter> gameHusbandos = new ArrayDeque<>();
    private final ArrayDeque<GameCharacter> gameOutros = new ArrayDeque<>();

    /** Lock proprio dos pools de jogos: um travamento do Giant Bomb nao segura os rolls de anime. */
    private final Object gameLock = new Object();
```

- [ ] **Step 3: Simplificar o rollGame**

Substituir o método inteiro por:

```java
    /** Sorteia um personagem de jogo (dataset do giant-bomb-wiki) e posta o embed de claim. */
    public void rollGame(CommandContext ctx, Genero genero) {
        ctx.deferReply();
        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();
        long hora = System.currentTimeMillis() / 3_600_000L;

        int restantes = repo.tryUseGameRoll(guildId, userId, hora, ROLLS_JOGO_POR_HORA);
        if (restantes < 0) {
            ctx.reply("Seus rolls de jogos acabaram~ pode rolar de novo "
                    + relativo((hora + 1) * 3_600_000L) + "! (｡•́︿•̀｡)");
            return;
        }

        GameCharacter ch = pickGameCharacter(genero);
        postarRoll(ctx, ch.id(), ch.name(), ch.game(), ch.imageUrl(), ch.kakera(), true, restantes);
    }
```

(O aviso de `GIANTBOMB_API_KEY` ausente e o fallback de "não consegui falar com o Giant Bomb" somem — dataset embarcado não falha em runtime.)

- [ ] **Step 4: Substituir pickGameCharacter e apagar pollGamePool/refillGames**

Substituir os três métodos (`pickGameCharacter`, `pollGamePool`, `refillGames`) por:

```java
    /** Sorteio direto do dataset local — sem pools nem rede. */
    private GameCharacter pickGameCharacter(Genero genero) {
        Random rng = java.util.concurrent.ThreadLocalRandom.current();
        switch (genero) {
            case WAIFU:
                return gameDataset.randomFemale(rng);
            case HUSBANDO:
                return gameDataset.randomMale(rng);
            default:
                return gameDataset.randomAny(rng);
        }
    }
```

(`ThreadLocalRandom` porque o sorteio de jogos não passa mais por nenhum `synchronized` — o `random` compartilhado do service não é thread-safe.)

- [ ] **Step 5: Apagar o cliente e o teste antigo**

```powershell
git rm src/main/java/org/chibot/Harem/GiantBombClient.java src/test/java/org/chibot/Harem/GiantBombClientTest.java
```

- [ ] **Step 6: Compilar e rodar a suíte inteira**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL — sem referências órfãs a `GiantBombClient` (se o compilador reclamar de import não usado de `ArrayDeque` em `HaremService`, checar se o lado anime ainda usa — usa, os pools de anime ficam).

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/org/chibot/Harem/HaremService.java src/main/java/org/chibot/Harem/GameCharacter.java
git commit -m "Harém: rolls de jogos sorteiam do dataset local; adeus GiantBombClient"
```

---

### Task 4: Limpeza de config, docs e lixo

**Files:**
- Modify: `src/main/java/org/chibot/Config/ChiConfig.java` (campo ~32, construtor ~34-51, load ~71-75, template ~200-203, getter ~250-253)
- Modify: `.env.example` (bloco do Giant Bomb, ~linhas 38-41)
- Modify: `README.md` (linha ~91, linha da tabela de variáveis)
- Delete: `resp.json` (raiz — lixo de exploração)

**Interfaces:**
- Consumes: nada.
- Produces: `ChiConfig` sem `giantBombApiKey` (campo, parâmetro do construtor, leitura no `load()`, linhas do template `createDefault()` e `getGiantBombApiKey()` removidos). Nenhum outro arquivo referencia esses símbolos (verificado por grep na exploração; o único consumidor era o `GiantBombClient`, deletado na Task 3).

- [ ] **Step 1: Limpar o ChiConfig**

Remover em `ChiConfig.java`:
1. O campo `private final String giantBombApiKey;`
2. O parâmetro `String giantBombApiKey` do construtor e a atribuição `this.giantBombApiKey = giantBombApiKey;`
3. No `load()`: a linha `String giantBombApiKey = value(env, "GIANTBOMB_API_KEY", "");` e o argumento `giantBombApiKey` na chamada `new ChiConfig(...)`
4. No template do `createDefault()`: as quatro linhas
   ```java
                "# ─── Harem de jogos (Giant Bomb) ──────────────────────────",
                "# Chave gratuita em giantbomb.com/api (rolls de personagens de jogos).",
                "# Vazio = comandos gamewaifu/gamehusbando/gameroll desligados.",
                "GIANTBOMB_API_KEY=",
   ```
   e a `""` de separação anterior a elas (pra não deixar linha em branco dupla no fim).
5. O getter `getGiantBombApiKey()` inteiro (com o javadoc).

- [ ] **Step 2: Limpar o .env.example**

Remover o bloco do Giant Bomb (título `# ─── Harem de jogos...`, os dois comentários e `GIANTBOMB_API_KEY=`), mantendo o resto intacto.

- [ ] **Step 3: Atualizar o README**

Remover a linha da tabela:
```markdown
| `GIANTBOMB_API_KEY`     | (Opcional) Chave da [API do Giant Bomb](...) ... |
```
Se o README descrever os comandos `gamewaifu`/`gamehusbando`/`gameroll` condicionados à chave, ajustar o texto pra dizer que os rolls de jogos usam um dataset embarcado extraído do [giant-bomb-wiki](https://github.com/Giant-Bomb-Dot-Com/giant-bomb-wiki) (re-gerável com `tools/extract_gb_characters.py`).

- [ ] **Step 4: Deletar o resp.json**

```powershell
Remove-Item resp.json
```

- [ ] **Step 5: Rodar a suíte e o build completos**

Run: `.\gradlew.bat build`
Expected: BUILD SUCCESSFUL (compilação + testes + jar com o dataset dentro).

Verificar que o dataset foi parar no jar:
```powershell
jar tf build/libs/ChiBot-1.0-SNAPSHOT.jar | Select-String "game_characters"
```
Expected: `harem/game_characters.tsv.gz`
(Se o nome do jar diferir, listar `build/libs/` e usar o que estiver lá.)

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/org/chibot/Config/ChiConfig.java .env.example README.md
git commit -m "Config: remove GIANTBOMB_API_KEY — rolls de jogos agora usam dataset embarcado"
```

---

## Verificação final (pós-plano)

- `.\gradlew.bat build` verde.
- `git log --oneline` mostra os 4 commits do plano.
- Deploy na VPS segue o de sempre (`git pull` + `docker compose up --build`) — nenhuma variável ou serviço novo; a `GIANTBOMB_API_KEY` que existir num `.env` antigo é simplesmente ignorada.
