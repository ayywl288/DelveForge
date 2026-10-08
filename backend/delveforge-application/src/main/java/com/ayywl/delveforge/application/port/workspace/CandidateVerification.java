package com.ayywl.delveforge.application.port.workspace;

import com.ayywl.delveforge.application.evolution.preparation.CodingProposal.VerificationAction;

import java.util.List;

/** 验证固定 Candidate 的有限意图；原资产位置用于隔离检查，strategy 不包含任意 shell 命令。 */
public record CandidateVerification(WorkspaceRef workingCopy, WorkspaceRef originalAsset, String baselineRevision,
        String candidateRevision, List<VerificationAction> strategy) {
    public CandidateVerification {
        strategy = List.copyOf(strategy);
    }
}
