package com.ayywl.delveforge.domain.evolution;

import java.util.List;

/** 原分析版本与可信准备基线的不可变关系；不承载源码内容或凭据。 */
public record PreparationProvenance(String originalRevision, String preparedRevision, String policyVersion,
        List<String> removedPaths, List<String> metadataPaths) {
    public PreparationProvenance {
        originalRevision = PlanningContent.text(originalRevision, "originalRevision");
        preparedRevision = PlanningContent.text(preparedRevision, "preparedRevision");
        policyVersion = PlanningContent.text(policyVersion, "preparation policy");
        removedPaths = List.copyOf(removedPaths);
        metadataPaths = List.copyOf(metadataPaths);
        if (policyVersion.equals("IDENTITY")) {
            if (!originalRevision.equals(preparedRevision) || !removedPaths.isEmpty() || !metadataPaths.isEmpty()) {
                throw new IllegalArgumentException("Identity preparation must preserve the original revision");
            }
        } else if (!policyVersion.equals("maven-generated-v1") || originalRevision.equals(preparedRevision)
                || removedPaths.isEmpty() || !originalRevision.matches("[0-9a-f]{40}")
                || !preparedRevision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Generated-output preparation requires explicit A/P provenance");
        }
        for (String path : removedPaths) {
            if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
                    || List.of(path.split("/")).stream().anyMatch(part -> part.equals("..") || part.equals(".git"))) {
                throw new IllegalArgumentException("Invalid preparation path");
            }
        }
        if (removedPaths.stream().distinct().count() != removedPaths.size()
                || metadataPaths.stream().anyMatch(path -> !path.equals(".gitignore"))) {
            throw new IllegalArgumentException("Preparation metadata must be narrowly scoped");
        }
    }

    public static PreparationProvenance unchanged(String revision) {
        return new PreparationProvenance(revision, revision, "IDENTITY", List.of(), List.of());
    }
}
