# Tradução por usuário — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cada usuário escolhe o idioma em que a Chi responde só pra ele (`!language en`), com tradução automática via Amazon Translate e cache persistido.

**Architecture:** Um banco novo dedicado (`ChiLang.db`) guarda a preferência de idioma por usuário e o cache de traduções. Um `TranslationService` (singleton, igual `HaremService`) orquestra: olha o idioma do autor, mascara emoticons/menções/comandos, consulta cache (memória → banco → API) e traduz. A interceptação acontece nos dois `CommandContext` (prefixo e slash), então as ~247 mensagens do código não são tocadas.

**Tech Stack:** Java 17, Gradle, JDA 6.4.2, SQLite (`org.xerial:sqlite-jdbc`), AWS SDK v2 (`software.amazon.awssdk:translate`), JUnit 5.

## Global Constraints

- **Java 17.** Mantém `sourceCompatibility`/`targetCompatibility` 17.
- **Acentuação correta em português** em todas as strings visíveis, comentários, logs e mensagens de commit. Não escrever "manutencao"/"usuario"/"nao".
- **Sem `Co-Authored-By: Claude`** (nem listar Claude como contribuidor) nas mensagens de commit.
- **Padrão de banco do projeto:** arquivo SQLite separado por sistema; conexão única; métodos `synchronized`; degrada com log e vira no-op se o banco não abrir. Construtor com URL explícita (`jdbc:sqlite::memory:` / arquivo) pros testes. Path padrão ao lado do banco principal, com env var de override.
- **Idioma fonte = `pt`.** `pt` é no-op (devolve o texto igual, sem chamar API).
- **Sem credencial AWS = tradução desligada** (degrada, tudo em `pt`), nunca quebra.
- **Comandos têm construtor sem argumentos** (auto-load por reflexão); dependências são alcançadas via singleton estático (`TranslationService.get()`).
- **`git add` sempre com caminhos explícitos** nos commits (há trabalho não commitado de outro sistema na árvore; não incluir por engano).

---

### Task 1: `LanguageRepository` (banco novo `ChiLang.db`)

Banco dedicado com duas tabelas: `user_language` (preferência por usuário) e `translation_cache` (cache persistido). Espelha o estilo de `MaintenanceRepository`/`MusicRepository`.

**Files:**
- Create: `src/main/java/org/chibot/Database/LanguageRepository.java`
- Create: `src/test/java/org/chibot/Database/LanguageRepositoryTest.java`
- Modify: `.gitignore` (adicionar `ChiLang.db` + `-wal`/`-shm`)

**Interfaces:**
- Produces:
  - `LanguageRepository()` e `LanguageRepository(String dbUrl)`
  - `String getLanguage(String userId)` — retorna `"pt"` se não houver registro
  - `void setLanguage(String userId, String lang)`
  - `String getCachedTranslation(String lang, String sourceHash)` — `null` se não houver
  - `void putCachedTranslation(String lang, String sourceHash, String translated)`
  - `void close()` — só pra teste/shutdown

- [ ] **Step 1: Escrever o teste que falha**

Create `src/test/java/org/chibot/Database/LanguageRepositoryTest.java`:

```java
package org.chibot.Database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LanguageRepositoryTest {

    private static LanguageRepository inMemory() {
        return new LanguageRepository("jdbc:sqlite::memory:");
    }

    @Test
    void idiomaPadraoEhPortugues() {
        LanguageRepository repo = inMemory();
        assertEquals("pt", repo.getLanguage("u1"));
    }

    @Test
    void salvaELeIdiomaPorUsuario() {
        LanguageRepository repo = inMemory();
        repo.setLanguage("u1", "en");
        assertEquals("en", repo.getLanguage("u1"));
        // Outro usuário continua no padrão.
        assertEquals("pt", repo.getLanguage("u2"));
    }

    @Test
    void cacheGuardaERecupera() {
        LanguageRepository repo = inMemory();
        assertNull(repo.getCachedTranslation("en", "hash1"));
        repo.putCachedTranslation("en", "hash1", "Roll used~");
        assertEquals("Roll used~", repo.getCachedTranslation("en", "hash1"));
        // Idioma diferente, mesmo hash = entrada diferente.
        assertNull(repo.getCachedTranslation("es", "hash1"));
    }

    @Test
    void preferenciaECacheSobrevivemAoRestart(@TempDir Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("ChiLang.db");

        LanguageRepository antes = new LanguageRepository(url);
        antes.setLanguage("u1", "ja");
        antes.putCachedTranslation("ja", "h", "ロール");
        antes.close();

        LanguageRepository depois = new LanguageRepository(url);
        assertEquals("ja", depois.getLanguage("u1"));
        assertEquals("ロール", depois.getCachedTranslation("ja", "h"));
        depois.close();
    }

    @Test
    void semBancoDegradaSemQuebrar() {
        LanguageRepository repo = new LanguageRepository("jdbc:sqlite:/caminho/invalido/??/x.db");
        repo.setLanguage("u1", "en");
        assertEquals("pt", repo.getLanguage("u1"));
        repo.putCachedTranslation("en", "h", "x");
        assertNull(repo.getCachedTranslation("en", "h"));
    }
}
```

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `./gradlew test --tests "org.chibot.Database.LanguageRepositoryTest" -q`
Expected: FALHA de compilação (`LanguageRepository` não existe).

- [ ] **Step 3: Implementar o repositório**

Create `src/main/java/org/chibot/Database/LanguageRepository.java`:

```java
package org.chibot.Database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Persistência local (SQLite) do sistema de tradução, num arquivo SEPARADO
 * ({@code ChiLang.db}) dos demais bancos. Guarda duas coisas:
 *
 * <ul>
 *   <li><b>Preferência de idioma por usuário</b> ({@code user_language}) — o idioma
 *       que cada pessoa escolheu com {@code !language}.</li>
 *   <li><b>Cache de traduções</b> ({@code translation_cache}) — cada frase já
 *       traduzida, por idioma, pra não bater na API de novo (sobrevive a restart).</li>
 * </ul>
 *
 * <p>Segue o mesmo espírito dos outros repositórios: degrada com log e vira no-op
 * se o banco não abrir. Métodos sincronizados (uma única conexão SQLite).
 */
public class LanguageRepository {

    private static final Logger log = LoggerFactory.getLogger(LanguageRepository.class);
    private static final String DEFAULT_DB_FILE = "ChiLang.db";
    private static final String IDIOMA_PADRAO = "pt";

    private Connection conn;

    public LanguageRepository() {
        this(defaultDbUrl());
    }

    /** Construtor com URL explícita (ex.: {@code jdbc:sqlite::memory:} nos testes). */
    public LanguageRepository(String dbUrl) {
        try {
            ensureParentDir(dbUrl);
            conn = DriverManager.getConnection(dbUrl);
            createSchema();
            log.info("Banco de idiomas pronto ({}).", dbUrl);
        } catch (SQLException e) {
            conn = null;
            log.warn("Não foi possível abrir o banco de idiomas; tradução vira só em memória.", e);
        }
    }

    /**
     * Fica num arquivo separado dos outros bancos. Por padrão ao lado do banco
     * principal (mesmo diretório/volume no Docker); {@code CHIBOT_LANG_DB_PATH}
     * sobrescreve.
     */
    private static String defaultDbUrl() {
        String explicit = System.getenv("CHIBOT_LANG_DB_PATH");
        if (explicit != null && !explicit.isBlank()) {
            return "jdbc:sqlite:" + explicit;
        }
        String mainDb = System.getenv("CHIBOT_DB_PATH");
        if (mainDb != null && !mainDb.isBlank()) {
            java.nio.file.Path parent = java.nio.file.Paths.get(mainDb).getParent();
            java.nio.file.Path langPath = parent != null
                    ? parent.resolve(DEFAULT_DB_FILE)
                    : java.nio.file.Paths.get(DEFAULT_DB_FILE);
            return "jdbc:sqlite:" + langPath;
        }
        return "jdbc:sqlite:" + DEFAULT_DB_FILE;
    }

    private static void ensureParentDir(String dbUrl) {
        String prefix = "jdbc:sqlite:";
        if (!dbUrl.startsWith(prefix)) {
            return;
        }
        String path = dbUrl.substring(prefix.length());
        if (path.isBlank() || path.startsWith(":")) {
            return; // :memory:, etc.
        }
        try {
            java.nio.file.Path parent = java.nio.file.Paths.get(path).getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
        } catch (Exception e) {
            log.warn("Não foi possível criar o diretório do banco para '{}'.", path, e);
        }
    }

    private boolean available() {
        return conn != null;
    }

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS user_language (
                        user_id TEXT NOT NULL PRIMARY KEY,
                        lang    TEXT NOT NULL
                    )
                    """);
            st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS translation_cache (
                        lang        TEXT NOT NULL,
                        source_hash TEXT NOT NULL,
                        translated  TEXT NOT NULL,
                        PRIMARY KEY (lang, source_hash)
                    )
                    """);
        }
    }

    // -------------------------------------------------------------- preferência

    /** Idioma escolhido pelo usuário, ou {@code pt} se nunca configurou / banco indisponível. */
    public synchronized String getLanguage(String userId) {
        if (!available()) {
            return IDIOMA_PADRAO;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT lang FROM user_language WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : IDIOMA_PADRAO;
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler o idioma de {}.", userId, e);
            return IDIOMA_PADRAO;
        }
    }

    public synchronized void setLanguage(String userId, String lang) {
        if (!available()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO user_language (user_id, lang) VALUES (?, ?)
                ON CONFLICT(user_id) DO UPDATE SET lang = excluded.lang
                """)) {
            ps.setString(1, userId);
            ps.setString(2, lang);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Falha ao salvar o idioma de {}.", userId, e);
        }
    }

    // ------------------------------------------------------------------- cache

    /** Tradução já guardada pra (idioma, hash), ou {@code null}. */
    public synchronized String getCachedTranslation(String lang, String sourceHash) {
        if (!available()) {
            return null;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT translated FROM translation_cache WHERE lang = ? AND source_hash = ?")) {
            ps.setString(1, lang);
            ps.setString(2, sourceHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            log.warn("Falha ao ler o cache de tradução ({}, {}).", lang, sourceHash, e);
            return null;
        }
    }

    public synchronized void putCachedTranslation(String lang, String sourceHash, String translated) {
        if (!available()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO translation_cache (lang, source_hash, translated) VALUES (?, ?, ?)
                ON CONFLICT(lang, source_hash) DO UPDATE SET translated = excluded.translated
                """)) {
            ps.setString(1, lang);
            ps.setString(2, sourceHash);
            ps.setString(3, translated);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Falha ao gravar o cache de tradução ({}, {}).", lang, sourceHash, e);
        }
    }

    /** Fecha a conexão com o banco. Só usado em teste / shutdown explícito. */
    public synchronized void close() {
        if (conn == null) {
            return;
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // fechando; nada a fazer
        } finally {
            conn = null;
        }
    }
}
```

- [ ] **Step 4: Atualizar o `.gitignore`**

Em `.gitignore`, na seção dos bancos locais, adicionar após as linhas do `ChiState.db`:

```
ChiLang.db
ChiLang.db-wal
ChiLang.db-shm
```

- [ ] **Step 5: Rodar e confirmar que passa**

Run: `./gradlew test --tests "org.chibot.Database.LanguageRepositoryTest" -q`
Expected: PASS (sem saída no `-q`).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/chibot/Database/LanguageRepository.java \
        src/test/java/org/chibot/Database/LanguageRepositoryTest.java \
        .gitignore
git commit -m "Tradução: banco ChiLang.db com idioma por usuário e cache"
```

---

### Task 2: `TranslationMasker` (protege emoticons, menções e comandos)

Esconde, antes de traduzir, os trechos que a API não deve mexer: spans de crase (`` `daily` ``), menções/emojis do Discord (`<@123>`, `<:pepe:456>`), URLs, e emoticons/emoji kaomoji. Substitui cada trecho por um marcador em Área de Uso Privado (`{i}`) e restaura na volta.

**Files:**
- Create: `src/main/java/org/chibot/Translation/TranslationMasker.java`
- Create: `src/test/java/org/chibot/Translation/TranslationMaskerTest.java`

**Interfaces:**
- Produces:
  - `record Masked(String text, java.util.List<String> originals)`
  - `static Masked mask(String text)`
  - `static String restore(String maskedText, java.util.List<String> originals)`

- [ ] **Step 1: Escrever o teste que falha**

Create `src/test/java/org/chibot/Translation/TranslationMaskerTest.java`:

```java
package org.chibot.Translation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationMaskerTest {

    private static String roundTrip(String s) {
        TranslationMasker.Masked m = TranslationMasker.mask(s);
        return TranslationMasker.restore(m.text(), m.originals());
    }

    @Test
    void preservaEmoticonKawaii() {
        String s = "Roll usado~ volte logo! (˘ω˘) ♡";
        assertEquals(s, roundTrip(s));
        // O emoticon não fica visível pro tradutor.
        assertFalse(TranslationMasker.mask(s).text().contains("˘ω˘"));
    }

    @Test
    void preservaSpanDeCrase() {
        String s = "Use `daily` pra coletar";
        assertEquals(s, roundTrip(s));
        assertFalse(TranslationMasker.mask(s).text().contains("daily"));
    }

    @Test
    void preservaMencaoEEmojiDoDiscord() {
        String s = "oi <@123456> e <:pepe:789>";
        assertEquals(s, roundTrip(s));
        assertFalse(TranslationMasker.mask(s).text().contains("<@123456>"));
        assertFalse(TranslationMasker.mask(s).text().contains("<:pepe:789>"));
    }

    @Test
    void preservaUrl() {
        String s = "veja https://exemplo.com/a?b=1 aqui";
        assertEquals(s, roundTrip(s));
        assertFalse(TranslationMasker.mask(s).text().contains("https://exemplo.com/a?b=1"));
    }

    @Test
    void preservaEmojiForaDoBmp() {
        String s = "parabéns 🎉✨";
        assertEquals(s, roundTrip(s));
    }

    @Test
    void textoSemNadaProtegidoNaoMuda() {
        String s = "Você tem 5 kakera";
        TranslationMasker.Masked m = TranslationMasker.mask(s);
        assertEquals(s, m.text());
        assertTrue(m.originals().isEmpty());
        // Parênteses comuns (sem emoticon) não são mascarados.
        String s2 = "isso (importante) aqui";
        assertEquals(s2, TranslationMasker.mask(s2).text());
    }
}
```

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `./gradlew test --tests "org.chibot.Translation.TranslationMaskerTest" -q`
Expected: FALHA de compilação (`TranslationMasker` não existe).

- [ ] **Step 3: Implementar o masker**

Create `src/main/java/org/chibot/Translation/TranslationMasker.java`:

```java
package org.chibot.Translation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Esconde, antes da tradução, os trechos que a API não deve mexer e restaura
 * depois. Cada trecho protegido vira um marcador em Área de Uso Privado
 * ({@code <índice>}) — códigos que o tradutor passa intactos.
 *
 * <p>Protege, nesta ordem: spans de crase ({@code `comando`}), menções/emojis do
 * Discord, URLs e emoticons/emoji (kaomoji). A detecção de emoticon é heurística:
 * uma sequência de caracteres "decorativos" (com pontuação de kaomoji em volta).
 */
public final class TranslationMasker {

    private TranslationMasker() {}

    /** Texto com os trechos protegidos trocados por marcadores, e a lista dos originais. */
    public record Masked(String text, List<String> originals) {}

    private static final char OPEN = (char) 0xE000;
    private static final char CLOSE = (char) 0xE001;

    // Caracteres "decorativos" típicos de kaomoji/emoji (não devem ser traduzidos).
    private static final String DECO =
            "\\u00B4\\u02C6-\\u02DF\\u0300-\\u036F"          // acentos soltos, modificadores, combinantes
            + "\\u0391-\\u03C9"                              // gregas usadas em kaomoji (ω, etc.)
            + "\\u2010-\\u2027\\u2030-\\u205E"              // travessões, aspas curvas, reticências, etc.
            + "\\u2190-\\u21FF\\u2200-\\u22FF\\u2300-\\u23FF" // setas, operadores matemáticos, técnicos
            + "\\u2460-\\u24FF\\u25A0-\\u27BF"             // fechados, geométricos, dingbats/símbolos
            + "\\u2900-\\u2BFF"                             // setas suplementares, símbolos diversos
            + "\\u3000-\\u303F\\u3040-\\u30FF\\u31F0-\\u31FF\\uFF00-\\uFFEF" // CJK punct, kana, halfwidth
            + "\\x{1F000}-\\x{1FAFF}";                      // emoji fora do BMP

    // Pontuação ASCII que costuma compor kaomoji (só mascarada junto se houver DECO).
    private static final String KAOMOJI_PUNCT = "()\\[\\]{}<>|/\\\\^~*;:._=+\\-'\"!?";

    private static final Pattern BACKTICK = Pattern.compile("`[^`]+`");
    private static final Pattern DISCORD = Pattern.compile("<a?:\\w+:\\d+>|<@[!&]?\\d+>|<#\\d+>");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    // Sequência de pontuação-de-kaomoji/decoração; o filtro "tem DECO?" é aplicado depois.
    private static final Pattern EMOTICON_RUN =
            Pattern.compile("[" + KAOMOJI_PUNCT + DECO + "]+");
    private static final Pattern HAS_DECO = Pattern.compile("[" + DECO + "]");
    private static final Pattern PLACEHOLDER = Pattern.compile(OPEN + "(\\d+)" + CLOSE);

    public static Masked mask(String text) {
        if (text == null || text.isEmpty()) {
            return new Masked(text, List.of());
        }
        List<String> originals = new ArrayList<>();
        String out = text;
        out = maskPattern(out, BACKTICK, originals, false);
        out = maskPattern(out, DISCORD, originals, false);
        out = maskPattern(out, URL, originals, false);
        out = maskPattern(out, EMOTICON_RUN, originals, true);
        return new Masked(out, originals);
    }

    /**
     * Troca cada match por um marcador. Se {@code requireDeco}, só mascara matches
     * que contenham ao menos um caractere decorativo (pra não pegar "(texto)" comum).
     */
    private static String maskPattern(String text, Pattern pattern, List<String> originals,
                                      boolean requireDeco) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String match = m.group();
            if (requireDeco && !HAS_DECO.matcher(match).find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(match));
                continue;
            }
            String token = OPEN + Integer.toString(originals.size()) + CLOSE;
            originals.add(match);
            m.appendReplacement(sb, Matcher.quoteReplacement(token));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String restore(String maskedText, List<String> originals) {
        if (maskedText == null || originals.isEmpty()) {
            return maskedText;
        }
        Matcher m = PLACEHOLDER.matcher(maskedText);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            String original = idx >= 0 && idx < originals.size() ? originals.get(idx) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(original));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
```

- [ ] **Step 4: Rodar e confirmar que passa**

Run: `./gradlew test --tests "org.chibot.Translation.TranslationMaskerTest" -q`
Expected: PASS. Se algum caso de emoticon falhar, ajuste os ranges de `DECO`/`KAOMOJI_PUNCT` até os testes passarem (os testes são o contrato).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/chibot/Translation/TranslationMasker.java \
        src/test/java/org/chibot/Translation/TranslationMaskerTest.java
git commit -m "Tradução: máscara de emoticons, menções e comandos antes da API"
```

---

### Task 3: `Translator` + `AwsTranslator` + dependência AWS

Abstrai a chamada de tradução atrás de uma interface (pra os testes usarem um fake) e implementa a versão real com o Amazon Translate.

**Files:**
- Create: `src/main/java/org/chibot/Translation/Translator.java`
- Create: `src/main/java/org/chibot/Translation/AwsTranslator.java`
- Modify: `build.gradle` (dependência do AWS SDK)

**Interfaces:**
- Produces:
  - `interface Translator { String translate(String text, String sourceLang, String targetLang); }`
  - `class AwsTranslator implements Translator`, com `static AwsTranslator fromConfig(ChiConfig config)` (retorna `null` se faltar credencial)

- [ ] **Step 1: Adicionar a dependência no `build.gradle`**

No bloco `dependencies { ... }`, depois da linha do `sqlite-jdbc`, adicionar:

```groovy
    // Amazon Translate (tradução por usuário). BOM fixa as versões do AWS SDK v2.
    implementation platform('software.amazon.awssdk:bom:2.31.18')
    implementation 'software.amazon.awssdk:translate'
```

- [ ] **Step 2: Confirmar que a dependência resolve**

Run: `./gradlew dependencies --configuration runtimeClasspath -q | grep -i translate`
Expected: aparece `software.amazon.awssdk:translate`. Se a versão `2.31.18` não resolver, troque por uma versão existente do AWS SDK v2 (mavenCentral) e repita.

- [ ] **Step 3: Criar a interface `Translator`**

Create `src/main/java/org/chibot/Translation/Translator.java`:

```java
package org.chibot.Translation;

/**
 * Abstração da chamada de tradução. A implementação real ({@link AwsTranslator})
 * fala com o Amazon Translate; nos testes, um fake permite verificar o cache e a
 * máscara sem bater na rede.
 */
public interface Translator {

    /**
     * Traduz {@code text} de {@code sourceLang} para {@code targetLang}. Em caso de
     * falha, a implementação deve devolver o próprio {@code text} (degrada).
     */
    String translate(String text, String sourceLang, String targetLang);
}
```

- [ ] **Step 4: Implementar `AwsTranslator`**

Create `src/main/java/org/chibot/Translation/AwsTranslator.java`:

```java
package org.chibot.Translation;

import org.chibot.Config.ChiConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.translate.TranslateClient;
import software.amazon.awssdk.services.translate.model.TranslateTextRequest;
import software.amazon.awssdk.services.translate.model.TranslateTextResponse;

/**
 * Implementação de {@link Translator} sobre o Amazon Translate. Se uma tradução
 * falhar (rede/credencial/limite), loga e devolve o texto original — a Chi nunca
 * quebra por causa disso.
 */
public class AwsTranslator implements Translator {

    private static final Logger log = LoggerFactory.getLogger(AwsTranslator.class);

    private final TranslateClient client;

    public AwsTranslator(TranslateClient client) {
        this.client = client;
    }

    /**
     * Monta o tradutor a partir do .env. Retorna {@code null} (tradução desligada)
     * se faltar qualquer uma das credenciais — aí o bot segue todo em português.
     */
    public static AwsTranslator fromConfig(ChiConfig config) {
        String key = config.getAwsAccessKeyId();
        String secret = config.getAwsSecretAccessKey();
        String region = config.getAwsRegion();
        if (blank(key) || blank(secret) || blank(region)) {
            log.warn("Credenciais da AWS ausentes no .env; tradução desligada (tudo em pt).");
            return null;
        }
        TranslateClient client = TranslateClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(key, secret)))
                .build();
        log.info("Amazon Translate pronto (região {}).", region);
        return new AwsTranslator(client);
    }

    @Override
    public String translate(String text, String sourceLang, String targetLang) {
        try {
            TranslateTextResponse resp = client.translateText(TranslateTextRequest.builder()
                    .text(text)
                    .sourceLanguageCode(sourceLang)
                    .targetLanguageCode(targetLang)
                    .build());
            return resp.translatedText();
        } catch (Exception e) {
            log.warn("Falha ao traduzir para {} — devolvendo original.", targetLang, e);
            return text;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
```

> **Nota:** `AwsTranslator` é um wrapper fino sobre o SDK e exige credenciais reais/rede pra um teste de verdade, então não tem teste unitário aqui — a lógica de cache/máscara é testada na Task 4 com um `Translator` fake. Este passo entrega só compilação.

- [ ] **Step 5: Confirmar que compila**

Run: `./gradlew compileJava -q`
Expected: BUILD SUCCESSFUL. (Ainda não compila `fromConfig` se os getters do `ChiConfig` não existirem — eles são adicionados na Task 5. Se este passo falhar por causa de `getAwsAccessKeyId()` etc., adiante o Step 1 da Task 5 — "getters do ChiConfig" — e volte aqui.)

- [ ] **Step 6: Commit**

```bash
git add build.gradle \
        src/main/java/org/chibot/Translation/Translator.java \
        src/main/java/org/chibot/Translation/AwsTranslator.java
git commit -m "Tradução: interface Translator + implementação com Amazon Translate"
```

---

### Task 4: `TranslationService` (orquestra idioma, cache e máscara)

Singleton (igual `HaremService`) que junta tudo: olha o idioma do autor, aplica máscara, consulta cache (memória → banco → API) e traduz; também traduz embeds. Métodos estáticos de conveniência pros contexts.

**Files:**
- Create: `src/main/java/org/chibot/Translation/TranslationService.java`
- Create: `src/test/java/org/chibot/Translation/TranslationServiceTest.java`

**Interfaces:**
- Consumes: `LanguageRepository` (Task 1), `TranslationMasker` (Task 2), `Translator` (Task 3).
- Produces:
  - `TranslationService(LanguageRepository repo, Translator translator)` (suportados = conjunto padrão)
  - `static TranslationService init(LanguageRepository repo, Translator translator)`
  - `static TranslationService get()`
  - `String getLanguage(String userId)`
  - `boolean setLanguage(String userId, String lang)` — valida; `false` se não suportado
  - `java.util.Set<String> supportedLanguages()`
  - `String translateForUser(String userId, String text)`
  - `net.dv8tion.jda.api.entities.MessageEmbed translateEmbedForUser(String userId, MessageEmbed embed)`
  - `java.util.List<MessageEmbed> translateEmbedsForUser(String userId, java.util.List<MessageEmbed> embeds)`
  - Estáticos null-safe: `static String forUser(...)`, `static MessageEmbed embedForUser(...)`, `static List<MessageEmbed> embedsForUser(...)`

- [ ] **Step 1: Escrever o teste que falha**

Create `src/test/java/org/chibot/Translation/TranslationServiceTest.java`:

```java
package org.chibot.Translation;

import org.chibot.Database.LanguageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationServiceTest {

    /** Tradutor fake: conta chamadas e prefixa o idioma alvo. */
    static class FakeTranslator implements Translator {
        int calls = 0;
        String lastText;
        @Override
        public String translate(String text, String source, String target) {
            calls++;
            lastText = text;
            return "<" + target + ">" + text;
        }
    }

    private static LanguageRepository inMemory() {
        return new LanguageRepository("jdbc:sqlite::memory:");
    }

    @Test
    void portuguesEhNoOp() {
        FakeTranslator fake = new FakeTranslator();
        TranslationService svc = new TranslationService(inMemory(), fake);
        svc.setLanguage("u1", "pt");
        assertEquals("Olá", svc.translateForUser("u1", "Olá"));
        assertEquals(0, fake.calls);
    }

    @Test
    void traduzParaOutroIdioma() {
        FakeTranslator fake = new FakeTranslator();
        TranslationService svc = new TranslationService(inMemory(), fake);
        svc.setLanguage("u1", "en");
        assertEquals("<en>Olá", svc.translateForUser("u1", "Olá"));
        assertEquals(1, fake.calls);
    }

    @Test
    void cacheEmMemoriaEvitaSegundaChamada() {
        FakeTranslator fake = new FakeTranslator();
        TranslationService svc = new TranslationService(inMemory(), fake);
        svc.setLanguage("u1", "en");
        svc.translateForUser("u1", "Olá");
        svc.translateForUser("u1", "Olá");
        assertEquals(1, fake.calls);
    }

    @Test
    void cacheDoBancoSobreviveAoRestart(@TempDir Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("ChiLang.db");

        FakeTranslator fakeA = new FakeTranslator();
        LanguageRepository repoA = new LanguageRepository(url);
        TranslationService svcA = new TranslationService(repoA, fakeA);
        svcA.setLanguage("u1", "en");
        svcA.translateForUser("u1", "Olá");
        assertEquals(1, fakeA.calls);
        repoA.close();

        // "Reinício": novo serviço/tradutor sobre o mesmo arquivo. Não bate na API.
        FakeTranslator fakeB = new FakeTranslator();
        LanguageRepository repoB = new LanguageRepository(url);
        TranslationService svcB = new TranslationService(repoB, fakeB);
        assertEquals("<en>Olá", svcB.translateForUser("u1", "Olá"));
        assertEquals(0, fakeB.calls);
        repoB.close();
    }

    @Test
    void mascaraComandoAntesDeTraduzir() {
        FakeTranslator fake = new FakeTranslator();
        TranslationService svc = new TranslationService(inMemory(), fake);
        svc.setLanguage("u1", "en");
        String out = svc.translateForUser("u1", "Use `daily` agora");
        // O tradutor não viu "daily" (estava mascarado)...
        assertFalse(fake.lastText.contains("daily"));
        // ...mas o resultado final tem "daily" de volta.
        assertTrue(out.contains("daily"));
    }

    @Test
    void setLanguageValidaCodigo() {
        TranslationService svc = new TranslationService(inMemory(), new FakeTranslator());
        assertTrue(svc.setLanguage("u1", "en"));
        assertFalse(svc.setLanguage("u1", "xx"));
        assertTrue(svc.supportedLanguages().contains("ja"));
    }

    @Test
    void semTradutorDegrada() {
        TranslationService svc = new TranslationService(inMemory(), null);
        svc.setLanguage("u1", "en");
        assertEquals("Olá", svc.translateForUser("u1", "Olá"));
    }
}
```

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `./gradlew test --tests "org.chibot.Translation.TranslationServiceTest" -q`
Expected: FALHA de compilação (`TranslationService` não existe).

- [ ] **Step 3: Implementar o serviço**

Create `src/main/java/org/chibot/Translation/TranslationService.java`:

```java
package org.chibot.Translation;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.chibot.Database.LanguageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coração do sistema de tradução. Singleton (igual {@code HaremService}): olha o
 * idioma do autor, mascara o que não pode ser traduzido, consulta cache (memória →
 * banco → API) e traduz. Também traduz embeds. {@code pt} é no-op; sem tradutor,
 * tudo degrada pro original.
 */
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    /** Idioma nativo da Chi — não traduz. */
    private static final String IDIOMA_FONTE = "pt";

    /** Idiomas oferecidos no {@code !language}. */
    private static final Set<String> SUPORTADOS = Set.of(
            "pt", "en", "es", "ja", "fr", "de", "it", "ru", "ko", "zh");

    // Limites do Discord pra não estourar ao traduzir embeds.
    private static final int MAX_TITULO = 256;
    private static final int MAX_DESC = 4096;
    private static final int MAX_CAMPO_NOME = 256;
    private static final int MAX_CAMPO_VALOR = 1024;

    private static volatile TranslationService instance;

    private final LanguageRepository repo;
    private final Translator translator;
    /** Cache em memória: chave "langhash" → tradução final (já restaurada). */
    private final ConcurrentHashMap<String, String> memCache = new ConcurrentHashMap<>();

    public TranslationService(LanguageRepository repo, Translator translator) {
        this.repo = repo;
        this.translator = translator;
    }

    public static TranslationService init(LanguageRepository repo, Translator translator) {
        instance = new TranslationService(repo, translator);
        return instance;
    }

    /** Instância criada no boot, ou {@code null} antes do boot. */
    public static TranslationService get() {
        return instance;
    }

    // --------------------------------------------------------------- preferência

    public String getLanguage(String userId) {
        return repo.getLanguage(userId);
    }

    /** Salva o idioma do usuário. Retorna {@code false} se o código não for suportado. */
    public boolean setLanguage(String userId, String lang) {
        if (lang == null) {
            return false;
        }
        String code = lang.toLowerCase();
        if (!SUPORTADOS.contains(code)) {
            return false;
        }
        repo.setLanguage(userId, code);
        return true;
    }

    public Set<String> supportedLanguages() {
        return SUPORTADOS;
    }

    // ------------------------------------------------------------------ tradução

    public String translateForUser(String userId, String text) {
        return translate(text, getLanguage(userId));
    }

    /** Traduz {@code text} para {@code lang} (no-op se {@code pt}/sem tradutor/vazio). */
    public String translate(String text, String lang) {
        if (translator == null || text == null || text.isBlank() || IDIOMA_FONTE.equals(lang)) {
            return text;
        }
        String hash = sha256(text);
        String memKey = lang + "" + hash;

        String mem = memCache.get(memKey);
        if (mem != null) {
            return mem;
        }
        String cached = repo.getCachedTranslation(lang, hash);
        if (cached != null) {
            memCache.put(memKey, cached);
            return cached;
        }

        TranslationMasker.Masked masked = TranslationMasker.mask(text);
        String translated = translator.translate(masked.text(), IDIOMA_FONTE, lang);
        String restored = TranslationMasker.restore(translated, masked.originals());

        memCache.put(memKey, restored);
        repo.putCachedTranslation(lang, hash, restored);
        return restored;
    }

    // -------------------------------------------------------------------- embeds

    public MessageEmbed translateEmbedForUser(String userId, MessageEmbed embed) {
        String lang = getLanguage(userId);
        if (translator == null || IDIOMA_FONTE.equals(lang) || embed == null) {
            return embed;
        }
        EmbedBuilder b = new EmbedBuilder(embed);
        if (embed.getTitle() != null) {
            b.setTitle(clamp(translate(embed.getTitle(), lang), MAX_TITULO), embed.getUrl());
        }
        if (embed.getDescription() != null) {
            b.setDescription(clamp(translate(embed.getDescription(), lang), MAX_DESC));
        }
        b.clearFields();
        for (MessageEmbed.Field f : embed.getFields()) {
            String nome = f.getName() == null ? "" : clamp(translate(f.getName(), lang), MAX_CAMPO_NOME);
            String valor = f.getValue() == null ? "" : clamp(translate(f.getValue(), lang), MAX_CAMPO_VALOR);
            b.addField(nome, valor, f.isInline());
        }
        return b.build();
    }

    public List<MessageEmbed> translateEmbedsForUser(String userId, List<MessageEmbed> embeds) {
        if (embeds == null || embeds.isEmpty()) {
            return embeds;
        }
        List<MessageEmbed> out = new ArrayList<>(embeds.size());
        for (MessageEmbed e : embeds) {
            out.add(translateEmbedForUser(userId, e));
        }
        return out;
    }

    // -------------------------------------------------- estáticos null-safe (contexts)

    public static String forUser(String userId, String text) {
        TranslationService s = instance;
        return s == null ? text : s.translateForUser(userId, text);
    }

    public static MessageEmbed embedForUser(String userId, MessageEmbed embed) {
        TranslationService s = instance;
        return s == null ? embed : s.translateEmbedForUser(userId, embed);
    }

    public static List<MessageEmbed> embedsForUser(String userId, List<MessageEmbed> embeds) {
        TranslationService s = instance;
        return s == null ? embeds : s.translateEmbedsForUser(userId, embeds);
    }

    // ----------------------------------------------------------------- auxiliares

    private static String clamp(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 sempre existe; fallback improvável.
            return Integer.toHexString(text.hashCode());
        }
    }
}
```

- [ ] **Step 4: Rodar e confirmar que passa**

Run: `./gradlew test --tests "org.chibot.Translation.TranslationServiceTest" -q`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/chibot/Translation/TranslationService.java \
        src/test/java/org/chibot/Translation/TranslationServiceTest.java
git commit -m "Tradução: serviço com cache em dois níveis, máscara e embeds"
```

---

### Task 5: Fiação (ChiConfig + boot + contexts)

Liga tudo: getters de AWS no `ChiConfig`, inicialização no boot, e interceptação nos dois contexts. Esta é a cola de integração (presa ao JDA), então a verificação é build + checagem manual.

**Files:**
- Modify: `src/main/java/org/chibot/Config/ChiConfig.java`
- Modify: `src/main/java/org/chibot/ChiBot.java:31-46`
- Modify: `src/main/java/org/chibot/Commands/PrefixCommandContext.java`
- Modify: `src/main/java/org/chibot/Commands/SlashCommandContext.java`

**Interfaces:**
- Consumes: `TranslationService` (Task 4), `LanguageRepository` (Task 1), `AwsTranslator` (Task 3).
- Produces: `ChiConfig.getAwsAccessKeyId()`, `getAwsSecretAccessKey()`, `getAwsRegion()`.

- [ ] **Step 1: Adicionar os getters de AWS no `ChiConfig`**

Em `ChiConfig.java`:

1. Adicionar os campos (junto dos outros `private final String ...`):

```java
    private final String awsAccessKeyId;
    private final String awsSecretAccessKey;
    private final String awsRegion;
```

2. Adicionar os parâmetros no fim do construtor privado e atribuí-los:

```java
                      String ownerId,
                      String awsAccessKeyId, String awsSecretAccessKey, String awsRegion) {
        // ... atribuições existentes ...
        this.ownerId = ownerId;
        this.awsAccessKeyId = awsAccessKeyId;
        this.awsSecretAccessKey = awsSecretAccessKey;
        this.awsRegion = awsRegion;
    }
```

3. Em `load()`, ler os valores e passá-los pro construtor:

```java
        String ownerId = value(env, "OWNER_ID", "");
        String awsAccessKeyId = value(env, "AWS_ACCESS_KEY_ID", "");
        String awsSecretAccessKey = value(env, "AWS_SECRET_ACCESS_KEY", "");
        String awsRegion = value(env, "AWS_REGION", "");

        ChiConfig config = new ChiConfig(token, prefix, guildId, lavalinkUri, lavalinkPassword,
                youtubeApiKey, youtubeRefreshToken, ownerId,
                awsAccessKeyId, awsSecretAccessKey, awsRegion);
```

4. Adicionar os getters (junto dos outros):

```java
    /** Credenciais do Amazon Translate (tradução por usuário). Vazias = tradução desligada. */
    public String getAwsAccessKeyId() {
        return awsAccessKeyId;
    }

    public String getAwsSecretAccessKey() {
        return awsSecretAccessKey;
    }

    public String getAwsRegion() {
        return awsRegion;
    }
```

5. No `createDefault()`, acrescentar ao final do conteúdo do `.env` padrão (antes da string vazia final):

```java
                "",
                "# ─── Tradução (Amazon Translate) ──────────────────────────",
                "# Credencial IAM com a política TranslateReadOnly. Vazio = tradução desligada.",
                "AWS_ACCESS_KEY_ID=",
                "AWS_SECRET_ACCESS_KEY=",
                "AWS_REGION=sa-east-1",
                "",
```

- [ ] **Step 2: Inicializar no boot (`ChiBot.start`)**

Em `ChiBot.java`, adicionar os imports:

```java
import org.chibot.Database.LanguageRepository;
import org.chibot.Translation.AwsTranslator;
import org.chibot.Translation.TranslationService;
```

E logo após `CommandManager commandManager = new CommandManager();` (antes do `MusicService.init`), adicionar:

```java
        // Sistema de tradução por usuário (banco ChiLang.db + Amazon Translate).
        // Sem credencial AWS no .env, o tradutor é null e tudo fica em português.
        LanguageRepository languageRepo = new LanguageRepository();
        TranslationService.init(languageRepo, AwsTranslator.fromConfig(config));
```

- [ ] **Step 3: Interceptar no `PrefixCommandContext`**

Substituir os cinco métodos de resposta de `PrefixCommandContext.java` por estas versões (adiciona o import `org.chibot.Translation.TranslationService`):

```java
    @Override
    public void reply(String message) {
        String userId = event.getAuthor().getId();
        event.getMessage().reply(TranslationService.forUser(userId, message)).queue();
    }

    @Override
    public void replyEmbeds(MessageEmbed embed) {
        String userId = event.getAuthor().getId();
        event.getMessage().replyEmbeds(TranslationService.embedForUser(userId, embed)).queue();
    }

    @Override
    public void replyEmbeds(List<MessageEmbed> embeds) {
        String userId = event.getAuthor().getId();
        event.getMessage().replyEmbeds(TranslationService.embedsForUser(userId, embeds)).queue();
    }

    @Override
    public void replyEmbedWithButtons(String content, MessageEmbed embed, List<Button> buttons) {
        String userId = event.getAuthor().getId();
        var action = event.getMessage().replyEmbeds(TranslationService.embedForUser(userId, embed));
        if (content != null && !content.isBlank()) {
            action.setContent(TranslationService.forUser(userId, content));
        }
        if (!buttons.isEmpty()) {
            action.setComponents(ActionRow.of(buttons));
        }
        action.queue();
    }

    @Override
    public void replyEmbedAndThen(String content, MessageEmbed embed, Consumer<Message> onSent) {
        String userId = event.getAuthor().getId();
        var action = event.getMessage().replyEmbeds(TranslationService.embedForUser(userId, embed));
        if (content != null && !content.isBlank()) {
            action.setContent(TranslationService.forUser(userId, content));
        }
        action.queue(onSent);
    }
```

- [ ] **Step 4: Interceptar no `SlashCommandContext`**

Em `SlashCommandContext.java` (adiciona o import `org.chibot.Translation.TranslationService`), trocar `message`/`embed`/`embeds`/`content` pela versão traduzida nos dois ramos (`deferred` e não-`deferred`) de cada método. O autor é `event.getUser().getId()`. Versões finais:

```java
    @Override
    public void reply(String message) {
        String userId = event.getUser().getId();
        String out = TranslationService.forUser(userId, message);
        if (deferred) {
            event.getHook().sendMessage(out).queue();
        } else {
            event.reply(out).queue();
        }
    }

    @Override
    public void replyEmbeds(MessageEmbed embed) {
        String userId = event.getUser().getId();
        MessageEmbed out = TranslationService.embedForUser(userId, embed);
        if (deferred) {
            event.getHook().sendMessageEmbeds(out).queue();
        } else {
            event.replyEmbeds(out).queue();
        }
    }

    @Override
    public void replyEmbeds(List<MessageEmbed> embeds) {
        String userId = event.getUser().getId();
        List<MessageEmbed> out = TranslationService.embedsForUser(userId, embeds);
        if (deferred) {
            event.getHook().sendMessageEmbeds(out).queue();
        } else {
            event.replyEmbeds(out).queue();
        }
    }

    @Override
    public void replyEmbedWithButtons(String content, MessageEmbed embed, List<Button> buttons) {
        String userId = event.getUser().getId();
        MessageEmbed outEmbed = TranslationService.embedForUser(userId, embed);
        String outContent = content == null || content.isBlank()
                ? content : TranslationService.forUser(userId, content);
        if (deferred) {
            var action = event.getHook().sendMessageEmbeds(outEmbed);
            if (outContent != null && !outContent.isBlank()) {
                action.setContent(outContent);
            }
            if (!buttons.isEmpty()) {
                action.setComponents(ActionRow.of(buttons));
            }
            action.queue();
        } else {
            var action = event.replyEmbeds(outEmbed);
            if (outContent != null && !outContent.isBlank()) {
                action.setContent(outContent);
            }
            if (!buttons.isEmpty()) {
                action.setComponents(ActionRow.of(buttons));
            }
            action.queue();
        }
    }

    @Override
    public void replyEmbedAndThen(String content, MessageEmbed embed, Consumer<Message> onSent) {
        String userId = event.getUser().getId();
        MessageEmbed outEmbed = TranslationService.embedForUser(userId, embed);
        String outContent = content == null || content.isBlank()
                ? content : TranslationService.forUser(userId, content);
        if (deferred) {
            var action = event.getHook().sendMessageEmbeds(outEmbed);
            if (outContent != null && !outContent.isBlank()) {
                action.setContent(outContent);
            }
            action.queue(onSent);
        } else {
            var action = event.replyEmbeds(outEmbed);
            if (outContent != null && !outContent.isBlank()) {
                action.setContent(outContent);
            }
            action.queue(hook -> hook.retrieveOriginal().queue(onSent));
        }
    }
```

- [ ] **Step 5: Compilar e rodar toda a suíte**

Run: `./gradlew test -q`
Expected: BUILD SUCCESSFUL (compila tudo e todos os testes passam).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/chibot/Config/ChiConfig.java \
        src/main/java/org/chibot/ChiBot.java \
        src/main/java/org/chibot/Commands/PrefixCommandContext.java \
        src/main/java/org/chibot/Commands/SlashCommandContext.java
git commit -m "Tradução: lê credencial AWS, inicializa no boot e intercepta as respostas"
```

---

### Task 6: `LanguageCommand` (`!language`)

Comando que o usuário usa pra trocar o próprio idioma. A confirmação sai já no idioma novo (porque passa pelo context, que traduz pro idioma recém-salvo).

**Files:**
- Create: `src/main/java/org/chibot/Commands/Core/LanguageCommand.java`

**Interfaces:**
- Consumes: `TranslationService.get()`, `setLanguage`, `getLanguage`, `supportedLanguages`.

- [ ] **Step 1: Implementar o comando**

Create `src/main/java/org/chibot/Commands/Core/LanguageCommand.java`:

```java
package org.chibot.Commands.Core;

import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.chibot.Commands.CommandContext;
import org.chibot.Commands.ICommand;
import org.chibot.Translation.TranslationService;

import java.util.ArrayList;
import java.util.List;

/**
 * Deixa cada pessoa escolher o idioma em que a Chi responde só pra ela.
 * {@code !language en} muda pra inglês; {@code !language} sozinho mostra o atual e
 * os suportados; {@code !language pt} volta ao padrão.
 */
public class LanguageCommand implements ICommand {

    @Override
    public String getName() {
        return "language";
    }

    @Override
    public List<String> getAliases() {
        return List.of("lang", "idioma");
    }

    @Override
    public String getDescription() {
        return "Escolhe o idioma em que eu falo só com você~ 🌎";
    }

    @Override
    public String getUsage() {
        return "language [código]";
    }

    @Override
    public String getCategory() {
        return "Core";
    }

    @Override
    public List<OptionData> getOptions() {
        return List.of(new OptionData(OptionType.STRING, "codigo",
                "Código do idioma (ex.: en, es, ja). Vazio = mostra o atual.", false));
    }

    @Override
    public void execute(CommandContext ctx) {
        TranslationService ts = TranslationService.get();
        if (ts == null) {
            ctx.reply("O sistema de idiomas ainda não acordou~ tenta de novo em instantes! (・_・;)");
            return;
        }

        String userId = ctx.getAuthor().getId();
        String code = resolveCodigo(ctx);

        if (code == null || code.isBlank()) {
            String atual = ts.getLanguage(userId);
            ctx.reply("Seu idioma agora é **" + atual + "**~ ♡\n"
                    + "Pra trocar, use `language <código>`. Suportados: " + listaSuportados(ts));
            return;
        }

        code = code.toLowerCase();
        if (!ts.setLanguage(userId, code)) {
            ctx.reply("Não conheço o idioma **" + code + "**~ (｡•́︿•̀｡)\n"
                    + "Os que eu falo: " + listaSuportados(ts));
            return;
        }

        // A confirmação passa pelo context, que já traduz pro idioma recém-escolhido.
        ctx.reply("Pronto~ agora eu falo **" + code + "** só com você! (｡•̀ᴗ-)✧");
    }

    /** Código pedido: opção "codigo" do slash ou primeiro argumento do prefixo. */
    private String resolveCodigo(CommandContext ctx) {
        String raw = ctx.getOption("codigo");
        if ((raw == null || raw.isBlank()) && !ctx.getArgs().isEmpty()) {
            raw = ctx.getArgs().get(0);
        }
        return raw;
    }

    private String listaSuportados(TranslationService ts) {
        List<String> codes = new ArrayList<>(ts.supportedLanguages());
        codes.sort(String::compareTo);
        return "`" + String.join("`, `", codes) + "`";
    }
}
```

- [ ] **Step 2: Compilar e rodar a suíte**

Run: `./gradlew test -q`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verificação manual (com credencial AWS no `.env`)**

Pré-requisito: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` preenchidos no `.env` (chave rotacionada). Subir o bot e, num servidor de teste:

1. `!ping` → responde em português (padrão).
2. `!language en` → confirmação chega em inglês.
3. `!help` (ou outro comando) → embeds/textos chegam em inglês **só pra você**; outra conta continua vendo português.
4. `!language pt` → volta ao português.
5. Verificar no log que, repetindo um comando, a segunda vez não loga nova chamada à AWS (cache).

Expected: comportamento acima. Se a credencial estiver ausente, tudo fica em português e o log mostra "Credenciais da AWS ausentes" — também é um resultado válido (degrada).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/chibot/Commands/Core/LanguageCommand.java
git commit -m "Tradução: comando !language pra cada um escolher o próprio idioma"
```

---

## Self-Review (preenchido)

**1. Cobertura do spec:**
- Idioma por usuário no banco → Task 1 (`user_language`) + Task 6 (comando).
- Banco novo dedicado `ChiLang.db` → Task 1.
- Cache em dois níveis (memória + banco), uma vez na vida → Task 1 (`translation_cache`) + Task 4 (`memCache` + leitura/escrita).
- Interceptação nos dois contexts sem tocar nas 247 mensagens → Task 5.
- Máscara de emoticons (+ menções/comandos/URLs) → Task 2 + integração na Task 4.
- Tradução de embeds (title/description/fields) → Task 4.
- `!language` (set/get/lista/validação) → Task 6 + `setLanguage` na Task 4.
- Config AWS no `.env`, degrada sem credencial → Task 5 (getters/boot) + Task 3 (`fromConfig` null).
- `.gitignore` + volume Docker → Task 1 (gitignore); o path padrão ao lado do `CHIBOT_DB_PATH` (`/app/data`) já cai no volume sem mexer no compose.
- Degradação sem banco → Task 1; testes de degradação em Task 1 e Task 4.

**2. Placeholders:** nenhum — todo passo tem código/comando reais.

**3. Consistência de tipos:** `LanguageRepository` (getLanguage/setLanguage/getCachedTranslation/putCachedTranslation/close), `Translator.translate(text, source, target)`, `TranslationService` (translateForUser/translate/setLanguage/getLanguage/supportedLanguages/forUser/embedForUser/embedsForUser) e `TranslationMasker` (mask/restore/Masked) batem entre as tasks que os definem e consomem.

> **Observação de dependência entre tasks:** o `AwsTranslator.fromConfig` (Task 3) usa getters do `ChiConfig` que só existem após a Task 5, Step 1. O `compileJava` da Task 3 (Step 5) avisa isso; se executar em ordem estrita, faça o Step 1 da Task 5 antes de fechar a Task 3 (já anotado no passo).