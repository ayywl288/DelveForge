package com.ayywl.delveforge.domain.evolution;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.*;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class StepExecutionLifecycleTest {
    private final EvolutionPlanId id = new EvolutionPlanId("plan");
    private final EvolutionStepId first = new EvolutionStepId("first");
    private final EvolutionStepId second = new EvolutionStepId("second");
    private final SoftwareAssetId asset = new SoftwareAssetId("asset");
    private final StepExecutionPolicy policy = new StepExecutionPolicy();

    private EvolutionPlan plan() {
        return plan(List.of(VerificationCriterion.technical(VerificationCriterion.Kind.BUILD_SUCCEEDS), VerificationCriterion.technical(VerificationCriterion.Kind.FULL_TEST_SUITE_PASSES)));
    }
    private EvolutionPlan plan(List<VerificationCriterion> criteria) {
        return plan(criteria, EvolutionStepStatus.PENDING_CONFIRMATION);
    }
    private EvolutionPlan plan(List<VerificationCriterion> criteria, EvolutionStepStatus secondStatus) {
        EvidenceBasis evidence = new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY, "src/A", "source", 0.8, true),
                new RepositoryProfileEvidenceOrigin(new RepositoryProfileId("profile")));
        return EvolutionPlan.reconstitute(id, new ProductDirectionId("direction"), asset, new RepositoryProfileId("profile"),
                new CurrentState("current", List.of(), List.of(), List.of()), new TargetState("problem", "target", "difference"),
                List.of(), List.of("change"), List.of(
                    EvolutionStep.reconstitute(first, id, "goal", "scope", List.of("change"), criteria),
                    EvolutionStep.reconstitute(second, id, "goal2", "scope2", List.of("change2"), List.of(VerificationCriterion.technical(VerificationCriterion.Kind.FULL_TEST_SUITE_PASSES)), secondStatus)),
                List.of(), List.of(evidence), new WorkingCopyId("copy"), EvolutionPlanStatus.ACTIVE);
    }
    private WorkingCopy copy() {
        return WorkingCopy.reconstitute(new WorkingCopyId("copy"), asset, "A", "managed", "A", "A", WorkingCopyStatus.READY);
    }
    private ExecutionResult execution(String revision) {
        return new ExecutionResult(revision, List.of("1 addition / 1 deletion"), List.of("src/A"), List.of(), List.of(), Instant.now());
    }
    private VerificationResult verification(String revision, boolean passed, List<Integer> criteria) {
        return new VerificationResult(revision, criteria.stream().map(index -> new VerificationResult.Check(
                index == 1 ? "BUILD" : "TEST", "", List.of(index), passed, "exit")).toList(),
                passed, passed ? List.of() : List.of("failed"), "summary", Instant.now());
    }
    @Test void completePathKeepsCandidateUntrustedUntilAllCriteriaPassAndNextStepUsesB() {
        EvolutionPlan loaded = plan();
        WorkingCopy before = copy();
        loaded.confirmStep(first, before, policy);
        EvolutionPlan plan = loaded.copy();
        WorkingCopy copy = before.copy();
        plan.startStep(first, copy, policy);
        assertEquals("A", plan.step(first).baselineRevision());
        assertEquals(EvolutionStepStatus.EXECUTING, plan.step(first).status());
        assertEquals(WorkingCopyStatus.EVOLVING, copy.status());
        assertNull(loaded.step(first).baselineRevision());
        assertEquals(WorkingCopyStatus.READY, before.status());
        assertThrows(EvolutionPlanStateException.class, () -> plan.startStep(first, copy, policy));
        plan.recordExecution(first, copy, execution("B"));
        assertEquals(EvolutionStepStatus.VERIFYING, plan.step(first).status());
        assertEquals("B", copy.currentRevision());
        assertEquals("A", copy.lastVerifiedRevision());
        assertThrows(EvolutionPlanStateException.class, () -> plan.confirmStep(second, copy, policy));
        VerificationResult result = verification("B", true, List.of(1, 2));
        plan.succeedStep(first, copy, result);
        assertEquals(EvolutionStepStatus.SUCCEEDED, plan.step(first).status());
        assertEquals("B", copy.lastVerifiedRevision());
        assertEquals("B", copy.currentRevision());
        assertEquals("A", plan.step(first).baselineRevision());
        assertEquals(result, plan.copy().step(first).verificationResult());
        plan.confirmStep(second, copy, policy);
        plan.startStep(second, copy, policy);
        assertEquals("B", plan.step(second).baselineRevision());
    }
    @Test void wrongCandidateIncompleteCoverageAndFailedChecksPreserveVerifyingCheckpoint() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        plan.startStep(first, copy, policy);
        plan.recordExecution(first, copy, execution("B"));
        for (VerificationResult result : List.of(verification("C", true, List.of(1, 2)),
                verification("B", true, List.of(1)), verification("B", false, List.of(1, 2)),
                verification("B", true, List.of(1, 3)))) {
            assertThrows(EvolutionPlanStateException.class, () -> plan.succeedStep(first, copy, result));
            assertEquals(EvolutionStepStatus.VERIFYING, plan.step(first).status());
            assertNull(plan.step(first).verificationResult());
            assertEquals("A", copy.lastVerifiedRevision());
        }
    }
    @Test void sourceExecutionOrSameRevisionCannotCreateCandidate() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        assertThrows(EvolutionPlanStateException.class, () -> plan.recordExecution(first, copy, execution("B")));
        plan.startStep(first, copy, policy);
        assertThrows(EvolutionPlanStateException.class, () -> plan.recordExecution(first, copy, execution("A")));
        assertThrows(EvolutionPlanStateException.class, () -> new ExecutionResult("B", List.of("change"),
                List.of("src/A"), List.of(), List.of("partial"), Instant.now()));
        assertEquals(EvolutionStepStatus.EXECUTING, plan.step(first).status());
        assertEquals("A", copy.currentRevision());
    }
    @ParameterizedTest @ValueSource(strings = {"BUILD", "DIFF_CHECK", "TEST", "FOCUSED_TEST"})
    void unsupportedBusinessEvidenceCannotPromoteCandidateButRecordedHistoryRemainsReadable(String action) {
        EvolutionPlan plan = plan(List.of(VerificationCriterion.unsupported("Reports run on schedule")));
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        plan.startStep(first, copy, policy);
        ExecutionResult execution = execution("B");
        plan.recordExecution(first, copy, execution);
        VerificationResult result = new VerificationResult("B", List.of(new VerificationResult.Check(action,
                action.equals("FOCUSED_TEST") ? "UnrelatedTest#value" : "", List.of(1), true, "Passed")),
                true, List.of(), "Passed", Instant.now());
        assertThrows(EvolutionPlanStateException.class, () -> plan.succeedStep(first, copy, result));
        assertEquals(EvolutionStepStatus.VERIFYING, plan.step(first).status());
        assertNull(plan.step(first).verificationResult());
        assertEquals("B", copy.currentRevision());
        assertEquals("A", copy.lastVerifiedRevision());
        EvolutionStep historical = EvolutionStep.reconstitute(first, id, "goal", "scope",
                List.of("change"), List.of(VerificationCriterion.unsupported("Reports run on schedule")), EvolutionStepStatus.SUCCEEDED,
                "A", execution, result);
        assertEquals(EvolutionStepStatus.SUCCEEDED, historical.status());
        assertEquals(result, historical.verificationResult());
        assertEquals(execution, historical.executionResult());
        assertEquals("A", historical.baselineRevision());
        assertFalse(result.satisfies(historical.verificationCriteria()));
        assertThrows(EvolutionPlanStateException.class, () -> historical.start("B"));
    }
    @Test void unconfirmedStepCannotFreezeBaselineOrChangeWorkingCopy() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        assertThrows(EvolutionPlanStateException.class, () -> plan.startStep(first, copy, policy));
        assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, plan.step(first).status());
        assertNull(plan.step(first).baselineRevision());
        assertEquals(WorkingCopyStatus.READY, copy.status());
        assertEquals("A", copy.currentRevision());
        assertEquals("A", copy.lastVerifiedRevision());
    }
    @Test void invalidRestoredCheckpointsAndContradictoryVerificationAreRejected() {
        assertThrows(EvolutionPlanStateException.class, () -> EvolutionStep.reconstitute(first, id, "goal", "scope",
                List.of("change"), List.of(VerificationCriterion.unsupported("one")), EvolutionStepStatus.VERIFYING, "A", null, null));
        assertThrows(EvolutionPlanStateException.class, () -> EvolutionStep.reconstitute(first, id, "goal", "scope",
                List.of("change"), List.of(VerificationCriterion.unsupported("one")), EvolutionStepStatus.READY, "A", null, null));
        assertThrows(EvolutionPlanStateException.class, () -> new VerificationResult("B", List.of(
                new VerificationResult.Check("TEST", "", List.of(1), false, "failed")), true, List.of(), "summary", Instant.now()));
    }

    @Test void historicalSuccessMustStillBeStructurallyCompleteAndRecordPassingFacts() {
        List<VerificationCriterion> criteria = List.of(VerificationCriterion.unsupported("BUILD passes"));
        for (VerificationResult result : List.of(verification("C", true, List.of(1)),
                verification("B", false, List.of(1)), verification("B", true, List.of(2)))) {
            assertThrows(EvolutionPlanStateException.class, () -> EvolutionStep.reconstitute(first, id, "goal", "scope",
                    List.of("change"), criteria, EvolutionStepStatus.SUCCEEDED, "A", execution("B"), result));
        }
        assertThrows(EvolutionPlanStateException.class, () -> EvolutionStep.reconstitute(first, id, "goal", "scope",
                List.of("change"), criteria, EvolutionStepStatus.SUCCEEDED, null, execution("B"), verification("B", true, List.of(1))));
        assertThrows(EvolutionPlanStateException.class, () -> EvolutionStep.reconstitute(first, id, "goal", "scope",
                List.of("change"), criteria, EvolutionStepStatus.SUCCEEDED, "A", null, verification("B", true, List.of(1))));
    }

    private VerificationResult confirmedFailure(String revision) {
        return new VerificationResult(revision, List.of(
                new VerificationResult.Check("BUILD", "", List.of(1), VerificationResult.Outcome.FAILED, "Build failed"),
                new VerificationResult.Check("TEST", "", List.of(2), VerificationResult.Outcome.NOT_RUN, "Not run")),
                false, List.of("Build failed"), "Candidate checks failed", Instant.now());
    }

    @Test void confirmedFailureRetainsCandidateAndBaselineAndBlocksEvenAnAlreadyReadyNextStep() {
        EvolutionPlan plan = plan(List.of(VerificationCriterion.technical(VerificationCriterion.Kind.BUILD_SUCCEEDS),
                VerificationCriterion.technical(VerificationCriterion.Kind.FULL_TEST_SUITE_PASSES)), EvolutionStepStatus.READY);
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        plan.startStep(first, copy, policy);
        plan.recordExecution(first, copy, execution("B"));
        VerificationResult result = confirmedFailure("B");
        plan.failStepVerification(first, copy, result);
        assertEquals(EvolutionStepStatus.VERIFICATION_FAILED, plan.step(first).status());
        assertEquals(result, plan.copy().step(first).verificationResult());
        assertEquals("A", plan.step(first).baselineRevision());
        assertEquals("B", copy.currentRevision());
        assertEquals("A", copy.lastVerifiedRevision());
        assertEquals(WorkingCopyStatus.EVOLVING, copy.status());
        assertEquals(EvolutionPlanStatus.ACTIVE, plan.status());
        assertThrows(EvolutionPlanStateException.class, () -> plan.startStep(second, copy, policy));
        assertNull(plan.step(second).baselineRevision());
        assertThrows(EvolutionPlanStateException.class, () -> plan.confirmStep(first, copy, policy));
        assertThrows(EvolutionPlanStateException.class, () -> plan.startStep(first, copy, policy));
        assertThrows(EvolutionPlanStateException.class, () -> plan.succeedStep(first, copy, verification("B", true, List.of(1, 2))));
        assertThrows(EvolutionPlanStateException.class, () -> plan.failStepVerification(first, copy, result));
    }

    @Test void invalidOrUnconfirmedFailureDoesNotMutateEitherAggregate() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        plan.startStep(first, copy, policy);
        assertThrows(EvolutionPlanStateException.class, () -> plan.failStepVerification(first, copy, confirmedFailure("B")));
        plan.recordExecution(first, copy, execution("B"));
        for (VerificationResult result : List.of(confirmedFailure("C"), verification("B", true, List.of(1, 2)),
                verification("B", false, List.of(1, 2)),
                new VerificationResult("B", List.of(new VerificationResult.Check("BUILD", "", List.of(1),
                        VerificationResult.Outcome.FAILED, "failed")), false, List.of("failed"), "failed", Instant.now()))) {
            assertThrows(EvolutionPlanStateException.class, () -> plan.failStepVerification(first, copy, result));
            assertEquals(EvolutionStepStatus.VERIFYING, plan.step(first).status());
            assertNull(plan.step(first).verificationResult());
            assertEquals("B", copy.currentRevision());
            assertEquals("A", copy.lastVerifiedRevision());
        }
        WorkingCopy drifted = WorkingCopy.reconstitute(copy.id(), asset, "A", "managed", "C", "A", WorkingCopyStatus.EVOLVING);
        assertThrows(EvolutionPlanStateException.class, () -> plan.failStepVerification(first, drifted, confirmedFailure("B")));
        assertNull(plan.step(first).verificationResult());
    }

    @Test void restoredFailureRequiresMatchingCandidateAndConfirmedFailureCoverage() {
        for (VerificationResult result : List.of(confirmedFailure("C"), verification("B", true, List.of(1, 2)),
                verification("B", false, List.of(1, 2)))) {
            assertThrows(EvolutionPlanStateException.class, () -> EvolutionStep.reconstitute(first, id, "goal", "scope",
                    List.of("change"), plan().step(first).verificationCriteria(), EvolutionStepStatus.VERIFICATION_FAILED,
                    "A", execution("B"), result));
        }
        assertThrows(EvolutionPlanStateException.class, () -> EvolutionStep.reconstitute(first, id, "goal", "scope",
                List.of("change"), plan().step(first).verificationCriteria(), EvolutionStepStatus.VERIFICATION_FAILED,
                "A", execution("B"), null));
    }

    @Test
    void repairsAppendIndependentAuthorizationsAndFailuresWithoutResettingBaseline() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        assertThrows(EvolutionPlanStateException.class,
                () -> plan.authorizeStepRepair(first, copy, policy, "repair-1", "A", Instant.now()));
        plan.startStep(first, copy, policy);
        plan.recordExecution(first, copy, execution("B"));
        plan.failStepVerification(first, copy, confirmedFailure("B"));
        EvolutionPlan before = plan.copy();
        plan.authorizeStepRepair(first, copy, policy, "repair-1", "B", Instant.now());
        assertEquals("A", plan.step(first).baselineRevision());
        assertEquals("B", plan.step(first).executionStartRevision());
        assertEquals("B", copy.currentRevision());
        assertEquals("A", copy.lastVerifiedRevision());
        assertNull(plan.step(first).executionResult());
        assertNull(plan.step(first).verificationResult());
        assertEquals(EvolutionStepStatus.VERIFICATION_FAILED, before.step(first).status());
        assertTrue(before.step(first).repairAuthorizations().isEmpty());
        assertEquals(before.step(first).verificationResult(), plan.step(first).repairAuthorizations().getFirst().previousVerification());
        assertEquals(plan.step(first).repairAuthorizations(), plan.copy().step(first).repairAuthorizations());
        assertThrows(UnsupportedOperationException.class, () -> plan.step(first).repairAuthorizations().clear());
        assertThrows(EvolutionPlanStateException.class, () -> plan.recordExecution(first, copy, execution("B")));
        plan.recordExecution(first, copy, execution("C"));
        plan.failStepVerification(first, copy, confirmedFailure("C"));
        assertThrows(EvolutionPlanStateException.class,
                () -> plan.authorizeStepRepair(first, copy, policy, "repair-1", "C", Instant.now()));
        assertThrows(EvolutionPlanStateException.class,
                () -> plan.authorizeStepRepair(first, copy, policy, "repair-2", "B", Instant.now()));
        assertEquals(1, plan.step(first).repairAuthorizations().size());
        plan.authorizeStepRepair(first, copy, policy, "repair-2", "C", Instant.now());
        plan.recordExecution(first, copy, execution("D"));
        plan.succeedStep(first, copy, verification("D", true, List.of(1, 2)));
        assertEquals(List.of("B", "C"), plan.step(first).repairAuthorizations().stream()
                .map(RepairAuthorization::candidateRevision).toList());
        assertEquals("A", plan.step(first).baselineRevision());
        assertEquals("D", copy.lastVerifiedRevision());
        assertEquals(EvolutionStepStatus.SUCCEEDED, plan.step(first).status());
        assertEquals(plan.step(first).repairAuthorizations(), plan.copy().step(first).repairAuthorizations());
        assertEquals(2, plan.steps().size());
        assertNull(plan.step(second).baselineRevision());
    }

    @Test
    void rollbackAfterRepairsRestoresOriginalBaselineAndPreservesEveryFailure() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        plan.startStep(first, copy, policy);
        plan.recordExecution(first, copy, execution("B"));
        plan.failStepVerification(first, copy, confirmedFailure("B"));
        plan.authorizeStepRepair(first, copy, policy, "repair-1", "B", Instant.now());
        plan.recordExecution(first, copy, execution("C"));
        plan.failStepVerification(first, copy, confirmedFailure("C"));
        plan.authorizeStepRepair(first, copy, policy, "repair-2", "C", Instant.now());
        plan.recordExecution(first, copy, execution("D"));
        plan.failStepVerification(first, copy, confirmedFailure("D"));
        EvolutionPlan before = plan.copy();
        assertThrows(EvolutionPlanStateException.class,
                () -> plan.authorizeStepRollback(first, copy, policy, "rollback", "D", "C", Instant.now()));
        assertThrows(EvolutionPlanStateException.class,
                () -> plan.authorizeStepRollback(first, copy, policy, "repair-1", "D", "A", Instant.now()));
        plan.authorizeStepRollback(first, copy, policy, "rollback", "D", "A", Instant.now());
        assertEquals(EvolutionStepStatus.ROLLING_BACK, plan.step(first).status());
        assertEquals("D", copy.currentRevision());
        assertEquals(before.step(first).executionResult(), plan.step(first).executionResult());
        assertEquals(before.step(first).verificationResult(), plan.step(first).verificationResult());
        assertThrows(EvolutionPlanStateException.class,
                () -> plan.authorizeStepRollback(first, copy, policy, "rollback", "D", "A", Instant.now()));
        assertThrows(EvolutionPlanStateException.class, () -> plan.finishStepRollback(first, copy,
                new RollbackResult("other", "A", "A", true, "confirmed", Instant.now())));
        plan.finishStepRollback(first, copy, new RollbackResult("rollback", "A", "A", true, "confirmed", Instant.now()));
        assertEquals(EvolutionStepStatus.FAILED, plan.step(first).status());
        assertEquals("A", copy.currentRevision());
        assertEquals("A", copy.lastVerifiedRevision());
        assertEquals("A", plan.step(first).baselineRevision());
        assertEquals(before.step(first).repairAuthorizations(), plan.copy().step(first).repairAuthorizations());
        assertEquals(before.step(first).verificationResult(), plan.copy().step(first).verificationResult());
        assertThrows(EvolutionPlanStateException.class, () -> plan.confirmStep(second, copy, policy));
        assertThrows(EvolutionPlanStateException.class, () -> plan.startStep(first, copy, policy));
    }

    @Test
    void unknownRollbackBlocksBothAggregatesEvenWhenObservedHeadEqualsBaseline() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        plan.startStep(first, copy, policy);
        plan.recordExecution(first, copy, execution("B"));
        plan.failStepVerification(first, copy, confirmedFailure("B"));
        plan.authorizeStepRollback(first, copy, policy, "rollback", "B", "A", Instant.now());
        plan.finishStepRollback(first, copy, new RollbackResult("rollback", "A", "A", false, "unknown", Instant.now()));
        assertEquals(EvolutionStepStatus.RECOVERY_REQUIRED, plan.copy().step(first).status());
        assertEquals(WorkingCopyStatus.RECOVERY_REQUIRED, copy.status());
        assertEquals("B", copy.currentRevision());
        assertEquals("A", copy.lastVerifiedRevision());
        assertThrows(EvolutionPlanStateException.class,
                () -> plan.authorizeStepRepair(first, copy, policy, "repair", "B", Instant.now()));
        assertThrows(EvolutionPlanStateException.class,
                () -> plan.authorizeStepRollback(first, copy, policy, "again", "B", "A", Instant.now()));
        assertThrows(EvolutionPlanStateException.class, () -> plan.startStep(second, copy, policy));
        assertThrows(EvolutionPlanStateException.class, () -> new RollbackResult("rollback", "A", "B", true, "wrong", Instant.now()));
    }

    @Test
    void rollbackOfLaterStepUsesItsOwnFirstBaselineRatherThanWorkingCopySource() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        plan.startStep(first, copy, policy);
        plan.recordExecution(first, copy, execution("B"));
        plan.succeedStep(first, copy, verification("B", true, List.of(1, 2)));
        plan.confirmStep(second, copy, policy);
        plan.startStep(second, copy, policy);
        plan.recordExecution(second, copy, execution("C"));
        plan.failStepVerification(second, copy, new VerificationResult("C", List.of(new VerificationResult.Check(
                "TEST", "", List.of(1), VerificationResult.Outcome.FAILED, "failed")), false, List.of("failed"), "failed", Instant.now()));
        assertThrows(EvolutionPlanStateException.class,
                () -> plan.authorizeStepRollback(second, copy, policy, "rollback", "C", "A", Instant.now()));
        plan.authorizeStepRollback(second, copy, policy, "rollback", "C", "B", Instant.now());
        plan.finishStepRollback(second, copy, new RollbackResult("rollback", "B", "B", true, "confirmed", Instant.now()));
        assertEquals("A", copy.sourceRevision());
        assertEquals("B", copy.currentRevision());
        assertEquals("B", copy.lastVerifiedRevision());
        assertEquals(EvolutionStepStatus.SUCCEEDED, plan.step(first).status());
    }

    @Test
    void repairRejectsWrongWorkingCopyOrUntrustedBaselineWithoutChangingHistory() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        plan.startStep(first, copy, policy);
        plan.recordExecution(first, copy, execution("B"));
        plan.failStepVerification(first, copy, confirmedFailure("B"));
        for (WorkingCopy invalid : List.of(
                WorkingCopy.reconstitute(new WorkingCopyId("other"), asset, "A", "other", "B", "A", WorkingCopyStatus.EVOLVING),
                WorkingCopy.reconstitute(copy.id(), asset, "A", "managed", "C", "A", WorkingCopyStatus.EVOLVING),
                WorkingCopy.reconstitute(copy.id(), asset, "A", "managed", "B", "B", WorkingCopyStatus.EVOLVING),
                WorkingCopy.reconstitute(copy.id(), asset, "A", "managed", "B", "A", WorkingCopyStatus.RECOVERY_REQUIRED))) {
            assertThrows(EvolutionPlanStateException.class,
                    () -> plan.authorizeStepRepair(first, invalid, policy, "repair", "B", Instant.now()));
        }
        assertEquals(EvolutionStepStatus.VERIFICATION_FAILED, plan.step(first).status());
        assertTrue(plan.step(first).repairAuthorizations().isEmpty());
        assertEquals("A", copy.lastVerifiedRevision());
    }
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void confirmedExecutionFailureRequiresExactSystemRollbackAndPreservesNoCandidate(boolean restored) {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        plan.startStep(first, copy, policy);
        ExecutionFailureResult failure = new ExecutionFailureResult("failure", "A", "A", "APPLY", 128,
                "CONFIRMED_GIT_REJECTION", "A", null, true, List.of(), Instant.now());
        plan.failStepExecution(first, copy, failure);
        assertEquals(EvolutionStepStatus.ROLLING_BACK, plan.copy().step(first).status());
        assertEquals("A", copy.currentRevision());
        assertNull(plan.step(first).executionResult());
        assertNull(plan.step(first).verificationResult());
        assertNull(plan.step(first).rollbackAuthorization());
        assertThrows(EvolutionPlanStateException.class, () -> plan.confirmStep(second, copy, policy));
        assertThrows(EvolutionPlanStateException.class, () -> plan.failStepExecution(first, copy, failure));
        assertThrows(EvolutionPlanStateException.class, () -> plan.finishAutomaticStepRollback(first, copy,
                new AutomaticRollbackResult("stale", "A", null, false, "UNKNOWN", Instant.now())));
        assertThrows(EvolutionPlanStateException.class, () -> new AutomaticRollbackResult("failure", "A", "B", true,
                "FALSE_SUCCESS", Instant.now()));
        plan.finishAutomaticStepRollback(first, copy, new AutomaticRollbackResult("failure", "A", restored ? "A" : null,
                restored, restored ? "EXACT_BASELINE_CONFIRMED" : "NOT_CONFIRMED", Instant.now()));
        assertEquals(restored ? EvolutionStepStatus.FAILED : EvolutionStepStatus.RECOVERY_REQUIRED,
                plan.copy().step(first).status());
        assertEquals(failure, plan.copy().step(first).executionFailureResult());
        assertEquals("A", copy.lastVerifiedRevision());
        assertThrows(EvolutionPlanStateException.class, () -> plan.confirmStep(second, copy, policy));
        assertThrows(EvolutionPlanStateException.class, () -> plan.startStep(first, copy, policy));
    }

    @Test
    void repairExecutionFailureRetainsOriginalBaselineAndAllFailedVerificationHistory() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        plan.startStep(first, copy, policy);
        plan.recordExecution(first, copy, execution("B"));
        plan.failStepVerification(first, copy, new VerificationResult("B", List.of(
                new VerificationResult.Check("BUILD", "", List.of(1), VerificationResult.Outcome.FAILED, "build failed"),
                new VerificationResult.Check("TEST", "", List.of(2), VerificationResult.Outcome.NOT_RUN, "not run")),
                false, List.of("build failed"), "confirmed failure", Instant.now()));
        plan.authorizeStepRepair(first, copy, policy, "repair", "B", Instant.now());
        var history = plan.step(first).repairAuthorizations();
        ExecutionFailureResult failure = new ExecutionFailureResult("failure", "A", "B", "PUBLISH", 128,
                "CONFIRMED_GIT_REJECTION", "B", "tree", true, List.of(), Instant.now());
        plan.failStepExecution(first, copy, failure);
        plan.finishAutomaticStepRollback(first, copy, new AutomaticRollbackResult("failure", "A", "A", true,
                "EXACT_BASELINE_CONFIRMED", Instant.now()));
        assertEquals("A", copy.currentRevision());
        assertEquals("A", plan.step(first).baselineRevision());
        assertEquals(history, plan.copy().step(first).repairAuthorizations());
        assertEquals("B", history.getFirst().previousExecution().candidateRevision());
        assertNull(plan.step(first).executionResult());
    }
    private void restoredExecutionFailure(EvolutionPlan plan, WorkingCopy copy, String failureId) {
        plan.startStep(first, copy, policy);
        plan.failStepExecution(first, copy, new ExecutionFailureResult(failureId, "A", "A", "APPLY_CHECK", 1,
                "CONFIRMED_GIT_REJECTION", "A", null, false, List.of(), Instant.now()));
        plan.finishAutomaticStepRollback(first, copy, new AutomaticRollbackResult(failureId, "A", "A", true,
                "Exact baseline confirmed", Instant.now()));
    }

    @Test
    void retriesPreserveEveryExecutionFailureAndFirstBaselineAcrossMultipleRounds() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        for (int index = 0; index < 3; index++) {
            restoredExecutionFailure(plan, copy, "failure-" + index);
            EvolutionStep failed = plan.copy().step(first);
            plan.authorizeStepRetry(first, copy, policy, "retry-" + index, "failure-" + index, "A", Instant.now());
            assertEquals(EvolutionStepStatus.READY, plan.step(first).status());
            assertEquals("A", plan.step(first).baselineRevision());
            assertEquals("A", plan.step(first).executionStartRevision());
            assertEquals("A", copy.currentRevision());
            assertEquals("A", copy.lastVerifiedRevision());
            assertEquals(failed.executionFailureResult(), plan.step(first).retryAuthorizations()
                    .getLast().previousExecutionFailure());
            assertEquals(failed.automaticRollbackResult(), plan.step(first).retryAuthorizations()
                    .getLast().previousAutomaticRollback());
            assertNull(plan.step(first).executionFailureResult());
            assertEquals(plan.step(first).retryAuthorizations(), plan.copy().step(first).retryAuthorizations());
            assertEquals(EvolutionStepStatus.FAILED, failed.status());
        }
        plan.startStep(first, copy, policy);
        plan.recordExecution(first, copy, execution("B"));
        plan.succeedStep(first, copy, verification("B", true, List.of(1, 2)));
        assertEquals("B", copy.lastVerifiedRevision());
        assertEquals("A", plan.step(first).baselineRevision());
        assertEquals(3, plan.step(first).retryAuthorizations().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.step(first).retryAuthorizations().clear());
    }

    @Test
    void retryArchivesRepairVerificationAndUserRollbackWithoutLosingPriorAuthorization() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        plan.startStep(first, copy, policy);
        plan.recordExecution(first, copy, execution("B"));
        plan.failStepVerification(first, copy, confirmedFailure("B"));
        plan.authorizeStepRepair(first, copy, policy, "repair-old", "B", Instant.now());
        plan.recordExecution(first, copy, execution("C"));
        plan.failStepVerification(first, copy, confirmedFailure("C"));
        plan.authorizeStepRollback(first, copy, policy, "rollback-old", "C", "A", Instant.now());
        plan.finishStepRollback(first, copy, new RollbackResult("rollback-old", "A", "A", true, "restored", Instant.now()));
        EvolutionStep failed = plan.copy().step(first);
        plan.authorizeStepRetry(first, copy, policy, "retry-new", "rollback-old", "A", Instant.now());
        RetryAuthorization retry = plan.step(first).retryAuthorizations().getLast();
        assertEquals(failed.executionResult(), retry.previousExecution());
        assertEquals(failed.verificationResult(), retry.previousVerification());
        assertEquals(failed.repairAuthorizations(), retry.previousRepairs());
        assertEquals(failed.rollbackAuthorization(), retry.previousRollbackAuthorization());
        assertEquals(failed.rollbackResult(), retry.previousRollbackResult());
        assertTrue(plan.step(first).repairAuthorizations().isEmpty());
        plan.startStep(first, copy, policy);
        plan.recordExecution(first, copy, execution("D"));
        plan.failStepVerification(first, copy, confirmedFailure("D"));
        for (String reused : List.of("repair-old", "rollback-old", "retry-new")) {
            assertThrows(EvolutionPlanStateException.class,
                    () -> plan.authorizeStepRepair(first, copy, policy, reused, "D", Instant.now()));
            assertThrows(EvolutionPlanStateException.class,
                    () -> plan.authorizeStepRollback(first, copy, policy, reused, "D", "A", Instant.now()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-baseline", "old-failure", "reused-id", "candidate", "trust-drift", "recovery"})
    void retryRejectsStaleOrUnsafeFactsWithoutChangingLoadedObjects(String fault) {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        restoredExecutionFailure(plan, copy, "failure");
        WorkingCopy supplied = switch (fault) {
            case "candidate" -> WorkingCopy.reconstitute(copy.id(), asset, "A", "managed", "B", "A", WorkingCopyStatus.EVOLVING);
            case "trust-drift" -> WorkingCopy.reconstitute(copy.id(), asset, "A", "managed", "B", "B", WorkingCopyStatus.EVOLVING);
            case "recovery" -> WorkingCopy.reconstitute(copy.id(), asset, "A", "managed", "A", "A", WorkingCopyStatus.RECOVERY_REQUIRED);
            default -> copy;
        };
        assertThrows(EvolutionPlanStateException.class, () -> plan.authorizeStepRetry(first, supplied, policy,
                fault.equals("reused-id") ? "failure" : "new", fault.equals("old-failure") ? "old" : "failure",
                fault.equals("wrong-baseline") ? "B" : "A", Instant.now()));
        assertEquals(EvolutionStepStatus.FAILED, plan.step(first).status());
        assertTrue(plan.step(first).retryAuthorizations().isEmpty());
        assertEquals("A", copy.currentRevision());
        assertEquals("A", plan.step(first).baselineRevision());
    }

    @Test
    void retryUsesTheStepBaselineAfterEarlierVerifiedWorkRatherThanTheSourceRevision() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = WorkingCopy.reconstitute(new WorkingCopyId("copy"), asset, "A", "managed", "B", "B",
                WorkingCopyStatus.EVOLVING);
        plan.confirmStep(first, copy, policy);
        plan.startStep(first, copy, policy);
        plan.failStepExecution(first, copy, new ExecutionFailureResult("failed-at-B", "B", "B", "APPLY_CHECK", 1,
                "CONFIRMED_GIT_REJECTION", "B", null, false, List.of(), Instant.now()));
        plan.finishAutomaticStepRollback(first, copy, new AutomaticRollbackResult("failed-at-B", "B", "B", true,
                "restored", Instant.now()));
        assertThrows(EvolutionPlanStateException.class, () -> plan.authorizeStepRetry(first, copy, policy,
                "retry", "failed-at-B", "A", Instant.now()));
        plan.authorizeStepRetry(first, copy, policy, "retry", "failed-at-B", "B", Instant.now());
        plan.startStep(first, copy, policy);
        assertEquals("A", copy.sourceRevision());
        assertEquals("B", copy.currentRevision());
        assertEquals("B", plan.step(first).baselineRevision());
    }

}
