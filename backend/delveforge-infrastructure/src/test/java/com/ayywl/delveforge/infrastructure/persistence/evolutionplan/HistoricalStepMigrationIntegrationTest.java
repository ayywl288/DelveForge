package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.domain.evolution.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = SqliteEvolutionPlanRepositoryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class HistoricalStepMigrationIntegrationTest {
    private static final Path DB = Path.of("target", "test-databases", UUID.randomUUID().toString(), "v10-history.db");
    private static final Instant RECORDED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final ExecutionResult EXECUTION = new ExecutionResult("B", List.of("change"),
            List.of("src/A.java"), List.of("warning"), List.of(), RECORDED_AT);
    private static final VerificationResult VERIFICATION = new VerificationResult("B", List.of(
            new VerificationResult.Check("BUILD", "", List.of(1), true, "Build passed"),
            new VerificationResult.Check("TEST", "", List.of(2), true, "Tests passed")),
            true, List.of(), "Verified", RECORDED_AT);

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(DB.toAbsolutePath().getParent());
        String url = "jdbc:sqlite:" + DB.toAbsolutePath();
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").target("10").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            for (EvolutionStepStatus status : List.of(EvolutionStepStatus.SUCCEEDED, EvolutionStepStatus.VERIFYING,
                    EvolutionStepStatus.EXECUTING)) {
                String id = status.name();
                sql.executeUpdate("INSERT INTO evolution_plan VALUES('" + id
                        + "','direction','asset','profile','copy-" + id + "','current','problem','target','difference','ACTIVE')");
                sql.executeUpdate("INSERT INTO evolution_plan_section_item VALUES('" + id + "','changes',0,'change')");
                sql.executeUpdate("INSERT INTO evolution_plan_evidence(plan_id,position,source_type,source_ref,claim,confirmed,"
                        + "origin_kind,origin_repository_profile_id) VALUES('" + id
                        + "',0,'REPOSITORY','src/A.java','source',1,'repositoryProfile','profile')");
                try (var insert = connection.prepareStatement("INSERT INTO evolution_step"
                        + "(id,plan_id,position,goal,scope,status,baseline_revision,execution_result,verification_result)"
                        + " VALUES(?,?,0,'goal','scope',?,'A',?,?)")) {
                    insert.setString(1, id);
                    insert.setString(2, id);
                    insert.setString(3, id);
                    insert.setString(4, status == EvolutionStepStatus.EXECUTING ? null : StepResultJson.write(EXECUTION));
                    insert.setString(5, status == EvolutionStepStatus.SUCCEEDED ? StepResultJson.write(VERIFICATION) : null);
                    insert.executeUpdate();
                }
                sql.executeUpdate("INSERT INTO evolution_step_section_item VALUES('" + id + "','plannedChanges',0,'change')");
                sql.executeUpdate("INSERT INTO evolution_step_section_item VALUES('" + id + "','verificationCriteria',0,'BUILD passes')");
                sql.executeUpdate("INSERT INTO evolution_step_section_item VALUES('" + id + "','verificationCriteria',1,'TEST passes')");
            }
        }
        // 正常应用启动执行 V11；随后使用真实 Repository 读取迁移后的完整 Aggregate。
        registry.add("delveforge.persistence.database-file", DB::toString);
    }

    @Autowired EvolutionPlanRepository plans;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest @EnumSource(value = EvolutionStepStatus.class, names = {"SUCCEEDED", "VERIFYING", "EXECUTING"})
    void v10CheckpointsRemainReadableWithoutGrantingLegacyCriteriaNewEligibility(EvolutionStepStatus status) {
        EvolutionPlan plan = plans.findById(new EvolutionPlanId(status.name())).orElseThrow();
        EvolutionStep step = plan.step(new EvolutionStepId(status.name()));
        assertEquals(status, step.status());
        assertEquals("A", step.baselineRevision());
        assertEquals(List.of(VerificationCriterion.unsupported("BUILD passes"), VerificationCriterion.unsupported("TEST passes")),
                step.verificationCriteria());
        assertEquals(status == EvolutionStepStatus.EXECUTING ? null : EXECUTION, step.executionResult());
        assertEquals(status == EvolutionStepStatus.SUCCEEDED ? VERIFICATION : null, step.verificationResult());
        assertEquals(step.verificationResult(), plan.copy().step(step.id()).verificationResult());
        assertFalse(VERIFICATION.satisfies(step.verificationCriteria()));
        assertEquals(status == EvolutionStepStatus.EXECUTING ? null : StepResultJson.write(EXECUTION),
                jdbc.queryForObject("SELECT execution_result FROM evolution_step WHERE id=?", String.class, status.name()));
        assertEquals(status == EvolutionStepStatus.SUCCEEDED ? StepResultJson.write(VERIFICATION) : null,
                jdbc.queryForObject("SELECT verification_result FROM evolution_step WHERE id=?", String.class, status.name()));
        assertEquals(List.of("UNSUPPORTED", "UNSUPPORTED"), jdbc.queryForList(
                "SELECT criterion_kind FROM evolution_step_section_item WHERE step_id=? AND section='verificationCriteria' ORDER BY position",
                String.class, status.name()));
    }
}
