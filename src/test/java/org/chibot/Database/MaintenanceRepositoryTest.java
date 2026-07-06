package org.chibot.Database;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceRepositoryTest {

    private static MaintenanceRepository inMemory() {
        return new MaintenanceRepository(PgTestDb.database("maint_basico"));
    }

    @Test
    void manutencaoComecaDesligada() {
        MaintenanceRepository repo = inMemory();
        assertFalse(repo.isMaintenanceActive());
    }

    @Test
    void ligarEDesligarPersisteNaMesmaSessao() {
        MaintenanceRepository repo = new MaintenanceRepository(PgTestDb.database("maint_liga_desliga"));

        repo.setMaintenanceActive(true);
        assertTrue(repo.isMaintenanceActive());

        repo.setMaintenanceActive(false);
        assertFalse(repo.isMaintenanceActive());
    }

    @Test
    void estadoSobreviveAoRestart() {
        // Liga e "desliga o bot" (fecha o repo).
        MaintenanceRepository antes = new MaintenanceRepository(PgTestDb.database("maint_persist"));
        antes.setMaintenanceActive(true);
        antes.close();

        // "Sobe de novo" abrindo o mesmo banco: o flag continua ligado.
        MaintenanceRepository depois = new MaintenanceRepository(PgTestDb.database("maint_persist"));
        assertTrue(depois.isMaintenanceActive());
        depois.close();
    }

    @Test
    void semBancoDegradaSemQuebrar() {
        // DataSource quebrado: a conexão não abre e tudo vira no-op seguro (manutenção off).
        MaintenanceRepository repo = new MaintenanceRepository(new BrokenDataSource());

        repo.setMaintenanceActive(true);
        assertFalse(repo.isMaintenanceActive());
    }
}
