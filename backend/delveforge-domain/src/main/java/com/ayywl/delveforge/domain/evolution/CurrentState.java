package com.ayywl.delveforge.domain.evolution;

import java.util.List;

/**
 * 保留与规划有关的 RepositoryProfile 事实投影，而非整个 Profile。
 * 逐项保留原文，使领域服务能核对事实来源。
 */
public record CurrentState(String summary, List<String> capabilities,
                           List<String> modules, List<String> limitations) {
    public CurrentState {
        summary = PlanningContent.text(summary, "currentState.summary");
        capabilities = PlanningContent.section(capabilities, "currentState.capabilities", false);
        modules = PlanningContent.section(modules, "currentState.modules", false);
        limitations = PlanningContent.section(limitations, "currentState.limitations", false);
    }
}
