package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.domain.evolution.VerificationCriterion;
import com.ayywl.delveforge.domain.evolution.VerificationResult;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepResultJsonCompatibilityTest {
    private static final String LEGACY = """
        {"candidateRevision":"B","checks":[{"action":"DIFF_CHECK","target":"","criterionIndexes":[1],
        "passed":true,"summary":"checked"}],"passed":true,"failures":[],"summary":"passed",
        "verifiedAt":"2026-01-01T00:00:00Z"}
        """;

    @Test
    void oldM4SuccessJsonRemainsReadable() {
        VerificationResult result = StepResultJson.read(LEGACY, VerificationResult.class);
        assertEquals(VerificationResult.Outcome.PASSED, result.checks().getFirst().outcome());
        assertTrue(result.satisfies(List.of(VerificationCriterion.technical(VerificationCriterion.Kind.DIFF_CHECK_PASSES))));
        assertEquals(result, StepResultJson.read(StepResultJson.write(result), VerificationResult.class));
    }

    @Test
    void oldUnclassifiedNegativeValueDoesNotManufactureConfirmedFailure() {
        VerificationResult result = StepResultJson.read(LEGACY.replace("true", "false"), VerificationResult.class);
        assertEquals(VerificationResult.Outcome.INCONCLUSIVE, result.checks().getFirst().outcome());
        assertFalse(result.confirmsFailure(List.of(VerificationCriterion.technical(VerificationCriterion.Kind.DIFF_CHECK_PASSES))));
    }
}
