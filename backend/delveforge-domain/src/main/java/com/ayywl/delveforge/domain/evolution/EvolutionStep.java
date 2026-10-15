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
    private List<RetryAuthorization> retryAuthorizations = List.of();
    private RollbackAuthorization rollbackAuthorization;
    private RollbackResult rollbackResult;
    private ExecutionFailureResult executionFailureResult;
    private AutomaticRollbackResult automaticRollbackResult;

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
                status, baselineRevision, executionResult, verificationResult, repairAuthorizations,
                rollbackAuthorization, rollbackResult, executionFailureResult, automaticRollbackResult, retryAuthorizations);
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
        return reconstitute(id, planId, goal, scope, plannedChanges, verificationCriteria, status, baseline,
                execution, verification, authorizations, null, null);
    }

    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<VerificationCriterion> verificationCriteria,
            EvolutionStepStatus status, String baseline, ExecutionResult execution, VerificationResult verification,
            List<RepairAuthorization> authorizations, RollbackAuthorization rollback, RollbackResult rollbackResult) {
        return reconstitute(id, planId, goal, scope, plannedChanges, verificationCriteria, status, baseline,
                execution, verification, authorizations, rollback, rollbackResult, null, null);
    }

    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<VerificationCriterion> verificationCriteria,
            EvolutionStepStatus status, String baseline, ExecutionResult execution, VerificationResult verification,
            List<RepairAuthorization> authorizations, RollbackAuthorization rollback, RollbackResult rollbackResult,
            ExecutionFailureResult failure, AutomaticRollbackResult automaticResult) {
        return reconstitute(id, planId, goal, scope, plannedChanges, verificationCriteria, status, baseline,
                execution, verification, authorizations, rollback, rollbackResult, failure, automaticResult, List.of());
    }

    public static EvolutionStep reconstitute(EvolutionStepId id, EvolutionPlanId planId, String goal, String scope,
            List<String> plannedChanges, List<VerificationCriterion> verificationCriteria,
            EvolutionStepStatus status, String baseline, ExecutionResult execution, VerificationResult verification,
            List<RepairAuthorization> authorizations, RollbackAuthorization rollback, RollbackResult rollbackResult,
            ExecutionFailureResult failure, AutomaticRollbackResult automaticResult, List<RetryAuthorization> retries) {
        EvolutionStep step = reconstitute(id, planId, goal, scope, plannedChanges, verificationCriteria);
        step.retryAuthorizations = List.copyOf(retries);
        HashSet<String> historyIds = new HashSet<>();
        for (RetryAuthorization retry : step.retryAuthorizations) {
            if (!retry.baselineRevision().equals(baseline)) {
                throw new EvolutionPlanStateException("Retry history cannot replace the original baseline");
            }
            reconstitute(id, planId, goal, scope, plannedChanges, verificationCriteria, EvolutionStepStatus.FAILED,
                    baseline, retry.previousExecution(), retry.previousVerification(), retry.previousRepairs(),
                    retry.previousRollbackAuthorization(), retry.previousRollbackResult(),
                    retry.previousExecutionFailure(), retry.previousAutomaticRollback());
            for (RepairAuthorization repair : retry.previousRepairs()) {
                addStoredAuthorization(historyIds, repair.id());
            }
            if (retry.previousRollbackAuthorization() != null) {
                addStoredAuthorization(historyIds, retry.previousRollbackAuthorization().id());
            }
            if (retry.previousExecutionFailure() != null) {
                addStoredAuthorization(historyIds, retry.previousExecutionFailure().id());
            }
            addStoredAuthorization(historyIds, retry.id());
        }
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
        boolean retryReady = status == EvolutionStepStatus.READY && !step.retryAuthorizations.isEmpty();
        if (status == EvolutionStepStatus.PENDING_CONFIRMATION && !step.retryAuthorizations.isEmpty()) {
            throw new EvolutionPlanStateException("Retry cannot manufacture a pending initial confirmation");
        }
        for (RepairAuthorization authorization : step.repairAuthorizations) {
            addStoredAuthorization(historyIds, authorization.id());
        }
        if (rollback != null) {
            addStoredAuthorization(historyIds, rollback.id());
        }
        if (failure != null) {
            addStoredAuthorization(historyIds, failure.id());
        }
        boolean executing = status == EvolutionStepStatus.EXECUTING;
        boolean verifying = status == EvolutionStepStatus.VERIFYING;
        boolean succeeded = status == EvolutionStepStatus.SUCCEEDED;
        boolean failed = status == EvolutionStepStatus.VERIFICATION_FAILED;
        boolean rolling = status == EvolutionStepStatus.ROLLING_BACK;
        boolean rolledBack = status == EvolutionStepStatus.FAILED;
        boolean recovery = status == EvolutionStepStatus.RECOVERY_REQUIRED;
        boolean automatic = failure != null;
        boolean anyRollbackFlow = rolling || rolledBack || recovery;
        boolean rollbackFlow = anyRollbackFlow && !automatic;
        if ((!prepared && !executing && !verifying && !succeeded && !failed && !anyRollbackFlow)
                || (prepared && ((!retryReady && baseline != null) || (retryReady && (baseline == null || baseline.isBlank()))
                        || execution != null || verification != null
                        || !step.repairAuthorizations.isEmpty()))
                || (!prepared && (baseline == null || baseline.isBlank()))
                || (executing && (execution != null || verification != null))
                || ((verifying || succeeded || failed || rollbackFlow)
                        && (execution == null || baseline.equals(execution.candidateRevision())
                                || step.executionStartRevision().equals(execution.candidateRevision())))
                || (verifying && verification != null)
                || (succeeded && (verification == null
                        || !execution.candidateRevision().equals(verification.candidateRevision())
                        || !verification.recordsPassedCoverage(step.verificationCriteria.size())))
                || ((failed || rollbackFlow) && (verification == null
                        || !execution.candidateRevision().equals(verification.candidateRevision())
                        || !verification.recordsFailedCoverage(step.verificationCriteria.size())))
                || (!rollbackFlow && (rollback != null || rollbackResult != null))
                || (rollbackFlow && (rollback == null || !baseline.equals(rollback.baselineRevision())
                        || !execution.candidateRevision().equals(rollback.candidateRevision())
                        || ids.contains(rollback.id())))
                || (rolling && rollbackResult != null)
                || ((rolledBack || recovery) && !automatic && (rollbackResult == null
                        || !rollback.id().equals(rollbackResult.authorizationId())
                        || !baseline.equals(rollbackResult.baselineRevision())
                        || rollbackResult.restored() != rolledBack))
                || (automatic && (!anyRollbackFlow || execution != null || verification != null
                        || !baseline.equals(failure.baselineRevision())
                        || !step.executionStartRevision().equals(failure.startingRevision())
                        || (rolling && automaticResult != null)
                        || ((rolledBack || recovery) && (automaticResult == null
                                || !failure.id().equals(automaticResult.failureId())
                                || !baseline.equals(automaticResult.baselineRevision())
                                || automaticResult.restored() != rolledBack))))
                || (!automatic && automaticResult != null)) {
            throw new EvolutionPlanStateException("Incomplete stored Step checkpoint");
        }
        // 历史完整检查点恢复记录的事实；新的成功转移仍通过 satisfies 校验现行证据资格。
        step.status = status;
        step.baselineRevision = baseline;
        step.executionResult = execution;
        step.verificationResult = verification;
        step.rollbackAuthorization = rollback;
        step.rollbackResult = rollbackResult;
        step.executionFailureResult = failure;
        step.automaticRollbackResult = automaticResult;
        return step;
    }

    /** 只有确认的本次执行失败能进入系统回滚；旧失败快照与 Repair 授权继续保留。 */
    void failExecution(ExecutionFailureResult failure) {
        if (status != EvolutionStepStatus.EXECUTING || executionResult != null || verificationResult != null
                || executionFailureResult != null || failure == null
                || !baselineRevision.equals(failure.baselineRevision())
                || !executionStartRevision().equals(failure.startingRevision()) || authorizationUsed(failure.id())) {
            throw new EvolutionPlanStateException("Execution failure does not match its uncompleted checkpoint");
        }
        executionFailureResult = failure;
        status = EvolutionStepStatus.ROLLING_BACK;
    }

    void requireAutomaticRollbackResult(AutomaticRollbackResult result) {
        if (status != EvolutionStepStatus.ROLLING_BACK || executionFailureResult == null || result == null
                || !executionFailureResult.id().equals(result.failureId())
                || !baselineRevision.equals(result.baselineRevision())) {
            throw new EvolutionPlanStateException("Automatic rollback does not match its recorded failure");
        }
    }

    void finishAutomaticRollback(AutomaticRollbackResult result) {
        requireAutomaticRollbackResult(result);
        automaticRollbackResult = result;
        status = result.restored() ? EvolutionStepStatus.FAILED : EvolutionStepStatus.RECOVERY_REQUIRED;
    }

    public ExecutionFailureResult executionFailureResult() { return executionFailureResult; }

    public AutomaticRollbackResult automaticRollbackResult() { return automaticRollbackResult; }

    void start(String baseline) {
        if (status != EvolutionStepStatus.READY || (baselineRevision != null
                && (retryAuthorizations.isEmpty() || !baselineRevision.equals(baseline)))) {
            throw new EvolutionPlanStateException("READY execution requires its original authorized baseline");
        }
        if (baselineRevision == null) {
            baselineRevision = PlanningContent.text(baseline, "baselineRevision");
        }
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
                || authorizationUsed(authorizationId)) {
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

    /** 在清空本轮槽位前追加完整失败快照；READY 不会自动发起外部操作。 */
    void authorizeRetry(String authorizationId, String failedId, Instant authorizedAt) {
        if (status != EvolutionStepStatus.FAILED || !failedCheckpointId().equals(failedId)
                || authorizationUsed(authorizationId)) {
            throw new EvolutionPlanStateException("Retry requires a new authorization for the current restored failure");
        }
        RetryAuthorization authorization = new RetryAuthorization(authorizationId, failedId, baselineRevision,
                authorizedAt, executionResult, verificationResult, repairAuthorizations, rollbackAuthorization,
                rollbackResult, executionFailureResult, automaticRollbackResult);
        List<RetryAuthorization> history = new ArrayList<>(retryAuthorizations);
        history.add(authorization);
        retryAuthorizations = List.copyOf(history);
        executionResult = null;
        verificationResult = null;
        repairAuthorizations = List.of();
        rollbackAuthorization = null;
        rollbackResult = null;
        executionFailureResult = null;
        automaticRollbackResult = null;
        status = EvolutionStepStatus.READY;
    }

    private static void addStoredAuthorization(HashSet<String> ids, String id) {
        if (!ids.add(id)) {
            throw new EvolutionPlanStateException("Stored action identity is duplicated across Retry history");
        }
    }

    private boolean authorizationUsed(String id) {
        if (id == null || id.isBlank()) {
            return true;
        }
        return repairAuthorizations.stream().anyMatch(value -> value.id().equals(id))
                || rollbackAuthorization != null && rollbackAuthorization.id().equals(id)
                || executionFailureResult != null && executionFailureResult.id().equals(id)
                || retryAuthorizations.stream().anyMatch(value -> value.id().equals(id)
                        || value.failedCheckpointId().equals(id)
                        || value.previousRepairs().stream().anyMatch(repair -> repair.id().equals(id)));
    }

    public String failedCheckpointId() {
        return executionFailureResult != null ? executionFailureResult.id()
                : rollbackAuthorization == null ? null : rollbackAuthorization.id();
    }

    public List<RetryAuthorization> retryAuthorizations() {
        return retryAuthorizations;
    }

    public String retryAuthorizationId() {
        return retryAuthorizations.isEmpty() ? null : retryAuthorizations.getLast().id();
    }

    /** 当前动作的代码起点；它不替代首次执行冻结的 baselineRevision。 */
    public String executionStartRevision() {
        return repairAuthorizations.isEmpty() ? baselineRevision
                : repairAuthorizations.getLast().candidateRevision();
    }

    public List<RepairAuthorization> repairAuthorizations() {
        return repairAuthorizations;
    }

    void authorizeRollback(String authorizationId, String candidate, String baseline, Instant authorizedAt) {
        if (status != EvolutionStepStatus.VERIFICATION_FAILED || rollbackAuthorization != null
                || !baselineRevision.equals(baseline) || !executionResult.candidateRevision().equals(candidate)
                || authorizationUsed(authorizationId)) {
            throw new EvolutionPlanStateException("Rollback requires a new authorization for the current failure");
        }
        rollbackAuthorization = new RollbackAuthorization(authorizationId, candidate, baseline, authorizedAt);
        // 当前失败结果与 Repair 历史继续保留，不用恢复结果替换 VerificationResult。
        status = EvolutionStepStatus.ROLLING_BACK;
    }

    void requireRollbackResult(RollbackResult result) {
        if (status != EvolutionStepStatus.ROLLING_BACK || rollbackAuthorization == null || result == null
                || !rollbackAuthorization.id().equals(result.authorizationId())
                || !baselineRevision.equals(result.baselineRevision())) {
            throw new EvolutionPlanStateException("Rollback result does not match its authorization checkpoint");
        }
    }

    void finishRollback(RollbackResult result) {
        requireRollbackResult(result);
        rollbackResult = result;
        status = result.restored() ? EvolutionStepStatus.FAILED : EvolutionStepStatus.RECOVERY_REQUIRED;
    }

    public RollbackAuthorization rollbackAuthorization() {
        return rollbackAuthorization;
    }

    public RollbackResult rollbackResult() {
        return rollbackResult;
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
