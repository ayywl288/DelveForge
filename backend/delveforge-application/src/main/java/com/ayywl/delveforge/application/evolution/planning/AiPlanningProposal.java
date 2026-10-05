package com.ayywl.delveforge.application.evolution.planning;

import com.ayywl.delveforge.domain.evolution.CurrentState;
import com.ayywl.delveforge.domain.evolution.TargetState;
import java.util.List;

/**
 * 结构解析后的 AI 候选。Evidence 仍是本次调用的临时引用，尚未还原为领域事实。
 * CurrentState / TargetState 只表达候选内容，构造这些值不代表领域接受。
 */
public record AiPlanningProposal(CurrentState currentState, TargetState targetState,
        List<String> reusableCapabilities, List<String> changes, List<AiPlanningStepProposal> steps,
        List<String> risks, List<String> evidenceReferences) {
    public AiPlanningProposal {
        reusableCapabilities = List.copyOf(reusableCapabilities);
        changes = List.copyOf(changes);
        steps = List.copyOf(steps);
        risks = List.copyOf(risks);
        evidenceReferences = List.copyOf(evidenceReferences);
    }
}
