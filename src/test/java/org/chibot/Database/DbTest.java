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
            // O SQL do projeto tem que rodar em PostgreSQL 16: o embarcado dos
            // testes é pinado nessa major pelo BOM de binários no build.gradle.
            assertEquals(16, c.getMetaData().getDatabaseMajorVersion());
        }
    }

    @Test
    void dataSourceGlobalNuloSemConfiguracao() {
        // Sem DATABASE_URL no ambiente de teste, o pool global não existe.
        assertNull(Db.dataSource());
    }
}
