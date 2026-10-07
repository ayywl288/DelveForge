package com.ayywl.delveforge.app.config;

import com.ayywl.delveforge.application.evolution.preparation.ExecutionPreparationLimits;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("delveforge.evolution.preparation")
public record ExecutionPreparationProperties(int maxExpansionRounds, int maxScoutCalls, int maxFiles,
        int maxFileBytes, int maxTotalBytes, int maxAiRequestBytes, int maxProposalBytes,
        int maxAffectedPaths, int maxVerificationChecks) {
    public ExecutionPreparationProperties {
        new ExecutionPreparationLimits(maxExpansionRounds, maxScoutCalls, maxFiles, maxFileBytes,
                maxTotalBytes, maxAiRequestBytes, maxProposalBytes, maxAffectedPaths, maxVerificationChecks);
    }

    public ExecutionPreparationLimits toLimits() {
        return new ExecutionPreparationLimits(maxExpansionRounds, maxScoutCalls, maxFiles, maxFileBytes,
                maxTotalBytes, maxAiRequestBytes, maxProposalBytes, maxAffectedPaths, maxVerificationChecks);
    }
}
