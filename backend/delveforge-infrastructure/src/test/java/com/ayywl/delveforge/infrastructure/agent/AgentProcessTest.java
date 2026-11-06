package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentProcessTest {
    @Test
    void completedTransportNeverDiscoversDescendantsOfAReusablePid() {
        Process process = mock(Process.class);
        ProcessHandle unrelated = mock(ProcessHandle.class);
        when(process.isAlive()).thenReturn(false);
        when(process.descendants()).thenReturn(java.util.stream.Stream.of(unrelated));
        when(unrelated.destroyForcibly()).thenThrow(new IllegalStateException("synthetic current-process refusal"));
        assertDoesNotThrow(() -> AgentProcess.stop(process));
        verify(process, never()).descendants();
        verify(unrelated, never()).destroyForcibly();
    }

    @Test
    void exitDuringSnapshotCannotAuthorizeAnUnrelatedKill() {
        Process process = mock(Process.class);
        ProcessHandle unrelated = mock(ProcessHandle.class);
        when(process.isAlive()).thenReturn(true, false);
        when(process.descendants()).thenReturn(java.util.stream.Stream.of(unrelated));
        when(unrelated.destroyForcibly()).thenThrow(new IllegalStateException("synthetic current-process refusal"));
        assertDoesNotThrow(() -> AgentProcess.stop(process));
        verify(process, never()).destroyForcibly();
        verify(unrelated, never()).destroyForcibly();
    }

    @Test
    void realTransportKeepsStdoutAndDiagnosticsSeparate() {
        var result = new AgentProcess().run(probe("output"), null, null, Duration.ofSeconds(5), 1024,
                () -> false, Map.of());
        assertEquals(0, result.exitCode());
        assertEquals("fixture-output", result.text());
        assertEquals("fixture-diagnostic", result.diagnostic());
    }

    @Test
    void realPerOperationTimeoutAndCancellationStayEnforced() {
        var transport = new AgentProcess();
        assertEquals("TIME_LIMIT", assertThrows(AgentException.class, () -> transport.run(
                probe("sleep"), null, null, Duration.ofMillis(200), 1024, () -> false, Map.of())).code());
        assertEquals("CANCELLED", assertThrows(AgentException.class, () -> transport.run(
                probe("sleep"), null, null, Duration.ofSeconds(5), 1024, () -> true, Map.of())).code());
    }

    @Test
    void processStartFailureKeepsSafeOsCodeWithoutCommandArguments() {
        AgentException failure = assertThrows(AgentException.class, () -> new AgentProcess().run(
                List.of("delveforge-nonexistent-executable", "sensitive-argument"), null, null,
                Duration.ofSeconds(1), 1024, () -> false, Map.of()));
        assertEquals("PROCESS_START_FAILED", failure.code());
        org.junit.jupiter.api.Assertions.assertTrue(failure.getMessage().matches(
                "Cannot start controlled-process; osError=(?:[0-9]+|UNKNOWN)"));
        org.junit.jupiter.api.Assertions.assertFalse(failure.getMessage().contains("sensitive-argument"));
    }

    private List<String> probe(String mode) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-cp",
                System.getProperty("java.class.path"), Probe.class.getName(), mode);
    }

    public static class Probe {
        public static void main(String[] arguments) throws Exception {
            if (arguments[0].equals("output")) {
                System.out.print("fixture-output");
                System.err.print("fixture-diagnostic");
            } else {
                Thread.sleep(10000);
            }
        }
    }
}
