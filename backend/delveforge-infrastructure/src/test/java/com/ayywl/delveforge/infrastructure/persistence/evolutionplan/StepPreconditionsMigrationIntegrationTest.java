package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.application.port.persistence.EvolutionPlanRepository;
import com.ayywl.delveforge.application.port.persistence.WorkingCopyRepository;
import com.ayywl.delveforge.domain.evolution.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.junit.jupiter.api.Assertions.*;

/** 使用旧生产 schema 建立历史事实，再由正常启动迁移；不靠删除旧条件获得执行资格。 */
@SpringBootTest(classes = SqliteEvolutionPlanRepositoryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StepPreconditionsMigrationIntegrationTest {
    private static final Path DB = Path.of("target", "test-databases", UUID.randomUUID().toString(), "v11-preconditions.db");
    private static final List<String> IDS = List.of("proposed", "ready", "executing", "verifying", "succeeded", "historic");
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final ExecutionResult EXECUTION = new ExecutionResult("B", List.of("change"),
            List.of("src/A.java"), List.of(), List.of(), AT);
    private static final VerificationResult VERIFICATION = new VerificationResult("B", List.of(
            new VerificationResult.Check("DIFF_CHECK", "", List.of(1), true, "checked")), true, List.of(), "passed", AT);

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) throws Exception {
        Files.createDirectories(DB.toAbsolutePath().getParent());
        String url = "jdbc:sqlite:" + DB.toAbsolutePath();
        Flyway.configure().dataSource(url, "", "").locations("classpath:db/migration").target("11").load().migrate();
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            for (String id : java.util.stream.Stream.concat(IDS.stream(), java.util.stream.Stream.of("empty-proposed", "empty-active")).toList()) {
                boolean bound = !id.endsWith("proposed") && !id.equals("historic");
                String planStatus = id.endsWith("proposed") ? "PROPOSED" : id.equals("historic") ? "ABANDONED" : "ACTIVE";
                String stepStatus = switch (id) {
                    case "ready" -> "READY";
                    case "executing" -> "EXECUTING";
                    case "verifying" -> "VERIFYING";
                    case "succeeded" -> "SUCCEEDED";
                    default -> "PENDING_CONFIRMATION";
                };
                String copyId = bound ? "'copy-" + id + "'" : "NULL";
                sql.executeUpdate("INSERT INTO evolution_plan VALUES('" + id
                        + "','direction','asset','profile'," + copyId + ",'current','problem','target','difference','" + planStatus + "')");
                sql.executeUpdate("INSERT INTO evolution_plan_section_item VALUES('" + id + "','changes',0,'change')");
                sql.executeUpdate("INSERT INTO evolution_plan_evidence(plan_id,position,source_type,source_ref,claim,confirmed,"
                        + "origin_kind,origin_repository_profile_id) VALUES('" + id
                        + "',0,'REPOSITORY','src/A.java','source',1,'repositoryProfile','profile')");
                boolean started = List.of("executing", "verifying", "succeeded").contains(id);
                try (var insert = connection.prepareStatement("INSERT INTO evolution_step"
                        + "(id,plan_id,position,goal,scope,status,baseline_revision,execution_result,verification_result)"
                        + " VALUES(?,?,0,'goal','scope',?,?,?,?)")) {
                    insert.setString(1, id);
                    insert.setString(2, id);
                    insert.setString(3, stepStatus);
                    insert.setString(4, started ? "A" : null);
                    insert.setString(5, List.of("verifying", "succeeded").contains(id) ? StepResultJson.write(EXECUTION) : null);
                    insert.setString(6, id.equals("succeeded") ? StepResultJson.write(VERIFICATION) : null);
                    insert.executeUpdate();
                }
                sql.executeUpdate("INSERT INTO evolution_step_section_item VALUES('" + id + "','plannedChanges',0,'change',NULL)");
                sql.executeUpdate("INSERT INTO evolution_step_section_item VALUES('" + id
                        + "','verificationCriteria',0,'本次代码差异无空白错误或冲突标记','DIFF_CHECK_PASSES')");
                if (!id.startsWith("empty-")) {
                    sql.executeUpdate("INSERT INTO evolution_step_section_item VALUES('" + id + "','preconditions',0,'external service available',NULL)");
                }
                if (bound) {
                    String current = List.of("verifying", "succeeded").contains(id) ? "B" : "A";
                    String trusted = id.equals("succeeded") ? "B" : "A";
                    sql.executeUpdate("INSERT INTO working_copy VALUES(" + copyId + ",'asset','A','managed-" + id
                            + "','" + current + "','" + trusted + "','" + (started ? "EVOLVING" : "READY") + "')");
                }
            }
        }
        registry.add("delveforge.persistence.database-file", DB::toString);
    }

    @Autowired EvolutionPlanRepository plans;
    @Autowired WorkingCopyRepository copies;
    @Autowired JdbcTemplate jdbc;

    @ParameterizedTest @ValueSource(strings = {"proposed", "ready", "executing", "verifying", "succeeded", "historic"})
    void legacyConditionsRequireReplanningWithoutChangingStepOrTrustCheckpoint(String id) {
        EvolutionPlan plan = plans.findById(new EvolutionPlanId(id)).orElseThrow();
        assertEquals(id.equals("historic") ? EvolutionPlanStatus.ABANDONED : EvolutionPlanStatus.SUPERSEDED, plan.status());
        EvolutionStep step = plan.steps().getFirst();
        EvolutionStepStatus expected = switch (id) {
            case "ready" -> EvolutionStepStatus.READY;
            case "executing" -> EvolutionStepStatus.EXECUTING;
            case "verifying" -> EvolutionStepStatus.VERIFYING;
            case "succeeded" -> EvolutionStepStatus.SUCCEEDED;
            default -> EvolutionStepStatus.PENDING_CONFIRMATION;
        };
        assertEquals(expected, step.status());
        assertEquals("external service available", jdbc.queryForObject(
                "SELECT value FROM evolution_step_section_item WHERE step_id=? AND section='preconditions'", String.class, id));
        boolean started = List.of("executing", "verifying", "succeeded").contains(id);
        assertEquals(started ? "A" : null, step.baselineRevision());
        assertEquals(List.of("verifying", "succeeded").contains(id) ? EXECUTION : null, step.executionResult());
        assertEquals(id.equals("succeeded") ? VERIFICATION : null, step.verificationResult());
        assertEquals(step.executionResult(), plan.copy().steps().getFirst().executionResult());
        assertThrows(EvolutionPlanStateException.class, () -> plan.bindWorkingCopy(readyCopy()));
        if (plan.workingCopyId() != null) {
            WorkingCopy copy = copies.findById(new WorkingCopyId(plan.workingCopyId())).orElseThrow();
            String current = List.of("verifying", "succeeded").contains(id) ? "B" : "A";
            assertEquals(current, copy.currentRevision());
            assertEquals(id.equals("succeeded") ? "B" : "A", copy.lastVerifiedRevision());
            assertEquals(started ? WorkingCopyStatus.EVOLVING : WorkingCopyStatus.READY, copy.status());
            assertThrows(EvolutionPlanStateException.class, () -> plan.confirmStep(step.id(), copy, new StepExecutionPolicy()));
            assertThrows(EvolutionPlanStateException.class, () -> plan.startStep(step.id(), copy, new StepExecutionPolicy()));
            assertEquals(current, copy.currentRevision());
        }
    }

    private WorkingCopy readyCopy() {
        return WorkingCopy.reconstitute(new WorkingCopyId("new-copy"), new com.ayywl.delveforge.domain.asset.SoftwareAssetId("asset"),
                "A", "managed-new", "A", "A", WorkingCopyStatus.READY);
    }

    @Test void oldPlansWithoutConditionsKeepTheirEligibilityAndMigrationIsRepeatable() {
        EvolutionPlan proposed = plans.findById(new EvolutionPlanId("empty-proposed")).orElseThrow();
        assertEquals(EvolutionPlanStatus.PROPOSED, proposed.status());
        assertNull(proposed.workingCopyId());
        EvolutionPlan active = plans.findById(new EvolutionPlanId("empty-active")).orElseThrow();
        WorkingCopy copy = copies.findById(new WorkingCopyId(active.workingCopyId())).orElseThrow();
        new StepExecutionPolicy().requireConfirmationAllowed(active, active.steps().getFirst().id(), copy);
        assertEquals(0, Flyway.configure().dataSource("jdbc:sqlite:" + DB.toAbsolutePath(), "", "")
                .locations("classpath:db/migration").load().migrate().migrationsExecuted);
        assertEquals(EvolutionPlanStatus.SUPERSEDED, plans.findById(new EvolutionPlanId("ready")).orElseThrow().status());
    }
}
