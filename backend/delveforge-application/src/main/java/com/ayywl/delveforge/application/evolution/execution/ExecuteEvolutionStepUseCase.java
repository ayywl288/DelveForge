package com.ayywl.delveforge.application.evolution.execution;

import com.ayywl.delveforge.application.evolution.preparation.CodingProposal;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationBasis;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState.Loaded;
import com.ayywl.delveforge.application.evolution.preparation.PrepareEvolutionStepUseCase;
import com.ayywl.delveforge.application.evolution.preparation.ValidatedExecutionProposal;
import com.ayywl.delveforge.application.port.persistence.StepExecutionCommitPort;
import com.ayywl.delveforge.application.port.workspace.AuthorizedStepExecution;
import com.ayywl.delveforge.application.port.workspace.AuthorizedStepRollback;
import com.ayywl.delveforge.application.port.workspace.CandidateMutationResult;
import com.ayywl.delveforge.application.port.workspace.CandidateVerification;
import com.ayywl.delveforge.application.port.workspace.ConfirmedExecutionFailureException;
import com.ayywl.delveforge.application.port.workspace.VerificationFacts;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyRollbackException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceMutationPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRollbackPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceVerificationPort;
import com.ayywl.delveforge.domain.evolution.AutomaticRollbackResult;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;
import com.ayywl.delveforge.domain.evolution.EvolutionStep;
import com.ayywl.delveforge.domain.evolution.EvolutionStepId;
import com.ayywl.delveforge.domain.evolution.ExecutionFailureResult;
import com.ayywl.delveforge.domain.evolution.ExecutionResult;
import com.ayywl.delveforge.domain.evolution.StepExecutionPolicy;
import com.ayywl.delveforge.domain.evolution.VerificationResult;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 编排授权 Step 的 Candidate 修改、验证及可信 revision 推进；生命周期由 Domain 决定。
 * Git 与 SQLite 不组成事务，只保存三个完整检查点；异常时不制造回滚或恢复事实。
 */
public final class ExecuteEvolutionStepUseCase {

    private final PrepareEvolutionStepUseCase preparation;
    private final ExecutionPreparationState state;
    private final StepExecutionPolicy policy;
    private final StepExecutionCommitPort commits;
    private final WorkspaceMutationPort mutation;
    private final WorkspaceVerificationPort verification;
    private final WorkspaceRollbackPort rollback;

    public ExecuteEvolutionStepUseCase(PrepareEvolutionStepUseCase preparation, ExecutionPreparationState state,
            StepExecutionPolicy policy, StepExecutionCommitPort commits, WorkspaceMutationPort mutation,
            WorkspaceVerificationPort verification, WorkspaceRollbackPort rollback) {
        this.preparation = preparation;
        this.state = state;
        this.policy = policy;
        this.commits = commits;
        this.mutation = mutation;
        this.verification = verification;
        this.rollback = rollback;
    }

    public EvolutionPlan execute(EvolutionPlanId planId, EvolutionStepId stepId) {
        return execute(preparation.prepare(planId, stepId));
    }

    public EvolutionPlan execute(EvolutionPlanId planId, EvolutionStepId stepId, String retryAuthorizationId) {
        return retryAuthorizationId == null ? execute(planId, stepId)
                : execute(preparation.prepare(planId, stepId, retryAuthorizationId));
    }

    /** 重新核对提案依据后执行；成功返回已保存的 Plan，异常保留最后完整检查点。 */
    public EvolutionPlan execute(ValidatedExecutionProposal validated) {
        // 准备时通过校验不代表此刻仍可执行；写入前重查受信 revision（A）与证据资格。
        ExecutionPreparationBasis basis = validated.basis();
        if (basis.repairAuthorizationId() != null) {
            throw new EvolutionExecutionException("Repair proposals require their newly authorized Repair call");
        }
        EvolutionStepId id = basis.stepId();
        Loaded loaded = state.load(basis.planId(), id, false);
        if (!basis.equals(loaded.basis(id))) {
            throw new EvolutionExecutionException("Validated proposal basis is stale");
        }

        requireCriterionEvidence(loaded.plan().step(id), validated.proposal());
        verification.requireSupported(request(loaded, validated, basis.expectedRevision()));
        loaded = state.revalidate(loaded, id, false);

        // 检查点 1：在副本上冻结 baseline=A 并保存 EXECUTING，再允许 Workspace 修改。
        // 副本隔离保证提交失败不会污染已加载的领域对象。
        EvolutionPlan plan = loaded.plan().copy();
        WorkingCopy copy = loaded.copy().copy();
        plan.startStep(id, copy, policy);
        commits.commit(loaded.plan(), id, loaded.copy(), loaded.asset(), plan, copy);
        Loaded executing = new Loaded(plan, copy, loaded.asset());
        state.requireCheckpoint(executing, id, basis.expectedRevision());
        return completeExecution(executing, validated);
    }

    /** 明确请求一次 Repair；先保存授权 / 历史及 EXECUTING，再调用 Provider，不重放未知状态。 */
    public EvolutionPlan repair(EvolutionPlanId planId, EvolutionStepId stepId, String candidate, String authorizationId) {
        return repair(planId, stepId, candidate, authorizationId, null);
    }

    public EvolutionPlan repair(EvolutionPlanId planId, EvolutionStepId stepId, String candidate, String authorizationId,
            String retryAuthorizationId) {
        Loaded failed = state.loadRepairCandidate(planId, stepId);
        if (!Objects.equals(failed.plan().step(stepId).retryAuthorizationId(), retryAuthorizationId)) {
            throw new EvolutionExecutionException("Repair belongs to a different Retry authorization");
        }
        PrepareEvolutionStepUseCase.requireSupportedCriteria(failed.plan().step(stepId));
        EvolutionPlan authorized = failed.plan().copy();
        WorkingCopy retained = failed.copy().copy();
        authorized.authorizeStepRepair(stepId, retained, policy, authorizationId, candidate, Instant.now());
        commits.commit(failed.plan(), stepId, failed.copy(), failed.asset(), authorized, retained);
        Loaded executing = new Loaded(authorized, retained, failed.asset());
        ValidatedExecutionProposal validated = preparation.prepareRepair(executing, stepId);
        requireCriterionEvidence(authorized.step(stepId), validated.proposal());
        verification.requireSupported(request(executing, validated, candidate));
        executing = state.revalidateRepair(executing, stepId);
        return completeExecution(executing, validated);
    }

    private EvolutionPlan completeExecution(Loaded executing, ValidatedExecutionProposal validated) {
        ExecutionPreparationBasis basis = validated.basis();
        EvolutionStepId id = basis.stepId();
        EvolutionPlan plan = executing.plan();
        WorkingCopy copy = executing.copy();

        // Candidate B 与受影响路径必须来自实际 mutation；提案声明不能替代执行事实。
        WorkspaceRef original = new WorkspaceRef(executing.asset().location());
        AuthorizedStepExecution capability = basis.repairAuthorizationId() == null
                ? AuthorizedStepExecution.from(plan, id, copy, original, validated)
                : AuthorizedStepExecution.fromRepair(plan, id, copy, original, validated);
        CandidateMutationResult candidate;
        try {
            candidate = mutation.applyCandidate(capability);
        } catch (ConfirmedExecutionFailureException failure) {
            return handleExecutionFailure(executing, id, failure.result());
        }
        if (candidate.revision() == null || candidate.revision().equals(basis.expectedRevision())
                || candidate.changedPaths().size() != new HashSet<>(candidate.changedPaths()).size()
                || !new HashSet<>(candidate.changedPaths())
                        .equals(new HashSet<>(validated.proposal().declaredAffectedPaths()))) {
            throw new EvolutionExecutionException("Actual mutation does not match the validated scope");
        }

        // 检查点 2：只记录完整执行结果，进入 VERIFYING；current=B，lastVerified 仍为 A。
        executing = state.requireCheckpoint(executing, id, candidate.revision());
        EvolutionPlan candidatePlan = executing.plan().copy();
        WorkingCopy candidateCopy = executing.copy().copy();
        candidatePlan.recordExecution(id, candidateCopy, new ExecutionResult(candidate.revision(), candidate.changes(),
                candidate.changedPaths(), candidate.warnings(), List.of(), candidate.executedAt()));
        commits.commit(executing.plan(), id, executing.copy(), executing.asset(), candidatePlan, candidateCopy);

        Loaded verifying = new Loaded(candidatePlan, candidateCopy, executing.asset());
        state.requireCheckpoint(verifying, id, candidate.revision());

        // 验证固定的 B；Workspace 返回实际事实，是否满足 Criteria 仍由 Domain 决定。
        VerificationFacts facts = verification.verify(request(verifying, validated, candidate.revision()));
        VerificationResult result = result(strategy(verifying, validated), candidate.revision(), facts);

        // 检查点 3：明确 FAIL 保存结果并保留 B；只有 Domain 接受 PASS 才推进信任。
        verifying = state.requireCheckpoint(verifying, id, candidate.revision());
        EvolutionPlan completed = verifying.plan().copy();
        WorkingCopy completedCopy = verifying.copy().copy();
        if (result.passed()) {
            completed.succeedStep(id, completedCopy, result);
        } else {
            completed.failStepVerification(id, completedCopy, result);
        }
        commits.commit(verifying.plan(), id, verifying.copy(), verifying.asset(), completed, completedCopy);
        return completed;
    }

    private EvolutionPlan handleExecutionFailure(Loaded executing, EvolutionStepId id, ExecutionFailureResult failure) {
        // 失败诊断只写已保存 EXECUTING 的完整 CAS；当前权限 / 资格只在恢复能力之前重新核对。
        EvolutionPlan failed = executing.plan().copy();
        WorkingCopy retained = executing.copy().copy();
        failed.failStepExecution(id, retained, failure);
        // 诊断与 ROLLING_BACK 必须先落盘；提交失败时不启动任何恢复动作。
        commits.commit(executing.plan(), id, executing.copy(), executing.asset(), failed, retained);
        Loaded rolling = new Loaded(failed, retained, executing.asset());
        String observed = null;
        try {
            rolling = state.requireCheckpointMetadata(rolling, id);
            WorkspaceRollbackPort.RestoredBaseline restored = rollback.restoreBaseline(
                    AuthorizedStepRollback.fromExecutionFailure(rolling.plan(), id, rolling.copy(),
                            new WorkspaceRef(rolling.asset().location())));
            observed = restored.revision();
            if (observed != null && !observed.matches("[0-9a-f]{40}")) {
                observed = null;
            }
            if (!failure.baselineRevision().equals(observed) || restored.restoredAt() == null) {
                throw new EvolutionExecutionException("Automatic rollback did not prove the original baseline");
            }
            rolling = state.requireCheckpoint(rolling, id, failure.baselineRevision());
            return finishAutomaticRollback(rolling, id, new AutomaticRollbackResult(failure.id(),
                    failure.baselineRevision(), observed, true, "EXACT_BASELINE_CONFIRMED", restored.restoredAt()));
        } catch (RuntimeException error) {
            if (error instanceof WorkingCopyRollbackException detail) {
                observed = detail.observedRevision();
            }
            String diagnostic = error instanceof WorkingCopyRollbackException detail
                    ? "RESTORATION_NOT_CONFIRMED:" + detail.phase()
                            + (detail.exitCode() == null ? "" : ":exit=" + detail.exitCode())
                    : "ROLLBACK_NOT_COMMITTED:" + error.getClass().getSimpleName();
            boolean interrupted = Thread.interrupted();
            try {
                return finishAutomaticRollback(rolling, id, new AutomaticRollbackResult(failure.id(),
                        failure.baselineRevision(), observed, false, diagnostic, Instant.now()));
            } catch (RuntimeException checkpointFailure) {
                checkpointFailure.addSuppressed(error);
                throw checkpointFailure;
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private EvolutionPlan finishAutomaticRollback(Loaded rolling, EvolutionStepId id, AutomaticRollbackResult result) {
        EvolutionPlan completed = rolling.plan().copy();
        WorkingCopy copy = rolling.copy().copy();
        completed.finishAutomaticStepRollback(id, copy, result);
        commits.commit(rolling.plan(), id, rolling.copy(), rolling.asset(), completed, copy);
        // 安全阻塞提交可能遇到已失效 Plan；返回数据库真实父状态，不用旧 ACTIVE 快照伪造资格。
        return state.readStoredPlan(completed.id());
    }

    private CandidateVerification request(Loaded loaded, ValidatedExecutionProposal validated, String candidate) {
        return new CandidateVerification(new WorkspaceRef(loaded.copy().location()),
                new WorkspaceRef(loaded.asset().location()),
                loaded.plan().step(validated.basis().stepId()).baselineRevision() == null
                        ? validated.basis().expectedRevision() : loaded.plan().step(validated.basis().stepId()).baselineRevision(),
                candidate, strategy(loaded, validated));
    }

    /** 本 Slice 的保守选择：重跑此前的动作 / 测试身份，再追加新的验证意图，避免缩小失败证据。 */
    private List<CodingProposal.VerificationAction> strategy(Loaded loaded, ValidatedExecutionProposal validated) {
        if (validated.basis().repairAuthorizationId() == null) {
            return validated.proposal().verificationStrategy();
        }
        List<CodingProposal.VerificationAction> combined = new ArrayList<>();
        VerificationResult previous = loaded.plan().step(validated.basis().stepId())
                .repairAuthorizations().getLast().previousVerification();
        for (VerificationResult.Check check : previous.checks()) {
            combined.add(new CodingProposal.VerificationAction(CodingProposal.Action.valueOf(check.action()),
                    check.target(), check.criterionIndexes()));
        }
        combined.addAll(validated.proposal().verificationStrategy());
        LinkedHashMap<String, CodingProposal.VerificationAction> actions = new LinkedHashMap<>();
        for (CodingProposal.VerificationAction action : combined) {
            String identity = action.action().name() + "\n" + action.target();
            CodingProposal.VerificationAction existing = actions.get(identity);
            LinkedHashSet<Integer> indexes = new LinkedHashSet<>(action.criterionIndexes());
            if (existing != null) {
                indexes.addAll(existing.criterionIndexes());
            }
            actions.put(identity, new CodingProposal.VerificationAction(action.action(), action.target(), List.copyOf(indexes)));
        }
        return List.copyOf(actions.values());
    }

    private void requireCriterionEvidence(EvolutionStep step, CodingProposal proposal) {
        // AI 的索引对应只是建议；每项标准的证据类型必须符合既有 Domain 契约。
        HashSet<Integer> supported = new HashSet<>();
        for (CodingProposal.VerificationAction action : proposal.verificationStrategy()) {
            for (Integer index : action.criterionIndexes()) {
                if (index == null || index < 1 || index > step.verificationCriteria().size()
                        || !step.verificationCriteria().get(index - 1).supportedBy(action.action().name(),
                                action.target())) {
                    throw new EvolutionExecutionException("Verification criterion has no supported corresponding evidence");
                }
                supported.add(index);
            }
        }
        if (supported.size() != step.verificationCriteria().size()) {
            throw new EvolutionExecutionException("Verification criteria are not completely supported");
        }
    }

    /** 先核对实际 Candidate 与动作身份，再关联已校验的 Criteria 索引；不在这里赋予软件信任。 */
    private VerificationResult result(List<CodingProposal.VerificationAction> strategy, String candidate, VerificationFacts facts) {
        if (!candidate.equals(facts.candidateRevision())
                || facts.checks().size() != strategy.size()) {
            throw new EvolutionExecutionException("Verification facts do not identify the requested Candidate and checks");
        }

        List<VerificationResult.Check> checks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (int index = 0; index < facts.checks().size(); index++) {
            CodingProposal.VerificationAction intent = strategy.get(index);
            VerificationFacts.Check fact = facts.checks().get(index);
            if (intent.action() != fact.action() || !Objects.equals(intent.target(), fact.target())) {
                throw new EvolutionExecutionException("Verification facts differ from the validated strategy");
            }
            if (fact.outcome() == VerificationResult.Outcome.INCONCLUSIVE) {
                throw new EvolutionExecutionException("Verification outcome is not established");
            }
            checks.add(new VerificationResult.Check(fact.action().name(), fact.target(), intent.criterionIndexes(),
                    fact.outcome(), fact.summary()));
            if (fact.outcome() == VerificationResult.Outcome.FAILED) {
                failures.add("Check " + (index + 1) + " failed: " + fact.action());
            }
        }

        return new VerificationResult(candidate, checks, checks.stream().allMatch(VerificationResult.Check::passed), failures,
                failures.isEmpty() ? "All requested checks passed" : "Candidate checks failed", facts.verifiedAt());
    }
}
