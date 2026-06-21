package org.chibot.Database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceRepositoryTest {

    /** Banco em memória — a mesma conexão vive enquanto o repo viver. */
    private static MaintenanceRepository inMemory() {
        return new MaintenanceRepository("jdbc:sqlite::memory:");
    }

    @Test
    void manutencaoComecaDesligada() {
        MaintenanceRepository repo = inMemory();
        assertFalse(repo.isMaintenanceActive());
    }

    @Test
    void ligarEDesligarPersisteNaMesmaSessao() {
        MaintenanceRepository repo = inMemory();

        repo.setMaintenanceActive(true);
        assertTrue(repo.isMaintenanceActive());

        repo.setMaintenanceActive(false);
        assertFalse(repo.isMaintenanceActive());
    }

    @Test
    void estadoSobreviveAoRestart(@TempDir Path dir) {
        String url = "jdbc:sqlite:" + dir.resolve("ChiState.db");

        // Liga e "desliga o bot" (fecha a conexão).
        MaintenanceRepository antes = new MaintenanceRepository(url);
        antes.setMaintenanceActive(true);
        antes.close();

        // "Sobe de novo" abrindo o mesmo arquivo: o flag continua ligado.
        MaintenanceRepository depois = new MaintenanceRepository(url);
        assertTrue(depois.isMaintenanceActive());
        depois.close();
    }

    @Test
    void semBancoDegradaSemQuebrar() {
        // URL inválida: a conexão não abre e tudo vira no-op seguro (manutenção off).
        MaintenanceRepository repo = new MaintenanceRepository("jdbc:sqlite:/caminho/invalido/??/x.db");

        repo.setMaintenanceActive(true);
        assertFalse(repo.isMaintenanceActive());
    }
}