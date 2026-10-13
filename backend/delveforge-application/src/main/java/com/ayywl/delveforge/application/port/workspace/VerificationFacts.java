package com.ayywl.delveforge.application.port.workspace;

import com.ayywl.delveforge.application.evolution.preparation.CodingProposal.Action;
import com.ayywl.delveforge.domain.evolution.VerificationResult.Outcome;

import java.time.Instant;
import java.util.List;

/** 仅返回有界技术事实；不携带原始 stdout / stderr 或领域成功状态。 */
public record VerificationFacts(String candidateRevision, List<Check> checks, Instant verifiedAt) {
    public VerificationFacts {
        checks = List.copyOf(checks);
    }
    public record Check(Action action, String target, boolean passed, String summary, Outcome outcome) {
        public Check(Action action, String target, boolean passed, String summary) {
            this(action, target, passed, summary, null);
        }

        public Check(Action action, String target, Outcome outcome, String summary) {
            this(action, target, outcome == Outcome.PASSED, summary, outcome);
        }

        public Check {
            outcome = outcome == null ? (passed ? Outcome.PASSED : Outcome.INCONCLUSIVE) : outcome;
            if (passed != (outcome == Outcome.PASSED)) {
                throw new IllegalArgumentException("Contradictory verification fact outcome");
            }
        }
    }
}
