package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairAuthorizationMigrationTest {
    @TempDir
    Path directory;

    @Test
    void v18AddsEmptyHistoryWithoutRewritingLegacyStatesOrResultBytes() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("m5-a.db");
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").target("17").load().migrate();
        List<String> states = List.of("READY", "EXECUTING", "VERIFYING", "VERIFICATION_FAILED", "SUCCEEDED");
        try (var connection = DriverManager.getConnection(url);
                var insert = connection.prepareStatement("INSERT INTO evolution_step"
                        + "(id,plan_id,position,goal,scope,status,baseline_revision,execution_result,verification_result)"
                        + " VALUES(?, 'plan', ?, 'goal', 'scope', ?, ?, ?, ?)")) {
            for (int index = 0; index < states.size(); index++) {
                insert.setString(1, states.get(index));
                insert.setInt(2, index);
                insert.setString(3, states.get(index));
                insert.setString(4, index == 0 ? null : "A");
                // 原文字节是迁移检查的哨兵，不冒充可被 Domain 接受的执行结果。
                insert.setString(5, index < 2 ? null : "{ legacy execution bytes }");
                insert.setString(6, index < 3 ? null : "{ legacy verification bytes }");
                insert.executeUpdate();
            }
        }
        // 历史迁移的字节保留合同只检查该版本；V25 的主动退役另由 DeliveryMigrationTest 验证。
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").target("18").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement();
                var rows = sql.executeQuery("SELECT * FROM evolution_step ORDER BY position")) {
            for (int index = 0; index < states.size(); index++) {
                assertTrue(rows.next());
                assertEquals(states.get(index), rows.getString("status"));
                assertEquals(index == 0 ? null : "A", rows.getString("baseline_revision"));
                assertEquals(index < 2 ? null : "{ legacy execution bytes }", rows.getString("execution_result"));
                assertEquals(index < 3 ? null : "{ legacy verification bytes }", rows.getString("verification_result"));
                assertEquals("[]", rows.getString("repair_authorizations"));
            }
        }
    }
}
