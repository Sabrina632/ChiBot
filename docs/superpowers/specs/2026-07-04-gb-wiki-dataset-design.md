# Personagens de jogos via dumps do giant-bomb-wiki

**Data:** 2026-07-04
**Status:** aprovado
**Substitui parcialmente:** `2026-07-04-game-character-design.md` (a parte da fonte de dados)

## Contexto e objetivo

O Giant Bomb não emite mais API keys, então o `GiantBombClient` (que depende de
`GIANTBOMB_API_KEY`) ficou sem futuro. O projeto
[Giant-Bomb-Dot-Com/giant-bomb-wiki](https://github.com/Giant-Bomb-Dot-Com/giant-bomb-wiki)
publica dumps SQL completos do banco da wiki em `gb_api_db_init/` — incluindo
`14_character.sql.gz` com ~57.100 personagens, `18_image.sql.gz` com as URLs de
imagem e `19_relations.sql.gz` com vínculos personagem↔franquia. Este design
troca a API por um **dataset local embarcado no bot**, extraído desses dumps.

Tudo abaixo foi verificado nos dumps reais em 2026-07-04:

- Os ids dos dumps **são os mesmos da API antiga** (Bowser = 337, igual ao
  ref `3005-337`), então os claims existentes com `char_id` negativo continuam
  apontando pros personagens certos.
- As URLs de imagem (`https://www.giantbomb.com/a/uploads/...`) funcionam por
  hotlink, inclusive a variante `scale_medium` (original passa de 1 MB).
- `is_important` está zerado pra todos os personagens e `deck` existe pra quase
  todos — nenhum dos dois serve como filtro de notabilidade.
- O vínculo personagem↔jogo (`wiki_assoc_game_character`) é incompleto demais
  (só 3.752 personagens; Mario tem zero). O vínculo com **franquia**
  (`wiki_assoc_character_franchise`, ~11.900 personagens) é o campo confiável
  pra "série".
- O dump de `wiki_character` tem 18 colunas (as 17 do schema +
  `mw_formatted_description` inserida após `description` pela migração
  `00_wiki_schema_add_formatted_description_column.sql`).

## Decisões validadas com o usuário

1. **Arquitetura:** dataset local compacto commitado no repo, carregado pelo
   bot na inicialização. Sem MySQL novo, sem self-host do wiki, sem serviço
   extra na VPS.
2. **Curadoria:** entram no pool personagens com descrição wiki ≥ 1000
   caracteres, não deletados e com imagem real → **~7.197 personagens**
   (topo: Master Chief, Batman, Snake, Ash Ketchum...).
3. **Gênero:** nenhum personagem fica de fora do `gamewaifu`/`gamehusbando`.
   Cascata determinística na extração (campo `gender` do dataset nunca nulo).
4. **`gameroll`** continua como roll misto dos dois lados, espelhando o `roll`
   do anime.

## Extração (offline, fora do bot)

Script novo **`tools/extract_gb_characters.py`** (Python 3, sem dependências
fora da stdlib), rodado manualmente na máquina de dev quando se quiser
(re)gerar o dataset:

1. Baixa de `raw.githubusercontent.com` os dumps `14_character.sql.gz`,
   `18_image.sql.gz`, `08_franchise.sql.gz` e `19_relations.sql.gz` (~27 MB).
2. Parseia os `INSERT INTO ... VALUES` (parser próprio de tuplas SQL, com
   tratamento de aspas escapadas com `\`; ler como UTF-8).
   Ordem real das colunas de `wiki_character`: `id, image_id, real_name,
   gender, birthday, date_created, date_updated, name, aliases, deck, slug,
   mw_formatted_description, description, is_important, background_image_id,
   death, stats_id, deleted`.
3. **Filtro:** `deleted != 1`, descrição com ≥ 1000 caracteres (contados no
   HTML cru — foi assim que os números deste spec foram medidos; o HTML só é
   removido pra inferência de gênero), e imagem resolvível na tabela `image`
   cuja URL não seja placeholder (`gb_default`/`default`).
4. **Série:** nome da franquia via `wiki_assoc_character_franchise` +
   `08_franchise` (se houver mais de uma, a de menor id); sem franquia →
   `"Origem desconhecida"`.
5. **Imagem:** URL do dump com `/original/` trocado por `/scale_medium/`.
6. **Gênero (cascata):**
   1. `gender` do dump (1 = Male, 2 = Female) — cobre 5.941;
   2. maioria simples de pronomes na descrição sem HTML
      (`he/him/his/himself` vs `she/her/hers/herself`, case-insensitive) —
      cobre 655, precisão medida de 98,5%;
   3. palavras de gênero no nome + primeiros 400 chars da descrição
      (masc: mr, sir, lord, king, prince, father, son, boy, man, male, guy,
      duke, emperor, god...; fem: mrs, ms, miss, lady, queen, princess,
      mother, daughter, girl, woman, female, empress, goddess, witch...) —
      cobre 15;
   4. fallback **Male** (sobra medida: 586, quase todos jogadores de futebol
      reais de jogos de esporte e criaturas).
   Resultado medido: **1.936 Female, 5.261 Male, 0 sem gênero**.
7. **Notável (pro kakera):** flag booleana = descrição ≥ 5000 caracteres
   (~1.400 personagens, ~20% do pool — proporção parecida com a do `deck` na
   API antiga).
8. Grava **`src/main/resources/harem/game_characters.tsv.gz`**: uma linha por
   personagem, colunas `id` (positivo, do Giant Bomb), `name`, `gender`
   (`Female`|`Male`), `series`, `image_url`, `notable` (`0`|`1`), separadas
   por TAB (TAB/quebras de linha dentro de campos são substituídos por
   espaço na extração). Tamanho estimado: algumas centenas de KB — vai no git.

O script imprime um resumo (total, waifus, husbandos, notáveis) pra conferência
a cada execução.

## Lado Java

**`GameCharacterDataset`** (novo, substitui `GiantBombClient`):

- Carrega `harem/game_characters.tsv.gz` do classpath no construtor
  (`GZIPInputStream` + parse do TSV) pra duas listas imutáveis em memória:
  `females` e `males` (~7.200 records no total, poucos MB de heap).
- Constrói cada `GameCharacter` com `id` **negativado** (mantém o namespacing
  `-idDoGiantBomb`) e kakera calculado por `kakeraValue(id, notable)` — mesma
  fórmula determinística atual (`15 + hash(id) % 386`, notável ×3 saturando em
  1200), só trocando o significado do booleano de "tem deck" pra "notável".
- Métodos: `randomFemale(Random)`, `randomMale(Random)`, `randomAny(Random)`
  (sorteia proporcional ao tamanho das listas). Linha malformada no TSV é
  pulada com warning; dataset ausente ou vazio no classpath = falha no startup
  (é bug de build, não condição de runtime).

**`GameCharacter`:** record inalterado (`gender` agora sempre "Female" ou
"Male"; `isFemale()`/`isMale()` continuam funcionando).

**`HaremService`:**

- Campo `giantBomb` vira `gameDataset`.
- `pickGameCharacter(Genero)` sorteia direto do dataset; os pools
  `gameWaifus`/`gameHusbandos`/`gameOutros`, o `refillGames()` e o lock próprio
  dos pools de jogos são **removidos** (existiam só pra amortizar rede).
- O aviso de "recurso não configurado" (quando não havia API key) some — o
  dataset embarcado está sempre disponível.
- Cota de rolls, cooldown de claim de jogos, fluxo de reação/claim/trade e o
  🎮 na listagem ficam intactos.

**Config:** `GIANTBOMB_API_KEY` sai do `ChiConfig`, do `.env.example` e do
README.

## Erros

- Dataset ausente/corrompido: exceção no startup com mensagem clara.
- Imagem que der 404 no futuro: o embed do Discord degrada (sem imagem), igual
  hoje — sem tratamento novo.

## Deploy (VPS)

Nada muda: o `.tsv.gz` vai no git, e o deploy segue `git pull` +
`docker compose up --build`. Nenhum serviço, volume ou variável nova.

## Testes

- `GameCharacterDataset`: parse de TSV (linha completa, linha malformada
  pulada, gzip do classpath), ids negativados, sorteio respeita gênero.
- `kakeraValue`: determinismo, faixa 15–1200, fator de notável (pode herdar os
  testes atuais trocando o nome do parâmetro).
- Ficam: testes de `tryUseGameRoll`/`setLastGameClaim` e de claims com id
  negativo.
- Morrem: testes de parse do JSON da API em `GiantBombClientTest`.
- O script Python é validado pelo resumo impresso + spot-check manual (Mario,
  Link, Peach), sem suite própria.

## Riscos aceitos

- **Hotlink das imagens** depende do giantbomb.com continuar servindo
  `/a/uploads/` (verificado funcionando hoje). Se sair do ar, migrar as URLs
  pro GCS do projeto wiki numa re-extração.
- **~2% de gênero errado** nas etapas 2–4 da cascata (descrição que fala de
  outro personagem, monstro classificado como masculino por fallback).
  Ajustável caso a caso re-rodando a extração com overrides, se um dia doer.
- **Dataset congelado** na data da extração — mas a API também está congelada
  (sem keys novas); o wiki é a fonte viva, e re-rodar o script atualiza tudo.

## Fora de escopo

- Servir/cachear imagens localmente.
- Overrides manuais de gênero (só se erros incomodarem na prática).
- Atualização automática/periódica do dataset.
- Mudanças na economia (cota, cooldown, kakera dos claims existentes).
