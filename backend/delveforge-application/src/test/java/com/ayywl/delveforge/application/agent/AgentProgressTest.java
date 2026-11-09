package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.domain.agent.Candidate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProgressTest {
    private final AgentProtocol.ToolCall read = new AgentProtocol.ToolCall("read", "read_file", "{\"path\":\"A.java\"}");
    private final AgentProtocol.ToolCall command = new AgentProtocol.ToolCall("exec", "exec_command",
            "{\"command\":\"mvn test\"}");

    @Test
    void completionComparesReferencesNotProseOrderingOrFirstReportedMismatch() {
        AgentProgress progress = new AgentProgress();
        var json = new ObjectMapper();
        var report = json.createObjectNode().put("summary", "first");
        var feedback = json.createObjectNode().put("classification", "EVIDENCE_INVALID")
                .put("issue", "UNKNOWN_REFERENCE").put("finalSnapshot", "a".repeat(64));
        for (String reference : new String[] {"a", "b", "c"}) {
            report.putArray("executions").add(reference);
            progress.recordCompletion(report, feedback);
            assertFalse(progress.warning());
        }
        report.putArray("executions").add("a").add("b");
        progress.recordCompletion(report, feedback);
        report.putArray("executions").add("b").add("a");
        report.put("summary", "different explanation").putArray("limitations").add("new prose");
        feedback.put("classification", "UNRESOLVED_CHECK_FAILURE").put("issue", "STALE_SOURCE");
        progress.recordCompletion(report, feedback);
        assertTrue(progress.warning());
        report.put("summary", "more prose");
        assertEquals("NO_PROGRESS", assertThrows(AgentException.class,
                () -> progress.recordCompletion(report, feedback)).code());
    }

    @Test
    void timingAndProgressNoiseCannotPreventRepeatedFailureStop() {
        AgentProgress progress = new AgentProgress();
        progress.record(command, observation(1, "dependency unavailable"), false);
        progress.record(command, observation(2, "dependency unavailable"), false);
        assertEquals("NO_PROGRESS", assertThrows(AgentException.class,
                () -> progress.record(command, observation(3, "dependency unavailable"), false)).code());
    }

    @Test
    void duplicateWarningIgnoresTimingButNewErrorClearsIt() {
        AgentProgress progress = new AgentProgress();
        progress.record(command, observation(1, "dependency unavailable"), false);
        assertFalse(progress.warning());
        progress.record(command, observation(2, "dependency unavailable"), false);
        assertTrue(progress.warning());
        progress.record(command, observation(3, "compiler symbol missing"), false);
        assertFalse(progress.warning());
        progress.record(command, observation(4, "compiler symbol missing"), false);
        assertTrue(progress.warning());
        assertThrows(AgentException.class,
                () -> progress.record(command, observation(5, "compiler symbol missing"), false));
    }

    private String observation(int tick, String error) {
        String stdout = "[INFO] Progress (1): " + tick + "/100 kB\r"
                + "[INFO] Total time: " + tick + ".123 s\n"
                + "[INFO] Finished at: 2026-11-08T10:00:0" + tick + "Z\n";
        String stderr = "2026-11-08T10:00:0" + tick + ".123Z [ERROR] " + error + "\n"
                + "Tests run: 1, Failures: 1, Time elapsed: " + tick + ".002 s -- in ExampleTest\n";
        return commandResult(tick, stdout, stderr);
    }

    private String commandResult(int tick, String stdout, String stderr) {
        var response = new ObjectMapper().createObjectNode().put("ok", false);
        response.putObject("result").put("stdout", stdout).put("stderr", stderr)
                .put("diagnosticDigest", Candidate.digest(stdout + "\0" + stderr))
                .put("progressDiagnosticDigest", AgentCommandDiagnostics.digest(stdout, stderr))
                .put("durationMs", tick * 100).put("exitCode", 1).put("invocationId", "call-" + tick);
        return response.toString();
    }

    @Test
    void repeatedSurefireMethodFailureWarnsThenStopsAcrossCompleteCommandOutputs() {
        String output = """
                [INFO] Running SomeTest
                [ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: %1$s s <<< FAILURE! -- in SomeTest
                [ERROR] SomeTest.method -- Time elapsed: %1$s s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected Duration: 2 s but was Duration: 3 s
                    at SomeTest.method(SomeTest.java:42)
                [INFO] BUILD FAILURE
                """;
        AgentProgress progress = new AgentProgress();
        progress.record(command, commandResult(1, output.formatted("0.003"), ""), false);
        assertFalse(progress.warning());
        progress.record(command, commandResult(2, output.formatted("0.004"), ""), false);
        assertTrue(progress.warning());
        assertEquals("NO_PROGRESS", assertThrows(AgentException.class, () ->
                progress.record(command, commandResult(3, output.formatted("0.005"), ""), false)).code());
    }

    @Test
    void changedAssertionDurationBreaksTheLoopUntilTheNewErrorRepeats() {
        AgentProgress progress = new AgentProgress();
        String first = "expected Duration: 2 s but was Duration: 3 s";
        String changed = "expected Duration: 2 s but was Duration: 4 s";
        progress.record(command, observation(1, first), false);
        progress.record(command, observation(2, first), false);
        assertTrue(progress.warning());
        progress.record(command, observation(3, changed), false);
        assertFalse(progress.warning());
        progress.record(command, observation(4, changed), false);
        assertTrue(progress.warning());
        assertEquals("NO_PROGRESS", assertThrows(AgentException.class,
                () -> progress.record(command, observation(5, changed), false)).code());
    }

    @Test
    void identicalObservationsStopEvenWhenDurationsChange() {
        AgentProgress progress = new AgentProgress();
        progress.record(read, "{\"ok\":true,\"durationMs\":1}", false);
        progress.record(read, "{\"durationMs\":2,\"ok\":true}", false);
        assertEquals("NO_PROGRESS", assertThrows(AgentException.class,
                () -> progress.record(read, "{\"ok\":true,\"durationMs\":3}", false)).code());
    }

    @Test
    void successfulMutationResetsRepeatedObservations() {
        AgentProgress progress = new AgentProgress();
        for (int index = 0; index < 100; index++) {
            progress.record(read, "{\"ok\":true}", false);
            progress.record(read, "{\"ok\":true}", false);
            progress.record(new AgentProtocol.ToolCall("edit", "edit_file", "{}"), "{\"ok\":true}", true);
        }
    }

    @Test
    void newFactsContinueWithoutACumulativeReadLimit() {
        AgentProgress progress = new AgentProgress();
        for (int index = 0; index < 200; index++) {
            progress.record(read, "{\"ok\":true,\"result\":{\"content\":\"new fact " + index + "\"}}", false);
        }
    }
    @Test
    void differentShellDiagnosticsAreProgressButIdenticalLoopWarnsThenStops() {
        AgentProgress progress = new AgentProgress();
        var command = new AgentProtocol.ToolCall("exec", "exec_command", "{\"command\":\"sh check.sh\"}");
        for (int index = 0; index < 20; index++) {
            progress.record(command, "{\"ok\":false,\"result\":{\"stderr\":\"missing dependency " + index + "\"}}", false);
        }
        String repeated = "{\"ok\":false,\"result\":{\"stderr\":\"same failure\"}}";
        progress.record(command, repeated, false);
        progress.record(command, repeated, false);
        org.junit.jupiter.api.Assertions.assertTrue(progress.warning());
        assertThrows(AgentException.class, () -> progress.record(command, repeated, false));
    }

    @Test
    void fullDiagnosticDigestDistinguishesChangesBeyondThePreview() {
        AgentProgress progress = new AgentProgress();
        var command = new AgentProtocol.ToolCall("exec", "exec_command", "{\"command\":\"sh check.sh\"}");
        for (int index = 0; index < 10; index++) {
            progress.record(command, "{\"ok\":false,\"result\":{\"stdout\":\"same truncated prefix\","
                    + "\"progressDiagnosticDigest\":\""
                    + AgentCommandDiagnostics.digest("same truncated prefix\nerror " + index, "") + "\"}}", false);
            assertFalse(progress.warning());
        }
    }

    @Test
    void fullDigestOverridesNoiseShiftedPreviewAndLeavesTheRecordUntouched() throws Exception {
        AgentProgress progress = new AgentProgress();
        var json = new ObjectMapper();
        for (int tick = 1; tick <= 3; tick++) {
            var record = json.readTree(observation(tick, "dependency unavailable"));
            var detail = (com.fasterxml.jackson.databind.node.ObjectNode) record.path("result");
            // 完整诊断相同，但时间/进度噪声改变了输出截断位置。
            detail.put("stdout", "noisy preview " + tick).put("outputTruncated", tick % 2 == 0);
            String original = record.toString();
            if (tick < 3) {
                progress.record(command, original, false);
                assertEquals(tick == 2, progress.warning());
            } else {
                assertThrows(AgentException.class, () -> progress.record(command, original, false));
            }
            assertEquals(original, record.toString());
            assertTrue(original.contains("noisy preview " + tick));
        }
    }

    @Test
    void commandsWithoutComparableDigestUseTheSameNormalization() throws Exception {
        AgentProgress progress = new AgentProgress();
        for (int tick = 1; tick <= 3; tick++) {
            var response = new ObjectMapper().readTree(observation(tick, "dependency unavailable"));
            ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("result")).remove("progressDiagnosticDigest");
            if (tick < 3) {
                progress.record(command, response.toString(), false);
            } else {
                assertThrows(AgentException.class, () -> progress.record(command, response.toString(), false));
            }
        }
    }
}
