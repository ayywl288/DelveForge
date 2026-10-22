package com.ayywl.delveforge.domain.evolution;

import java.util.List;

/** Step 只拥有确认、顺序及正式接受事实；编码尝试和交付日志具有各自身份。 */
public final class EvolutionStep {
    private final EvolutionStepId id;
    private final EvolutionPlanId planId;
    private final String goal;
    private final String scope;
    private final List<String> plannedChanges;
    private EvolutionStepStatus status;
    private String baselineRevision;
    private String deliveryRevision;

    private EvolutionStep(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> changes) {
        if (id == null || planId == null) {
            throw new IllegalArgumentException("Step identity and owner are required");
        }
        this.id = id;
        this.planId = planId;
        this.goal = PlanningContent.text(goal, "step.goal");
        this.scope = PlanningContent.text(scope, "step.scope");
        this.plannedChanges = PlanningContent.section(changes, "step.plannedChanges", true);
        this.status = EvolutionStepStatus.PENDING_CONFIRMATION;
    }

    static EvolutionStep planned(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> changes) {
        return new EvolutionStep(id, planId, goal, scope, changes);
    }

    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> changes) {
        return new EvolutionStep(id, planId, goal, scope, changes);
    }

    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> changes, EvolutionStepStatus status) {
        return reconstitute(id, planId, goal, scope, changes, status, null, null);
    }

    /** 重建正式事实，不创建授权，不伪造测试通过。旧执行快照由向前迁移归档。 */
    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> changes, EvolutionStepStatus status, String baseline, String revision) {
        EvolutionStep step = new EvolutionStep(id, planId, goal, scope, changes);
        if (status == null || (status == EvolutionStepStatus.SUCCEEDED) != (revision != null)
                || (revision != null && (baseline == null || !baseline.matches("[0-9a-f]{40}")
                || !revision.matches("[0-9a-f]{40}") || baseline.equals(revision)))
                || (status != EvolutionStepStatus.SUCCEEDED && baseline != null)) {
            throw new EvolutionPlanStateException("Stored Step acceptance is incomplete or contradictory");
        }
        step.status = status;
        step.baselineRevision = baseline;
        step.deliveryRevision = revision;
        return step;
    }

    EvolutionStep copy() {
        return reconstitute(id, planId, goal, scope, plannedChanges, status, baselineRevision, deliveryRevision);
    }

    void confirm() {
        if (status != EvolutionStepStatus.PENDING_CONFIRMATION) {
            throw new EvolutionPlanStateException("Only a pending Step can be confirmed");
        }
        status = EvolutionStepStatus.READY;
    }

    void acceptDelivery(String baseline, String revision) {
        if (status != EvolutionStepStatus.READY || baseline == null || !baseline.matches("[0-9a-f]{40}")
                || revision == null || !revision.matches("[0-9a-f]{40}") || baseline.equals(revision)) {
            throw new EvolutionPlanStateException("Delivery acceptance requires a confirmed Step and official commit");
        }
        baselineRevision = baseline;
        deliveryRevision = revision;
        status = EvolutionStepStatus.SUCCEEDED;
    }

    public EvolutionStepId id() { return id; }
    public EvolutionPlanId planId() { return planId; }
    public String goal() { return goal; }
    public String scope() { return scope; }
    public List<String> plannedChanges() { return plannedChanges; }
    public EvolutionStepStatus status() { return status; }
    public String baselineRevision() { return baselineRevision; }
    public String deliveryRevision() { return deliveryRevision; }
}
