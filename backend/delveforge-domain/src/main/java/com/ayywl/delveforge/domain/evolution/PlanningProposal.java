package com.ayywl.delveforge.domain.evolution;

import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import java.util.List;

/**
 * 未被接受的候选内容，不携带身份、生命周期状态或调用内短引用。
 * 它只作为 EvolutionPlanningService 的决策输入；接受后不作为 Aggregate 内容或独立记录保存。
 */
public record PlanningProposal(CurrentState currentState, TargetState targetState,
                               List<String> reusableCapabilities, List<String> changes,
                               List<PlanningStepProposal> steps, List<String> risks,
                               List<EvidenceBasis> evidence) {
    public PlanningProposal {
        if (currentState == null || targetState == null || steps == null || evidence == null) {
            throw new IllegalArgumentException("Planning proposal is incomplete");
        }
        reusableCapabilities = PlanningContent.section(reusableCapabilities, "reusableCapabilities", false);
        changes = PlanningContent.section(changes, "changes", true);
        steps = List.copyOf(steps);
        risks = PlanningContent.section(risks, "risks", false);
        evidence = List.copyOf(evidence);
    }
}
