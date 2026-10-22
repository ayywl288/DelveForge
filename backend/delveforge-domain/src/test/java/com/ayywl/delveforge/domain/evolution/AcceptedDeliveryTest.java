package com.ayywl.delveforge.domain.evolution;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AcceptedDeliveryTest {
    private static final String A = "a".repeat(40);
    private static final String C = "c".repeat(40);
    private final EvolutionPlanId id = new EvolutionPlanId("plan");
    private final EvolutionStepId first = new EvolutionStepId("first");
    private final EvolutionStepId second = new EvolutionStepId("second");
    private final SoftwareAssetId asset = new SoftwareAssetId("asset");

    private EvolutionPlan plan() {
        var profile = new RepositoryProfileId("profile");
        var basis = new EvidenceBasis(new Evidence(EvidenceSourceType.REPOSITORY, "Counter.java", "source", 1.0, true),
                new RepositoryProfileEvidenceOrigin(profile));
        return EvolutionPlan.reconstitute(id, new ProductDirectionId("direction"), asset, profile,
                new CurrentState("current", List.of(), List.of(), List.of()), new TargetState("problem", "target", "difference"),
                List.of(), List.of("change"), List.of(EvolutionStep.reconstitute(first, id, "first", "counter", List.of("change")),
                        EvolutionStep.reconstitute(second, id, "second", "counter", List.of("change"))),
                List.of(), List.of(basis), new WorkingCopyId("copy"), EvolutionPlanStatus.ACTIVE);
    }

    private WorkingCopy copy() {
        return WorkingCopy.reconstitute(new WorkingCopyId("copy"), asset, A, "managed", A, A, WorkingCopyStatus.READY);
    }

    @Test
    void onlyConfirmedOrderedDeliveryAdvancesTrustAndNextStepUsesAcceptedCommit() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        assertThrows(EvolutionPlanStateException.class, () -> plan.acceptDelivery(first, copy, A, C));
        plan.confirmStep(first, copy, new StepExecutionPolicy());
        assertNull(plan.step(first).baselineRevision());
        assertThrows(EvolutionPlanStateException.class,
                () -> plan.confirmStep(second, copy, new StepExecutionPolicy()));
        plan.acceptDelivery(first, copy, A, C);
        assertEquals(A, plan.step(first).baselineRevision());
        assertEquals(C, plan.step(first).deliveryRevision());
        assertEquals(C, copy.currentRevision());
        assertEquals(C, copy.lastVerifiedRevision());
        plan.confirmStep(second, copy, new StepExecutionPolicy());
        assertThrows(EvolutionPlanStateException.class, () -> plan.acceptDelivery(second, copy, A, "d".repeat(40)));
        plan.acceptDelivery(second, copy, C, "d".repeat(40));
        assertEquals(C, plan.step(second).baselineRevision());
        assertEquals(C, plan.copy().step(first).deliveryRevision());
    }

    @Test
    void rejectedAcceptanceDoesNotPartiallyMutateStepOrCopyAndNoTestContractIsRequired() {
        EvolutionPlan plan = plan();
        WorkingCopy copy = copy();
        plan.confirmStep(first, copy, new StepExecutionPolicy());
        assertThrows(EvolutionPlanStateException.class, () -> plan.acceptDelivery(first, copy, A, "invalid"));
        assertEquals(EvolutionStepStatus.READY, plan.step(first).status());
        assertNull(plan.step(first).deliveryRevision());
        assertEquals(A, copy.currentRevision());
        assertEquals(A, copy.lastVerifiedRevision());
    }
}
