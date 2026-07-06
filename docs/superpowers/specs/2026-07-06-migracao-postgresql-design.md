# Migração de SQLite para PostgreSQL

**Data:** 2026-07-06
**Status:** Aprovado

## Objetivo

Trocar a persistência do ChiBot de quatro bancos SQLite embutidos (`ChiData.db`,
`ChiMusic.db`, `ChiState.db`, `ChiLang.db`) por um único banco PostgreSQL rodando
como serviço no `docker-compose`, sem perder nenhum dado de produção e sem mudar
o fluxo de deploy da VPS (`git pull` + `docker compose up -d --build`).

## Decisões tomadas (com a usuária)

| Decisão | Escolha | Motivo |
|---|---|---|
| Banco | PostgreSQL 16 (`postgres:16-alpine`) | Padrão da indústria; abre caminho para dashboard/múltiplos processos |
| Dados existentes | Migração automática no boot | Ninguém perde harém/kakera/playlist; deploy continua igual |
| Testes | H2 em modo PostgreSQL | A máquina de desenvolvimento (Windows) não tem Docker |
| Consolidação | Os 4 bancos viram 1 banco, todas as tabelas juntas | A separação em arquivos era por lock do SQLite; PostgreSQL resolve concorrência nativamente |

## Contexto: por que os 4 SQLite existiam

O `ChiMusic.db` era separado para as gravações do harém/Party Finder não
disputarem lock de arquivo com o áudio (lock de escritor único do SQLite).
No PostgreSQL esse problema não existe (MVCC, escritores concorrentes), então a
consolidação é segura. Os nomes de tabela não colidem: `harem_*`, `music_*`,
`pf_listing`, `user_language`, `bot_state`.

## Arquitetura

### 1. Compose

Novo serviço `postgres`:

- Imagem `postgres:16-alpine`, volume nomeado `chibot-pgdata` em `/var/lib/postgresql/data`.
- **Sem `ports`** — só a rede interna do compose alcança (mesmo modelo do yt-cipher).
- Credenciais via `.env`: `POSTGRES_DB=chibot`, `POSTGRES_USER=chibot`,
  `POSTGRES_PASSWORD=` (obrigatória, sem default fraco).
- Healthcheck: `pg_isready -U chibot -d chibot`.
- RAM contida: `command: postgres -c shared_buffers=32MB -c max_connections=20`
  e `mem_limit: 256m`. Orçamento da VPS de 2 GB: 512 M bot + 768 M Lavalink +
  ~256 M PostgreSQL + yt-cipher + SO, com swap de 1–2 G absorvendo picos.
- O serviço `chibot` ganha `depends_on: postgres: condition: service_healthy`
  (além do Lavalink que já existe).
- O volume `chibot-data` (onde moram os `.db` antigos) **continua montado** no
  bot — é de lá que a migração lê os dados.

### 2. Código do bot

**Dependências** (`build.gradle`):

- `org.postgresql:postgresql` (driver)
- `com.zaxxer:HikariCP` (pool de conexões)
- `org.xerial:sqlite-jdbc` permanece, usado apenas pela migração de dados
- `com.h2database:h2` em `testImplementation`

**Config** (`ChiConfig` / `.env`): novas chaves `DATABASE_URL`
(ex.: `jdbc:postgresql://postgres:5432/chibot`), `DATABASE_USER`,
`DATABASE_PASSWORD`. Vazias → repositórios indisponíveis e comandos degradam
com aviso, exatamente como hoje quando o banco falha (padrão `available()`).

**Pool**: um único `HikariDataSource` criado no boot (máx. 4 conexões,
`connectionTimeout` curto) e compartilhado pelos 5 repositórios. O pool valida
e recicla conexões — resolve o ponto fraco do modelo atual (uma `Connection`
eterna que, com banco de rede, morre em silêncio e derruba todas as queries).

**Repositórios** (`HaremRepository`, `MusicRepository`, `PfRepository`,
`LanguageRepository`, `MaintenanceRepository`):

- Construtor passa a receber `DataSource` (o construtor com URL JDBC vira um
  helper que embrulha a URL num DataSource simples, mantendo os testes diretos).
- Cada método pega conexão do pool em try-with-resources em vez de usar o campo
  `conn`. O `synchronized` deixa de ser necessário para proteger a conexão;
  permanece somente onde protege invariantes de lógica (ex.: checar-e-inserir).
- `createSchema()` continua rodando no boot (idempotente, `CREATE TABLE IF NOT EXISTS`).

**Dialeto SQL** (SQLite → PostgreSQL):

| SQLite | PostgreSQL |
|---|---|
| `INSERT OR IGNORE` | `INSERT ... ON CONFLICT DO NOTHING` |
| `INSERT OR REPLACE` | `INSERT ... ON CONFLICT (...) DO UPDATE SET ...` |
| `INTEGER PRIMARY KEY AUTOINCREMENT` | `BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY` |
| `PRAGMA journal_mode/busy_timeout/...` | removidos (sem equivalente/necessidade) |
| `TEXT`, `INTEGER` | mantidos (`TEXT`, `BIGINT`/`INTEGER` conforme o caso) |

Timestamps hoje gravados como epoch millis em `INTEGER` continuam como
`BIGINT` epoch millis — sem conversão de dados, sem mudança de semântica.

### 3. Migração automática dos dados

Classe nova `org.chibot.Database.SqliteToPostgresMigration`, executada no boot
depois do `createSchema()`:

1. Verifica a tabela-marcador `data_migration` no PostgreSQL
   (`name TEXT PRIMARY KEY, done_at BIGINT`). Se a linha `sqlite-import`
   existe → não faz nada (custo de um SELECT por boot).
2. Se não existe, procura os arquivos SQLite nos caminhos atuais (mesmas
   variáveis `CHIBOT_DB_PATH`/`CHIBOT_MUSIC_DB_PATH`/etc. e defaults de hoje).
   Nenhum arquivo encontrado → grava o marcador e segue (instalação nova).
3. Para cada arquivo presente: abre com `sqlite-jdbc` (somente leitura) e copia
   tabela por tabela com `INSERT ... ON CONFLICT DO NOTHING`, em **uma
   transação por arquivo** no PostgreSQL.
4. Ao final de tudo, grava o marcador `sqlite-import` na mesma transação da
   última cópia.
5. Os arquivos `.db` **não são apagados nem renomeados** — ficam no volume como
   backup natural. O marcador impede reimportação.

Falha no meio → transação desfaz, marcador não é gravado, bot loga o erro e
sobe com repositórios indisponíveis (degradação, não crash). Próximo boot
tenta de novo.

### 4. Testes

- Os 5 testes de repositório trocam `jdbc:sqlite::memory:` por H2 em memória
  com `MODE=PostgreSQL` (ex.: `jdbc:h2:mem:<nome>;MODE=PostgreSQL;DB_CLOSE_DELAY=-1`).
- Limitação aceita: H2 imita o dialeto mas não é o binário do PostgreSQL;
  diferenças sutis só aparecem na VPS. Mitigação: SQL escrito no subconjunto
  comum (o `ON CONFLICT` é suportado pelo H2 em modo PostgreSQL).
- `SqliteToPostgresMigration` ganha teste próprio: origem SQLite em memória →
  destino H2, verifica cópia e idempotência do marcador.

### 5. Documentação e config

- `.env.example`: novas variáveis `POSTGRES_*` e `DATABASE_*` comentadas.
- `README.md`: badge SQLite → PostgreSQL, tabela de serviços do compose,
  seção de variáveis, nota sobre a migração automática.
- `.gitignore`: entradas dos `.db` permanecem (arquivos legados ainda existem).

## O que NÃO muda

- Fluxo de deploy (`git pull` + `up --build`).
- Padrão `available()`/degradação quando o banco está fora.
- Nomes de tabelas e colunas, semântica dos dados.
- Lavalink, yt-cipher e todo o resto do compose.

## Critérios de sucesso

1. `gradlew build` verde na máquina de desenvolvimento (sem Docker).
2. Na VPS: primeira subida migra os dados (log explícito de quantas linhas por
   tabela) e o bot responde comandos de harém/música com os dados antigos.
3. Reinício seguinte não migra de novo (marcador).
4. `docker compose ps`: nenhuma porta nova exposta no host.
5. Instalação do zero (sem `.db` antigos) sobe limpa e funcional.

## Rollback

O commit é revertível: os `.db` originais ficam intactos no volume. Voltar a
imagem anterior do bot (git revert + rebuild) restaura o comportamento SQLite
no estado em que estava no momento da migração (mudanças feitas depois, no
PostgreSQL, não retroagem).
