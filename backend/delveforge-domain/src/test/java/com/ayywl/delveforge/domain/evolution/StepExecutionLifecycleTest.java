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

    private EvolutionPlan plan(List<String> preconditions) {
        return plan(preconditions, List.of(VerificationCriterion.technical(VerificationCriterion.Kind.BUILD_SUCCEEDS), VerificationCriterion.technical(VerificationCriterion.Kind.FULL_TEST_SUITE_PASSES)));
    }
    private EvolutionPlan plan(List<String> preconditions, List<VerificationCriterion> criteria) {
        EvidenceBasis evidence = new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY, "src/A", "source", 0.8, true),
                new RepositoryProfileEvidenceOrigin(new RepositoryProfileId("profile")));
        return EvolutionPlan.reconstitute(id, new ProductDirectionId("direction"), asset, new RepositoryProfileId("profile"),
                new CurrentState("current", List.of(), List.of(), List.of()), new TargetState("problem", "target", "difference"),
                List.of(), List.of("change"), List.of(
                    EvolutionStep.reconstitute(first, id, "goal", "scope", List.of("change"), preconditions, criteria),
                    EvolutionStep.reconstitute(second, id, "goal2", "scope2", List.of("change2"), List.of(), List.of(VerificationCriterion.technical(VerificationCriterion.Kind.FULL_TEST_SUITE_PASSES)))),
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
        EvolutionPlan loaded = plan(List.of());
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
        EvolutionPlan plan = plan(List.of());
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
        EvolutionPlan plan = plan(List.of());
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
        EvolutionPlan plan = plan(List.of(), List.of(VerificationCriterion.unsupported("Reports run on schedule")));
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
                List.of("change"), List.of(), List.of(VerificationCriterion.unsupported("Reports run on schedule")), EvolutionStepStatus.SUCCEEDED,
                "A", execution, result);
        assertEquals(EvolutionStepStatus.SUCCEEDED, historical.status());
        assertEquals(result, historical.verificationResult());
        assertEquals(execution, historical.executionResult());
        assertEquals("A", historical.baselineRevision());
        assertFalse(result.satisfies(historical.verificationCriteria()));
        assertThrows(EvolutionPlanStateException.class, () -> historical.start("B"));
    }
    @Test void unestablishedPreconditionsRejectStartWithoutFreezingOrChangingCopy() {
        EvolutionPlan plan = plan(List.of("external fact"));
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, policy);
        assertThrows(EvolutionPlanStateException.class, () -> plan.startStep(first, copy, policy));
        assertEquals(EvolutionStepStatus.READY, plan.step(first).status());
        assertNull(plan.step(first).baselineRevision());
        assertEquals(WorkingCopyStatus.READY, copy.status());
    }
    @Test void invalidRestoredCheckpointsAndContradictoryVerificationAreRejected() {
        assertThrows(EvolutionPlanStateException.class, () -> EvolutionStep.reconstitute(first, id, "goal", "scope",
                List.of("change"), List.of(), List.of(VerificationCriterion.unsupported("one")), EvolutionStepStatus.VERIFYING, "A", null, null));
        assertThrows(EvolutionPlanStateException.class, () -> EvolutionStep.reconstitute(first, id, "goal", "scope",
                List.of("change"), List.of(), List.of(VerificationCriterion.unsupported("one")), EvolutionStepStatus.READY, "A", null, null));
        assertThrows(EvolutionPlanStateException.class, () -> new VerificationResult("B", List.of(
                new VerificationResult.Check("TEST", "", List.of(1), false, "failed")), true, List.of(), "summary", Instant.now()));
    }

    @Test void historicalSuccessMustStillBeStructurallyCompleteAndRecordPassingFacts() {
        List<VerificationCriterion> criteria = List.of(VerificationCriterion.unsupported("BUILD passes"));
        for (VerificationResult result : List.of(verification("C", true, List.of(1)),
                verification("B", false, List.of(1)), verification("B", true, List.of(2)))) {
            assertThrows(EvolutionPlanStateException.class, () -> EvolutionStep.reconstitute(first, id, "goal", "scope",
                    List.of("change"), List.of(), criteria, EvolutionStepStatus.SUCCEEDED, "A", execution("B"), result));
        }
        assertThrows(EvolutionPlanStateException.class, () -> EvolutionStep.reconstitute(first, id, "goal", "scope",
                List.of("change"), List.of(), criteria, EvolutionStepStatus.SUCCEEDED, null, execution("B"), verification("B", true, List.of(1))));
        assertThrows(EvolutionPlanStateException.class, () -> EvolutionStep.reconstitute(first, id, "goal", "scope",
                List.of("change"), List.of(), criteria, EvolutionStepStatus.SUCCEEDED, "A", null, verification("B", true, List.of(1))));
    }
}
