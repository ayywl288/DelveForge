package com.ayywl.delveforge.application.port.workspace;

import java.time.Instant;
import java.util.List;

public record CandidateMutationResult(String revision, List<String> changedPaths, List<String> changes,
        List<String> warnings, Instant executedAt) {
    public CandidateMutationResult {
        changedPaths = List.copyOf(changedPaths);
        changes = List.copyOf(changes);
        warnings = List.copyOf(warnings);
    }
}
