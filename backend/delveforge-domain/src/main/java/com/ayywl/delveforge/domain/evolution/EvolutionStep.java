package com.ayywl.delveforge.domain.evolution;

import java.util.List;

/**
 * EvolutionPlan 内部 Entity；M3 不开放授权或代码修改入口。
 */
public final class EvolutionStep {
    private final EvolutionStepId id;
    private final EvolutionPlanId planId;
    private final String goal;
    private final String scope;
    private final List<String> plannedChanges;
    private final List<String> preconditions;
    private final List<String> verificationCriteria;

    private EvolutionStep(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<String> preconditions, List<String> verificationCriteria) {
        if (id == null || planId == null) {
            throw new IllegalArgumentException("Step identity, owner and definition are required");
        }
        this.id = id;
        this.planId = planId;
        this.goal = PlanningContent.text(goal, "step.goal");
        this.scope = PlanningContent.text(scope, "step.scope");
        this.plannedChanges = PlanningContent.section(plannedChanges, "step.plannedChanges", true);
        this.preconditions = PlanningContent.section(preconditions, "step.preconditions", false);
        this.verificationCriteria = PlanningContent.section(verificationCriteria, "step.verificationCriteria", true);
    }

    static EvolutionStep planned(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<String> preconditions, List<String> verificationCriteria) {
        return new EvolutionStep(id, planId, goal, scope, plannedChanges, preconditions, verificationCriteria);
    }

    /**
     * 从已保存的正式定义还原 M3 Step，不制造新的提案或执行授权。
     */
    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<String> preconditions, List<String> verificationCriteria) {
        return new EvolutionStep(id, planId, goal, scope, plannedChanges, preconditions, verificationCriteria);
    }

    public EvolutionStepId id() {
        return id;
    }

    public EvolutionPlanId planId() {
        return planId;
    }

    public String goal() {
        return goal;
    }

    public String scope() {
        return scope;
    }

    public List<String> plannedChanges() {
        return plannedChanges;
    }

    public List<String> preconditions() {
        return preconditions;
    }

    public List<String> verificationCriteria() {
        return verificationCriteria;
    }

    public EvolutionStepStatus status() {
        return EvolutionStepStatus.PENDING_CONFIRMATION;
    }

    public String baselineRevision() {
        return null;
    }
}
