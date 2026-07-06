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
