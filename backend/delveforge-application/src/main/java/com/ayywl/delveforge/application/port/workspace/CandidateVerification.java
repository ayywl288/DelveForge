package com.ayywl.delveforge.application.port.workspace;

import com.ayywl.delveforge.application.evolution.preparation.CodingProposal.VerificationAction;
import java.util.List;

public record CandidateVerification(WorkspaceRef workingCopy, WorkspaceRef originalAsset, String baselineRevision,
        String candidateRevision, List<VerificationAction> strategy) {
    public CandidateVerification { strategy = List.copyOf(strategy); }
}
