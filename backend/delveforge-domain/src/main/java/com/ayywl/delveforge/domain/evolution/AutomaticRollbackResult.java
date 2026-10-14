package com.ayywl.delveforge.domain.evolution;

import java.time.Instant;

/** 系统回滚绑定已保存的失败事实；failureId 是关联身份，不是用户授权。 */
public record AutomaticRollbackResult(String failureId, String baselineRevision, String observedRevision,
        boolean restored, String diagnostic, Instant completedAt) {
    public AutomaticRollbackResult {
        failureId = PlanningContent.text(failureId, "automaticRollback.failureId");
        baselineRevision = PlanningContent.text(baselineRevision, "automaticRollback.baseline");
        diagnostic = PlanningContent.text(diagnostic, "automaticRollback.diagnostic");
        if (completedAt == null || (restored && !baselineRevision.equals(observedRevision))) {
            throw new EvolutionPlanStateException("Automatic rollback requires exact baseline proof");
        }
    }
}
