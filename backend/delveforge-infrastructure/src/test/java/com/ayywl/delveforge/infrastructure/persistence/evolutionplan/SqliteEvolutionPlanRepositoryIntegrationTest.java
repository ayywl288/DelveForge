package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;
import static org.junit.jupiter.api.Assertions.*;
import com.ayywl.delveforge.application.port.persistence.*;
import com.ayywl.delveforge.domain.asset.*;
import com.ayywl.delveforge.domain.direction.*;
import com.ayywl.delveforge.domain.evidence.*;
import com.ayywl.delveforge.domain.evolution.*;
import com.ayywl.delveforge.domain.repositoryprofile.*;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.infrastructure.persistence.SqliteDataSourceConfiguration;
import com.ayywl.delveforge.infrastructure.persistence.productdirection.SqliteProductDirectionRepository;
import com.ayywl.delveforge.infrastructure.persistence.softwareasset.SqliteSoftwareAssetRepository;
import com.ayywl.delveforge.infrastructure.persistence.repositoryprofile.SqliteRepositoryProfileRepository;
import com.ayywl.delveforge.infrastructure.persistence.workingcopy.SqliteWorkingCopyRepository;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** No enclosing test transaction: every success commits and every failure really rolls back. */
@SpringBootTest(classes = SqliteEvolutionPlanRepositoryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SqliteEvolutionPlanRepositoryIntegrationTest {
    private static final Path DB = Path.of("target", "test-databases", UUID.randomUUID().toString(), "planning.db");
    private static final List<String> TABLES = List.of("evolution_plan", "evolution_plan_section_item",
            "evolution_step", "evolution_step_section_item", "evolution_plan_evidence");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DB::toString);
    }
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SqliteDataSourceConfiguration.class, SqliteEvolutionPlanRepository.class, SqliteProductDirectionRepository.class,
        SqliteEvolutionLifecycleCommitAdapter.class, SqliteWorkingCopyRepository.class,
        SqliteSoftwareAssetRepository.class, SqliteRepositoryProfileRepository.class})
    @MapperScan({"com.ayywl.delveforge.infrastructure.persistence.evolutionplan",
                 "com.ayywl.delveforge.infrastructure.persistence.productdirection",
                 "com.ayywl.delveforge.infrastructure.persistence.workingcopy",
                 "com.ayywl.delveforge.infrastructure.persistence.softwareasset",
                 "com.ayywl.delveforge.infrastructure.persistence.repositoryprofile"})
    static class TestApplication {}
    @Autowired EvolutionPlanRepository plans;
    @Autowired ProductDirectionRepository directions;
    @Autowired JdbcTemplate jdbc;
    @Autowired EvolutionLifecycleCommitPort commits;
    @Autowired WorkingCopyRepository copies;
    @Autowired SoftwareAssetRepository assets;
    @Autowired RepositoryProfileRepository profiles;
    private static final SoftwareAssetId ASSET = new SoftwareAssetId("asset");
    private static final RepositoryProfileId PROFILE = new RepositoryProfileId("profile");
    private static final Evidence FACT = new Evidence(EvidenceSourceType.REPOSITORY, "src/report", "Existing report exports", 0.8, true);
    private static final EvidenceBasis BASIS = new EvidenceBasis(FACT, new RepositoryProfileEvidenceOrigin(PROFILE));
    private static SoftwareAsset asset(boolean read, String license, UsageAuthorization authorization) {
        return SoftwareAsset.create(ASSET, SoftwareAssetType.GIT_REPOSITORY, SoftwareAssetSource.USER_SPECIFIED,
                "never-open-this-path", read, license, authorization);
    }
    private static RepositoryProfile profile() {
        return RepositoryProfile.create(PROFILE, ASSET, "commit123", "Reporting", List.of("Java"),
                List.of("reports"), List.of("Export"), List.of("Render"), List.of("No scheduling"), List.of(), List.of(FACT));
    }
    private static ProductDirection direction(boolean selected) {
        var direction = ProductDirection.create(new ProductDirectionId("direction"), new UserProfileId("user"), 1,
                List.of(PROFILE), "Personal reports", "Manual exports", "Scheduled reports", "Fits goals",
                List.of(ASSET), "Personal schedule", "Scheduling", "Small", List.of(),
                new DirectionEvidenceSupport(List.of(BASIS), List.of(BASIS), List.of(BASIS)));
        if (selected) direction.select();
        return direction;
    }
    private static PlanningProposal proposal() {
        return new PlanningProposal(new CurrentState("Existing exports with no scheduling", List.of("Export"),
                List.of("reports"), List.of("No scheduling")),
                new TargetState("Manual exports", "Scheduled reports", "Personal schedule"),
                List.of("Render", "Export"), List.of("Add scheduling"),
                List.of(new PlanningStepProposal("Schedule reports", "Reporting scheduling",
                        List.of("Add scheduling capability"), List.of(), List.of("Reports run at the user-selected time")),
                        new PlanningStepProposal("Expose schedule management", "Personal schedule controls",
                        List.of("Manage schedules"), List.of("Scheduling capability exists"), List.of("Users can change schedules"))),
                List.of("Missed reports"), List.of(BASIS));
    }

    @BeforeEach void seed() {
        jdbc.update("DELETE FROM working_copy");
        for (String table : TABLES) jdbc.update("DELETE FROM " + table);
        for (String table : List.of("product_direction_repository_profile", "product_direction_candidate_asset",
                "product_direction_risk", "product_direction_evidence_support"))
            jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM product_direction");
        directions.save(direction(true));
        assets.save(asset(true, "MIT", UsageAuthorization.ALLOWED));
        // Repository Profiles are immutable snapshots; use the saved one across test cases.
        if (profiles.findById(PROFILE).isEmpty()) profiles.save(profile());
    }
    private EvolutionPlan plan(String id) {
        AtomicInteger ids = new AtomicInteger();
        return new EvolutionPlanningService(new AssetUsagePolicy(), () -> new EvolutionPlanId(id),
                () -> new EvolutionStepId(id + "-step-" + ids.incrementAndGet()))
                .plan(direction(true), asset(true, "MIT", UsageAuthorization.ALLOWED), profile(), proposal());
    }

    private WorkingCopy ready(String id) {
        var copy = WorkingCopy.create(new WorkingCopyId(id), ASSET, "commit123", "managed-" + id);
        copy.markReady("commit123");
        return copy;
    }
    private EvolutionPlan activeCandidate(EvolutionPlan proposed, WorkingCopy copy) {
        var candidate = proposed.copy();
        candidate.bindWorkingCopy(copy);
        candidate.activate(new PlanActivationPolicy(new AssetUsagePolicy()), copy, direction(true),
                asset(true, "MIT", UsageAuthorization.ALLOWED), profile());
        return candidate;
    }
    @Test void atomicActivationPersistsReadyMetadataBindingAndPendingSteps() {
        var loaded = plan("activate"); plans.save(loaded);
        var copy = ready("copy");
        commits.commitActivation(activeCandidate(loaded, copy), copy, asset(true, "MIT", UsageAuthorization.ALLOWED));
        var restored = plans.findById(loaded.id()).orElseThrow();
        var restoredCopy = copies.findById(copy.id()).orElseThrow();
        assertEquals(EvolutionPlanStatus.ACTIVE, restored.status());
        assertEquals("copy", restored.workingCopyId());
        assertEquals(WorkingCopyStatus.READY, restoredCopy.status());
        assertEquals("commit123", restoredCopy.sourceRevision());
        assertEquals(restoredCopy.sourceRevision(), restoredCopy.currentRevision());
        assertEquals(restoredCopy.sourceRevision(), restoredCopy.lastVerifiedRevision());
        assertEquals(copy.location(), restoredCopy.location());
        assertEquals(ASSET, restoredCopy.sourceAssetId());
        restored.steps().forEach(step -> assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, step.status()));
        assertEquals(EvolutionPlanStatus.PROPOSED, loaded.status());
        assertNull(loaded.workingCopyId());
    }
    @Test void staleAuthorizationOrDirectionRejectsActivationAndRollsBackInsertedCopy() {
        var loaded = plan("stale-activation"); plans.save(loaded);
        var copy = ready("copy"); var candidate = activeCandidate(loaded, copy);
        assets.save(asset(true, "MIT", UsageAuthorization.DENIED));
        assertThrows(EvolutionLifecycleConflictException.class,
                () -> commits.commitActivation(candidate, copy, asset(true, "MIT", UsageAuthorization.ALLOWED)));
        assertTrue(copies.findById(copy.id()).isEmpty());
        assets.save(asset(true, "MIT", UsageAuthorization.ALLOWED));
        jdbc.update("UPDATE product_direction SET status='SUPERSEDED'");
        assertThrows(EvolutionLifecycleConflictException.class,
                () -> commits.commitActivation(candidate, copy, asset(true, "MIT", UsageAuthorization.ALLOWED)));
        assertTrue(copies.findById(copy.id()).isEmpty());
        assertEquals(EvolutionPlanStatus.PROPOSED, plans.findById(loaded.id()).orElseThrow().status());
        assertNull(plans.findById(loaded.id()).orElseThrow().workingCopyId());
    }
    @Test void actualSqliteCommitFailureRollsBackBothAggregatesAndLeavesPoolUsable() throws Exception {
        var loaded = plan("busy-commit"); plans.save(loaded);
        var copy = ready("copy"); var candidate = activeCandidate(loaded, copy);
        // A reader holds a SHARED lock: INSERT/UPDATE succeeds, COMMIT cannot acquire EXCLUSIVE.
        try (var reader = java.sql.DriverManager.getConnection("jdbc:sqlite:" + DB.toAbsolutePath())) {
            reader.setAutoCommit(false);
            try (var statement = reader.createStatement(); var rows = statement.executeQuery("SELECT * FROM evolution_plan")) {
                assertTrue(rows.next());
            }
            assertThrows(EvolutionLifecycleConflictException.class,
                    () -> commits.commitActivation(candidate, copy, asset(true, "MIT", UsageAuthorization.ALLOWED)));
            reader.rollback();
        }
        assertTrue(copies.findById(copy.id()).isEmpty());
        assertNull(plans.findById(loaded.id()).orElseThrow().workingCopyId());
        assertEquals(EvolutionPlanStatus.PROPOSED, plans.findById(loaded.id()).orElseThrow().status());
        commits.commitActivation(candidate, copy, asset(true, "MIT", UsageAuthorization.ALLOWED));
        assertEquals(EvolutionPlanStatus.ACTIVE, plans.findById(loaded.id()).orElseThrow().status());
    }
    @Test void secondPreparedCandidateCannotOverwriteAnAlreadyActivatedPlan() {
        var loaded = plan("two-candidates"); plans.save(loaded);
        var first = ready("first-copy"); var second = ready("second-copy");
        var firstPlan = activeCandidate(loaded, first); var secondPlan = activeCandidate(loaded, second);
        commits.commitActivation(firstPlan, first, asset(true, "MIT", UsageAuthorization.ALLOWED));
        assertThrows(EvolutionLifecycleConflictException.class,
                () -> commits.commitActivation(secondPlan, second, asset(true, "MIT", UsageAuthorization.ALLOWED)));
        assertTrue(copies.findById(second.id()).isEmpty());
        assertEquals(first.id().value(), plans.findById(loaded.id()).orElseThrow().workingCopyId());
        assertEquals(EvolutionPlanStatus.PROPOSED, loaded.status()); assertNull(loaded.workingCopyId());
    }
    @Test void directionSwitchRejectsAnOmittedConcurrentPlanAndRollsBackEveryLifecycleTransition() {
        var loaded = plan("existing"); plans.save(loaded);
        var historic = loaded.copy(); historic.supersede();
        var oldDirection = directions.findById(new ProductDirectionId("direction")).orElseThrow();
        var supersededDirection = oldDirection.copy(); supersededDirection.supersede();
        var target = ProductDirection.reconstitute(new ProductDirectionId("target"), oldDirection.userProfileId(),
                oldDirection.userProfileRevision(), oldDirection.repositoryProfileIds(), oldDirection.title(),
                oldDirection.problem(), oldDirection.targetProduct(), oldDirection.userFit(), oldDirection.candidateAssetIds(),
                oldDirection.differentiation(), oldDirection.technicalValue(), oldDirection.estimatedComplexity(),
                oldDirection.risks(), oldDirection.evidenceSupport(), ProductDirectionStatus.CANDIDATE);
        directions.save(target);
        var selectedTarget = target.copy(); selectedTarget.select();
        var directionTransitions = List.of(new ProductDirectionTransition(supersededDirection, ProductDirectionStatus.SELECTED),
                new ProductDirectionTransition(selectedTarget, ProductDirectionStatus.CANDIDATE));
        var planTransitions = List.of(new EvolutionPlanTransition(historic, EvolutionPlanStatus.PROPOSED, null));
        // Simulates a new Plan committed after the application loaded the old direction's Plan list.
        plans.save(plan("concurrent"));
        assertThrows(EvolutionLifecycleConflictException.class,
                () -> commits.commitDirectionSelection(directionTransitions, planTransitions));
        assertEquals(ProductDirectionStatus.SELECTED, directions.findById(oldDirection.id()).orElseThrow().status());
        assertEquals(ProductDirectionStatus.CANDIDATE, directions.findById(target.id()).orElseThrow().status());
        for (var plan : plans.findByProductDirectionId(oldDirection.id()))
            assertEquals(EvolutionPlanStatus.PROPOSED, plan.status());
        assertEquals(ProductDirectionStatus.SELECTED, oldDirection.status());
        assertEquals(ProductDirectionStatus.CANDIDATE, target.status());
        assertEquals(EvolutionPlanStatus.PROPOSED, loaded.status());
    }
    @Test void commitsAndRestoresAllFieldsAndStepOrderOnIndependentReads() {
        var plan = plan("first");
        plans.save(plan);
        var loaded = plans.findById(plan.id()).orElseThrow();
        assertEquals(plan.id(), loaded.id());
        assertEquals(plan.productDirectionId(), loaded.productDirectionId());
        assertEquals(plan.baseAssetId(), loaded.baseAssetId());
        assertEquals(plan.baseRepositoryProfileId(), loaded.baseRepositoryProfileId());
        assertEquals(plan.currentState(), loaded.currentState());
        assertEquals(plan.targetState(), loaded.targetState());
        assertEquals(plan.reusableCapabilities(), loaded.reusableCapabilities());
        assertEquals(plan.changes(), loaded.changes());
        assertEquals(plan.risks(), loaded.risks());
        assertEquals(plan.evidence(), loaded.evidence());
        assertEquals(plan.status(), loaded.status());
        assertNull(loaded.workingCopyId());
        for (int index = 0; index < plan.steps().size(); index++) {
            var before = plan.steps().get(index); var after = loaded.steps().get(index);
            assertEquals(before.id(), after.id()); assertEquals(plan.id(), after.planId());
            assertEquals(before.goal(), after.goal()); assertEquals(before.scope(), after.scope());
            assertEquals(before.plannedChanges(), after.plannedChanges());
            assertEquals(before.preconditions(), after.preconditions());
            assertEquals(before.verificationCriteria(), after.verificationCriteria());
            assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, after.status());
            assertNull(after.baselineRevision());
        }
        assertThrows(EvolutionPlanAlreadyExistsException.class, () -> plans.save(plan));
        plans.save(plan("second"));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM evolution_plan", Integer.class));
        // Loading historical facts does not re-run current planning eligibility.
        jdbc.update("UPDATE product_direction SET status='SUPERSEDED' WHERE id='direction'");
        assertTrue(plans.findById(plan.id()).isPresent());
        assertTrue(plans.findById(new EvolutionPlanId("missing")).isEmpty());
    }
    @Test void preservesRepositoryAndUserEvidenceOriginsAndConfirmationValues() {
        var p = proposal();
        var user = new EvidenceBasis(new Evidence(EvidenceSourceType.USER_INPUT, "input", "User needs exports", null, false),
                new UserProfileEvidenceOrigin(new UserProfileId("user"), 3));
        var direction = ProductDirection.create(new ProductDirectionId("direction-2"), new UserProfileId("user"), 3,
                List.of(PROFILE), "Personal reports", "Manual exports", "Scheduled reports", "Fits",
                List.of(ASSET), "Personal schedule", "Value", "Small", List.of(),
                new DirectionEvidenceSupport(List.of(user), List.of(user), List.of(BASIS)));
        direction.select();
        jdbc.update("UPDATE product_direction SET status='SUPERSEDED'");
        directions.save(direction);
        var proposal = new PlanningProposal(p.currentState(), p.targetState(), p.reusableCapabilities(), p.changes(),
                p.steps(), List.of(), List.of(user, BASIS));
        AtomicInteger ids = new AtomicInteger();
        var plan = new EvolutionPlanningService(new AssetUsagePolicy(), () -> new EvolutionPlanId("origins"),
                () -> new EvolutionStepId("origin-step-" + ids.incrementAndGet()))
                .plan(direction, asset(true, "MIT", UsageAuthorization.ALLOWED), profile(), proposal);
        plans.save(plan);
        assertEquals(List.of(user, BASIS), plans.findById(plan.id()).orElseThrow().evidence());
    }
    @Test void rollsBackEveryWrittenTableWhenAnyChildInsertFails() {
        for (String table : TABLES.subList(1, TABLES.size())) {
            jdbc.execute("CREATE TRIGGER fail_planning_insert BEFORE INSERT ON " + table
                    + " BEGIN SELECT RAISE(ABORT, 'injected child failure'); END");
            try {
                var plan = plan("failure-" + table);
                assertThrows(RuntimeException.class, () -> plans.save(plan));
                assertTrue(plans.findById(plan.id()).isEmpty());
                for (String affected : TABLES)
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + affected, Integer.class), affected);
                assertEquals(2, plan.steps().size());
                assertEquals(EvolutionPlanStatus.PROPOSED, plan.status());
            } finally {
                jdbc.execute("DROP TRIGGER fail_planning_insert");
            }
        }
    }
    @Test void rejectsSelectionThatChangedBeforeTheCommitAndWritesNothing() {
        var plan = plan("stale");
        jdbc.update("UPDATE product_direction SET status='SUPERSEDED'");
        assertThrows(EvolutionPlanningPreconditionException.class, () -> plans.save(plan));
        for (String table : TABLES)
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }
}
