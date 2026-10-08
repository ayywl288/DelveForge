package com.ayywl.delveforge.domain.evolution;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerificationEvidenceTest {
    private VerificationResult result(String action, String target) {
        return new VerificationResult("B", List.of(new VerificationResult.Check(action, target, List.of(1),
                true, "Actual check passed")), true, List.of(), "Passed", Instant.now());
    }

    @ParameterizedTest @ValueSource(strings = {"BUILD", "DIFF_CHECK", "TEST", "FOCUSED_TEST"})
    void technicalSuccessAndModelIndexesCannotProveBusinessBehavior(String action) {
        assertFalse(result(action, action.equals("FOCUSED_TEST") ? "UnrelatedTest#value" : "")
                .satisfies(List.of(VerificationCriterion.unsupported("Reports run on schedule"))));
    }

    @ParameterizedTest @ValueSource(strings = {"BUILD", "TEST", "DIFF_CHECK"})
    void explicitTechnicalCriteriaRequireTheirCorrespondingFacts(String action) {
        VerificationCriterion.Kind kind = switch (action) {
            case "BUILD" -> VerificationCriterion.Kind.BUILD_SUCCEEDS;
            case "TEST" -> VerificationCriterion.Kind.FULL_TEST_SUITE_PASSES;
            default -> VerificationCriterion.Kind.DIFF_CHECK_PASSES;
        };
        assertTrue(result(action, "").satisfies(List.of(VerificationCriterion.technical(kind))));
        assertFalse(result("FOCUSED_TEST", "ReportTest#value")
                .satisfies(List.of(VerificationCriterion.technical(VerificationCriterion.Kind.FULL_TEST_SUITE_PASSES))));
    }

    @Test void structuredWhatDoesNotPrescribeM4TestTargetsAndWrongEvidenceKindsAreRejected() {
        VerificationCriterion selected = VerificationCriterion.technical(VerificationCriterion.Kind.SELECTED_TESTS_PASS);
        assertTrue(result("FOCUSED_TEST", "ReportTest#value").satisfies(List.of(selected)));
        assertTrue(result("TEST", "").satisfies(List.of(selected)));
        assertFalse(result("BUILD", "").satisfies(List.of(selected)));
        assertFalse(result("DIFF_CHECK", "").satisfies(List.of(
                VerificationCriterion.technical(VerificationCriterion.Kind.BUILD_SUCCEEDS))));
        assertFalse(selected.description().contains("ReportTest"));
    }

    @Test void businessSemanticsCannotBeSmuggledIntoATechnicalKind() {
        for (VerificationCriterion.Kind kind : VerificationCriterion.Kind.values()) {
            if (kind != VerificationCriterion.Kind.UNSUPPORTED) {
                assertThrows(IllegalArgumentException.class, () -> new VerificationCriterion(kind, "Reports run on schedule"));
                assertThrows(IllegalArgumentException.class, () -> new VerificationCriterion(kind,
                        kind.description() + "，并且报表按时投递"));
            }
        }
    }
}
