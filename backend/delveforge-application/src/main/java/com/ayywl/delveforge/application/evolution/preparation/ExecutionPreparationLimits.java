package com.ayywl.delveforge.application.evolution.preparation;

/** 技术预算；初始发现与扩展共享累计额度，默认值由 Composition Root 配置。 */
public record ExecutionPreparationLimits(int maxExpansionRounds, int maxScoutCalls, int maxFiles,
        int maxFileBytes, int maxTotalBytes, int maxAiRequestBytes, int maxProposalBytes,
        int maxAffectedPaths, int maxVerificationChecks) {
    public ExecutionPreparationLimits {
        if (maxExpansionRounds < 0 || maxScoutCalls <= 0 || maxFiles <= 0 || maxFileBytes <= 0
                || maxTotalBytes < maxFileBytes || maxAiRequestBytes <= 0 || maxProposalBytes <= 0
                || maxAffectedPaths <= 0 || maxVerificationChecks <= 0) {
            throw new IllegalArgumentException("Execution preparation budgets are required and must be bounded");
        }
    }
}
