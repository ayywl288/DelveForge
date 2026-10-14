package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionFailureMigrationTest {
    @Test
    void v20KeepsAllHistoricalBytesAndAddsNoInventedFailure() throws Exception {
        Path database = Path.of("target", "m5d-migration", UUID.randomUUID() + ".db").toAbsolutePath();
        Files.createDirectories(database.getParent());
        String url = "jdbc:sqlite:" + database;
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").target("19").load().migrate();
        List<String> states = List.of("READY", "EXECUTING", "VERIFYING", "VERIFICATION_FAILED", "SUCCEEDED", "ROLLING_BACK", "FAILED", "RECOVERY_REQUIRED");
        try (var connection = DriverManager.getConnection(url); var statement = connection.prepareStatement(
                "INSERT INTO evolution_step(id,plan_id,position,goal,scope,status,baseline_revision,"
                        + "execution_result,verification_result,repair_authorizations,rollback_authorization,rollback_result) VALUES(?, 'plan', ?, 'goal', 'scope', ?, 'A', ?, ?, ?, ?, ?)")) {
            for (int index = 0; index < states.size(); index++) {
                statement.setString(1, states.get(index));
                statement.setInt(2, index);
                statement.setString(3, states.get(index));
                // 原文哨兵只测试迁移不改写证据，不冒充有效 Domain 记录。
                statement.setString(4, "{ execution bytes }");
                statement.setString(5, "{ verification bytes }");
                statement.setString(6, "[ repair history bytes ]");
                statement.setString(7, index >= 5 ? "{ rollback authorization bytes }" : null);
                statement.setString(8, index >= 6 ? "{ rollback result bytes }" : null);
                statement.executeUpdate();
            }
        }
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT * FROM evolution_step ORDER BY position")) {
            for (String status : states) {
                assertTrue(rows.next());
                assertEquals(status, rows.getString("status"));
                assertEquals("A", rows.getString("baseline_revision"));
                assertEquals("{ execution bytes }", rows.getString("execution_result"));
                assertEquals("{ verification bytes }", rows.getString("verification_result"));
                assertEquals("[ repair history bytes ]", rows.getString("repair_authorizations"));
                assertEquals(List.of("ROLLING_BACK", "FAILED", "RECOVERY_REQUIRED").contains(status)
                        ? "{ rollback authorization bytes }" : null, rows.getString("rollback_authorization"));
                assertEquals(List.of("FAILED", "RECOVERY_REQUIRED").contains(status)
                        ? "{ rollback result bytes }" : null, rows.getString("rollback_result"));
                assertNull(rows.getString("execution_failure_result"));
                assertNull(rows.getString("automatic_rollback_result"));
            }
        }
    }
}
