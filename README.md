# ChiBot ♡

<p align="center">
  <img src="Imagem/chibot-banner.png" alt="ChiBot" width="640">
</p>

> Um bot de Discord fofo e enxuto, feito em Java com a [JDA](https://github.com/discord-jda/JDA), com console kawaii e carregamento automático de comandos~ (｡•ᴗ•｡)♡

---

## ✨ Features

- **Comandos por prefixo e por slash (`/`)** — o mesmo comando funciona dos dois jeitos.
- **Auto-load de comandos** — basta criar uma classe que implementa `ICommand` no pacote `org.chibot.Commands`; ela é descoberta e registrada sozinha por reflection, sem precisar editar nada.
- **Console kawaii** — banner com degradê pastel e logs coloridos em truecolor (veja [`KawaiiLayout`](src/main/java/org/chibot/Logging/KawaiiLayout.java)).
- **Configuração simples** — um único `ChiConfig.json` que é criado automaticamente na primeira execução.
- **Pronto pra Docker** — build multi-stage e `docker-compose` com o config montado como volume.

## 📦 Requisitos

- **Java 17+**
- Um **bot do Discord** com seu token ([Discord Developer Portal](https://discord.com/developers/applications))
- Intent **MESSAGE CONTENT** habilitado no portal (necessário para os comandos por prefixo)
- (Opcional) **Docker** + **Docker Compose** para deploy

## ⚙️ Configuração

Na primeira execução, o ChiBot cria um `ChiConfig.json` padrão no diretório de trabalho e encerra pedindo o token. Preencha:

```json
{
    "Token": "SEU_TOKEN_AQUI",
    "Prefix": "!",
    "GuildId": ""
}
```

| Campo     | Descrição                                                                                                  |
|-----------|------------------------------------------------------------------------------------------------------------|
| `Token`   | Token do bot (obrigatório).                                                                                |
| `Prefix`  | Prefixo dos comandos de texto. Padrão: `!`.                                                                 |
| `GuildId` | Se preenchido, os slash commands são registrados **só nesse servidor** e aparecem na hora (ótimo pra dev). Vazio = registro **global** (pode levar até ~1h pra propagar). |

> ⚠️ **Nunca compartilhe nem commite seu token.** Ele dá controle total sobre o bot. Veja [Segurança](#-segurança).

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

## 🐳 Rodando com Docker

O `Dockerfile` usa build multi-stage (compila com o JDK, roda só com o JRE) e o container roda como usuário sem privilégios.

```bash
# Edite o ChiConfig.json com seu token antes de subir
docker compose up -d --build

# Acompanhar os logs (com o console fofo~)
docker compose logs -f
```

O `ChiConfig.json` do host é montado como volume em `/app/ChiConfig.json` — para trocar token/prefixo/servidor, basta editar o arquivo e reiniciar o container, **sem rebuildar**:

```bash
docker compose restart
```

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

| Comando | Categoria   | Descrição                                       |
|---------|-------------|-------------------------------------------------|
| `ping`  | Utilidades  | Mostra a latência de gateway e API num embed fofo~ |

## 🗂️ Estrutura do projeto

```
src/main/java/org/chibot/
├── ChiMain.java              # ponto de entrada: banner, UTF-8, carrega config e inicia o bot
├── ChiBot.java               # monta a JDA, registra listeners e slash commands
├── Commands/
│   ├── ICommand.java          # contrato de um comando
│   ├── CommandManager.java    # auto-load por reflection + build dos slash commands
│   ├── CommandListener.java   # roteia mensagens/interações e checa permissões
│   ├── CommandContext.java    # abstração prefixo vs. slash
│   ├── PrefixCommandContext.java
│   ├── SlashCommandContext.java
│   └── PingCommand.java
├── Config/
│   └── ChiConfig.java         # leitura/criação do ChiConfig.json
└── Logging/
    └── KawaiiLayout.java      # layout de log pastel em truecolor
src/main/resources/
├── banner.txt                 # arte ASCII do boot
└── logback.xml                # configuração de logging
```

## 🔐 Segurança

O `ChiConfig.json` guarda seu token em texto puro — **ele não deve ir pro versionamento**. Adicione ao `.gitignore`:

```gitignore
ChiConfig.json
.gradle/
build/
```

Se um token já tiver sido exposto (commitado, compartilhado, etc.), **regenere-o imediatamente** no Discord Developer Portal — revogar é a única forma segura de invalidar o antigo.

## 🛠️ Stack

- [JDA 5.3.0](https://github.com/discord-jda/JDA) — Java Discord API
- [logback-classic 1.5.18](https://logback.qos.ch/) — logging
- [org.json](https://github.com/stleary/JSON-java) — leitura do config
- Gradle (wrapper incluído) + plugin `application`

---

