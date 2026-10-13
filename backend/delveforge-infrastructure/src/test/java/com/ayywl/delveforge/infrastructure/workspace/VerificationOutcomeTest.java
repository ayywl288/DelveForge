package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.evolution.preparation.CodingProposal.Action;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.domain.evolution.VerificationResult.Outcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationOutcomeTest {
    @TempDir
    Path directory;

    @Test
    void onlyCompletedNegativeChecksBecomeConfirmedFailures() {
        assertEquals(Outcome.FAILED, classify(Action.DIFF_CHECK, 2, "A.java:1: trailing whitespace.", false, true));
        assertEquals(Outcome.FAILED, classify(Action.BUILD, 1, "[INFO] BUILD FAILURE\nCompilation failure", false, true));
        assertEquals(Outcome.FAILED, classify(Action.TEST, 1, "[INFO] BUILD FAILURE\nThere are test failures.", false, true));
        assertEquals(Outcome.PASSED, classify(Action.TEST, 0, "", true, true));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 137, -1073741819})
    void abnormalOrUnclassifiedExitsCannotBeAssumedToBeVerificationFailures(int exit) {
        assertEquals(Outcome.INCONCLUSIVE, classify(Action.BUILD, exit, "", false, true));
        assertEquals(Outcome.INCONCLUSIVE, classify(Action.TEST, exit, "", false, true));
        assertEquals(Outcome.INCONCLUSIVE, classify(Action.DIFF_CHECK, exit, "fatal: cannot read revision", false, true));
    }

    @Test
    void missingExecutionOrEvidenceIsNotAConfirmedFailure() {
        assertEquals(Outcome.INCONCLUSIVE, classify(Action.TEST, 1, "", false, false));
        assertEquals(Outcome.INCONCLUSIVE, classify(Action.TEST, 0, "[INFO] BUILD SUCCESS", false, true));
        assertEquals(Outcome.INCONCLUSIVE, classify(Action.TEST, 1, "[INFO] BUILD FAILURE", false, true));
        assertEquals(Outcome.INCONCLUSIVE, GitWorkspaceVerificationAdapter.completedOutcome(Action.TEST,
                new WorkspaceCommandRunner.Result(1, "[INFO] BUILD FAILURE\nThere are test failures."), false, true, false));
        assertEquals(Outcome.INCONCLUSIVE, classify(Action.TEST, 1,
                "[INFO] BUILD FAILURE\nThere are test failures.\nThe forked VM terminated without properly saying goodbye", false, true));
        assertEquals(Outcome.INCONCLUSIVE, classify(Action.FOCUSED_TEST, 1,
                "No tests matching pattern\n[INFO] BUILD FAILURE", false, true));
        assertEquals(Outcome.INCONCLUSIVE, classify(Action.TEST, 1,
                "No tests were executed\n[INFO] BUILD FAILURE", false, true));
    }

    @Test
    void realTimeoutDoesNotReturnFailedFacts() {
        assertThrows(WorkspaceException.class, () -> WorkspaceCommandRunner.run(directory,
                command("wait"), Duration.ofMillis(100), 4096));
    }

    @Test
    void actualAbruptProcessExitIsInconclusive() {
        WorkspaceCommandRunner.Result result = WorkspaceCommandRunner.run(directory,
                command("halt"), Duration.ofSeconds(10), 4096);
        assertEquals(137, result.exitCode());
        assertEquals(Outcome.INCONCLUSIVE,
                GitWorkspaceVerificationAdapter.completedOutcome(Action.TEST, result, false, true, false));
    }

    @Test
    void interruptionPreservesInterruptAndDoesNotReturnFacts() {
        Thread.currentThread().interrupt();
        try {
            assertThrows(WorkspaceException.class, () -> WorkspaceCommandRunner.run(directory,
                    command("wait"), Duration.ofSeconds(10), 4096));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    private Outcome classify(Action action, int exit, String output, boolean passed, boolean attempted) {
        return GitWorkspaceVerificationAdapter.completedOutcome(action,
                new WorkspaceCommandRunner.Result(exit, output), passed, attempted, true);
    }

    private List<String> command(String mode) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ControlledProcess.class.getName(), mode);
    }

    public static class ControlledProcess {
        public static void main(String[] args) throws InterruptedException {
            if (args[0].equals("halt")) {
                Runtime.getRuntime().halt(137);
            }
            Thread.sleep(10_000);
        }
    }
}
