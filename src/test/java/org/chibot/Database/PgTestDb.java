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
