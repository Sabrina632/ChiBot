# Migração SQLite → PostgreSQL — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Trocar os 4 bancos SQLite do ChiBot por um único PostgreSQL no compose, com migração automática dos dados no boot, sem mudar o fluxo de deploy.

**Architecture:** Um `HikariDataSource` compartilhado (classe `Db`) alimenta os 5 repositórios, que passam a pegar conexão do pool por operação. Uma classe `SqliteToPostgresMigration` copia os dados legados dos arquivos `.db` na primeira subida (marcador impede repetição). Testes usam PostgreSQL real embarcado (zonky), sem Docker.

**Tech Stack:** Java 17, Gradle, PostgreSQL 16 (`postgres:16-alpine`), driver `org.postgresql:postgresql`, HikariCP, `io.zonky.test:embedded-postgres` (testes), sqlite-jdbc (só leitura legada).

**Spec:** `docs/superpowers/specs/2026-07-06-migracao-postgresql-design.md`

## Global Constraints

- Java 17, encoding UTF-8 (já configurado no `build.gradle`).
- Todo texto novo em português (comentários, javadoc, strings de log, commits) usa acentuação correta.
- Mensagens de commit SEM `Co-Authored-By: Claude`.
- Build/testes: `.\gradlew.bat build --console=plain` (Windows, sem Docker local).
- Nunca commitar `.env`; segredos só via `.env` da VPS.
- O padrão de degradação é sagrado: banco indisponível → loga `warn` e vira no-op/valor padrão; o bot NUNCA deixa de subir por causa de banco.
- SQL deve rodar em PostgreSQL 16. Regra de tipos: toda coluna `INTEGER` do SQLite vira `BIGINT` no PostgreSQL (o SQLite guarda 64 bits em INTEGER; epoch millis e kakera estouram o INTEGER de 32 bits do PostgreSQL). `TEXT` permanece `TEXT`.
- `ON CONFLICT ... DO UPDATE SET x = excluded.x` já é sintaxe PostgreSQL válida — NÃO mexer nessas queries. Só `INSERT OR IGNORE` (vira `ON CONFLICT DO NOTHING`) e `PRAGMA` (removidos) precisam de tradução.

---

### Task 1: Dependências + classe `Db` (pool compartilhado)

**Files:**
- Modify: `build.gradle`
- Create: `src/main/java/org/chibot/Database/Db.java`
- Create: `src/test/java/org/chibot/Database/PgTestDb.java`
- Create: `src/test/java/org/chibot/Database/DbTest.java`

**Interfaces:**
- Produces: `Db.dataSource()` → `javax.sql.DataSource` compartilhado ou `null` se não configurado; `Db.forUrl(String url, String user, String password)` → `DataSource` pooled avulso; `PgTestDb.database(String nome)` → `DataSource` de um banco PostgreSQL embarcado (cria se não existe, reusa se existe).

- [ ] **Step 1: Adicionar dependências no `build.gradle`**

No bloco `dependencies`, logo após a linha do sqlite-jdbc, adicionar:

```gradle
    // PostgreSQL: banco principal (o sqlite-jdbc acima fica só pra ler os
    // bancos legados na migração de dados)
    implementation 'org.postgresql:postgresql:42.7.5'
    implementation 'com.zaxxer:HikariCP:6.2.1'

    // PostgreSQL real embarcado nos testes (sem Docker)
    testImplementation 'io.zonky.test:embedded-postgres:2.1.0'
```

E atualizar o comentário da linha do sqlite-jdbc de `// Banco de dados local (persistencia do Party Finder)` para `// SQLite legado: usado só pela migração de dados pro PostgreSQL`.

- [ ] **Step 2: Escrever o teste de fumaça do `Db` e do helper `PgTestDb`**

`src/test/java/org/chibot/Database/PgTestDb.java`:

```java
package org.chibot.Database;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * PostgreSQL real embarcado pros testes (sem Docker): sobe UMA instância por
 * JVM (barato: os testes todos compartilham) e entrega um banco por nome —
 * cada classe de teste usa nomes próprios pra não pisar nos dados das outras.
 */
public final class PgTestDb {

    private static EmbeddedPostgres pg;

    private PgTestDb() {
    }

    private static synchronized EmbeddedPostgres instance() {
        if (pg == null) {
            try {
                pg = EmbeddedPostgres.start();
            } catch (IOException e) {
                throw new UncheckedIOException("Não subiu o PostgreSQL embarcado.", e);
            }
        }
        return pg;
    }

    /** DataSource de um banco com esse nome, criando o banco se ainda não existe. */
    public static synchronized DataSource database(String nome) {
        EmbeddedPostgres ep = instance();
        try (Connection c = ep.getPostgresDatabase().getConnection();
             Statement st = c.createStatement()) {
            st.executeUpdate("CREATE DATABASE " + nome);
        } catch (SQLException e) {
            // 42P04 = banco já existe: reuso proposital (testes de persistência
            // abrem o mesmo banco duas vezes). Qualquer outro erro é bug real.
            if (!"42P04".equals(e.getSQLState())) {
                throw new IllegalStateException("Falha criando banco de teste " + nome, e);
            }
        }
        return ep.getDatabase("postgres", nome);
    }
}
```

`src/test/java/org/chibot/Database/DbTest.java`:

```java
package org.chibot.Database;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DbTest {

    @Test
    void executaQueryNoPostgresEmbarcado() throws Exception {
        DataSource ds = PgTestDb.database("db_smoke");
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void dataSourceGlobalNuloSemConfiguracao() {
        // Sem DATABASE_URL no ambiente de teste, o pool global não existe.
        assertNull(Db.dataSource());
    }
}
```

- [ ] **Step 3: Rodar os testes e ver falharem por compilação**

Run: `.\gradlew.bat test --tests "org.chibot.Database.DbTest" --console=plain`
Expected: FAIL — `Db` não existe (erro de compilação).

- [ ] **Step 4: Implementar `Db`**

`src/main/java/org/chibot/Database/Db.java`:

```java
package org.chibot.Database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.chibot.Config.ChiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;

/**
 * Pool de conexões compartilhado com o PostgreSQL (HikariCP). Todos os
 * repositórios pegam conexão daqui por operação — o pool valida e recicla
 * conexões, então uma queda do banco não mata o bot: a operação falha, loga
 * e a próxima tenta de novo com conexão nova.
 *
 * <p>Sem {@code DATABASE_URL} configurada, {@link #dataSource()} devolve
 * {@code null} e os repositórios degradam como sempre (no-op com aviso).
 */
public final class Db {

    private static final Logger log = LoggerFactory.getLogger(Db.class);

    private static volatile HikariDataSource pool;
    private static volatile boolean initialized;

    private Db() {
    }

    /** Pool global (lazy), ou {@code null} se o banco não está configurado. */
    public static synchronized DataSource dataSource() {
        if (!initialized) {
            initialized = true;
            String url = config("DATABASE_URL");
            if (url == null || url.isBlank()) {
                log.warn("DATABASE_URL não configurada — persistência desligada.");
            } else {
                pool = build(url, config("DATABASE_USER"), config("DATABASE_PASSWORD"), "chibot-db");
                log.info("Pool do PostgreSQL pronto ({}).", url);
            }
        }
        return pool;
    }

    /** Pool avulso pra uma URL explícita (testes e ferramentas). */
    public static DataSource forUrl(String url, String user, String password) {
        return build(url, user, password, "chibot-db-avulso");
    }

    private static HikariDataSource build(String url, String user, String password, String poolName) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        if (user != null && !user.isBlank()) {
            cfg.setUsername(user);
        }
        if (password != null && !password.isBlank()) {
            cfg.setPassword(password);
        }
        cfg.setPoolName(poolName);
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5_000);
        // Não testa conexão ao criar o pool: se o banco estiver fora no boot,
        // quem falha (e loga) é a primeira operação, não a subida do bot.
        cfg.setInitializationFailTimeout(-1);
        return new HikariDataSource(cfg);
    }

    /** Config pela mesma ordem do resto do bot: env do processo > .env > nada. */
    private static String config(String key) {
        String fromProcess = System.getenv(key);
        if (fromProcess != null && !fromProcess.isBlank()) {
            return fromProcess;
        }
        ChiConfig cfg = ChiConfig.get();
        if (cfg == null) {
            return null;
        }
        return switch (key) {
            case "DATABASE_URL" -> cfg.getDatabaseUrl();
            case "DATABASE_USER" -> cfg.getDatabaseUser();
            case "DATABASE_PASSWORD" -> cfg.getDatabasePassword();
            default -> null;
        };
    }
}
```

- [ ] **Step 5: Adicionar os getters no `ChiConfig`**

Em `src/main/java/org/chibot/Config/ChiConfig.java`:

1. Campos novos após `private final String deeplApiKey;`:

```java
    private final String databaseUrl;
    private final String databaseUser;
    private final String databasePassword;
```

2. No construtor privado: adicionar os 3 parâmetros `String databaseUrl, String databaseUser, String databasePassword` ao final da lista e as atribuições `this.databaseUrl = databaseUrl;` etc.

3. Em `load()`, após a linha do `deeplApiKey`:

```java
        String databaseUrl = value(env, "DATABASE_URL", "");
        String databaseUser = value(env, "DATABASE_USER", "");
        String databasePassword = value(env, "DATABASE_PASSWORD", "");
```

e passar os 3 no `new ChiConfig(...)`.

4. Getters no final da classe:

```java
    /** URL JDBC do PostgreSQL (ex.: jdbc:postgresql://postgres:5432/chibot). Vazia = sem persistência. */
    public String getDatabaseUrl() {
        return databaseUrl;
    }

    public String getDatabaseUser() {
        return databaseUser;
    }

    public String getDatabasePassword() {
        return databasePassword;
    }
```

5. Em `createDefault()`, adicionar antes do bloco do DeepL:

```java
                "# ─── Banco de dados (PostgreSQL) ──────────────────────────",
                "# No Docker o compose preenche isto sozinho a partir de POSTGRES_*.",
                "# Local: jdbc:postgresql://localhost:5432/chibot (vazio = sem persistência).",
                "DATABASE_URL=",
                "DATABASE_USER=",
                "DATABASE_PASSWORD=",
                "",
```

- [ ] **Step 6: Rodar os testes e ver passarem**

Run: `.\gradlew.bat test --tests "org.chibot.Database.DbTest" --console=plain`
Expected: PASS (o primeiro run baixa os binários do PostgreSQL embarcado — pode demorar uns minutos).

- [ ] **Step 7: Commit**

```powershell
git add build.gradle src/main/java/org/chibot/Database/Db.java src/main/java/org/chibot/Config/ChiConfig.java src/test/java/org/chibot/Database/PgTestDb.java src/test/java/org/chibot/Database/DbTest.java
git commit -m 'PostgreSQL: pool HikariCP compartilhado (Db) + config DATABASE_* e PG embarcado nos testes'
```

---

### Task 2: Helper de teste `BrokenDataSource` (degradação)

**Files:**
- Create: `src/test/java/org/chibot/Database/BrokenDataSource.java`

**Interfaces:**
- Produces: `new BrokenDataSource()` → `DataSource` cujo `getConnection()` lança `SQLException` — substitui as URLs SQLite inválidas (`jdbc:sqlite:/caminho/invalido/??/x.db`) nos testes de degradação das Tasks 3–7.

- [ ] **Step 1: Criar a classe**

```java
package org.chibot.Database;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/** DataSource que sempre falha — simula banco indisponível nos testes de degradação. */
public final class BrokenDataSource implements DataSource {

    @Override
    public Connection getConnection() throws SQLException {
        throw new SQLException("banco indisponível (teste)");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLException("banco indisponível (teste)");
    }

    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
    }

    @Override
    public void setLoginTimeout(int seconds) {
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException("não suportado");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return false;
    }
}
```

- [ ] **Step 2: Compilar**

Run: `.\gradlew.bat compileTestJava --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```powershell
git add src/test/java/org/chibot/Database/BrokenDataSource.java
git commit -m 'Testes: BrokenDataSource pra simular banco indisponível'
```

---

### Task 3: Converter `MaintenanceRepository` (o exemplar do padrão)

Este é o repositório menor — a conversão dele define o padrão que as Tasks 4–7 repetem. O padrão completo:

1. O campo `private Connection conn;` vira `private DataSource ds;`.
2. O construtor sem argumentos vira `this(Db.dataSource())`.
3. O construtor com `String dbUrl` é REMOVIDO; entra `Repo(DataSource ds)`.
4. `defaultDbUrl()` e `ensureParentDir()` são REMOVIDOS (a resolução de caminho SQLite morre com o SQLite; a migração de dados na Task 8 tem a sua própria).
5. `available()` vira `return ds != null;`.
6. Cada método que usava `conn` passa a abrir conexão do pool: `try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(...))`.
7. `close()` vira no-op de compatibilidade (o pool é global): apenas `ds = null;`.
8. Schema: `INTEGER` → `BIGINT`; `INSERT OR IGNORE` → `INSERT ... ON CONFLICT DO NOTHING`; `PRAGMA` some. Queries `ON CONFLICT ... DO UPDATE` ficam como estão.
9. Se `createSchema()` falhar no construtor: `ds = null` + `log.warn` (degrada, como hoje).

**Files:**
- Modify: `src/main/java/org/chibot/Database/MaintenanceRepository.java`
- Modify: `src/test/java/org/chibot/Database/MaintenanceRepositoryTest.java`

**Interfaces:**
- Consumes: `Db.dataSource()` (Task 1), `PgTestDb.database(...)`, `BrokenDataSource` (Task 2).
- Produces: `MaintenanceRepository(DataSource ds)`; API pública inalterada (`isMaintenanceActive()`, `setMaintenanceActive(boolean)`, `close()`).

- [ ] **Step 1: Atualizar o teste**

Em `MaintenanceRepositoryTest.java`: o factory da linha 15 vira

```java
        return new MaintenanceRepository(PgTestDb.database("maint_basico"));
```

O teste de persistência (linhas ~40–45, duas instâncias com a mesma URL) vira duas instâncias com `PgTestDb.database("maint_persist")` (mesmo nome = mesmo banco). O teste de degradação (linha ~53) vira

```java
        MaintenanceRepository repo = new MaintenanceRepository(new BrokenDataSource());
```

Cada método de teste que criava banco próprio usa um nome de banco distinto (prefixo `maint_`), porque o PostgreSQL embarcado é compartilhado pela JVM inteira.

- [ ] **Step 2: Rodar e ver falhar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.MaintenanceRepositoryTest" --console=plain`
Expected: FAIL — construtor `MaintenanceRepository(DataSource)` não existe.

- [ ] **Step 3: Converter a classe**

Substituir imports/campo/construtores/`available`/`createSchema`/`close` por:

```java
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
```

```java
    private DataSource ds;

    public MaintenanceRepository() {
        this(Db.dataSource());
    }

    /** Construtor com DataSource explícito (testes). Null = degrada pra no-op. */
    public MaintenanceRepository(DataSource ds) {
        this.ds = ds;
        if (ds == null) {
            log.warn("Banco de estado não configurado; manutenção não vai persistir.");
            return;
        }
        try {
            createSchema();
            log.info("Banco de estado pronto.");
        } catch (SQLException e) {
            this.ds = null;
            log.warn("Não foi possível preparar o banco de estado; manutenção não vai persistir.", e);
        }
    }

    private boolean available() {
        return ds != null;
    }

    private void createSchema() throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS bot_state (
                        key   TEXT NOT NULL PRIMARY KEY,
                        value TEXT
                    )
                    """);
        }
    }
```

Os dois métodos de dados trocam `conn.prepareStatement` pelo padrão do pool — exemplo completo do leitor:

```java
    public synchronized boolean isMaintenanceActive() {
        if (!available()) {
            return false;
        }
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT value FROM bot_state WHERE key = ?")) {
            ps.setString(1, KEY_MAINTENANCE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "true".equals(rs.getString(1));
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler o estado da manutenção.", e);
            return false;
        }
    }
```

`setMaintenanceActive` segue idêntico (o SQL `ON CONFLICT(key) DO UPDATE` fica como está). `close()`:

```java
    /** O pool é global (Db); aqui só descarta a referência. Mantido por compatibilidade. */
    public synchronized void close() {
        ds = null;
    }
```

Remover também `DriverManager` dos imports e apagar `defaultDbUrl()`/`ensureParentDir()`/`DEFAULT_DB_FILE`. Atualizar o javadoc da classe: trocar "(SQLite)"/"uma única conexão SQLite" por "(PostgreSQL)"/"conexões do pool compartilhado (Db)".

- [ ] **Step 4: Rodar e ver passar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.MaintenanceRepositoryTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/org/chibot/Database/MaintenanceRepository.java src/test/java/org/chibot/Database/MaintenanceRepositoryTest.java
git commit -m 'PostgreSQL: MaintenanceRepository no pool compartilhado'
```

---

### Task 4: Converter `LanguageRepository`

**Files:**
- Modify: `src/main/java/org/chibot/Database/LanguageRepository.java`
- Modify: `src/test/java/org/chibot/Database/LanguageRepositoryTest.java`
- Modify: `src/test/java/org/chibot/Translation/TranslationServiceTest.java`

**Interfaces:**
- Consumes: `Db.dataSource()`, `PgTestDb.database(...)`, `BrokenDataSource`.
- Produces: `LanguageRepository(DataSource ds)`; API pública inalterada (`getLanguage`, `setLanguage`, `getCachedTranslation`, `putCachedTranslation`, `close`).

- [ ] **Step 1: Atualizar os testes**

`LanguageRepositoryTest.java`: factory (linha 14) → `new LanguageRepository(PgTestDb.database("lang_basico"))`; teste de persistência (linhas ~46–51) → duas instâncias com `PgTestDb.database("lang_persist")`; degradação (linha ~59) → `new LanguageRepository(new BrokenDataSource())`.

`TranslationServiceTest.java`: linha 30 → `new LanguageRepository(PgTestDb.database("transl_basico"))`; linhas 66/75 → mesmas duas instâncias com `PgTestDb.database("transl_persist")`.

- [ ] **Step 2: Rodar e ver falhar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.LanguageRepositoryTest" --tests "org.chibot.Translation.TranslationServiceTest" --console=plain`
Expected: FAIL — construtor com `DataSource` não existe.

- [ ] **Step 3: Converter a classe**

Aplicar o padrão da Task 3 (campo `ds`, construtores, `available`, `close`, remoção de `defaultDbUrl`/`ensureParentDir`/`DEFAULT_DB_FILE`/`DriverManager`). Mensagens de log mantêm o texto atual ("banco de idiomas", "tradução vira só em memória"). O schema não muda de tipos (só TEXT). Os 4 métodos de dados (`getLanguage`, `setLanguage`, `getCachedTranslation`, `putCachedTranslation`) trocam `conn.prepareStatement(...)` por `try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(...))` — mesmo formato do exemplo completo da Task 3, sem nenhuma mudança de SQL (os `ON CONFLICT ... DO UPDATE` ficam).

- [ ] **Step 4: Rodar e ver passar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.LanguageRepositoryTest" --tests "org.chibot.Translation.TranslationServiceTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/org/chibot/Database/LanguageRepository.java src/test/java/org/chibot/Database/LanguageRepositoryTest.java src/test/java/org/chibot/Translation/TranslationServiceTest.java
git commit -m 'PostgreSQL: LanguageRepository no pool compartilhado'
```

---

### Task 5: Converter `PfRepository`

**Files:**
- Modify: `src/main/java/org/chibot/Database/PfRepository.java`
- Modify: `src/test/java/org/chibot/Database/PfRepositoryTest.java`

**Interfaces:**
- Consumes: `Db.dataSource()`, `PgTestDb.database(...)`.
- Produces: `PfRepository(DataSource ds)`; API pública inalterada.

- [ ] **Step 1: Atualizar o teste**

`PfRepositoryTest.java` linha 16 → `new PfRepository(PgTestDb.database("pf_basico"))` (e nomes `pf_*` distintos se outros métodos criarem repositórios próprios).

- [ ] **Step 2: Rodar e ver falhar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.PfRepositoryTest" --console=plain`
Expected: FAIL — construtor com `DataSource` não existe.

- [ ] **Step 3: Converter a classe**

Padrão da Task 3, mais os pontos específicos deste repositório:

a) Schema (`createSchema`): `filled INTEGER` e `total INTEGER` viram `BIGINT`; `count INTEGER NOT NULL DEFAULT 0` vira `BIGINT NOT NULL DEFAULT 0`. Resto igual (índice incluído — a sintaxe `CREATE INDEX IF NOT EXISTS ... (duty, count DESC)` vale no PostgreSQL).

b) `indexTokens` (linhas ~153–193) usa transação. Novo formato — a conexão emprestada vive o método inteiro:

```java
    public synchronized void indexTokens(List<PfListing> listings, String dataCenter, Instant when) {
        if (!available()) {
            return;
        }
        String ts = when.toString();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement gate = c.prepareStatement(
                         "INSERT INTO pf_indexed_listing (id) VALUES (?) ON CONFLICT DO NOTHING");
                 PreparedStatement upsert = c.prepareStatement("""
                         INSERT INTO pf_duty_token (duty, token, count, first_seen, last_seen)
                         VALUES (?, ?, 1, ?, ?)
                         ON CONFLICT(duty, token)
                         DO UPDATE SET count = pf_duty_token.count + 1, last_seen = excluded.last_seen
                         """)) {
                // ... corpo do laço EXATAMENTE como está hoje ...
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            log.warn("Falha ao indexar tokens de strat do Party Finder.", e);
        }
    }
```

Atenção: o `INSERT OR IGNORE` virou `ON CONFLICT DO NOTHING` (o retorno `executeUpdate() == 0` continua significando "já indexado") e o `count = count + 1` do upsert ganhou o prefixo `pf_duty_token.` (no PostgreSQL o nome sem prefixo dentro de `DO UPDATE` é ambíguo com `excluded`). Os helpers `rollbackQuietly()`/`restoreAutoCommit()` que operavam no campo `conn` são apagados — a transação agora é local à conexão emprestada.

c) `replaceAll` (linhas ~233+, transação com `DELETE FROM pf_listing` + inserts): mesmo formato de transação do item (b).

d) Métodos de leitura simples (`topTokens`, `all`, e os demais que usam `conn.prepareStatement`/`conn.createStatement`): padrão do pool da Task 3.

- [ ] **Step 4: Rodar e ver passar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.PfRepositoryTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/org/chibot/Database/PfRepository.java src/test/java/org/chibot/Database/PfRepositoryTest.java
git commit -m 'PostgreSQL: PfRepository no pool compartilhado (upsert de strats com ON CONFLICT)'
```

---

### Task 6: Converter `MusicRepository`

**Files:**
- Modify: `src/main/java/org/chibot/Database/MusicRepository.java`
- Modify: `src/test/java/org/chibot/Database/MusicRepositoryTest.java`

**Interfaces:**
- Consumes: `Db.dataSource()`, `PgTestDb.database(...)`, `BrokenDataSource`.
- Produces: `MusicRepository(DataSource ds)`; API pública inalterada.

- [ ] **Step 1: Atualizar o teste**

`MusicRepositoryTest.java` linha 20 → `new MusicRepository(PgTestDb.database("music_basico"))`; linha 109 (degradação) → `new MusicRepository(new BrokenDataSource())`.

- [ ] **Step 2: Rodar e ver falhar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.MusicRepositoryTest" --console=plain`
Expected: FAIL — construtor com `DataSource` não existe.

- [ ] **Step 3: Converter a classe**

Padrão da Task 3, mais os pontos específicos:

a) **Apagar `applyPragmas()` inteiro** (linhas ~89–101) e a chamada dele no construtor — WAL/busy_timeout/synchronous são conceitos de SQLite; o PostgreSQL cuida disso sozinho. Atualizar o javadoc da classe e o comentário do `defaultDbUrl` que explicavam o "arquivo separado" (a nota histórica pode ficar em uma frase: "no SQLite a música ficava em arquivo separado por causa de lock; no PostgreSQL isso não é mais necessário").

b) Schema: `volume INTEGER NOT NULL DEFAULT 50`, `position INTEGER` (nas duas tabelas) e `created_at INTEGER` viram `BIGINT` (com o mesmo DEFAULT no volume).

c) `saveSession` (transação, linhas ~216+): mesmo formato de transação da Task 5(b) — `try (Connection c = ds.getConnection())`, `c.setAutoCommit(false)`, `c.commit()`, `c.rollback()` no catch interno. O SQL não muda (só `ON CONFLICT ... DO UPDATE`, que fica).

d) Todos os demais métodos (`getVolume`, `setVolume`, `sessionField`, `loadQueue`, `savedPlaylists`, os de playlist e os `clear*`): padrão do pool da Task 3. Os que fazem múltiplos statements atomicamente (ex.: salvar playlist = DELETE + INSERTs) seguem o formato de transação.

- [ ] **Step 4: Rodar e ver passar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.MusicRepositoryTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/org/chibot/Database/MusicRepository.java src/test/java/org/chibot/Database/MusicRepositoryTest.java
git commit -m 'PostgreSQL: MusicRepository no pool compartilhado (PRAGMAs de SQLite removidos)'
```

---

### Task 7: Converter `HaremRepository`

**Files:**
- Modify: `src/main/java/org/chibot/Database/HaremRepository.java`
- Modify: `src/test/java/org/chibot/Database/HaremRepositoryTest.java`

**Interfaces:**
- Consumes: `Db.dataSource()`, `PgTestDb.database(...)`.
- Produces: `HaremRepository(DataSource ds)`; API pública inalterada (records `Player`, `Claim`, `Profile`, `HaremStats`, enum `WishResult` intactos).

- [ ] **Step 1: Atualizar o teste**

`HaremRepositoryTest.java` linha 20 → `new HaremRepository(PgTestDb.database("harem_basico"))` (nomes `harem_*` distintos por teste que crie repositório próprio).

- [ ] **Step 2: Rodar e ver falhar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.HaremRepositoryTest" --console=plain`
Expected: FAIL — construtor com `DataSource` não existe.

- [ ] **Step 3: Converter a classe**

Padrão da Task 3, mais os pontos específicos:

a) Schema: TODAS as colunas `INTEGER` viram `BIGINT` (com os mesmos `NOT NULL DEFAULT`): `char_id`, `kakera` (nas duas tabelas), `claimed_at`, `last_claim`, `rolls_used`, `rolls_hour`, `last_daily`, `bonus_rolls`, `tower_level`, `game_rolls_used`, `game_rolls_hour`, `game_last_claim`, `color` (DEFAULT -1), `fav_char_id`, `harem_char_id`, `acquired`, `equipped`. Isto é obrigatório: epoch millis e kakera estouram INTEGER de 32 bits.

b) `addColumnIfMissing(...)`: o corpo vira um `ALTER TABLE ... ADD COLUMN IF NOT EXISTS ...` direto (PostgreSQL suporta nativamente — sem precisar engolir "duplicate column"):

```java
    /** Migração de bancos antigos: adiciona a coluna se ainda não existir. */
    private static void addColumnIfMissing(Statement st, String table, String columnDef) throws SQLException {
        st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + columnDef);
    }
```

As chamadas existentes passam `BIGINT` no lugar de `INTEGER` (ex.: `"last_daily BIGINT NOT NULL DEFAULT 0"`).

c) Os 5 `INSERT OR IGNORE` (linhas ~406, ~500, ~771, ~932, ~1022) viram `INSERT ... ON CONFLICT DO NOTHING` — a semântica de `executeUpdate() == 0` = "já existia" se mantém (o claim atômico da linha ~406 continua correto).

d) Todos os métodos trocam `conn.*` pelo padrão do pool da Task 3; os que fazem várias escritas atômicas (ex.: `tryUseRoll`, trade/divórcio se houver) usam o formato de transação da Task 5(b) numa única conexão emprestada.

e) `setProfileField` (linhas ~765–786) mantém o design (coluna vem de literal interno — seguro), só migra pro padrão do pool com as DUAS operações na MESMA conexão emprestada.

- [ ] **Step 4: Rodar e ver passar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.HaremRepositoryTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Rodar a suíte inteira (regressão)**

Run: `.\gradlew.bat build --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```powershell
git add src/main/java/org/chibot/Database/HaremRepository.java src/test/java/org/chibot/Database/HaremRepositoryTest.java
git commit -m 'PostgreSQL: HaremRepository no pool compartilhado (colunas 64 bits em BIGINT)'
```

---

### Task 8: `SqliteToPostgresMigration` (cópia automática dos dados legados)

**Files:**
- Create: `src/main/java/org/chibot/Database/SqliteToPostgresMigration.java`
- Create: `src/test/java/org/chibot/Database/SqliteToPostgresMigrationTest.java`

**Interfaces:**
- Consumes: os 5 repositórios (constroem o schema no destino), `Db.forUrl(...)` NÃO é usado aqui (SQLite legado abre via `DriverManager`).
- Produces: `SqliteToPostgresMigration.run(DataSource target)` (produção) e `SqliteToPostgresMigration.copyDatabase(Connection sqlite, Connection pg, List<String> tables)` (unidade testável). Marcador: tabela `data_migration (name TEXT PRIMARY KEY, done_at BIGINT)`, linha `sqlite-import`.

- [ ] **Step 1: Escrever o teste**

```java
package org.chibot.Database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteToPostgresMigrationTest {

    @TempDir
    Path dir;

    @Test
    void copiaTabelasDoSqliteProPostgres() throws Exception {
        // Origem: um SQLite de verdade com dados de estado e idioma.
        Path sqliteFile = dir.resolve("ChiState.db");
        try (Connection sq = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile);
             Statement st = sq.createStatement()) {
            st.executeUpdate("CREATE TABLE bot_state (key TEXT NOT NULL PRIMARY KEY, value TEXT)");
            st.executeUpdate("INSERT INTO bot_state VALUES ('maintenance_active', 'true')");
        }

        // Destino: PostgreSQL embarcado com o schema criado pelo repositório real.
        DataSource pg = PgTestDb.database("migra_copia");
        new MaintenanceRepository(pg);

        try (Connection sq = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile);
             Connection dest = pg.getConnection()) {
            SqliteToPostgresMigration.copyDatabase(sq, dest, List.of("bot_state"));
        }

        try (Connection c = pg.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT value FROM bot_state WHERE key = 'maintenance_active'")) {
            assertTrue(rs.next());
            assertEquals("true", rs.getString(1));
        }
    }

    @Test
    void copiarDuasVezesNaoDuplica() throws Exception {
        Path sqliteFile = dir.resolve("ChiLang.db");
        try (Connection sq = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile);
             Statement st = sq.createStatement()) {
            st.executeUpdate("CREATE TABLE user_language (user_id TEXT NOT NULL PRIMARY KEY, lang TEXT NOT NULL)");
            st.executeUpdate("INSERT INTO user_language VALUES ('123', 'en')");
        }

        DataSource pg = PgTestDb.database("migra_idempotente");
        new LanguageRepository(pg);

        for (int i = 0; i < 2; i++) {
            try (Connection sq = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile);
                 Connection dest = pg.getConnection()) {
                SqliteToPostgresMigration.copyDatabase(sq, dest, List.of("user_language"));
            }
        }

        try (Connection c = pg.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM user_language")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void tabelaAusenteNaOrigemEhIgnorada() throws Exception {
        Path sqliteFile = dir.resolve("Vazio.db");
        try (Connection sq = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile)) {
            // banco criado vazio de propósito
        }
        DataSource pg = PgTestDb.database("migra_ausente");
        new MaintenanceRepository(pg);

        try (Connection sq = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile);
             Connection dest = pg.getConnection()) {
            // Não pode lançar: banco velho pode nunca ter criado alguma tabela.
            SqliteToPostgresMigration.copyDatabase(sq, dest, List.of("bot_state"));
        }
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.SqliteToPostgresMigrationTest" --console=plain`
Expected: FAIL — classe não existe.

- [ ] **Step 3: Implementar**

```java
package org.chibot.Database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Migração única dos bancos SQLite legados (ChiData/ChiMusic/ChiState/ChiLang)
 * pro PostgreSQL. Roda no boot: se o marcador {@code sqlite-import} já existe
 * no destino, não faz nada (custo de um SELECT). Senão, copia cada tabela com
 * {@code INSERT ... ON CONFLICT DO NOTHING} — reexecutar após falha parcial é
 * seguro. Os arquivos .db NÃO são apagados (ficam de backup no volume).
 */
public final class SqliteToPostgresMigration {

    private static final Logger log = LoggerFactory.getLogger(SqliteToPostgresMigration.class);
    private static final String MARKER = "sqlite-import";

    private SqliteToPostgresMigration() {
    }

    /** Arquivo legado → tabelas que moravam nele (mesmos defaults/env de antes). */
    private static Map<Path, List<String>> legacyFiles() {
        Map<Path, List<String>> map = new LinkedHashMap<>();
        Path main = resolve("CHIBOT_DB_PATH", "ChiData.db", null);
        map.put(main, List.of(
                "harem_claim", "harem_player", "harem_wish", "harem_profile",
                "harem_badge", "harem_meta",
                "pf_listing", "pf_meta", "pf_indexed_listing", "pf_duty_token"));
        map.put(resolve("CHIBOT_MUSIC_DB_PATH", "ChiMusic.db", main), List.of(
                "music_config", "music_session", "music_queue",
                "music_playlist", "music_playlist_track"));
        map.put(resolve("CHIBOT_STATE_DB_PATH", "ChiState.db", main), List.of("bot_state"));
        map.put(resolve("CHIBOT_LANG_DB_PATH", "ChiLang.db", main), List.of(
                "user_language", "translation_cache"));
        return map;
    }

    /** Mesma resolução de caminho dos repositórios antigos: env própria > ao lado do principal > cwd. */
    private static Path resolve(String envKey, String defaultFile, Path mainDb) {
        String explicit = System.getenv(envKey);
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit);
        }
        if (mainDb != null && mainDb.getParent() != null) {
            return mainDb.getParent().resolve(defaultFile);
        }
        return Paths.get(defaultFile);
    }

    /** Entrada de produção: garante schema no destino, checa o marcador e copia o que houver. */
    public static void run(DataSource target) {
        if (target == null) {
            return;
        }
        // Constrói os repositórios só pelo efeito colateral: cada um cria as
        // próprias tabelas no destino (idempotente).
        new MaintenanceRepository(target);
        new LanguageRepository(target);
        new PfRepository(target);
        new MusicRepository(target);
        new HaremRepository(target);

        try (Connection pg = target.getConnection()) {
            ensureMarkerTable(pg);
            if (markerPresent(pg)) {
                return;
            }
            long total = 0;
            for (Map.Entry<Path, List<String>> e : legacyFiles().entrySet()) {
                if (!Files.exists(e.getKey())) {
                    log.info("Migração: {} não existe; nada a importar dele.", e.getKey());
                    continue;
                }
                try (Connection sqlite = DriverManager.getConnection("jdbc:sqlite:" + e.getKey())) {
                    total += copyDatabase(sqlite, pg, e.getValue());
                }
            }
            writeMarker(pg);
            log.info("Migração SQLite → PostgreSQL concluída: {} linha(s) importada(s). "
                    + "Os arquivos .db antigos ficaram no volume como backup.", total);
        } catch (SQLException e) {
            log.warn("Migração de dados do SQLite falhou; tento de novo no próximo boot.", e);
        }
    }

    /**
     * Copia as tabelas listadas de um SQLite aberto pro PostgreSQL, uma
     * transação por tabela, com ON CONFLICT DO NOTHING (reexecução segura).
     * Tabela ausente na origem é pulada (bancos velhos podem não ter todas).
     * Retorna o total de linhas inseridas. Visível pra teste.
     */
    static long copyDatabase(Connection sqlite, Connection pg, List<String> tables) throws SQLException {
        long total = 0;
        for (String table : tables) {
            if (!sqliteHasTable(sqlite, table)) {
                continue;
            }
            total += copyTable(sqlite, pg, table);
        }
        return total;
    }

    private static boolean sqliteHasTable(Connection sqlite, String table) throws SQLException {
        try (PreparedStatement ps = sqlite.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static long copyTable(Connection sqlite, Connection pg, String table) throws SQLException {
        long count = 0;
        try (Statement src = sqlite.createStatement();
             ResultSet rs = src.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();
            StringBuilder names = new StringBuilder();
            StringBuilder marks = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                if (i > 1) {
                    names.append(", ");
                    marks.append(", ");
                }
                names.append(meta.getColumnName(i));
                marks.append('?');
            }
            String sql = "INSERT INTO " + table + " (" + names + ") VALUES (" + marks
                    + ") ON CONFLICT DO NOTHING";
            boolean oldAutoCommit = pg.getAutoCommit();
            pg.setAutoCommit(false);
            try (PreparedStatement ins = pg.prepareStatement(sql)) {
                while (rs.next()) {
                    for (int i = 1; i <= cols; i++) {
                        ins.setObject(i, rs.getObject(i));
                    }
                    count += ins.executeUpdate();
                }
                pg.commit();
            } catch (SQLException e) {
                pg.rollback();
                throw e;
            } finally {
                pg.setAutoCommit(oldAutoCommit);
            }
        }
        log.info("Migração: {} — {} linha(s).", table, count);
        return count;
    }

    private static void ensureMarkerTable(Connection pg) throws SQLException {
        try (Statement st = pg.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS data_migration (
                        name    TEXT NOT NULL PRIMARY KEY,
                        done_at BIGINT NOT NULL
                    )
                    """);
        }
    }

    private static boolean markerPresent(Connection pg) throws SQLException {
        try (PreparedStatement ps = pg.prepareStatement(
                "SELECT 1 FROM data_migration WHERE name = ?")) {
            ps.setString(1, MARKER);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static void writeMarker(Connection pg) throws SQLException {
        try (PreparedStatement ps = pg.prepareStatement(
                "INSERT INTO data_migration (name, done_at) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, MARKER);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `.\gradlew.bat test --tests "org.chibot.Database.SqliteToPostgresMigrationTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/org/chibot/Database/SqliteToPostgresMigration.java src/test/java/org/chibot/Database/SqliteToPostgresMigrationTest.java
git commit -m 'PostgreSQL: migração automática dos bancos SQLite legados no boot'
```

---

### Task 9: Fiação no boot (`ChiBot.start()`)

**Files:**
- Modify: `src/main/java/org/chibot/ChiBot.java:42-47`

**Interfaces:**
- Consumes: `Db.dataSource()` (Task 1), `SqliteToPostgresMigration.run(...)` (Task 8).

- [ ] **Step 1: Chamar a migração antes de qualquer serviço**

Em `ChiBot.start()`, ANTES da linha `MaintenanceCommand.init(new MaintenanceRepository());`, inserir:

```java
        // Primeira subida com PostgreSQL: importa os dados dos bancos SQLite
        // legados (se existirem) antes de qualquer serviço tocar no banco.
        // Nas subidas seguintes o marcador faz isso custar um SELECT.
        SqliteToPostgresMigration.run(Db.dataSource());
```

E os imports `org.chibot.Database.Db` e `org.chibot.Database.SqliteToPostgresMigration`.

- [ ] **Step 2: Build completo**

Run: `.\gradlew.bat build --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```powershell
git add src/main/java/org/chibot/ChiBot.java
git commit -m 'Boot: roda a migração SQLite → PostgreSQL antes dos serviços subirem'
```

---

### Task 10: Compose + `.env.example`

**Files:**
- Modify: `docker-compose.yml`
- Modify: `.env.example`

- [ ] **Step 1: Adicionar o serviço `postgres` no `docker-compose.yml`**

Depois do serviço `yt-cipher` e antes do bloco `volumes:`, adicionar:

```yaml
  # Banco de dados do bot (harém, música, Party Finder, idiomas, estado).
  # Sem "ports": só o bot precisa alcançar (rede interna do compose).
  postgres:
    image: postgres:16-alpine
    container_name: chibot-postgres
    restart: unless-stopped
    environment:
      - POSTGRES_DB=${POSTGRES_DB:-chibot}
      - POSTGRES_USER=${POSTGRES_USER:-chibot}
      # Obrigatória no .env da VPS — sem default de propósito.
      - POSTGRES_PASSWORD=${POSTGRES_PASSWORD:?defina POSTGRES_PASSWORD no .env}
    # VPS de 2G: buffers pequenos e teto de RAM pro banco não disputar com o
    # Lavalink (que é quem não pode engasgar).
    command: postgres -c shared_buffers=32MB -c max_connections=20
    mem_limit: 256m
    volumes:
      - chibot-pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER:-chibot} -d ${POSTGRES_DB:-chibot}"]
      interval: 10s
      timeout: 5s
      retries: 12
      start_period: 20s
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
```

No bloco `volumes:` do final, adicionar `chibot-pgdata:` abaixo de `chibot-data:`.

No serviço `chibot`:

1. Em `environment`, após a linha do `_JAVA_OPTIONS`:

```yaml
      # Conexão com o postgres do compose, derivada das mesmas POSTGRES_* do
      # .env — uma senha só pra configurar, e bot e banco ficam em sincronia.
      - DATABASE_URL=jdbc:postgresql://postgres:5432/${POSTGRES_DB:-chibot}
      - DATABASE_USER=${POSTGRES_USER:-chibot}
      - DATABASE_PASSWORD=${POSTGRES_PASSWORD:-}
```

2. Em `depends_on`, adicionar:

```yaml
      postgres:
        condition: service_healthy
```

3. No comentário do volume `chibot-data`, acrescentar: `# (segue montado: é de lá que a migração lê os .db antigos do SQLite)`.

- [ ] **Step 2: Validar o compose**

Run: `docker compose config` — **não vai funcionar aqui (sem Docker local)**; em vez disso, conferir indentação visualmente e seguir. A validação real acontece na VPS.

- [ ] **Step 3: Atualizar o `.env.example`**

Depois da seção de música (linha do `LAVALINK_PASSWORD`), adicionar:

```
# ─── Banco de dados (PostgreSQL) ──────────────────────────
# No Docker, defina só a senha: o compose cria o banco com ela e injeta a
# conexão no bot (DATABASE_URL/USER/PASSWORD) sozinho.
POSTGRES_DB=chibot
POSTGRES_USER=chibot
POSTGRES_PASSWORD=

# Rodando o bot FORA do Docker, aponte direto (vazio = sem persistência):
# DATABASE_URL=jdbc:postgresql://localhost:5432/chibot
# DATABASE_USER=chibot
# DATABASE_PASSWORD=...
DATABASE_URL=
DATABASE_USER=
DATABASE_PASSWORD=
```

- [ ] **Step 4: Commit**

```powershell
git add docker-compose.yml .env.example
git commit -m 'Compose: serviço postgres interno com healthcheck; bot conecta via POSTGRES_* do .env'
```

---

### Task 11: README, badge e verificação final

**Files:**
- Modify: `README.md`
- Create: `assets/badges/postgresql.svg`
- Delete: `assets/badges/sqlite.svg`

- [ ] **Step 1: Criar o badge do PostgreSQL**

`assets/badges/postgresql.svg` (mesmo estilo dos existentes — conferir `assets/badges/sqlite.svg` antes de apagar e copiar `width`/cores de lá; modelo baseado no badge do JDA):

```xml
<svg xmlns="http://www.w3.org/2000/svg" width="150" height="28" role="img" aria-label="PostgreSQL 16">
  <title>PostgreSQL 16</title>
  <rect width="150" height="28" rx="14" fill="#FF6FA5"/>
  <text x="75" y="18.5" fill="#ffffff" font-family="'Segoe UI',Verdana,'DejaVu Sans',sans-serif" font-size="12" font-weight="bold" text-anchor="middle" letter-spacing="0.6">&#9829; PostgreSQL 16</text>
</svg>
```

(Se o `sqlite.svg` atual tiver fill/tamanho diferentes, seguir o padrão dele.)

- [ ] **Step 2: Atualizar o README**

1. Linha 14: `[![SQLite 3](assets/badges/sqlite.svg)](https://www.sqlite.org/)` → `[![PostgreSQL 16](assets/badges/postgresql.svg)](https://www.postgresql.org/)`.
2. Buscar TODAS as menções a SQLite/`.db` no README (`ChiMusic.db`, "Banco de dados local", tabela de serviços do compose, estrutura do projeto, seção de tecnologias) e atualizar: o bot agora usa PostgreSQL no compose; os dados de música/harém/PF/idioma ficam em um banco só; mencionar a migração automática ("na primeira subida após o upgrade, o bot importa os dados dos .db antigos sozinho; os arquivos ficam no volume como backup").
3. Na tabela de variáveis do `.env`, adicionar `POSTGRES_PASSWORD` (obrigatória no Docker) e `DATABASE_URL/USER/PASSWORD` (uso fora do Docker).
4. Na tabela de serviços do compose, adicionar a linha do `postgres`.
5. Seção de tecnologias (final): trocar a linha do sqlite-jdbc por PostgreSQL + HikariCP (manter menção ao sqlite-jdbc como leitor legado da migração, se a seção listar tudo).

- [ ] **Step 3: Apagar o badge antigo**

```powershell
git rm assets/badges/sqlite.svg
```

- [ ] **Step 4: Build final completo**

Run: `.\gradlew.bat clean build --console=plain`
Expected: BUILD SUCCESSFUL, todos os testes verdes.

- [ ] **Step 5: Commit e push**

```powershell
git add README.md assets/badges/postgresql.svg
git commit -m 'README: PostgreSQL no lugar do SQLite (badge, serviços do compose e variáveis)'
git push
```

---

## Verificação na VPS (pós-deploy, manual — fora do escopo dos tasks)

1. `git pull`, adicionar `POSTGRES_PASSWORD=<senha forte>` ao `.env`, `docker compose up -d --build`.
2. `docker logs chibot | grep -i migração` — deve listar as tabelas importadas e o total de linhas.
3. Testar no Discord: `!harem` (dados antigos presentes), tocar música, `!language`.
4. `docker restart chibot` — o log NÃO deve importar de novo.
5. `docker compose ps` — porta 5432 não aparece publicada no host.
