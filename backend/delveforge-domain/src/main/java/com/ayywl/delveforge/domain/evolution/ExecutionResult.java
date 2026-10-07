package com.ayywl.delveforge.domain.evolution;

import java.time.Instant;
import java.util.List;

/** 完整执行产生的实际事实；物理 diff 由 baseline / candidate Git revision 追溯。 */
public record ExecutionResult(String candidateRevision, List<String> changes, List<String> affectedPaths,
        List<String> warnings, List<String> errors, Instant executedAt) {
    public ExecutionResult {
        candidateRevision = PlanningContent.text(candidateRevision, "candidateRevision");
        changes = PlanningContent.section(changes, "execution.changes", true);
        affectedPaths = PlanningContent.section(affectedPaths, "execution.affectedPaths", true);
        warnings = PlanningContent.section(warnings, "execution.warnings", false);
        errors = PlanningContent.section(errors, "execution.errors", false);
        if (executedAt == null || !errors.isEmpty()) {
            throw new EvolutionPlanStateException("Only a complete execution can produce a Candidate");
        }
    }
}
