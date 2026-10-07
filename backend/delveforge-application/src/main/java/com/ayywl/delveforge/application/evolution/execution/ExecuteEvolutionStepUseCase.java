package com.ayywl.delveforge.application.evolution.execution;

import com.ayywl.delveforge.application.evolution.preparation.*;
import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationState.Loaded;
import com.ayywl.delveforge.application.port.persistence.StepExecutionCommitPort;
import com.ayywl.delveforge.application.port.workspace.*;
import com.ayywl.delveforge.domain.evolution.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** 外部操作与 SQLite 不组成事务；只提交完整业务检查点，异常时不制造补偿事实。 */
public final class ExecuteEvolutionStepUseCase {
    private final PrepareEvolutionStepUseCase preparation;
    private final ExecutionPreparationState state;
    private final StepExecutionPolicy policy;
    private final StepExecutionCommitPort commits;
    private final WorkspaceMutationPort mutation;
    private final WorkspaceVerificationPort verification;

    public ExecuteEvolutionStepUseCase(PrepareEvolutionStepUseCase preparation, ExecutionPreparationState state,
            StepExecutionPolicy policy, StepExecutionCommitPort commits, WorkspaceMutationPort mutation,
            WorkspaceVerificationPort verification) {
        this.preparation = preparation;
        this.state = state;
        this.policy = policy;
        this.commits = commits;
        this.mutation = mutation;
        this.verification = verification;
    }

    public EvolutionPlan execute(EvolutionPlanId planId, EvolutionStepId stepId) {
        return execute(preparation.prepare(planId, stepId));
    }

    public EvolutionPlan execute(ValidatedExecutionProposal validated) {
        ExecutionPreparationBasis basis = validated.basis();
        EvolutionStepId id = basis.stepId();
        Loaded loaded = state.load(basis.planId(), id, false);
        if (!basis.equals(loaded.basis(id))) {
            throw new EvolutionExecutionException("Validated proposal basis is stale");
        }
        requireCriterionEvidence(loaded.plan().step(id), validated.proposal());
        verification.requireSupported(request(loaded, validated, basis.expectedRevision()));
        loaded = state.revalidate(loaded, id, false);

        EvolutionPlan plan = loaded.plan().copy();
        WorkingCopy copy = loaded.copy().copy();
        plan.startStep(id, copy, policy);
        commits.commit(loaded.plan(), id, loaded.copy(), loaded.asset(), plan, copy);
        Loaded executing = new Loaded(plan, copy, loaded.asset());
        state.requireCheckpoint(executing, id, basis.expectedRevision());

        CandidateMutationResult candidate = mutation.applyCandidate(AuthorizedStepExecution.from(plan, id, copy,
                new WorkspaceRef(loaded.asset().location()), validated));
        if (candidate.revision() == null || candidate.revision().equals(basis.expectedRevision())
                || candidate.changedPaths().size() != new HashSet<>(candidate.changedPaths()).size()
                || !new HashSet<>(candidate.changedPaths()).equals(new HashSet<>(validated.proposal().declaredAffectedPaths()))) {
            throw new EvolutionExecutionException("Actual mutation does not match the validated scope");
        }
        executing = state.requireCheckpoint(executing, id, candidate.revision());
        EvolutionPlan candidatePlan = executing.plan().copy();
        WorkingCopy candidateCopy = executing.copy().copy();
        candidatePlan.recordExecution(id, candidateCopy, new ExecutionResult(candidate.revision(), candidate.changes(),
                candidate.changedPaths(), candidate.warnings(), List.of(), candidate.executedAt()));
        commits.commit(executing.plan(), id, executing.copy(), executing.asset(), candidatePlan, candidateCopy);

        Loaded verifying = new Loaded(candidatePlan, candidateCopy, executing.asset());
        state.requireCheckpoint(verifying, id, candidate.revision());
        VerificationFacts facts = verification.verify(request(verifying, validated, candidate.revision()));
        VerificationResult result = result(validated.proposal(), candidate.revision(), facts);
        verifying = state.requireCheckpoint(verifying, id, candidate.revision());
        EvolutionPlan succeeded = verifying.plan().copy();
        WorkingCopy trusted = verifying.copy().copy();
        succeeded.succeedStep(id, trusted, result);
        commits.commit(verifying.plan(), id, verifying.copy(), verifying.asset(), succeeded, trusted);
        return succeeded;
    }

    private CandidateVerification request(Loaded loaded, ValidatedExecutionProposal validated, String candidate) {
        return new CandidateVerification(new WorkspaceRef(loaded.copy().location()), new WorkspaceRef(loaded.asset().location()),
                validated.basis().expectedRevision(), candidate, validated.proposal().verificationStrategy());
    }

    private void requireCriterionEvidence(EvolutionStep step, CodingProposal proposal) {
        HashSet<Integer> supported = new HashSet<>();
        for (CodingProposal.VerificationAction action : proposal.verificationStrategy()) {
            for (Integer index : action.criterionIndexes()) {
                if (index == null || index < 1 || index > step.verificationCriteria().size()
                        || !VerificationCriterion.supportedBy(step.verificationCriteria().get(index - 1),
                                action.action().name(), action.target())) {
                    throw new EvolutionExecutionException("Verification criterion has no supported corresponding evidence");
                }
                supported.add(index);
            }
        }
        if (supported.size() != step.verificationCriteria().size()) {
            throw new EvolutionExecutionException("Verification criteria are not completely supported");
        }
    }

    private VerificationResult result(CodingProposal proposal, String candidate, VerificationFacts facts) {
        if (!candidate.equals(facts.candidateRevision()) || facts.checks().size() != proposal.verificationStrategy().size()) {
            throw new EvolutionExecutionException("Verification facts do not identify the requested Candidate and checks");
        }
        List<VerificationResult.Check> checks = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (int index = 0; index < facts.checks().size(); index++) {
            CodingProposal.VerificationAction intent = proposal.verificationStrategy().get(index);
            VerificationFacts.Check fact = facts.checks().get(index);
            if (intent.action() != fact.action() || !Objects.equals(intent.target(), fact.target())) {
                throw new EvolutionExecutionException("Verification facts differ from the validated strategy");
            }
            checks.add(new VerificationResult.Check(fact.action().name(), fact.target(), intent.criterionIndexes(),
                    fact.passed(), fact.summary()));
            if (!fact.passed()) {
                failures.add("Check " + (index + 1) + " failed: " + fact.action());
            }
        }
        return new VerificationResult(candidate, checks, failures.isEmpty(), failures,
                failures.isEmpty() ? "All requested checks passed" : "Candidate checks failed", facts.verifiedAt());
    }
}
