package com.ayywl.delveforge.infrastructure.persistence.project;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvolutionFlowScopeMigrationTest {
    @Test
    void upgradeUsesOnlyExplicitUnambiguousReferencesAndPreservesTerminalEvidence() throws Exception {
        Path file = Path.of("target", "test-databases", UUID.randomUUID().toString(), "v16.db");
        Files.createDirectories(file.getParent());
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target("15").load().migrate();
        try (Connection connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            for (String id : new String[] {"known", "shared", "orphan", "legacy"}) {
                sql.executeUpdate("INSERT INTO product_direction(id,user_profile_id,user_profile_revision,title,problem,"
                        + "target_product,user_fit,differentiation,technical_value,estimated_complexity,status) VALUES('"
                        + id + "','user',2,'title','problem','target','fit','diff','value','small','SUPERSEDED')");
            }
            sql.executeUpdate("INSERT INTO evolution_plan(id,product_direction_id,base_asset_id,base_repository_profile_id,"
                    + "working_copy_id,current_summary,target_problem,target_product,target_differentiation,status) "
                    + "VALUES('gs','known','asset','repo','copy','state','problem','target','diff','SUPERSEDED')");
            sql.executeUpdate("INSERT INTO evolution_plan(id,product_direction_id,base_asset_id,base_repository_profile_id,"
                    + "current_summary,target_problem,target_product,target_differentiation,status) "
                    + "VALUES('old','legacy','asset','repo','state','problem','target','diff','SUPERSEDED')");
            sql.executeUpdate("INSERT INTO project_flow_context VALUES('a','project','user','[]','[]',"
                    + "'[\"known\",\"shared\"]','gs','2026-10-11T00:00:00Z','2026-10-11T00:00:00Z',3)");
            sql.executeUpdate("INSERT INTO project_flow_context VALUES('b','project','user','[]','[]',"
                    + "'[\"shared\"]',NULL,'2026-10-11T00:00:00Z','2026-10-11T00:00:00Z',4)");
            sql.executeUpdate("INSERT INTO working_copy VALUES('copy','asset','e38e4a1','never-touch','c822cc3','c822cc3','EVOLVING')");
            sql.executeUpdate("INSERT INTO evolution_step(id,plan_id,position,goal,scope,status,baseline_revision,"
                    + "execution_result,verification_result) VALUES('step','gs',0,'goal','scope','SUCCEEDED','e38e4a1',"
                    + "'{\"revision\":\"c822cc3\"}','{\"success\":true}')");
        }
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration").target("16").load().migrate();
        try (Connection connection = DriverManager.getConnection(url)) {
            assertEquals("a", value(connection, "SELECT flow_id FROM product_direction WHERE id='known'"));
            assertNull(value(connection, "SELECT flow_id FROM product_direction WHERE id='shared'"));
            assertNull(value(connection, "SELECT flow_id FROM product_direction WHERE id='orphan'"));
            assertEquals("legacy-legacy", value(connection, "SELECT flow_id FROM evolution_plan WHERE id='old'"));
            assertEquals("a", value(connection, "SELECT flow_id FROM evolution_plan WHERE id='gs'"));
            assertEquals("SUPERSEDED", value(connection, "SELECT status FROM evolution_plan WHERE id='gs'"));
            assertEquals("SUCCEEDED", value(connection, "SELECT status FROM evolution_step WHERE id='step'"));
            assertEquals("{\"success\":true}", value(connection, "SELECT verification_result FROM evolution_step WHERE id='step'"));
            assertEquals("c822cc3", value(connection, "SELECT last_verified_revision FROM working_copy WHERE id='copy'"));
            assertEquals("3", value(connection, "SELECT version FROM project_flow_context WHERE id='a'"));
            try (var sql = connection.createStatement()) {
                assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE product_direction SET flow_id='b' WHERE id='known'"));
                assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE product_direction SET status='SELECTED' WHERE id='orphan'"));
            }
        }
    }

    private static String value(Connection connection, String query) throws SQLException {
        try (var sql = connection.createStatement(); var row = sql.executeQuery(query)) {
            row.next();
            return row.getString(1);
        }
    }
}
