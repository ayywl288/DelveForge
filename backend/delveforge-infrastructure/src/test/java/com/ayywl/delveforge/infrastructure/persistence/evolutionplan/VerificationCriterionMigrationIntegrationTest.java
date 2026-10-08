package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.domain.evolution.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VerificationCriterionMigrationIntegrationTest {
    @TempDir Path directory;

    @Test void v10TextCriteriaRetainTheirOriginalMeaningAndOrderWithoutInventedKinds() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("legacy.db");
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").target("10").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.executeUpdate("INSERT INTO evolution_step(id,plan_id,position,goal,scope,status) VALUES('step','plan',0,'goal','scope','READY')");
            sql.executeUpdate("INSERT INTO evolution_step_section_item VALUES('step','verificationCriteria',0,'Reports run on schedule')");
            sql.executeUpdate("INSERT INTO evolution_step_section_item VALUES('step','verificationCriteria',1,'项目构建成功')");
            sql.executeUpdate("INSERT INTO evolution_step_section_item VALUES('step','plannedChanges',0,'change')");
        }
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement();
                var rows = sql.executeQuery("SELECT * FROM evolution_step_section_item WHERE section='verificationCriteria' ORDER BY position")) {
            java.util.ArrayList<VerificationCriterion> criteria = new java.util.ArrayList<>();
            while (rows.next()) {
                assertEquals(criteria.size(), rows.getInt("position"));
                assertEquals("step", rows.getString("step_id"));
                assertEquals("UNSUPPORTED", rows.getString("criterion_kind"));
                criteria.add(new VerificationCriterion(VerificationCriterion.Kind.valueOf(rows.getString("criterion_kind")), rows.getString("value")));
            }
            assertEquals(List.of("Reports run on schedule", "项目构建成功"), criteria.stream().map(VerificationCriterion::description).toList());
            EvolutionStep restored = EvolutionStep.reconstitute(new EvolutionStepId("step"), new EvolutionPlanId("plan"),
                    "goal", "scope", List.of("change"), criteria, EvolutionStepStatus.READY);
            assertEquals(criteria, restored.verificationCriteria());
            assertFalse(criteria.get(1).supportedBy("BUILD", ""));
        }
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement();
                var rows = sql.executeQuery("SELECT status,baseline_revision,execution_result,verification_result FROM evolution_step WHERE id='step'")) {
            assertTrue(rows.next());
            assertEquals("READY", rows.getString("status"));
            assertNull(rows.getString("baseline_revision"));
            assertNull(rows.getString("execution_result"));
            assertNull(rows.getString("verification_result"));
        }
    }
}
