package com.ayywl.delveforge.application.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCommandDiagnosticsTest {
    @Test
    void surefireFailureHeaderChangesOnlyItsElapsedValue() {
        for (String status : new String[] {"FAILURE", "ERROR"}) {
            String header = "[ERROR] example.SomeTest.method(String)[1] -- Time elapsed: 0.003 s <<< " + status + "!";
            String body = "\n[ERROR] expected Duration: 2 s but was Duration: 3 s\n";
            assertEquals(header.replace("0.003 s", "<duration>") + body,
                    AgentCommandDiagnostics.normalize(header + body));
            String diagnostic = AgentCommandDiagnostics.digest(header + body, "");
            assertNotEquals(diagnostic, AgentCommandDiagnostics.digest(header.replace("method", "other") + body, ""));
            assertNotEquals(diagnostic, AgentCommandDiagnostics.digest(header + body.replace("3 s", "4 s"), ""));
        }
        String assertion = "[ERROR] expected SomeTest.method -- Time elapsed: 0.003 s <<< FAILURE!";
        assertEquals(assertion + "\n", AgentCommandDiagnostics.normalize(assertion));
    }

    @Test
    void assertionDurationsRemainPartOfTheDiagnostic() {
        String first = "[ERROR] expected Duration: 2 s but was Duration: 3 s";
        String second = "[ERROR] expected Duration: 2 s but was Duration: 4 s";
        assertNotEquals(AgentCommandDiagnostics.digest("", first), AgentCommandDiagnostics.digest("", second));
        for (String line : new String[] {first, second, "[ERROR] Duration: 3 s", "[INFO] retry Duration: 3 s"}) {
            assertEquals(line + "\n", AgentCommandDiagnostics.normalize(line));
        }
    }

    @Test
    void testSummaryNormalizesOnlyElapsedTime() {
        String summary = "[ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, "
                + "Time elapsed: 3.2 s <<< FAILURE! -- in ExampleTest";
        assertEquals(AgentCommandDiagnostics.normalize(summary),
                AgentCommandDiagnostics.normalize(summary.replace("3.2 s", "4.7 s")));
        assertNotEquals(AgentCommandDiagnostics.normalize(summary),
                AgentCommandDiagnostics.normalize(summary.replace("Failures: 1", "Failures: 2")));
    }

    @Test
    void timestampPrefixesDurationsAndNumericProgressAreComparisonOnlyNoise() {
        String first = "\u001b[31m[ERROR] [10:11:12.123] dependency missing\u001b[0m\n"
                + "Progress: 20%\rProgress (1): 2/10 kB\nTotal time: 3.21 s\n";
        String second = "[ERROR] [10:15:22.456] dependency missing\n"
                + "Progress: 80%\rProgress (1): 8/10 kB\nTotal time: 1:03 min\n";
        assertEquals(AgentCommandDiagnostics.digest(first, ""), AgentCommandDiagnostics.digest(second, ""));
        assertTrue(first.contains("10:11:12.123"));
    }

    @Test
    void diagnosticNumbersSeverityAndEmbeddedDatesRemainMeaningful() {
        String diagnostic = "[ERROR] expected 2 but got 3 at Source.java:42; deadline 2026-11-08T10:00:01Z\n";
        for (String changed : new String[] {diagnostic.replace("got 3", "got 4"),
                diagnostic.replace("ERROR", "WARN"), diagnostic.replace("java:42", "java:43"),
                diagnostic.replace("10:00:01", "10:00:02")}) {
            assertNotEquals(AgentCommandDiagnostics.digest(diagnostic, ""),
                    AgentCommandDiagnostics.digest(changed, ""));
        }
        assertTrue(AgentCommandDiagnostics.normalize("Progress: failed to read index 3").contains("failed"));
    }
}
