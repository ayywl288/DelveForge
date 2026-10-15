package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryAuthorizationMigrationTest {
    @Test
    void v21PreservesEveryLegacyCheckpointByteWithoutManufacturingRetryAuthority() throws Exception {
        Path db = Path.of("target", "m5e-migration", UUID.randomUUID() + ".db").toAbsolutePath();
        Files.createDirectories(db.getParent());
        String url = "jdbc:sqlite:" + db;
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").target("20").load().migrate();
        List<String> columns = List.of("execution_result", "verification_result", "repair_authorizations",
                "rollback_authorization", "rollback_result", "execution_failure_result", "automatic_rollback_result");
        List<String> states = List.of("READY", "EXECUTING", "VERIFYING", "VERIFICATION_FAILED", "SUCCEEDED",
                "ROLLING_BACK", "FAILED", "RECOVERY_REQUIRED");
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement()) {
            int position = 0;
            for (String state : states) {
                statement.executeUpdate("INSERT INTO evolution_step(id,plan_id,position,goal,scope,status) VALUES ('"
                        + state + "','plan'," + position++ + ",'goal','scope','" + state + "')");
                for (String column : columns) {
                    // 原文哨兵仅证明迁移未改写历史，不冒充合法 Domain 检查点。
                    statement.executeUpdate("UPDATE evolution_step SET " + column + "=' raw " + column + " '");
                }
            }
        }
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(url); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT * FROM evolution_step")) {
            int count = 0;
            while (rows.next()) {
                assertTrue(states.contains(rows.getString("status")));
                assertEquals("[]", rows.getString("retry_authorizations"));
                for (String column : columns) {
                    assertEquals(" raw " + column + " ", rows.getString(column));
                }
                count++;
            }
            assertEquals(8, count);
        }
    }
}
