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

        // Destino deve continuar vazio: nada foi copiado da tabela ausente.
        try (Connection c = pg.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM bot_state")) {
            rs.next();
            assertEquals(0, rs.getInt(1));
        }
    }
}
