# GameCharacter (personagens de jogos no harém) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rolls de personagens de jogos (Giant Bomb) no sistema de harém, com comandos, cota e cooldown de claim próprios, convivendo com os personagens de anime no mesmo harém/kakera.

**Architecture:** Espelho do fluxo AniList: `GameCharacter` (record) + `GiantBombClient` (HTTP) alimentam pools próprios no `HaremService`; claims de jogo entram na mesma tabela `harem_claim` com **char_id negativo** (`-idDoGiantBomb`), então troca/divórcio/desejos/perfil funcionam sem mudança. `harem_player` ganha 3 colunas (`game_rolls_used`, `game_rolls_hour`, `game_last_claim`) pros timers separados.

**Tech Stack:** Java 17, Gradle, JDA 6, org.json, SQLite (JDBC), JUnit 5.

**Spec:** `docs/superpowers/specs/2026-07-04-game-character-design.md`

## Global Constraints

- Comandos: `gamewaifu` (aliases `wg`, `gw`), `gamehusbando` (aliases `hg`, `gh`), `gameroll` (aliases `gr`, `rg`) — já verificado que nenhum alias colide com os existentes.
- Cota de jogos: `ROLLS_JOGO_POR_HORA = 10`, fixa (SEM bônus de torre, SEM consumir `bonus_rolls`).
- Kakera é moeda única; harém é listagem única (claims de jogo ganham 🎮).
- Kakera de personagem de jogo: determinístico a partir do id, faixa 15–1200.
- Chave da API: `GIANTBOMB_API_KEY` via `ChiConfig` (env do processo tem prioridade sobre `.env`, padrão do projeto). Sem chave = comandos respondem aviso amigável.
- A API do Giant Bomb exige `User-Agent` customizado (bloqueia o padrão do JDK).
- Comentários/javadoc/mensagens em português, sem acento nos comentários de código só onde o arquivo já faz assim (o código existente usa "nao", "servico"...) — siga o estilo do arquivo vizinho.
- Commits SEM `Co-Authored-By: Claude`, mensagens em português com acentuação correta.
- Testes: `.\gradlew.bat test` (Windows). Build: `.\gradlew.bat build -x test` quando só quiser compilar.
- Comandos são autodescobertos por scan de classpath (`CommandManager.autoLoad`) — basta criar a classe no pacote `org.chibot.Commands.Harem`, sem registro manual.

---

### Task 1: `GIANTBOMB_API_KEY` no ChiConfig, `.env.example` e README

**Files:**
- Modify: `src/main/java/org/chibot/Config/ChiConfig.java`
- Modify: `.env.example`
- Modify: `README.md` (tabela de variáveis, ~linha 83-90)

**Interfaces:**
- Produces: `ChiConfig.getGiantBombApiKey()` → `String` (vazia = não configurada). Usada pela Task 2.

- [ ] **Step 1: Adicionar o campo no ChiConfig**

Em `ChiConfig.java`:

1. Campo (junto de `deeplApiKey`):
```java
    private final String giantBombApiKey;
```

2. Parâmetro no construtor privado (depois de `String deeplApiKey`) e atribuição:
```java
    private ChiConfig(String token, String prefix, String guildId,
                      String lavalinkUri, String lavalinkPassword,
                      String youtubeApiKey, String youtubeRefreshToken,
                      String ownerId,
                      String deeplApiKey,
                      String giantBombApiKey) {
        ...
        this.deeplApiKey = deeplApiKey;
        this.giantBombApiKey = giantBombApiKey;
    }
```

3. Em `load()`, depois de `deeplApiKey`:
```java
        String giantBombApiKey = value(env, "GIANTBOMB_API_KEY", "");
```
e passar no `new ChiConfig(..., deeplApiKey, giantBombApiKey)`.

4. Getter no fim da classe:
```java
    /** Chave da API do Giant Bomb (rolls de personagens de jogos). Vazia = desligado. */
    public String getGiantBombApiKey() {
        return giantBombApiKey;
    }
```

5. Em `createDefault()`, antes da linha final `""`:
```java
                "",
                "# ─── Harem de jogos (Giant Bomb) ──────────────────────────",
                "# Chave gratuita em giantbomb.com/api (rolls de personagens de jogos).",
                "# Vazio = comandos gamewaifu/gamehusbando/gameroll desligados.",
                "GIANTBOMB_API_KEY=",
```

- [ ] **Step 2: Atualizar `.env.example`**

Acrescentar no fim do arquivo:

```dotenv

# ─── Harem de jogos (Giant Bomb) ──────────────────────────
# Chave gratuita em giantbomb.com/api (rolls de personagens de jogos).
# Vazio = comandos gamewaifu/gamehusbando/gameroll desligados.
GIANTBOMB_API_KEY=
```

- [ ] **Step 3: Atualizar a tabela de variáveis do README**

Na tabela de chaves do README (que tem `DEEPL_API_KEY` etc.), adicionar a linha:

```markdown
| `GIANTBOMB_API_KEY`     | (Opcional) Chave da [API do Giant Bomb](https://www.giantbomb.com/api/) — habilita os rolls de personagens de **jogos** (`gamewaifu`/`gamehusbando`/`gameroll`). Vazio = só personagens de anime. |
```

- [ ] **Step 4: Compilar e rodar os testes**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL (nenhum teste existente quebra).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/chibot/Config/ChiConfig.java .env.example README.md
git commit -m "Config: chave GIANTBOMB_API_KEY pro harém de jogos"
```

---

### Task 2: `GameCharacter` + `GiantBombClient` (com testes)

**Files:**
- Modify: `src/main/java/org/chibot/Harem/GameCharacter.java` (existe **vazio** — preencher)
- Create: `src/main/java/org/chibot/Harem/GiantBombClient.java`
- Test: `src/test/java/org/chibot/Harem/GiantBombClientTest.java`

**Interfaces:**
- Consumes: `ChiConfig.get().getGiantBombApiKey()` (Task 1).
- Produces (usados pela Task 4):
  - `record GameCharacter(long id, String name, String gender, String game, String imageUrl, int kakera)` com `isFemale()`/`isMale()` — **`id` já vem negativo** (`-idDoGiantBomb`).
  - `GiantBombClient()` (construtor padrão lê a chave do ChiConfig; fallback `System.getenv`).
  - `GiantBombClient.isAvailable()` → `boolean` (false = sem chave).
  - `GiantBombClient.fetchPage(int offset)` → `List<GameCharacter>` (throws IOException, InterruptedException).
  - `GiantBombClient.PER_PAGE = 100`, `GiantBombClient.MAX_OFFSET = 7900`.

- [ ] **Step 1: Escrever os testes que falham**

`src/test/java/org/chibot/Harem/GiantBombClientTest.java`:

```java
package org.chibot.Harem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GiantBombClientTest {

    /**
     * Resposta tipica do endpoint /characters/: dois personagens completos,
     * um com a imagem placeholder do site, um sem nome e um sem jogo de origem.
     */
    private static final String JSON = """
            {
              "error": "OK",
              "status_code": 1,
              "results": [
                {
                  "id": 2977,
                  "name": "Tifa Lockhart",
                  "gender": 2,
                  "deck": "Lutadora e dona do bar Setimo Ceu.",
                  "image": { "medium_url": "https://www.giantbomb.com/a/uploads/scale_medium/tifa.jpg" },
                  "first_appeared_in_game": { "name": "Final Fantasy VII" }
                },
                {
                  "id": 1,
                  "name": "Mario",
                  "gender": 1,
                  "deck": "O encanador mais famoso dos videogames.",
                  "image": { "medium_url": "https://www.giantbomb.com/a/uploads/scale_medium/mario.jpg" },
                  "first_appeared_in_game": { "name": "Donkey Kong" }
                },
                {
                  "id": 50,
                  "name": "Sem Foto",
                  "gender": 0,
                  "deck": null,
                  "image": { "medium_url": "https://www.giantbomb.com/a/uploads/scale_medium/3026329-gb_default-16_9.png" },
                  "first_appeared_in_game": { "name": "Jogo Obscuro" }
                },
                {
                  "id": 51,
                  "name": "",
                  "gender": 2,
                  "deck": null,
                  "image": { "medium_url": "https://img/x.jpg" },
                  "first_appeared_in_game": { "name": "Jogo X" }
                },
                {
                  "id": 60,
                  "name": "Misterioso",
                  "gender": 0,
                  "deck": null,
                  "image": { "medium_url": "https://img/misterioso.jpg" },
                  "first_appeared_in_game": null
                }
              ]
            }""";

    @Test
    void parseConverteEDescartaInvalidos() throws IOException {
        List<GameCharacter> chars = GiantBombClient.parse(JSON);
        // Placeholder (gb_default) e sem nome caem fora; sobram 3.
        assertEquals(3, chars.size());

        GameCharacter tifa = chars.get(0);
        assertEquals(-2977, tifa.id());
        assertEquals("Tifa Lockhart", tifa.name());
        assertTrue(tifa.isFemale());
        assertFalse(tifa.isMale());
        assertEquals("Final Fantasy VII", tifa.game());
        assertEquals("https://www.giantbomb.com/a/uploads/scale_medium/tifa.jpg", tifa.imageUrl());

        GameCharacter mario = chars.get(1);
        assertEquals(-1, mario.id());
        assertTrue(mario.isMale());

        GameCharacter misterioso = chars.get(2);
        assertNull(misterioso.gender());
        assertFalse(misterioso.isFemale());
        assertEquals("Origem desconhecida", misterioso.game());
    }

    @Test
    void parseFalhaComStatusDeErro() {
        assertThrows(IOException.class, () -> GiantBombClient.parse(
                "{\"error\":\"Invalid API Key\",\"status_code\":100,\"results\":[]}"));
    }

    @Test
    void kakeraDeterministicoENaFaixa() {
        // Mesmo id, mesmo valor — sempre.
        assertEquals(GiantBombClient.kakeraValue(2977, true), GiantBombClient.kakeraValue(2977, true));
        assertEquals(GiantBombClient.kakeraValue(2977, false), GiantBombClient.kakeraValue(2977, false));
        for (long id = 1; id <= 500; id++) {
            int sem = GiantBombClient.kakeraValue(id, false);
            int com = GiantBombClient.kakeraValue(id, true);
            assertTrue(sem >= 15 && sem <= 400, "sem deck fora da faixa: " + sem);
            assertTrue(com >= 15 && com <= 1200, "com deck fora da faixa: " + com);
            assertTrue(com >= sem, "deck deveria valorizar o personagem");
        }
    }

    @Test
    void clienteSemChaveFicaIndisponivel() {
        assertFalse(new GiantBombClient("").isAvailable());
        assertFalse(new GiantBombClient(null).isAvailable());
        assertFalse(new GiantBombClient("   ").isAvailable());
        assertTrue(new GiantBombClient("abc123").isAvailable());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar (não compila)**

Run: `.\gradlew.bat test --tests "org.chibot.Harem.GiantBombClientTest"`
Expected: FAIL — erro de compilação (`GiantBombClient` não existe / `GameCharacter` vazio).

- [ ] **Step 3: Preencher `GameCharacter.java`**

```java
package org.chibot.Harem;

/**
 * Personagem de jogo vindo do Giant Bomb, pronto pra ser rolado. O {@code id}
 * ja vem negativo ({@code -idDoGiantBomb}): e o namespace que separa os
 * personagens de jogos dos de anime (AniList) na tabela de claims.
 */
public record GameCharacter(
        long id,
        String name,
        String gender,
        String game,
        String imageUrl,
        int kakera) {

    public boolean isFemale() {
        return "Female".equalsIgnoreCase(gender);
    }

    public boolean isMale() {
        return "Male".equalsIgnoreCase(gender);
    }
}
```

- [ ] **Step 4: Criar `GiantBombClient.java`**

```java
package org.chibot.Harem;

import org.chibot.Config.ChiConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Cliente da API REST do Giant Bomb (giantbomb.com/api), de onde vem os
 * personagens de jogos do harem. Cada chamada busca uma pagina de personagens
 * ordenados por id crescente — os ids baixos sao os classicos cadastrados
 * primeiro no site (Mario, Link...), entao sortear o offset dentro dos
 * primeiros {@code MAX_OFFSET} mantem os rolls reconheciveis.
 *
 * <p>Sem {@code GIANTBOMB_API_KEY} configurada, {@link #isAvailable()} retorna
 * false e os comandos de roll de jogos respondem um aviso amigavel.
 */
public class GiantBombClient {

    private static final String ENDPOINT = "https://www.giantbomb.com/api/characters/";
    private static final String FIELDS = "id,name,gender,image,first_appeared_in_game,deck";

    public static final int PER_PAGE = 100;
    /** Offsets sorteaveis: ~8000 primeiros personagens cadastrados no site. */
    public static final int MAX_OFFSET = 7900;

    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public GiantBombClient() {
        this(apiKeyFromConfig());
    }

    /** Construtor com chave explicita (testes). */
    GiantBombClient(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    private static String apiKeyFromConfig() {
        ChiConfig config = ChiConfig.get();
        if (config != null) {
            return config.getGiantBombApiKey();
        }
        String fromEnv = System.getenv("GIANTBOMB_API_KEY");
        return fromEnv == null ? "" : fromEnv;
    }

    /** false = sem chave configurada (rolls de jogos desligados). */
    public boolean isAvailable() {
        return !apiKey.isBlank();
    }

    /** Busca uma pagina de personagens a partir do offset (descarta os sem nome/imagem). */
    public List<GameCharacter> fetchPage(int offset) throws IOException, InterruptedException {
        String url = ENDPOINT + "?api_key=" + apiKey + "&format=json&limit=" + PER_PAGE
                + "&offset=" + offset + "&sort=id:asc&field_list=" + FIELDS;

        // O Giant Bomb bloqueia o User-Agent padrao do JDK — precisa ser custom.
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .header("User-Agent", "ChiBot/1.0 (bot de Discord)")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Giant Bomb respondeu HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    /** Package-private pros testes: converte o JSON da API em personagens rolaveis. */
    static List<GameCharacter> parse(String json) throws IOException {
        JSONObject root = new JSONObject(json);
        if (root.optInt("status_code", -1) != 1) {
            throw new IOException("Giant Bomb retornou erro: " + root.optString("error", "desconhecido"));
        }
        List<GameCharacter> out = new ArrayList<>();
        JSONArray results = root.optJSONArray("results");
        if (results == null) {
            return out;
        }
        for (int i = 0; i < results.length(); i++) {
            JSONObject c = results.getJSONObject(i);
            String name = c.optString("name", "");
            JSONObject image = c.optJSONObject("image");
            String imageUrl = image == null ? "" : image.optString("medium_url", "");
            // "gb_default" e a imagem placeholder do site — personagem sem arte real.
            if (name.isBlank() || imageUrl.isBlank() || imageUrl.contains("gb_default")) {
                continue;
            }
            long gbId = c.getLong("id");
            boolean temDeck = !c.isNull("deck") && !c.optString("deck", "").isBlank();
            out.add(new GameCharacter(
                    -gbId,
                    name,
                    genderOf(c.optInt("gender", 0)),
                    gameOf(c),
                    imageUrl,
                    kakeraValue(gbId, temDeck)));
        }
        return out;
    }

    /** Giant Bomb codifica genero como int: 1 = masculino, 2 = feminino, 0 = desconhecido. */
    private static String genderOf(int gender) {
        return switch (gender) {
            case 1 -> "Male";
            case 2 -> "Female";
            default -> null;
        };
    }

    private static String gameOf(JSONObject character) {
        JSONObject game = character.optJSONObject("first_appeared_in_game");
        String name = game == null ? "" : game.optString("name", "");
        return name.isBlank() ? "Origem desconhecida" : name;
    }

    /**
     * Valor em kakera do personagem, deterministico (mesmo id = mesmo valor,
     * sempre): o Giant Bomb nao tem contagem de favoritos como o AniList, entao
     * um hash estavel do id vira 15..400, e quem tem descricao ({@code deck} —
     * sinal de personagem notavel) vale 3x, saturando em 1200.
     */
    static int kakeraValue(long gbId, boolean temDeck) {
        int base = 15 + (int) Math.floorMod(gbId * 2654435761L, 386);
        return temDeck ? Math.min(1200, base * 3) : base;
    }
}
```

- [ ] **Step 5: Rodar os testes e ver passar**

Run: `.\gradlew.bat test --tests "org.chibot.Harem.GiantBombClientTest"`
Expected: PASS (4 testes).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/chibot/Harem/GameCharacter.java src/main/java/org/chibot/Harem/GiantBombClient.java src/test/java/org/chibot/Harem/GiantBombClientTest.java
git commit -m "Harém: GameCharacter e cliente da API do Giant Bomb"
```

---

### Task 3: Timers de jogos no `HaremRepository` (com testes)

**Files:**
- Modify: `src/main/java/org/chibot/Database/HaremRepository.java`
- Test: `src/test/java/org/chibot/Database/HaremRepositoryTest.java`

**Interfaces:**
- Produces (usados pela Task 4/5):
  - `record Player(long kakera, long lastClaimMs, int rollsUsed, long rollsHour, long lastDailyMs, int bonusRolls, int towerLevel, int gameRollsUsed, long gameRollsHour, long gameLastClaimMs)` — 3 campos novos **no fim**.
  - `tryUseGameRoll(String guildId, String userId, long hour, int maxRolls)` → `int` (restantes, ou -1 se acabou; sem bonus_rolls).
  - `setLastGameClaim(String guildId, String userId, long epochMs)` → `void`.

- [ ] **Step 1: Escrever os testes que falham**

Adicionar em `HaremRepositoryTest.java`:

```java
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
    }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.HaremRepositoryTest"`
Expected: FAIL — erro de compilação (`tryUseGameRoll`, `setLastGameClaim`, `gameLastClaimMs` não existem).

- [ ] **Step 3: Implementar no repositório**

Em `HaremRepository.java`:

1. **Record `Player`** — substituir por:
```java
    /** Estado de um jogador num servidor. */
    public record Player(long kakera, long lastClaimMs, int rollsUsed, long rollsHour,
                         long lastDailyMs, int bonusRolls, int towerLevel,
                         int gameRollsUsed, long gameRollsHour, long gameLastClaimMs) {}
```

2. **Schema** — no `CREATE TABLE IF NOT EXISTS harem_player`, depois de `tower_level INTEGER NOT NULL DEFAULT 0,` adicionar:
```sql
                        game_rolls_used INTEGER NOT NULL DEFAULT 0,
                        game_rolls_hour INTEGER NOT NULL DEFAULT 0,
                        game_last_claim INTEGER NOT NULL DEFAULT 0,
```
e junto dos `addColumnIfMissing` existentes de `harem_player`:
```java
            addColumnIfMissing(st, "harem_player", "game_rolls_used INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(st, "harem_player", "game_rolls_hour INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(st, "harem_player", "game_last_claim INTEGER NOT NULL DEFAULT 0");
```

3. **`getPlayer`** — SELECT passa a incluir as colunas novas:
```java
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT kakera, last_claim, rolls_used, rolls_hour, last_daily, bonus_rolls, tower_level,
                       game_rolls_used, game_rolls_hour, game_last_claim
                FROM harem_player WHERE guild_id = ? AND user_id = ?
                """)) {
```
o `new Player(...)` do resultado ganha os 3 campos:
```java
                    return new Player(rs.getLong("kakera"), rs.getLong("last_claim"),
                            rs.getInt("rolls_used"), rs.getLong("rolls_hour"),
                            rs.getLong("last_daily"), rs.getInt("bonus_rolls"),
                            rs.getInt("tower_level"),
                            rs.getInt("game_rolls_used"), rs.getLong("game_rolls_hour"),
                            rs.getLong("game_last_claim"));
```
e os DOIS fallbacks `new Player(0, 0, 0, 0, 0, 0, 0)` viram `new Player(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)`.

4. **`tryUseGameRoll`** — logo depois de `tryUseRoll`:
```java
    /**
     * Consome um roll de jogos do jogador (cota propria, sem bonus de torre nem
     * rolls comprados). Retorna quantos sobram depois desse, ou -1 se acabou.
     * Sem banco, libera o roll (so nao conta).
     */
    public synchronized int tryUseGameRoll(String guildId, String userId, long hour, int maxRolls) {
        if (!available()) {
            return maxRolls - 1;
        }
        try {
            ensurePlayer(guildId, userId);
            int used = 0;
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT game_rolls_used, game_rolls_hour
                    FROM harem_player WHERE guild_id = ? AND user_id = ?
                    """)) {
                ps.setString(1, guildId);
                ps.setString(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getLong("game_rolls_hour") == hour) {
                        used = rs.getInt("game_rolls_used");
                    }
                }
            }
            if (used >= maxRolls) {
                return -1;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE harem_player SET game_rolls_used = ?, game_rolls_hour = ? WHERE guild_id = ? AND user_id = ?")) {
                ps.setInt(1, used + 1);
                ps.setLong(2, hour);
                ps.setString(3, guildId);
                ps.setString(4, userId);
                ps.executeUpdate();
            }
            return maxRolls - used - 1;
        } catch (SQLException e) {
            log.warn("Falha ao consumir roll de jogo de {}/{}.", guildId, userId, e);
            return maxRolls - 1;
        }
    }
```

5. **`setLastGameClaim`** — logo depois de `setLastClaim`:
```java
    public synchronized void setLastGameClaim(String guildId, String userId, long epochMs) {
        if (!available()) {
            return;
        }
        try {
            ensurePlayer(guildId, userId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE harem_player SET game_last_claim = ? WHERE guild_id = ? AND user_id = ?")) {
                ps.setLong(1, epochMs);
                ps.setString(2, guildId);
                ps.setString(3, userId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warn("Falha ao registrar o claim de jogo de {}/{}.", guildId, userId, e);
        }
    }
```

- [ ] **Step 4: Rodar TODOS os testes e ver passar**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL — os 3 testes novos passam e nenhum existente quebra (atenção: se algum código construir `Player` posicionalmente fora do repositório, o compilador aponta — corrigir adicionando `, 0, 0, 0`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/chibot/Database/HaremRepository.java src/test/java/org/chibot/Database/HaremRepositoryTest.java
git commit -m "Harém: cota de rolls e cooldown de claim próprios pra personagens de jogos"
```

---

### Task 4: `rollGame` no `HaremService`

**Files:**
- Modify: `src/main/java/org/chibot/Harem/HaremService.java`

**Interfaces:**
- Consumes: `GameCharacter`, `GiantBombClient` (Task 2); `tryUseGameRoll`, `setLastGameClaim`, `Player.gameRollsUsed()/gameRollsHour()/gameLastClaimMs()` (Task 3).
- Produces (usados pela Task 5):
  - `HaremService.ROLLS_JOGO_POR_HORA` = 10 (`public static final int`).
  - `rollGame(CommandContext ctx, Genero genero)` → `void`.
  - `gameRollsRestantes(String guildId, String userId)` → `int`.
  - `proximoGameClaimMs(String guildId, String userId)` → `long`.

- [ ] **Step 1: Constante, cliente e pools**

Em `HaremService.java`:

1. Constante junto de `ROLLS_POR_HORA`:
```java
    /** Cota propria dos rolls de jogos (sem bonus de torre nem rolls comprados). */
    public static final int ROLLS_JOGO_POR_HORA = 10;
```

2. Campo junto de `aniList`:
```java
    private final GiantBombClient giantBomb = new GiantBombClient();
```

3. Pools junto dos existentes:
```java
    private final ArrayDeque<GameCharacter> gameWaifus = new ArrayDeque<>();
    private final ArrayDeque<GameCharacter> gameHusbandos = new ArrayDeque<>();
    private final ArrayDeque<GameCharacter> gameOutros = new ArrayDeque<>();
```

- [ ] **Step 2: Flag `game` no record `Roll`**

Substituir o record `Roll` por:
```java
    /** Dados de um personagem livre rolado, esperando alguem reagir pra casar. */
    private record Roll(long charId, String name, String series, String image, int kakera,
                        long expiraMs, String guildId, boolean game) {
    }
```

- [ ] **Step 3: Extrair `postarRoll` e reescrever `roll`**

O trecho de `roll(...)` a partir de `HaremRepository.Claim dona = repo.findOwner(...)` até o fim do método vira um método compartilhado (usado pelo roll de anime e pelo de jogos):

```java
    /** Monta e posta o embed do personagem sorteado (livre = casavel por reacao; casado = botao de kakera). */
    private void postarRoll(CommandContext ctx, long charId, String name, String origem,
                            String imageUrl, int kakera, boolean game, int restantes) {
        String guildId = ctx.getGuild().getId();
        long agora = System.currentTimeMillis();
        HaremRepository.Claim dona = repo.findOwner(guildId, charId);
        long expira = (agora + JANELA_CLAIM.toMillis()) / 1000L;

        EmbedBuilder eb = new EmbedBuilder()
                .setTitle(name)
                .setDescription((game ? "🎮 " : "") + origem + "\n\n" + HaremEmojis.kakera(kakera)
                        + " **" + kakera + "** kakera")
                .setImage(imageUrl);

        if (dona == null) {
            eb.setColor(COR_LIVRE)
                    .setFooter("💗 Reage com qualquer emoji pra casar! · " + restantes + " roll(s) restantes");
            String conteudo = null;
            List<String> desejantes = repo.findWishers(guildId, name.toLowerCase(Locale.ROOT));
            if (!desejantes.isEmpty()) {
                conteudo = "✨ " + desejantes.stream()
                        .map(id -> "<@" + id + ">")
                        .collect(Collectors.joining(" "))
                        + " — apareceu alguém da sua lista de desejos!";
            }
            // Sem botao: quem reagir primeiro (qualquer emoji) casa — ver onMessageReactionAdd.
            Roll roll = new Roll(charId, name, origem, imageUrl, kakera,
                    agora + JANELA_CLAIM.toMillis(), guildId, game);
            ctx.replyEmbedAndThen(conteudo, eb.build(), msg -> registrarRoll(msg.getIdLong(), roll));
        } else {
            int saque = saqueDe(kakera);
            eb.setColor(COR_CASADA)
                    .setFooter("💍 Pertence a " + dona.ownerName());
            Button botao = Button.of(ButtonStyle.SECONDARY, "hkak:" + saque + ":" + expira,
                    String.valueOf(saque), HaremEmojis.kakeraEmoji(kakera));
            ctx.replyEmbedWithButtons(null, eb.build(), List.of(botao));
        }
    }
```

E `roll(...)` fica só com a parte de cota + sorteio:

```java
    /** Sorteia um personagem e posta o embed com o botao de claim (ou de kakera, se ja casado). */
    public void roll(CommandContext ctx, Genero genero) {
        ctx.deferReply();
        String guildId = ctx.getGuild().getId();
        String userId = ctx.getAuthor().getId();
        long hora = System.currentTimeMillis() / 3_600_000L;

        // A torre de kakera da +1 roll por hora a cada nivel.
        int maxRolls = ROLLS_POR_HORA + repo.getPlayer(guildId, userId).towerLevel();
        int restantes = repo.tryUseRoll(guildId, userId, hora, maxRolls);
        if (restantes < 0) {
            ctx.reply("Seus rolls acabaram~ pode rolar de novo " + relativo((hora + 1) * 3_600_000L)
                    + "! (｡•́︿•̀｡)");
            return;
        }

        AnimeCharacter ch = pickCharacter(genero);
        if (ch == null) {
            ctx.reply("Não consegui falar com o AniList agora... tenta de novo daqui a pouco? (；△；)");
            return;
        }
        postarRoll(ctx, ch.id(), ch.name(), ch.series(), ch.imageUrl(), ch.kakera(), false, restantes);
    }
```

(A variável `agora` do método original sai — `postarRoll` calcula a própria; `hora` passa a ser derivada direto.)

- [ ] **Step 4: `rollGame` + pools de jogos**

Depois de `roll(...)`:

```java
    /** Sorteia um personagem de jogo (Giant Bomb) e posta o embed de claim. */
    public void rollGame(CommandContext ctx, Genero genero) {
        if (!giantBomb.isAvailable()) {
            ctx.reply("Os rolls de jogos não estão configurados aqui (falta a `GIANTBOMB_API_KEY`)~ (・_・;)");
            return;
        }
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
        if (ch == null) {
            ctx.reply("Não consegui falar com o Giant Bomb agora... tenta de novo daqui a pouco? (；△；)");
            return;
        }
        postarRoll(ctx, ch.id(), ch.name(), ch.game(), ch.imageUrl(), ch.kakera(), true, restantes);
    }
```

E, junto de `pickCharacter`/`pollPool`/`refill`, os espelhos de jogos:

```java
    private synchronized GameCharacter pickGameCharacter(Genero genero) {
        for (int tentativa = 0; tentativa < 3; tentativa++) {
            GameCharacter ch = pollGamePool(genero);
            if (ch != null) {
                return ch;
            }
            refillGames();
        }
        return pollGamePool(genero);
    }

    private GameCharacter pollGamePool(Genero genero) {
        switch (genero) {
            case WAIFU:
                return gameWaifus.poll();
            case HUSBANDO:
                return gameHusbandos.poll();
            default:
                int total = gameWaifus.size() + gameHusbandos.size() + gameOutros.size();
                if (total == 0) {
                    return null;
                }
                int r = random.nextInt(total);
                if (r < gameWaifus.size()) {
                    return gameWaifus.poll();
                }
                return r < gameWaifus.size() + gameHusbandos.size()
                        ? gameHusbandos.poll() : gameOutros.poll();
        }
    }

    /** Busca um offset aleatorio do Giant Bomb e distribui os personagens nos pools por genero. */
    private void refillGames() {
        int offset = random.nextInt(GiantBombClient.MAX_OFFSET / GiantBombClient.PER_PAGE + 1)
                * GiantBombClient.PER_PAGE;
        try {
            List<GameCharacter> lote = new ArrayList<>(giantBomb.fetchPage(offset));
            Collections.shuffle(lote, random);
            for (GameCharacter ch : lote) {
                ArrayDeque<GameCharacter> pool =
                        ch.isFemale() ? gameWaifus : ch.isMale() ? gameHusbandos : gameOutros;
                if (pool.size() < MAX_POOL) {
                    pool.add(ch);
                }
            }
        } catch (Exception e) {
            log.warn("Falha ao buscar personagens no Giant Bomb (offset {}).", offset, e);
        }
    }
```

- [ ] **Step 5: Cooldown de claim por tipo (reação e finalização)**

Em `onMessageReactionAdd`, trocar:
```java
        long proximoClaim = repo.getPlayer(guildId, userId).lastClaimMs() + INTERVALO_CLAIM.toMillis();
```
por:
```java
        // Claims de anime e de jogos tem cooldowns independentes.
        HaremRepository.Player p = repo.getPlayer(guildId, userId);
        long ultimoClaim = roll.game() ? p.gameLastClaimMs() : p.lastClaimMs();
        long proximoClaim = ultimoClaim + INTERVALO_CLAIM.toMillis();
```

Em `finalizarClaim`, trocar `repo.setLastClaim(guildId, userId, agora);` por:
```java
        if (roll.game()) {
            repo.setLastGameClaim(guildId, userId, agora);
        } else {
            repo.setLastClaim(guildId, userId, agora);
        }
```

Em `editarRoll`, o 🎮 se mantém quando o embed é reescrito — trocar `.setDescription(roll.series() + ...)` por:
```java
                .setDescription((roll.game() ? "🎮 " : "") + roll.series() + "\n\n"
                        + HaremEmojis.kakera(roll.kakera()) + " **" + roll.kakera() + "** kakera")
```

- [ ] **Step 6: Helpers pros timers**

Depois de `proximoClaimMs`:

```java
    /** Rolls de jogos que ainda sobram pro jogador na hora atual, sem consumir nenhum. */
    public int gameRollsRestantes(String guildId, String userId) {
        HaremRepository.Player p = repo.getPlayer(guildId, userId);
        long hora = System.currentTimeMillis() / 3_600_000L;
        int usados = p.gameRollsHour() == hora ? p.gameRollsUsed() : 0;
        return Math.max(0, ROLLS_JOGO_POR_HORA - usados);
    }

    /** Instante (epoch ms) em que o jogador pode casar um personagem de jogo de novo. */
    public long proximoGameClaimMs(String guildId, String userId) {
        return repo.getPlayer(guildId, userId).gameLastClaimMs() + INTERVALO_CLAIM.toMillis();
    }
```

Também atualizar o javadoc da classe (primeiro parágrafo) pra mencionar a fonte nova:
```java
 * Sistema de waifu/husbando estilo Mudae: rolls sorteiam personagens reais de
 * anime (AniList) ou de jogos (Giant Bomb) e quem reagir (com qualquer emoji)
 * dentro da janela casa com o personagem — um dono por personagem por servidor.
```

- [ ] **Step 7: Compilar e rodar todos os testes**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL (o serviço não tem teste unitário próprio — depende de JDA —, então a verificação é compilação + suíte inteira verde).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/chibot/Harem/HaremService.java
git commit -m "Harém: rollGame com pools do Giant Bomb e cooldown de claim próprio"
```

---

### Task 5: Comandos novos + timers + 🎮 na listagem

**Files:**
- Create: `src/main/java/org/chibot/Commands/Harem/GameWaifuCommand.java`
- Create: `src/main/java/org/chibot/Commands/Harem/GameHusbandoCommand.java`
- Create: `src/main/java/org/chibot/Commands/Harem/GameRollCommand.java`
- Modify: `src/main/java/org/chibot/Commands/Harem/TimersCommand.java`
- Modify: `src/main/java/org/chibot/Commands/Harem/HaremCommand.java` (linhas ~139 e ~156)
- Modify: `src/main/java/org/chibot/Commands/Harem/ProfileCommand.java` (linha ~181)

**Interfaces:**
- Consumes: `HaremService.rollGame(ctx, Genero)`, `HaremService.ROLLS_JOGO_POR_HORA`, `gameRollsRestantes(...)`, `proximoGameClaimMs(...)` (Task 4).
- Produces: comandos `gamewaifu`/`gamehusbando`/`gameroll` (autodescobertos pelo `CommandManager` — sem registro manual).

- [ ] **Step 1: Criar os três comandos**

`GameWaifuCommand.java`:
```java
package org.chibot.Commands.Harem;

import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Harem.HaremService;

import java.util.List;

public class GameWaifuCommand implements ICommand {

    @Override
    public String getName() {
        return "gamewaifu";
    }

    @Override
    public List<String> getAliases() {
        return List.of("wg", "gw");
    }

    @Override
    public String getDescription() {
        return "Rola uma waifu de jogos~ clica no 💗 pra casar! 🎮";
    }

    @Override
    public String getUsage() {
        return "gamewaifu";
    }

    @Override
    public String getCategory() {
        return "Harém";
    }

    @Override
    public boolean isGuildOnly() {
        return true;
    }

    @Override
    public void execute(CommandContext ctx) {
        HaremService service = HaremService.get();
        if (service == null) {
            ctx.reply("O sistema de harém ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }
        service.rollGame(ctx, HaremService.Genero.WAIFU);
    }
}
```

`GameHusbandoCommand.java` — idêntico, trocando:
```java
    public String getName() {
        return "gamehusbando";
    }

    public List<String> getAliases() {
        return List.of("hg", "gh");
    }

    public String getDescription() {
        return "Rola um husbando de jogos~ clica no 💗 pra casar! 🎮";
    }

    public String getUsage() {
        return "gamehusbando";
    }
    // ...
        service.rollGame(ctx, HaremService.Genero.HUSBANDO);
```

`GameRollCommand.java` — idêntico, trocando:
```java
    public String getName() {
        return "gameroll";
    }

    public List<String> getAliases() {
        return List.of("gr", "rg");
    }

    public String getDescription() {
        return "Rola um personagem de jogos aleatório (waifu ou husbando)~ 🎮";
    }

    public String getUsage() {
        return "gameroll";
    }
    // ...
        service.rollGame(ctx, HaremService.Genero.QUALQUER);
```

- [ ] **Step 2: Timers de jogos no `TimersCommand`**

Em `execute(...)`, junto das variáveis existentes:
```java
        int gameRolls = service.gameRollsRestantes(guildId, userId);
        long proximoGameClaim = service.proximoGameClaimMs(guildId, userId);
        String gameClaimTexto = agora >= proximoGameClaim
                ? "Disponível agora! Vai lá rolar~ 🕹️"
                : "De novo " + HaremService.relativo(proximoGameClaim);
```

E no builder do embed, logo depois do field `"💍 Casamento"`:
```java
                .addField("🎮 Rolls (jogos)", gameRolls + "/" + HaremService.ROLLS_JOGO_POR_HORA
                        + " · reseta " + HaremService.relativo(resetRolls), true)
                .addField("🕹️ Casamento (jogos)", gameClaimTexto, true)
```

- [ ] **Step 3: 🎮 na listagem do harém**

Em `HaremCommand.render(...)`, trocar a linha que monta cada item:
```java
            sb.append(HaremEmojis.kakera(claim.kakera())).append("`").append(claim.kakera())
                    .append("` **").append(claim.name())
                    .append("** · ").append(claim.series()).append('\n');
```
por:
```java
            sb.append(HaremEmojis.kakera(claim.kakera())).append("`").append(claim.kakera())
                    .append("` ").append(claim.charId() < 0 ? "🎮 " : "").append("**")
                    .append(claim.name()).append("** · ").append(claim.series()).append('\n');
```

- [ ] **Step 4: Aceitar ids negativos na imagem do harém e no favorito do perfil**

Os ids de jogo são negativos, mas dois guards tratam "id definido" como `> 0`
(0 = nunca definido) e ignorariam personagens de jogo:

Em `HaremCommand.render(...)` (~linha 139), trocar:
```java
        long escolhida = repo.getProfile(guildId, donoId).haremCharId();
        if (escolhida > 0) {
```
por:
```java
        long escolhida = repo.getProfile(guildId, donoId).haremCharId();
        if (escolhida != 0) {
```

Em `ProfileCommand` (~linha 181), trocar:
```java
        HaremRepository.Claim fav = profile.favCharId() > 0
                ? repo.findOwner(guildId, profile.favCharId()) : null;
```
por:
```java
        HaremRepository.Claim fav = profile.favCharId() != 0
                ? repo.findOwner(guildId, profile.favCharId()) : null;
```

- [ ] **Step 5: Compilar e rodar todos os testes**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/chibot/Commands/Harem/GameWaifuCommand.java src/main/java/org/chibot/Commands/Harem/GameHusbandoCommand.java src/main/java/org/chibot/Commands/Harem/GameRollCommand.java src/main/java/org/chibot/Commands/Harem/TimersCommand.java src/main/java/org/chibot/Commands/Harem/HaremCommand.java src/main/java/org/chibot/Commands/Harem/ProfileCommand.java
git commit -m "Harém: comandos gamewaifu/gamehusbando/gameroll, timers e 🎮 na listagem"
```

---

### Task 6: Verificação de ponta a ponta (manual, com chave real)

**Files:** nenhum (verificação).

- [ ] **Step 1: Suíte completa**

Run: `.\gradlew.bat test`
Expected: BUILD SUCCESSFUL, todos os testes verdes.

- [ ] **Step 2: Smoke da API real (se houver `GIANTBOMB_API_KEY` disponível)**

Com a chave no `.env` (ou env var), subir o bot localmente (`.\gradlew.bat run`) e num servidor de teste:
1. `!wg` → embed com personagem feminino de jogo, 🎮 antes do nome do jogo, contador "roll(s) restantes" decrementando a partir de 10 (independente do `!w`).
2. Reagir no roll → casa; `!timers` mostra "Casamento (jogos)" em cooldown e "Casamento" (anime) livre.
3. `!harem` → personagem de jogo listado com 🎮.
4. Sem a chave (remover do `.env` e reiniciar): `!wg` responde o aviso de não configurado.

Se não houver chave disponível na hora, registrar isso e deixar o smoke pro deploy.
