package com.ayywl.delveforge.app.api.evolutionplan;
import com.ayywl.delveforge.app.api.evidence.EvidenceBasisPayload;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanStatus;
import com.ayywl.delveforge.domain.evolution.EvolutionStepStatus;
import java.util.List;

public record EvolutionPlanResponse(String id, String productDirectionId, String baseAssetId,
        String baseRepositoryProfileId, String workingCopyId, CurrentStatePayload currentState,
        TargetStatePayload targetState, List<String> reusableCapabilities, List<String> changes,
        List<StepPayload> steps, List<String> risks, List<EvidenceBasisPayload> evidence, EvolutionPlanStatus status) {
    public static EvolutionPlanResponse from(EvolutionPlan plan) {
        var current = plan.currentState();
        var target = plan.targetState();
        return new EvolutionPlanResponse(plan.id().value(), plan.productDirectionId().value(),
                plan.baseAssetId().value(), plan.baseRepositoryProfileId().value(), plan.workingCopyId(),
                new CurrentStatePayload(current.summary(), current.capabilities(), current.modules(), current.limitations()),
                new TargetStatePayload(target.problem(), target.targetProduct(), target.differentiation()),
                plan.reusableCapabilities(), plan.changes(), plan.steps().stream().map(step -> new StepPayload(
                        step.id().value(), step.planId().value(), step.goal(), step.scope(), step.plannedChanges(),
                        step.preconditions(), step.verificationCriteria(), step.status(), step.baselineRevision())).toList(),
                plan.risks(), plan.evidence().stream().map(EvidenceBasisPayload::from).toList(), plan.status());
    }
    public record CurrentStatePayload(String summary, List<String> capabilities, List<String> modules, List<String> limitations) {}
    public record TargetStatePayload(String problem, String targetProduct, String differentiation) {}
    public record StepPayload(String id, String planId, String goal, String scope, List<String> plannedChanges,
            List<String> preconditions, List<String> verificationCriteria, EvolutionStepStatus status, String baselineRevision) {}
}
