package com.ayywl.delveforge.domain.evolution;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
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
    private final List<VerificationCriterion> verificationCriteria;

    private EvolutionStepStatus status = EvolutionStepStatus.PENDING_CONFIRMATION;
    // 首次进入 EXECUTING 时冻结的可信起点；Candidate 或验证成功均不改写这项历史事实。
    private String baselineRevision;
    private ExecutionResult executionResult;
    private VerificationResult verificationResult;
    // 每次 Repair 开始前追加其授权及失败快照；当前结果可继续推进，历史事实不被替换。
    private List<RepairAuthorization> repairAuthorizations = List.of();

    private EvolutionStep(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<VerificationCriterion> verificationCriteria) {
        if (id == null || planId == null) {
            throw new IllegalArgumentException("Step identity, owner and definition are required");
        }
        this.id = id;
        this.planId = planId;
        this.goal = PlanningContent.text(goal, "step.goal");
        this.scope = PlanningContent.text(scope, "step.scope");
        this.plannedChanges = PlanningContent.section(plannedChanges, "step.plannedChanges", true);
        this.verificationCriteria = PlanningContent.criteria(verificationCriteria);
    }

    static EvolutionStep planned(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<VerificationCriterion> verificationCriteria) {
        return new EvolutionStep(id, planId, goal, scope, plannedChanges, verificationCriteria);
    }

    /**
     * 从已保存的正式定义还原 M3 Step，不制造新的提案或执行授权。
     */
    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<VerificationCriterion> verificationCriteria) {
        return new EvolutionStep(id, planId, goal, scope, plannedChanges, verificationCriteria);
    }

    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<VerificationCriterion> verificationCriteria,
            EvolutionStepStatus status) {
        if (status != EvolutionStepStatus.PENDING_CONFIRMATION && status != EvolutionStepStatus.READY) {
            throw new IllegalArgumentException("Unsupported preparation lifecycle state");
        }
        EvolutionStep step = reconstitute(id, planId, goal, scope, plannedChanges, verificationCriteria);
        step.status = status;
        return step;
    }

    EvolutionStep copy() {
        return reconstitute(id, planId, goal, scope, plannedChanges, verificationCriteria,
                status, baselineRevision, executionResult, verificationResult, repairAuthorizations);
    }

    /** 恢复完整历史检查点；不以当前证据适配规则重新判定已保存的成功事实。 */
    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<VerificationCriterion> verificationCriteria,
            EvolutionStepStatus status, String baseline, ExecutionResult execution, VerificationResult verification) {
        return reconstitute(id, planId, goal, scope, plannedChanges, verificationCriteria,
                status, baseline, execution, verification, List.of());
    }

    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<VerificationCriterion> verificationCriteria,
            EvolutionStepStatus status, String baseline, ExecutionResult execution, VerificationResult verification,
            List<RepairAuthorization> authorizations) {
        EvolutionStep step = reconstitute(id, planId, goal, scope, plannedChanges, verificationCriteria);
        step.baselineRevision = baseline;
        step.repairAuthorizations = List.copyOf(authorizations);
        HashSet<String> ids = new HashSet<>();
        String previousCandidate = baseline;
        for (RepairAuthorization authorization : step.repairAuthorizations) {
            if (!ids.add(authorization.id()) || authorization.candidateRevision().equals(baseline)
                    || authorization.candidateRevision().equals(previousCandidate)
                    || !authorization.previousVerification().recordsFailedCoverage(verificationCriteria.size())) {
                throw new EvolutionPlanStateException("Incomplete or duplicated stored Repair authorization");
            }
            previousCandidate = authorization.candidateRevision();
        }
        boolean prepared = status == EvolutionStepStatus.PENDING_CONFIRMATION || status == EvolutionStepStatus.READY;
        boolean executing = status == EvolutionStepStatus.EXECUTING;
        boolean verifying = status == EvolutionStepStatus.VERIFYING;
        boolean succeeded = status == EvolutionStepStatus.SUCCEEDED;
        boolean failed = status == EvolutionStepStatus.VERIFICATION_FAILED;
        if ((!prepared && !executing && !verifying && !succeeded && !failed)
                || (prepared && (baseline != null || execution != null || verification != null
                        || !step.repairAuthorizations.isEmpty()))
                || (!prepared && (baseline == null || baseline.isBlank()))
                || (executing && (execution != null || verification != null))
                || ((verifying || succeeded || failed)
                        && (execution == null || baseline.equals(execution.candidateRevision())
                                || step.executionStartRevision().equals(execution.candidateRevision())))
                || (verifying && verification != null)
                || (succeeded && (verification == null
                        || !execution.candidateRevision().equals(verification.candidateRevision())
                        || !verification.recordsPassedCoverage(step.verificationCriteria.size())))
                || (failed && (verification == null
                        || !execution.candidateRevision().equals(verification.candidateRevision())
                        || !verification.recordsFailedCoverage(step.verificationCriteria.size())))) {
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
                || baselineRevision.equals(result.candidateRevision())
                || executionStartRevision().equals(result.candidateRevision())) {
            throw new EvolutionPlanStateException("Complete execution must create a new Candidate");
        }
        executionResult = result;
        status = EvolutionStepStatus.VERIFYING;
    }

    void authorizeRepair(String authorizationId, String candidate, Instant authorizedAt) {
        if (status != EvolutionStepStatus.VERIFICATION_FAILED || !candidate.equals(executionResult.candidateRevision())
                || repairAuthorizations.stream().anyMatch(value -> value.id().equals(authorizationId))) {
            throw new EvolutionPlanStateException("Repair requires a new authorization for the current failed Candidate");
        }
        RepairAuthorization authorization = new RepairAuthorization(authorizationId, candidate, authorizedAt,
                executionResult, verificationResult);
        List<RepairAuthorization> history = new ArrayList<>(repairAuthorizations);
        history.add(authorization);
        repairAuthorizations = List.copyOf(history);
        executionResult = null;
        verificationResult = null;
        status = EvolutionStepStatus.EXECUTING;
    }

    /** 当前动作的代码起点；它不替代首次执行冻结的 baselineRevision。 */
    public String executionStartRevision() {
        return repairAuthorizations.isEmpty() ? baselineRevision
                : repairAuthorizations.getLast().candidateRevision();
    }

    public List<RepairAuthorization> repairAuthorizations() {
        return repairAuthorizations;
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

    void requireVerificationFailed(VerificationResult result) {
        if (status != EvolutionStepStatus.VERIFYING || result == null
                || !executionResult.candidateRevision().equals(result.candidateRevision())
                || !result.confirmsFailure(verificationCriteria)) {
            throw new EvolutionPlanStateException("Candidate verification has no confirmed corresponding failure");
        }
    }

    void failVerification(VerificationResult result) {
        requireVerificationFailed(result);
        verificationResult = result;
        status = EvolutionStepStatus.VERIFICATION_FAILED;
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
