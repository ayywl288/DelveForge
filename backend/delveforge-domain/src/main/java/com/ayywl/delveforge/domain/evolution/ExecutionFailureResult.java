package com.ayywl.delveforge.domain.evolution;

import java.time.Instant;
import java.util.List;

/** 已确认的执行失败；保存实际字节 / index 证据，不产生 Candidate 或制造用户回滚授权。 */
public record ExecutionFailureResult(String id, String baselineRevision, String startingRevision,
        String phase, int exitCode, String diagnostic, String observedRevision, String expectedTree,
        boolean diffComplete, List<DiffEntry> diff, Instant failedAt) {
    public ExecutionFailureResult {
        id = PlanningContent.text(id, "executionFailure.id");
        baselineRevision = PlanningContent.text(baselineRevision, "executionFailure.baseline");
        startingRevision = PlanningContent.text(startingRevision, "executionFailure.start");
        phase = PlanningContent.text(phase, "executionFailure.phase");
        diagnostic = PlanningContent.text(diagnostic, "executionFailure.diagnostic");
        diff = List.copyOf(diff);
        if (exitCode <= 0 || failedAt == null || (!diffComplete && !diff.isEmpty())) {
            throw new EvolutionPlanStateException("Execution failure requires confirmed failure facts");
        }
    }

    /** blob 是实读文件的 identity；它不是新的软件 Revision，null 表示该侧文件不存在。 */
    public record DiffEntry(String path, String baselineBlob, String indexEntry, String workingBlob) {
        public DiffEntry {
            path = PlanningContent.text(path, "failureDiff.path");
        }
    }
}
