package com.ayywl.delveforge.application.port.workspace;

import java.time.Instant;
import java.util.List;

/** Workspace 返回的实际 Candidate 事实；既不是提案声明，也不代表验证成功。 */
public record CandidateMutationResult(String revision, List<String> changedPaths, List<String> changes,
        List<String> warnings, Instant executedAt) {
    public CandidateMutationResult {
        changedPaths = List.copyOf(changedPaths);
        changes = List.copyOf(changes);
        warnings = List.copyOf(warnings);
    }
}
