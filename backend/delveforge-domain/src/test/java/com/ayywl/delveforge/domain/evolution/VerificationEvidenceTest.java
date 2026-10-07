package com.ayywl.delveforge.domain.evolution;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class VerificationEvidenceTest {
    private VerificationResult result(String action, String target) {
        return new VerificationResult("B", List.of(new VerificationResult.Check(action, target, List.of(1),
                true, "Actual check passed")), true, List.of(), "Passed", Instant.now());
    }

    @ParameterizedTest @ValueSource(strings = {"BUILD", "DIFF_CHECK", "TEST", "FOCUSED_TEST"})
    void technicalSuccessAndModelIndexesCannotProveBusinessBehavior(String action) {
        assertFalse(result(action, action.equals("FOCUSED_TEST") ? "UnrelatedTest#value" : "")
                .satisfies(List.of("Reports run on schedule")));
    }

    @ParameterizedTest @ValueSource(strings = {"BUILD", "TEST", "DIFF_CHECK"})
    void explicitTechnicalCriteriaRequireTheirCorrespondingFacts(String action) {
        assertTrue(result(action, "").satisfies(List.of(action + " passes")));
        assertFalse(result(action, "").satisfies(List.of("FOCUSED_TEST ScheduleTest#schedule passes")));
    }

    @ParameterizedTest @ValueSource(strings = {"UnrelatedTest#value", "ScheduleTest#other", "ScheduleTest"})
    void unrelatedTestsCannotSatisfyAnExplicitFocusedTestCriterion(String target) {
        assertFalse(result("FOCUSED_TEST", target)
                .satisfies(List.of("FOCUSED_TEST ScheduleTest#schedule passes")));
        assertTrue(result("FOCUSED_TEST", "ScheduleTest#schedule")
                .satisfies(List.of("FOCUSED_TEST ScheduleTest#schedule passes")));
    }
}
