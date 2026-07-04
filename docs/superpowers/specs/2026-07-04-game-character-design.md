# GameCharacter — personagens de jogos no harém

**Data:** 2026-07-04
**Status:** aprovado

## Objetivo

Adicionar personagens de jogos ao sistema de harém, espelhando o fluxo existente
de personagens de anime (`AnimeCharacter` + `AniListClient`): rolls sorteiam
personagens reais de jogos, quem reagir dentro da janela casa com o personagem,
e o claim entra no mesmo harém do jogador.

## Decisões de escopo (validadas com o usuário)

1. **Fonte de dados:** API pública do Giant Bomb (`giantbomb.com/api`), com
   chave gratuita lida da variável de ambiente `GIANTBOMB_API_KEY`.
2. **Comandos separados:** o roll de jogos NÃO se mistura ao roll de anime.
   Comandos novos: `gamewaifu` (alias `wg`), `gamehusbando` (alias `hg`),
   `gameroll` (alias `gr`).
3. **Com gênero:** os três comandos espelham waifu/husbando/roll usando o campo
   de gênero do Giant Bomb (1 = masculino, 2 = feminino, 0 = desconhecido).
4. **Economia parcialmente separada:**
   - Cota de rolls de jogos própria: 10/hora fixa (constante própria), sem
     bônus de torre e sem consumo de `bonus_rolls` — torre e `buyrolls`
     continuam valendo só pro lado anime.
   - Cooldown de casamento próprio pra jogos (mesmas 3h, mas contador
     independente do de anime).
   - **Kakera é moeda única** (mesmo saldo, mesma loja/torre/daily).
   - **Harém único:** claims de anime e de jogos convivem na mesma tabela e na
     mesma listagem; claims de jogo ganham um 🎮 na exibição.

## Arquitetura

### Namespacing de ids (decisão central)

`harem_claim` tem PK `(guild_id, char_id)`. Ids do Giant Bomb colidiriam com os
do AniList, então **personagens de jogos são armazenados com `char_id`
negativo** (`-idDoGiantBomb`). Consequências:

- `findOwner`, `tryClaim`, `tradeClaims`, `removeClaim`, `setProfileFav` etc.
  funcionam sem mudança — tudo é keyed por `char_id`.
- `char_id < 0` identifica um personagem de jogo em qualquer ponto do código
  (usado pro 🎮 na listagem e pra escolher o cooldown certo).
- Desejos (`wish`) são por nome, então funcionam automaticamente.

### Componentes novos

**`org.chibot.Harem.GameCharacter`** — record espelho do `AnimeCharacter`:

```java
public record GameCharacter(
        long id,        // JÁ negativo (namespace de jogos)
        String name,
        String gender,  // "Female" | "Male" | null
        String game,    // primeiro jogo em que apareceu
        String imageUrl,
        int kakera) {
    public boolean isFemale() { ... }
    public boolean isMale() { ... }
}
```

**`org.chibot.Harem.GiantBombClient`** — espelho do `AniListClient`:

- Endpoint: `GET https://www.giantbomb.com/api/characters/` com
  `api_key`, `format=json`, `limit=100`, `offset`, `sort=id:asc` e
  `field_list=id,name,gender,image,first_appeared_in_game,deck`.
- A API do Giant Bomb **exige um `User-Agent` customizado** (bloqueia o UA
  padrão do HttpClient); enviar algo como `ChiBot/1.0`.
- `fetchPage(int offset)` retorna `List<GameCharacter>`. Offset sorteado pelo
  chamador dentro dos primeiros ~8000 personagens (`MAX_OFFSET = 7900`,
  `PER_PAGE = 100`) — ids baixos são os clássicos cadastrados primeiro no site.
- Descarta personagem sem nome, sem imagem, ou com a imagem placeholder do
  Giant Bomb (URL contendo `gb_default`).
- Campo `game` vem de `first_appeared_in_game.name` (fallback:
  "Origem desconhecida", como no AniList).
- **Kakera determinístico:** o Giant Bomb não tem contagem de favoritos, então
  o valor é derivado do id + presença de `deck` (descrição curta — personagens
  notáveis têm), na mesma faixa 15–1200 do lado anime. Mesmo personagem sempre
  vale o mesmo. Fórmula de referência: base pseudo-aleatória estável a partir
  do id (hash simples) mapeada pra 15–400, com bônus multiplicador se tem
  `deck`, saturando em 1200.
- Se `GIANTBOMB_API_KEY` estiver ausente/vazia, o cliente reporta indisponível
  e os comandos respondem com aviso amigável (sem stack trace).

### Mudanças em componentes existentes

**`HaremService`:**

- Constante `ROLLS_JOGO_POR_HORA = 10`.
- Pools novos: `gameWaifus`, `gameHusbandos`, `gameOutros`
  (`ArrayDeque<GameCharacter>`, mesmo `MAX_POOL`).
- `rollGame(CommandContext, Genero)` (ou flag no `roll` atual): consome a cota
  de jogos (`tryUseGameRoll`), sorteia do pool de jogos (`pickGameCharacter` +
  `refillGames()` espelhando `pickCharacter`/`refill`), monta o mesmo embed
  (título, jogo como "série", kakera, imagem) com 🎮 no rodapé.
- Record `Roll` ganha o flag `boolean game` pra:
  - `onMessageReactionAdd` checar o cooldown de claim de jogos
    (`game_last_claim`) em vez do de anime;
  - `finalizarClaim` gravar `setLastGameClaim`.
- Fluxo de reação, corrida de claim, botão de kakera de personagem casado e
  troca: reaproveitados sem mudança (ids negativos passam direto).

**`HaremRepository`:**

- Colunas novas em `harem_player` via `addColumnIfMissing`:
  `game_rolls_used INTEGER NOT NULL DEFAULT 0`,
  `game_rolls_hour INTEGER NOT NULL DEFAULT 0`,
  `game_last_claim INTEGER NOT NULL DEFAULT 0`.
- Record `Player` ganha os campos correspondentes.
- Métodos novos (mesma lógica dos atuais, sem bonus_rolls):
  `tryUseGameRoll(guildId, userId, hour, maxRolls)` e
  `setLastGameClaim(guildId, userId, epochMs)`.

**Comandos (`Commands/Harem`):**

- `GameWaifuCommand` (`gamewaifu`, alias `wg`), `GameHusbandoCommand`
  (`gamehusbando`, alias `hg`), `GameRollCommand` (`gameroll`, alias `gr`) —
  espelhos de `WaifuCommand`/`Husbando`/`RollCommand` chamando `rollGame`.
- Registro no `CommandManager` junto dos demais.
- `TimersCommand`: campo novo mostrando rolls de jogo restantes e cooldown de
  casamento de jogos.

**Listagem do harém (`HaremCommand`):** claims com `char_id < 0` ganham 🎮 na
linha.

## Tratamento de erros

- Giant Bomb fora do ar / HTTP != 200: log de warning + mensagem "tenta de novo
  daqui a pouco", igual ao fluxo do AniList.
- Chave ausente: resposta amigável no comando explicando que o recurso não está
  configurado.
- Rate limit do Giant Bomb (200 req/recurso/hora) é folgado: cada refill busca
  100 personagens de uma vez e os pools seguram até 400 por gênero.

## Testes

- Parse do JSON do Giant Bomb (fixture com personagem completo, sem imagem,
  com placeholder, sem `first_appeared_in_game`).
- Kakera determinístico: mesmo id → mesmo valor; faixa 15–1200 respeitada.
- `tryUseGameRoll`/`setLastGameClaim` com banco em memória
  (`jdbc:sqlite::memory:`), incluindo a independência dos contadores de anime.
- Claim de id negativo: `tryClaim`/`findOwner`/`tradeClaims` com ids negativos
  (sanidade do namespacing).

## Fora de escopo

- Torre/buyrolls/daily aplicados ao lado de jogos.
- Badges específicos de personagens de jogos.
- Migrar/misturar o roll de anime com o de jogos.
