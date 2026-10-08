package com.ayywl.delveforge.domain.evolution;

import java.util.List;

/**
 * EvolutionPlan 内部 Entity；授权只能通过 Aggregate Root 建立。
 */
public final class EvolutionStep {

    private final EvolutionStepId id;
    private final EvolutionPlanId planId;
    private final String goal;
    private final String scope;
    private final List<String> plannedChanges;
    private final List<String> preconditions;
    private final List<VerificationCriterion> verificationCriteria;

    private EvolutionStepStatus status = EvolutionStepStatus.PENDING_CONFIRMATION;
    // 首次进入 EXECUTING 时冻结的可信起点；Candidate 或验证成功均不改写这项历史事实。
    private String baselineRevision;
    private ExecutionResult executionResult;
    private VerificationResult verificationResult;

    private EvolutionStep(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<String> preconditions, List<VerificationCriterion> verificationCriteria) {
        if (id == null || planId == null) {
            throw new IllegalArgumentException("Step identity, owner and definition are required");
        }
        this.id = id;
        this.planId = planId;
        this.goal = PlanningContent.text(goal, "step.goal");
        this.scope = PlanningContent.text(scope, "step.scope");
        this.plannedChanges = PlanningContent.section(plannedChanges, "step.plannedChanges", true);
        this.preconditions = PlanningContent.section(preconditions, "step.preconditions", false);
        this.verificationCriteria = PlanningContent.criteria(verificationCriteria);
    }

    static EvolutionStep planned(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<String> preconditions, List<VerificationCriterion> verificationCriteria) {
        return new EvolutionStep(id, planId, goal, scope, plannedChanges, preconditions, verificationCriteria);
    }

    /**
     * 从已保存的正式定义还原 M3 Step，不制造新的提案或执行授权。
     */
    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<String> preconditions, List<VerificationCriterion> verificationCriteria) {
        return new EvolutionStep(id, planId, goal, scope, plannedChanges, preconditions, verificationCriteria);
    }

    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<String> preconditions, List<VerificationCriterion> verificationCriteria,
            EvolutionStepStatus status) {
        if (status != EvolutionStepStatus.PENDING_CONFIRMATION && status != EvolutionStepStatus.READY) {
            throw new IllegalArgumentException("Unsupported preparation lifecycle state");
        }
        EvolutionStep step = reconstitute(id, planId, goal, scope, plannedChanges, preconditions, verificationCriteria);
        step.status = status;
        return step;
    }

    EvolutionStep copy() {
        return reconstitute(id, planId, goal, scope, plannedChanges, preconditions, verificationCriteria,
                status, baselineRevision, executionResult, verificationResult);
    }

    /** 恢复完整历史检查点；不以当前证据适配规则重新判定已保存的成功事实。 */
    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<String> preconditions, List<VerificationCriterion> verificationCriteria,
            EvolutionStepStatus status, String baseline, ExecutionResult execution, VerificationResult verification) {
        EvolutionStep step = reconstitute(id, planId, goal, scope, plannedChanges, preconditions, verificationCriteria);
        boolean prepared = status == EvolutionStepStatus.PENDING_CONFIRMATION || status == EvolutionStepStatus.READY;
        boolean executing = status == EvolutionStepStatus.EXECUTING;
        boolean verifying = status == EvolutionStepStatus.VERIFYING;
        boolean succeeded = status == EvolutionStepStatus.SUCCEEDED;
        if ((!prepared && !executing && !verifying && !succeeded)
                || (prepared && (baseline != null || execution != null || verification != null))
                || (!prepared && (baseline == null || baseline.isBlank()))
                || (executing && (execution != null || verification != null))
                || ((verifying || succeeded) && (execution == null || baseline.equals(execution.candidateRevision())))
                || (verifying && verification != null)
                || (succeeded && (verification == null
                        || !execution.candidateRevision().equals(verification.candidateRevision())
                        || !verification.recordsPassedCoverage(step.verificationCriteria.size())))) {
            throw new EvolutionPlanStateException("Incomplete stored Step checkpoint");
        }
        // 历史完整检查点恢复记录的事实；新的成功转移仍通过 satisfies 校验现行证据资格。
        step.status = status;
        step.baselineRevision = baseline;
        step.executionResult = execution;
        step.verificationResult = verification;
        return step;
    }

    void start(String baseline) {
        if (status != EvolutionStepStatus.READY || baselineRevision != null) {
            throw new EvolutionPlanStateException("Only a READY Step can freeze its first baseline");
        }
        baselineRevision = PlanningContent.text(baseline, "baselineRevision");
        status = EvolutionStepStatus.EXECUTING;
    }

    void recordExecution(ExecutionResult result) {
        if (status != EvolutionStepStatus.EXECUTING || result == null
                || baselineRevision.equals(result.candidateRevision())) {
            throw new EvolutionPlanStateException("Complete execution must create a new Candidate");
        }
        executionResult = result;
        status = EvolutionStepStatus.VERIFYING;
    }

    void requireVerificationAccepted(VerificationResult result) {
        if (status != EvolutionStepStatus.VERIFYING || result == null
                || !executionResult.candidateRevision().equals(result.candidateRevision())
                || !result.satisfies(verificationCriteria)) {
            throw new EvolutionPlanStateException("Candidate verification does not satisfy every Step criterion");
        }
    }

    void succeed(VerificationResult result) {
        requireVerificationAccepted(result);
        verificationResult = result;
        status = EvolutionStepStatus.SUCCEEDED;
    }

    void confirm() {
        if (status != EvolutionStepStatus.PENDING_CONFIRMATION) {
            throw new EvolutionPlanStateException("Only a pending Step can be confirmed");
        }
        status = EvolutionStepStatus.READY;
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

    public List<VerificationCriterion> verificationCriteria() {
        return verificationCriteria;
    }

    public EvolutionStepStatus status() {
        return status;
    }

    public String baselineRevision() {
        return baselineRevision;
    }

    public ExecutionResult executionResult() {
        return executionResult;
    }

    public VerificationResult verificationResult() {
        return verificationResult;
    }
}
