package com.ayywl.delveforge.domain.evolution;

import java.time.Instant;
import java.util.List;
import java.util.HashSet;

/** 检查事实不自行赋予信任；Step 对 Candidate 身份及全部 Criteria 的对应证据作最终判断。 */
public record VerificationResult(String candidateRevision, List<Check> checks, boolean passed,
        List<String> failures, String summary, Instant verifiedAt) {
    public VerificationResult {
        candidateRevision = PlanningContent.text(candidateRevision, "verification.candidateRevision");
        checks = List.copyOf(checks);
        failures = PlanningContent.section(failures, "verification.failures", false);
        summary = PlanningContent.text(summary, "verification.summary");
        if (checks.isEmpty() || verifiedAt == null
                || passed != (checks.stream().allMatch(Check::passed) && failures.isEmpty())) {
            throw new EvolutionPlanStateException("Verification facts are incomplete or contradictory");
        }
    }

    public boolean satisfies(List<VerificationCriterion> criteria) {
        HashSet<Integer> covered = new HashSet<>();
        for (Check check : checks) {
            for (Integer index : check.criterionIndexes()) {
                if (index == null || index < 1 || index > criteria.size()
                        || !criteria.get(index - 1).supportedBy(check.action(), check.target())) {
                    return false;
                }
                covered.add(index);
            }
        }
        return passed && !criteria.isEmpty() && covered.size() == criteria.size();
    }

    /** 历史结构检查不赋予标准当前执行资格，也不重新解释历史证据类型。 */
    boolean recordsPassedCoverage(int criterionCount) {
        HashSet<Integer> covered = new HashSet<>();
        for (Check check : checks) {
            for (Integer index : check.criterionIndexes()) {
                if (index == null || index < 1 || index > criterionCount) {
                    return false;
                }
                covered.add(index);
            }
        }
        return passed && criterionCount > 0 && covered.size() == criterionCount;
    }

    public record Check(String action, String target, List<Integer> criterionIndexes, boolean passed, String summary) {
        public Check {
            if (!List.of("BUILD", "TEST", "FOCUSED_TEST", "DIFF_CHECK").contains(action)) {
                throw new EvolutionPlanStateException("Unsupported verification fact");
            }
            target = target == null ? "" : target;
            criterionIndexes = List.copyOf(criterionIndexes);
            summary = PlanningContent.text(summary, "check.summary");
        }
    }
}
