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

class RollbackMigrationTest {
    @Test
    void v19AddsEmptyRollbackSlotsWithoutRewritingM4OrRepairEvidence() throws Exception {
        Path database = Path.of("target", "m5c-migration", UUID.randomUUID() + ".db").toAbsolutePath();
        Files.createDirectories(database.getParent());
        String url = "jdbc:sqlite:" + database;
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").target("18").load().migrate();
        List<String> states = List.of("READY", "EXECUTING", "VERIFYING", "VERIFICATION_FAILED", "SUCCEEDED");
        try (var connection = DriverManager.getConnection(url); var statement = connection.prepareStatement(
                "INSERT INTO evolution_step(id,plan_id,position,goal,scope,status,baseline_revision,"
                        + "execution_result,verification_result,repair_authorizations) VALUES(?, 'plan', ?, 'goal', 'scope', ?, 'A', ?, ?, ?)")) {
            for (int index = 0; index < states.size(); index++) {
                statement.setString(1, states.get(index));
                statement.setInt(2, index);
                statement.setString(3, states.get(index));
                // 原文哨兵只测试迁移不改写证据，不冒充有效 Domain 记录。
                statement.setString(4, "{ execution bytes }");
                statement.setString(5, "{ verification bytes }");
                statement.setString(6, "[ repair history bytes ]");
                statement.executeUpdate();
            }
        }
        // 历史迁移的字节保留合同只检查该版本；V25 的主动退役另由 DeliveryMigrationTest 验证。
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").target("19").load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT * FROM evolution_step ORDER BY position")) {
            for (String status : states) {
                assertTrue(rows.next());
                assertEquals(status, rows.getString("status"));
                assertEquals("A", rows.getString("baseline_revision"));
                assertEquals("{ execution bytes }", rows.getString("execution_result"));
                assertEquals("{ verification bytes }", rows.getString("verification_result"));
                assertEquals("[ repair history bytes ]", rows.getString("repair_authorizations"));
                assertNull(rows.getString("rollback_authorization"));
                assertNull(rows.getString("rollback_result"));
            }
        }
    }
}
