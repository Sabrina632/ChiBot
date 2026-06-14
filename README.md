<div align="center">

# ChiBot ♡

**Um bot de Discord fofo e enxuto, feito em Java com a [JDA](https://github.com/discord-jda/JDA).**

Música via Lavalink · Harém estilo Mudae · Party Finder de FFXIV · console fofo~ (｡•ᴗ•｡)♡

[![Java 17](assets/badges/java.svg)](https://adoptium.net/)
[![JDA 6.4.2](assets/badges/jda.svg)](https://github.com/discord-jda/JDA)
[![Lavalink v4](assets/badges/lavalink.svg)](https://github.com/lavalink-devs/Lavalink)
[![SQLite 3](assets/badges/sqlite.svg)](https://www.sqlite.org/)
[![Docker](assets/badges/docker.svg)](https://www.docker.com/)
[![Gradle](assets/badges/gradle.svg)](https://gradle.org/)
[![MIT License](assets/badges/license.svg)](LICENSE)

</div>

---

## 📑 Índice

- [✨ Features](#-features)
- [📦 Requisitos](#-requisitos)
- [🔧 Configuração](#-configuração)
- [🚀 Rodando localmente](#-rodando-localmente)
- [🐳 Rodando com Docker](#-rodando-com-docker)
- [🔑 Login do YouTube (OAuth)](#-login-do-youtube-oauth)
- [🧩 Criando um novo comando](#-criando-um-novo-comando)
- [📖 Comandos incluídos](#-comandos-incluídos)
- [📂 Estrutura do projeto](#-estrutura-do-projeto)
- [🔐 Segurança](#-segurança)
- [🧰 Stack](#-stack)
- [📜 Licença](#-licença)

---

## ✨ Features

- **Música** — toca do YouTube (link ou busca), SoundCloud, Bandcamp, Twitch e streams HTTP. O áudio roda num servidor [Lavalink](https://github.com/lavalink-devs/Lavalink), que resolve e toca tudo; a busca por nome pode usar a **YouTube Data API** (chave opcional no config).
- **Harém (estilo Mudae)** — rola waifus/husbandos reais de anime (via [AniList](https://anilist.co)), casa clicando no 💗 dentro de 45s (um dono por personagem por servidor), kakera por popularidade, harém, divórcio, trocas com confirmação por botão, daily, torre de kakera com perks, badges colecionáveis (conquistas, loja e personagens de anime com a arte do AniList), rolls extras comprados com kakera, lista de desejos com ping e timers — tudo persistido em SQLite.
- **Party Finder de FFXIV** — `/pf` lista os PF de Ultimates e Savage do data center Aether (via [xivpf.com](https://xivpf.com)), com emojis de job e composição; `/strats` mostra as strats mais citadas nas descrições dos PF de cada duty (acumuladas em SQLite ao longo do tempo).
- **Moderação** — `ban`, `kick`, `mute` (timeout do Discord: bloqueia voz **e** chat, com duração e expiração automática), `unmute` e `clear`, tudo em embed, com checagem de hierarquia de cargos e motivo no audit log.
- **Ajuda embutida** — `/help` lista os comandos por categoria num embed fofo; `/help <comando>` mostra uso e atalhos.
- **Comandos por prefixo e por slash (`/`)** — o mesmo comando funciona dos dois jeitos.
- **Auto-load de comandos** — basta criar uma classe que implementa `ICommand` no pacote `org.chibot.Commands`; ela é descoberta e registrada sozinha por reflection, sem precisar editar nada.
- **Console fofo** — banner com degradê pastel e logs coloridos em truecolor (veja [`KawaiiLayout`](src/main/java/org/chibot/Logging/KawaiiLayout.java)).
- **Configuração simples** — um único `.env` que é criado automaticamente na primeira execução.
- **Pronto pra Docker** — build multi-stage e `docker-compose` com bot + Lavalink, tudo na rede interna.

## 📦 Requisitos

- **Java 17+**
- Um **bot do Discord** com seu token ([Discord Developer Portal](https://discord.com/developers/applications))
- Intent **MESSAGE CONTENT** habilitado no portal (necessário para os comandos por prefixo)
- Para **música**: um servidor **Lavalink v4** acessível (o `docker-compose.yml` já sobe um)
- (Opcional) **Docker** + **Docker Compose** para deploy — recomendado, porque já amarra tudo

## 🔧 Configuração

Na primeira execução, o ChiBot cria um `.env` padrão no diretório de trabalho e encerra pedindo o token (ou copie o [`.env.example`](.env.example)). Preencha:

```dotenv
DISCORD_TOKEN=SEU_TOKEN_AQUI
PREFIX=!
GUILD_ID=
LAVALINK_URI=ws://localhost:2333
LAVALINK_PASSWORD=youshallnotpass
YOUTUBE_API_KEY=
YOUTUBE_REFRESH_TOKEN=
```

As mesmas chaves também podem vir de variáveis de ambiente do processo, que **têm prioridade** sobre o `.env` (útil no Docker).

| Variável                | Descrição                                                                                                  |
|-------------------------|------------------------------------------------------------------------------------------------------------|
| `DISCORD_TOKEN`         | Token do bot (obrigatório).                                                                                |
| `PREFIX`                | Prefixo dos comandos de texto. Padrão: `!`.                                                                 |
| `GUILD_ID`              | Se preenchido, os slash commands são registrados **só nesse servidor** e aparecem na hora (ótimo pra dev). Vazio = registro **global** (pode levar até ~1h pra propagar). |
| `LAVALINK_URI`          | Endereço do servidor Lavalink. Local: `ws://localhost:2333`; no compose: `ws://lavalink:2333`.              |
| `LAVALINK_PASSWORD`     | Senha do Lavalink — precisa bater com a do [`lavalink/application.yml`](lavalink/application.yml).          |
| `YOUTUBE_API_KEY`       | (Opcional) Chave da YouTube Data API v3 — melhora a busca por nome. Vazio = busca pelo `ytsearch` do Lavalink. |
| `YOUTUBE_REFRESH_TOKEN` | (Opcional) Login do YouTube via OAuth — necessário em IP de datacenter (veja [Login do YouTube](#-login-do-youtube-oauth)). |

> ⚠️ **Nunca compartilhe nem commite seu token.** Ele dá controle total sobre o bot. Veja [Segurança](#-segurança).

### Variáveis de ambiente (opcionais)

| Variável                | Para quê serve                                                                       | Padrão                |
|--------------------------|---------------------------------------------------------------------------------------|-----------------------|
| `CHIBOT_DB_PATH`         | Caminho do banco SQLite (Party Finder + harém).                                       | `ChiData.db`          |

Qualquer chave do `.env` (`DISCORD_TOKEN`, `LAVALINK_URI`, `YOUTUBE_REFRESH_TOKEN`...) também pode ser passada como variável de ambiente do processo, que tem prioridade sobre o arquivo.

No Docker, o `Dockerfile` e o `docker-compose.yml` já configuram tudo isso.

## 🚀 Rodando localmente

```bash
# Linux/macOS
./gradlew run

# Windows (PowerShell)
.\gradlew.bat run
```

Para gerar uma distribuição executável (scripts + libs em `build/install/ChiBot`):

```bash
./gradlew installDist
./build/install/ChiBot/bin/ChiBot      # .bat no Windows
```

Rodar os testes:

```bash
./gradlew test
```

> 🎵 Sem um Lavalink rodando, o bot sobe normalmente — só os comandos de música não funcionam. Pra ter música local, suba só o Lavalink do compose (`docker compose up -d lavalink`) e use `ws://localhost:2333` no config (é preciso expor a porta 2333 no compose pra isso).

## 🐳 Rodando com Docker

O `docker-compose.yml` sobe **três serviços** na rede interna (nenhuma porta exposta pra fora):

| Serviço           | O que faz                                                                                     |
|-------------------|-----------------------------------------------------------------------------------------------|
| `chibot`          | O bot em si (build multi-stage: compila com JDK, roda só com JRE, usuário sem privilégios). |
| `lavalink`        | Servidor de áudio (Lavalink v4 + plugin do YouTube). É quem de fato toca a música.            |
| `yt-cipher`       | Resolve os desafios de assinatura do player do YouTube pro plugin — sem ele o playback quebra quando o YouTube troca o `base.js`. |

```bash
# Edite o .env com seu token antes de subir
# (e use LAVALINK_URI=ws://lavalink:2333)
docker compose up -d --build

# Acompanhar os logs (com o console fofo~)
docker compose logs -f chibot
```

O que é montado do host (sobrevive a restart, rebuild e `down -v`):

- **`.env`** → `/app/.env` — trocar token/prefixo/servidor é só editar e `docker compose restart chibot`, sem rebuildar. O mesmo `.env` alimenta o `chibot` (montado como volume) e o `lavalink` (substituição de variáveis do compose).
- **`lavalink/application.yml`** → config do Lavalink.

E o volume nomeado `chibot-data` (`/app/data`) guarda o banco do Party Finder (`ChiData.db`) entre recriações do container.

Quem resolve o YouTube é o plugin do Lavalink. Em IP de datacenter o YouTube pode exigir login ("This video requires login") — veja a seção abaixo.

## 🔑 Login do YouTube (OAuth)

Em IP residencial nada disso é necessário. Em VPS (IP de datacenter) o YouTube exige login em **duas etapas diferentes**, e cada uma tem seu lado:

- **Play (streamar o áudio)** — gerenciado **pelo bot** (modo [client-provided token](https://github.com/lavalink-devs/youtube-source#using-oauth-tokens) do youtube-source): o `YtOauth` troca o refresh token por access tokens e anexa `{"oauth-token": ...}` no `userData` de cada track do YouTube.
- **Load (busca/resolução de link)** — roda no **node**, antes de existir track, então o token por track não alcança: o plugin precisa do login dele. Pra não editar o `application.yml` versionado (conflito a cada `git pull`), o login entra por variável de ambiente.

Passo a passo (uma vez só):

1. Suba tudo sem token. O log do bot (`docker logs chibot`) mostra um código pra ativar em <https://www.google.com/device> — autorize com uma **conta Google descartável** (há risco de bloqueio).
2. O bot salva o `YOUTUBE_REFRESH_TOKEN` sozinho no `.env` (se não conseguir escrever no arquivo, imprime o token no log pra você colar manualmente).
3. Habilite o OAuth no **node** acrescentando uma linha no mesmo `.env`:

   ```dotenv
   YOUTUBE_OAUTH_ENABLED=true
   ```

4. `docker compose up -d` — recria o `lavalink` logado e reinicia o bot.

Como o `.env` é único, o token vale pelos dois lados: o bot lê do arquivo montado e o compose injeta o mesmo `YOUTUBE_REFRESH_TOKEN` no `lavalink` — então com o token salvo nada mais pede login, nunca.

## 🧩 Criando um novo comando

1. Crie uma classe em `src/main/java/org/chibot/Commands/` que implemente [`ICommand`](src/main/java/org/chibot/Commands/ICommand.java).
2. Implemente pelo menos `getName()` e `execute(CommandContext ctx)`.
3. Pronto — o [`CommandManager`](src/main/java/org/chibot/Commands/CommandManager.java) descobre e registra a classe automaticamente na inicialização.

Exemplo mínimo:

```java
package org.chibot.Commands;

public class OiCommand implements ICommand {

    @Override
    public String getName() {
        return "oi";
    }

    @Override
    public String getDescription() {
        return "Diz oi de um jeitinho fofo~ ♡";
    }

    @Override
    public void execute(CommandContext ctx) {
        ctx.reply("Oiee~ (≧◡≦) ♡");
    }
}
```

Esse comando passa a responder tanto a `!oi` quanto a `/oi`.

A interface `ICommand` ainda oferece, via métodos `default`, recursos opcionais:

| Método                     | Para quê serve                                                        |
|----------------------------|-----------------------------------------------------------------------|
| `getAliases()`             | Nomes alternativos (só no prefixo).                                   |
| `getDescription()`         | Texto curto (usado na descrição do slash command).                   |
| `getUsage()` / `getCategory()` | Metadados exibidos pelo comando `help`.                         |
| `isGuildOnly()`            | Bloqueia o uso em DM.                                                  |
| `getRequiredPermissions()` | Exige permissões do autor (ex.: `Permission.BAN_MEMBERS`).            |
| `getOptions()`             | Parâmetros do slash command.                                          |
| `isSlashEnabled()`         | `false` para registrar **só** como comando de prefixo.                |

O contexto ([`CommandContext`](src/main/java/org/chibot/Commands/CommandContext.java)) abstrai a origem (prefixo ou slash), então o mesmo `execute` funciona para os dois.

## 📖 Comandos incluídos

### ★ Geral

| Comando     | Aliases                  | Descrição                                                                 |
|-------------|--------------------------|----------------------------------------------------------------------------|
| `help`      | `ajuda`, `comandos`      | Lista os comandos por categoria; `help <comando>` mostra os detalhes.       |

### ★ Utilidades

| Comando     | Aliases                  | Descrição                                                                 |
|-------------|--------------------------|----------------------------------------------------------------------------|
| `ping`      | —                        | Mostra a latência de gateway e API num embed fofo~                          |

### ★ Música

| Comando     | Aliases                  | Descrição                                                                 |
|-------------|--------------------------|----------------------------------------------------------------------------|
| `play`      | `p`, `tocar`             | Toca um link (YouTube, SoundCloud...) ou busca no YouTube.                  |
| `pause`     | `pausar`                 | Pausa a música atual.                                                       |
| `resume`    | `continuar`, `unpause`   | Continua de onde parou.                                                     |
| `skip`      | `pular`, `s`             | Pula pra próxima da fila.                                                   |
| `stop`      | `parar`, `leave`, `sair` | Para tudo, limpa a fila e sai do canal de voz.                              |
| `playlist`  | `queue`, `fila`, `q`     | Mostra o que tá tocando e a fila; `playlist add` enfileira uma playlist inteira do YouTube (até 100 músicas). |

### ★ FFXIV

| Comando     | Aliases                  | Descrição                                                                 |
|-------------|--------------------------|----------------------------------------------------------------------------|
| `pf`        | `partyfinder`            | Lista os PF de Ultimates/Savage do Aether (filtro por duty: `ucob`, `uwu`, `tea`, `dsr`, `top`, `fru`, `umad`...). |
| `strats`    | `strat`, `strategies`    | Ranking das strats mais citadas nos PF de uma duty (ex.: `/strats fru`).    |

### ★ Harém

| Comando     | Aliases                  | Descrição                                                                 |
|-------------|--------------------------|----------------------------------------------------------------------------|
| `waifu`     | `w`, `wa`                | Rola uma waifu aleatória — clica no 💗 em até 45s pra casar (10 rolls/hora). |
| `husbando`  | `h`, `ha`                | Rola um husbando aleatório.                                                 |
| `roll`      | `m`, `mx`                | Rola qualquer personagem (waifu ou husbando).                               |
| `harem`     | `mm`, `meuharem`         | Mostra seu harém (ou o de outra pessoa: `harem @user`), ordenado por valor.  |
| `divorce`   | `divorciar`              | Se divorcia de um personagem (devolve metade do valor em kakera).           |
| `trade`     | `trocar`                 | Propõe troca: `trade @user <seu personagem> por <o dele>` — a outra pessoa aceita/recusa por botão (expira em 2 min). |
| `daily`     | `diario`, `dk`           | Coleta kakera diário (a cada 20h, com bônus da torre).                      |
| `buyrolls`  | `comprarrolls`, `br`     | Compra rolls extras com kakera (30 💎 cada; não expiram).                    |
| `tower`     | `torre`                  | Torre de kakera (6 níveis): cada nível dá +1 roll/hora, +15% de saque e +50 no daily; `tower up` pra subir. |
| `badge`     | `badges`, `bg`, `emblema`| Badges colecionáveis (estilo Mudae): conquistas por marcos, emblemas de loja e **badges de personagem** (com o rosto real puxado do AniList — desbloqueia casando com o personagem ou comprando). `badge buy <nome>` compra, `badge equip <nome>` exibe até 6 no perfil. |
| `profile`   | `perfil`                 | Perfil do harém (seu ou de alguém): stats, rank do servidor, torre, badges e favorito; personaliza com `profile cor <hex>`, `profile bio <texto>` e `profile fav <personagem>`. |
| `wish`      | `desejo`, `wishlist`     | Lista de desejos (até 5): te menciona quando o personagem aparecer num roll. |
| `timers`    | `tu`, `tempos`           | Rolls restantes, casamento, daily, kakera, torre e desejos.                 |

> Personagens já casados aparecem com borda laranja e um botão 💎 — o primeiro a clicar coleta kakera. O claim fica disponível a cada 3 horas; os rolls resetam a cada hora cheia.

### ★ Moderação

| Comando     | Aliases                          | Descrição                                                                 |
|-------------|----------------------------------|----------------------------------------------------------------------------|
| `clear`     | `limpar`, `purge`                | Apaga até 100 mensagens do canal (exige Gerenciar Mensagens).               |
| `ban`       | `banir`                          | Bane um usuário, com motivo opcional — funciona até por ID de quem nem está no servidor (exige Banir Membros). |
| `kick`      | `expulsar`                       | Expulsa um usuário, com motivo opcional (exige Expulsar Membros).           |
| `mute`      | `mutar`, `silenciar`, `castigo`  | Timeout do Discord: sem falar na voz nem no chat. Duração tipo `30s`, `10m`, `2h`, `7d` (padrão 10m, máx. 28d), expira sozinha (exige Castigar Membros). |
| `unmute`    | `desmutar`                       | Tira o mute antes da hora (exige Castigar Membros).                         |

## 📂 Estrutura do projeto

```
src/main/java/org/chibot/
├── ChiMain.java               # ponto de entrada: banner, UTF-8, carrega config e inicia o bot
├── ChiBot.java                # monta a JDA, registra listeners e slash commands
├── Commands/
│   ├── ICommand.java           # contrato de um comando
│   ├── CommandManager.java     # auto-load por reflection + build dos slash commands
│   ├── CommandListener.java    # roteia mensagens/interações e checa permissões
│   ├── CommandContext.java     # abstração prefixo vs. slash
│   ├── PrefixCommandContext.java
│   ├── SlashCommandContext.java
│   ├── PingCommand.java
│   ├── Core/                   # help (lista por categoria) e clear (faxina do canal)
│   ├── Admin/                  # ban, kick, mute (timeout), unmute
│   │   └── ModUtils.java       # alvo/motivo/hierarquia + embed compartilhados
│   ├── Harem/                  # waifu, husbando, roll, harem, divorce, trade, daily, buyrolls, tower, badge, profile, wish, timers
│   ├── Music/                  # play, pause, resume, skip, stop, playlist (+ add)
│   │   └── MusicCommand.java   # base: guild-only + atalhos de voz
│   └── PartyFinderCommands/
│       ├── PartyFinderCommand.java  # /pf — embeds por duty com emojis de job
│       ├── PartyFinderService.java  # scraping do xivpf.com (cache 5 min + fallback)
│       ├── PfListing.java
│       ├── StratsCommand.java       # /strats — ranking de strats por duty
│       ├── StratsService.java
│       ├── StratsTokenizer.java     # tokeniza descrições dos PF (filtra ruído)
│       ├── XivpfApiClient.java
│       └── XivpfWorlds.java
├── Config/
│   └── ChiConfig.java          # leitura/criação do .env
├── Database/
│   ├── PfRepository.java       # SQLite: snapshot dos PF + contagem de strats
│   └── HaremRepository.java    # SQLite: casamentos, kakera/cooldowns e desejos
├── Harem/
│   ├── HaremService.java       # singleton: pools de personagens + botões de claim/kakera + conquistas
│   ├── HaremBadges.java        # catálogo dos badges (conquistas + loja)
│   ├── HaremEmojis.java        # application emojis do harém (kakera, torre, badges)
│   ├── AniListClient.java      # API GraphQL do AniList (personagens populares)
│   └── AnimeCharacter.java
├── Logging/
│   └── KawaiiLayout.java       # layout de log pastel em truecolor
└── Music/
    ├── MusicService.java       # singleton: conexão com o Lavalink + manager por guild
    ├── GuildMusicManager.java  # player + fila de um servidor
    ├── AudioLoader.java        # callbacks de carregamento (tocando/enfileirado/erro)
    ├── MusicUi.java            # embeds fofos da música
    ├── YtOauth.java            # login do YouTube: refresh token -> access token por track
    └── YtSearch.java           # busca por nome via YouTube Data API (opcional)
src/main/resources/
├── banner.txt                  # arte ASCII do boot
└── logback.xml                 # configuração de logging
src/test/java/                  # testes (JUnit 5): tokenizer, parser do xivpf, repositório
lavalink/application.yml        # config do servidor Lavalink (plugin do YouTube, fontes)
```

## 🔐 Segurança

O **`.env`** guarda credenciais em texto puro (token do bot, chave da API do YouTube) e **não deve ir pro versionamento** (já está no `.gitignore`). Se o token for exposto (commitado, compartilhado...), **regenere-o imediatamente** no Discord Developer Portal — revogar é a única forma segura de invalidar o antigo.

## 🧰 Stack

- [JDA 6.4.2](https://github.com/discord-jda/JDA) — Java Discord API
- [lavalink-client 3.4.0](https://github.com/lavalink-devs/lavalink-client) — cliente do servidor [Lavalink v4](https://github.com/lavalink-devs/Lavalink) (+ [youtube-plugin](https://github.com/lavalink-devs/youtube-source))
- [jsoup 1.18.3](https://jsoup.org/) — scraping do xivpf.com
- [sqlite-jdbc 3.46](https://github.com/xerial/sqlite-jdbc) — persistência do Party Finder
- [logback-classic 1.5.18](https://logback.qos.ch/) — logging
- [org.json](https://github.com/stleary/JSON-java) — parsing das respostas JSON das APIs (OAuth do YouTube, AniList, xivpf)
- JUnit 5 — testes
- Gradle (wrapper incluído) + plugin `application`

## 📜 Licença

Distribuído sob a licença **MIT** — veja o arquivo [`LICENSE`](LICENSE) para os detalhes. Em resumo: pode usar, copiar, modificar e distribuir à vontade, desde que mantenha o aviso de copyright. ♡

---

<div align="center">
</div>
