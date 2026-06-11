# ChiBot ♡

> Um bot de Discord fofo e enxuto, feito em Java com a [JDA](https://github.com/discord-jda/JDA): música via Lavalink, Party Finder de FFXIV, console kawaii e carregamento automático de comandos~ (｡•ᴗ•｡)♡

---

## ✨ Features

- **Música** — toca do YouTube (link ou busca), SoundCloud, Bandcamp, Twitch e streams HTTP. O áudio roda num servidor [Lavalink](https://github.com/lavalink-devs/Lavalink), que resolve e toca tudo; a busca por nome pode usar a **YouTube Data API** (chave opcional no config).
- **Party Finder de FFXIV** — `/pf` lista os PF de Ultimates e Savage do data center Aether (via [xivpf.com](https://xivpf.com)), com emojis de job e composição; `/strats` mostra as strats mais citadas nas descrições dos PF de cada duty (acumuladas em SQLite ao longo do tempo).
- **Comandos por prefixo e por slash (`/`)** — o mesmo comando funciona dos dois jeitos.
- **Auto-load de comandos** — basta criar uma classe que implementa `ICommand` no pacote `org.chibot.Commands`; ela é descoberta e registrada sozinha por reflection, sem precisar editar nada.
- **Console kawaii** — banner com degradê pastel e logs coloridos em truecolor (veja [`KawaiiLayout`](src/main/java/org/chibot/Logging/KawaiiLayout.java)).
- **Configuração simples** — um único `ChiConfig.json` que é criado automaticamente na primeira execução.
- **Pronto pra Docker** — build multi-stage e `docker-compose` com bot + Lavalink, tudo na rede interna.

## 📦 Requisitos

- **Java 17+**
- Um **bot do Discord** com seu token ([Discord Developer Portal](https://discord.com/developers/applications))
- Intent **MESSAGE CONTENT** habilitado no portal (necessário para os comandos por prefixo)
- Para **música**: um servidor **Lavalink v4** acessível (o `docker-compose.yml` já sobe um)
- (Opcional) **Docker** + **Docker Compose** para deploy — recomendado, porque já amarra tudo

## ⚙️ Configuração

Na primeira execução, o ChiBot cria um `ChiConfig.json` padrão no diretório de trabalho e encerra pedindo o token. Preencha:

```json
{
    "Token": "SEU_TOKEN_AQUI",
    "Prefix": "!",
    "GuildId": "",
    "LavalinkUri": "ws://localhost:2333",
    "LavalinkPassword": "youshallnotpass",
    "YoutubeApiKey": "",
    "YoutubeRefreshToken": ""
}
```

| Campo                 | Descrição                                                                                                  |
|-----------------------|------------------------------------------------------------------------------------------------------------|
| `Token`               | Token do bot (obrigatório).                                                                                |
| `Prefix`              | Prefixo dos comandos de texto. Padrão: `!`.                                                                 |
| `GuildId`             | Se preenchido, os slash commands são registrados **só nesse servidor** e aparecem na hora (ótimo pra dev). Vazio = registro **global** (pode levar até ~1h pra propagar). |
| `LavalinkUri`         | Endereço do servidor Lavalink. Local: `ws://localhost:2333`; no compose: `ws://lavalink:2333`.              |
| `LavalinkPassword`    | Senha do Lavalink — precisa bater com a do [`lavalink/application.yml`](lavalink/application.yml).          |
| `YoutubeApiKey`       | (Opcional) Chave da YouTube Data API v3 — melhora a busca por nome. Vazio = busca pelo `ytsearch` do Lavalink. |
| `YoutubeRefreshToken` | (Opcional) Login do YouTube via OAuth — necessário em IP de datacenter (veja [Login do YouTube](#-login-do-youtube-oauth)). |

> ⚠️ **Nunca compartilhe nem commite seu token.** Ele dá controle total sobre o bot. Veja [Segurança](#-segurança).

### Variáveis de ambiente (opcionais)

| Variável             | Para quê serve                                                                       | Padrão                |
|----------------------|---------------------------------------------------------------------------------------|-----------------------|
| `CHIBOT_DB_PATH`     | Caminho do banco SQLite do Party Finder.                                              | `ChiData.db`          |

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
# Edite o ChiConfig.json com seu token antes de subir
# (e use "LavalinkUri": "ws://lavalink:2333")
docker compose up -d --build

# Acompanhar os logs (com o console fofo~)
docker compose logs -f chibot
```

O que é montado do host (sobrevive a restart, rebuild e `down -v`):

- **`ChiConfig.json`** → `/app/ChiConfig.json` — trocar token/prefixo/servidor é só editar e `docker compose restart chibot`, sem rebuildar.
- **`lavalink/application.yml`** → config do Lavalink.

E o volume nomeado `chibot-data` (`/app/data`) guarda o banco do Party Finder (`ChiData.db`) entre recriações do container.

Quem resolve o YouTube é o plugin do Lavalink. Em IP de datacenter o YouTube pode exigir login ("This video requires login") — veja a seção abaixo.

## 🔑 Login do YouTube (OAuth)

Em IP residencial nada disso é necessário. Em VPS (IP de datacenter) o YouTube exige login em **duas etapas diferentes**, e cada uma tem seu lado:

- **Play (streamar o áudio)** — gerenciado **pelo bot** (modo [client-provided token](https://github.com/lavalink-devs/youtube-source#using-oauth-tokens) do youtube-source): o `YtOauth` troca o refresh token por access tokens e anexa `{"oauth-token": ...}` no `userData` de cada track do YouTube.
- **Load (busca/resolução de link)** — roda no **node**, antes de existir track, então o token por track não alcança: o plugin precisa do login dele. Pra não editar o `application.yml` versionado (conflito a cada `git pull`), o login entra por variável de ambiente.

Passo a passo (uma vez só):

1. Suba o bot com `"YoutubeRefreshToken": ""` no `ChiConfig.json`. O log (`docker logs chibot`) mostra um código pra ativar em <https://www.google.com/device> — autorize com uma **conta Google descartável** (há risco de bloqueio).
2. Depois de autorizar, o log imprime o refresh token (`1//...`). Cole no `ChiConfig.json` (`"YoutubeRefreshToken": "1//..."`).
3. Crie um arquivo `.env` ao lado do `docker-compose.yml` (já está no `.gitignore`) com **o mesmo token**:

   ```env
   YOUTUBE_OAUTH_ENABLED=true
   YOUTUBE_REFRESH_TOKEN=1//...
   ```

4. `docker compose up -d` — o compose recria o `lavalink` com o login e reinicia o bot.

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
| `getUsage()` / `getCategory()` | Metadados pra um futuro comando de ajuda.                       |
| `isGuildOnly()`            | Bloqueia o uso em DM.                                                  |
| `getRequiredPermissions()` | Exige permissões do autor (ex.: `Permission.BAN_MEMBERS`).            |
| `getOptions()`             | Parâmetros do slash command.                                          |
| `isSlashEnabled()`         | `false` para registrar **só** como comando de prefixo.                |

O contexto ([`CommandContext`](src/main/java/org/chibot/Commands/CommandContext.java)) abstrai a origem (prefixo ou slash), então o mesmo `execute` funciona para os dois.

### Comandos incluídos

| Comando     | Aliases                  | Categoria  | Descrição                                                                 |
|-------------|--------------------------|------------|----------------------------------------------------------------------------|
| `ping`      | —                        | Utilidades | Mostra a latência de gateway e API num embed fofo~                          |
| `play`      | `p`, `tocar`             | Música     | Toca um link (YouTube, SoundCloud...) ou busca no YouTube.                  |
| `pause`     | `pausar`                 | Música     | Pausa a música atual.                                                       |
| `resume`    | `continuar`, `unpause`   | Música     | Continua de onde parou.                                                     |
| `skip`      | `pular`, `s`             | Música     | Pula pra próxima da fila.                                                   |
| `stop`      | `parar`, `leave`, `sair` | Música     | Para tudo, limpa a fila e sai do canal de voz.                              |
| `playlist`  | `queue`, `fila`, `q`     | Música     | Mostra o que tá tocando e a fila.                                           |
| `pf`        | `partyfinder`            | FFXIV      | Lista os PF de Ultimates/Savage do Aether (filtro por duty: `ucob`, `uwu`, `tea`, `dsr`, `top`, `fru`, `umad`...). |
| `strats`    | `strat`, `strategies`    | FFXIV      | Ranking das strats mais citadas nos PF de uma duty (ex.: `/strats fru`).    |

## 🗂️ Estrutura do projeto

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
│   ├── Music/                  # play, pause, resume, skip, stop, playlist
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
│   └── ChiConfig.java          # leitura/criação do ChiConfig.json
├── Database/
│   └── PfRepository.java       # SQLite: snapshot dos PF + contagem de strats
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

O **`ChiConfig.json`** guarda credenciais em texto puro (token do bot, chave da API do YouTube) e **não deve ir pro versionamento** (já está no `.gitignore`). Se o token for exposto (commitado, compartilhado...), **regenere-o imediatamente** no Discord Developer Portal — revogar é a única forma segura de invalidar o antigo.

## 🛠️ Stack

- [JDA 6.4.2](https://github.com/discord-jda/JDA) — Java Discord API
- [lavalink-client 3.4.0](https://github.com/lavalink-devs/lavalink-client) — cliente do servidor [Lavalink v4](https://github.com/lavalink-devs/Lavalink) (+ [youtube-plugin](https://github.com/lavalink-devs/youtube-source))
- [jsoup 1.18.3](https://jsoup.org/) — scraping do xivpf.com
- [sqlite-jdbc 3.46](https://github.com/xerial/sqlite-jdbc) — persistência do Party Finder
- [logback-classic 1.5.18](https://logback.qos.ch/) — logging
- [org.json](https://github.com/stleary/JSON-java) — leitura do config
- JUnit 5 — testes
- Gradle (wrapper incluído) + plugin `application`

---