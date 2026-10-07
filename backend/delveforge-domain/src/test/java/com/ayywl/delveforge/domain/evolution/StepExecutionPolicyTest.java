package com.ayywl.delveforge.domain.evolution;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.*;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StepExecutionPolicyTest {
    private final StepExecutionPolicy policy = new StepExecutionPolicy();
    private final EvolutionPlanId planId = new EvolutionPlanId("plan");
    private final EvolutionStepId first = new EvolutionStepId("first");
    private final SoftwareAssetId asset = new SoftwareAssetId("asset");

    private EvolutionPlan plan() {
        EvidenceBasis evidence = new EvidenceBasis(
                new Evidence(EvidenceSourceType.REPOSITORY, "src/A", "source", 0.8, true),
                new RepositoryProfileEvidenceOrigin(new RepositoryProfileId("profile")));
        return EvolutionPlan.reconstitute(planId, new ProductDirectionId("direction"), asset,
                new RepositoryProfileId("profile"), new CurrentState("current", List.of(), List.of(), List.of()),
                new TargetState("problem", "target", "difference"), List.of(), List.of("change"),
                List.of(EvolutionStep.reconstitute(first, planId, "goal", "scope", List.of("change"), List.of(), List.of("check")),
                        EvolutionStep.reconstitute(new EvolutionStepId("second"), planId, "goal2", "scope2",
                                List.of("change2"), List.of(), List.of("check2"))),
                List.of(), List.of(evidence), new WorkingCopyId("copy"), EvolutionPlanStatus.ACTIVE);
    }

    private WorkingCopy copy(WorkingCopyStatus status, String current, String verified) {
        return WorkingCopy.reconstitute(new WorkingCopyId("copy"), asset, "A", "managed", current, verified, status);
    }

    @Test void confirmationOnlyChangesChosenStepAndDeepCopyPreservesLoadedAggregate() {
        EvolutionPlan loaded = plan();
        EvolutionPlan candidate = loaded.copy();
        WorkingCopy copy = copy(WorkingCopyStatus.READY, "A", "A");
        candidate.confirmStep(first, copy, policy);
        assertEquals(EvolutionStepStatus.READY, candidate.step(first).status());
        assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, loaded.step(first).status());
        assertEquals(EvolutionStepStatus.PENDING_CONFIRMATION, candidate.steps().get(1).status());
        assertNull(candidate.step(first).baselineRevision());
        assertEquals("A", copy.currentRevision());
        assertEquals("A", copy.lastVerifiedRevision());
        policy.requireExecutionAllowed(candidate, first, copy);
        assertThrows(EvolutionPlanStateException.class, () -> candidate.confirmStep(first, copy, policy));
    }

    @Test void preparationRequiresConfirmation() {
        assertThrows(EvolutionPlanStateException.class, () -> policy.requireExecutionAllowed(plan(), first,
                copy(WorkingCopyStatus.READY, "A", "A")));
    }

    @Test void cannotConfirmOrPrepareLaterRequiredStep() {
        EvolutionPlan plan = plan();
        assertThrows(EvolutionPlanStateException.class, () -> plan.confirmStep(new EvolutionStepId("second"),
                copy(WorkingCopyStatus.READY, "A", "A"), policy));
        assertTrue(plan.steps().stream().allMatch(step -> step.status() == EvolutionStepStatus.PENDING_CONFIRMATION));
    }

    @Test void unverifiedCandidateAndUnavailableCopyFailClosed() {
        for (WorkingCopyStatus status : List.of(WorkingCopyStatus.EVOLVING, WorkingCopyStatus.CLOSED,
                WorkingCopyStatus.RECOVERY_REQUIRED, WorkingCopyStatus.UNRECOVERABLE)) {
            assertThrows(EvolutionPlanStateException.class,
                    () -> plan().confirmStep(first, copy(status, "B", "A"), policy));
        }
    }

    @Test void currentTrustedEvolvingRevisionCanBePreparedWithoutUsingHistoricalSourceRevision() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy(WorkingCopyStatus.EVOLVING, "B", "B");
        plan.confirmStep(first, copy, policy);
        policy.requireExecutionAllowed(plan, first, copy);
        assertNull(plan.step(first).baselineRevision());
        assertEquals("A", copy.sourceRevision());
    }

    @Test void supersededPlanAndForeignStepAreRejected() {
        EvolutionPlan plan = plan();
        plan.supersede();
        assertThrows(EvolutionPlanStateException.class,
                () -> plan.confirmStep(first, copy(WorkingCopyStatus.READY, "A", "A"), policy));
        assertThrows(EvolutionStepNotFoundException.class,
                () -> plan.step(new EvolutionStepId("foreign")));
    }

    @Test void wrongBindingAndSourceAssetAreRejected() {
        WorkingCopy wrong = WorkingCopy.reconstitute(new WorkingCopyId("other"), asset, "A", "managed",
                "A", "A", WorkingCopyStatus.READY);
        assertThrows(EvolutionPlanStateException.class, () -> plan().confirmStep(first, wrong, policy));
        WorkingCopy foreign = WorkingCopy.reconstitute(new WorkingCopyId("copy"), new SoftwareAssetId("other"),
                "A", "managed", "A", "A", WorkingCopyStatus.READY);
        assertThrows(EvolutionPlanStateException.class, () -> plan().confirmStep(first, foreign, policy));
    }
}
