package com.ayywl.delveforge.infrastructure.persistence.evolutionplan;

import com.ayywl.delveforge.domain.evolution.VerificationCriterion;
import com.ayywl.delveforge.domain.evolution.VerificationResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @ParameterizedTest
    @ValueSource(strings = {" false", " null", " {}", " []", " 0", " \"extra\"", " true", "\nfalse", " invalid"})
    void trailingTokensAfterAValidCheckpointAreRejected(String suffix) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> StepResultJson.read(LEGACY + suffix, VerificationResult.class));
        assertEquals("Cannot decode stored Step checkpoint", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null false", "null null", "[] []", "[] false"})
    void nullAndArrayPrefixesCannotHideAnotherJsonValue(String value) {
        assertThrows(IllegalStateException.class, () -> StepResultJson.read(value, Object.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "\r\n", " \n\t\r"})
    void oneCompleteValueAllowsLeadingAndTrailingJsonWhitespace(String whitespace) {
        VerificationResult expected = StepResultJson.read(LEGACY, VerificationResult.class);
        assertEquals(expected, StepResultJson.read(whitespace + LEGACY + whitespace, VerificationResult.class));
        assertNull(StepResultJson.read(whitespace + "null" + whitespace, VerificationResult.class));
        assertEquals(List.of(), StepResultJson.read(whitespace + "[]" + whitespace, List.class));
    }

    @Test
    void sqlNullRetainsAbsentCheckpointSemantics() {
        assertNull(StepResultJson.read(null, VerificationResult.class));
    }
}
