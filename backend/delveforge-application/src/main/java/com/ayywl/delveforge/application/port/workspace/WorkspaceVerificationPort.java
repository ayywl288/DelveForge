package com.ayywl.delveforge.application.port.workspace;

public interface WorkspaceVerificationPort {
    void requireSupported(CandidateVerification request);
    VerificationFacts verify(CandidateVerification request);
}
