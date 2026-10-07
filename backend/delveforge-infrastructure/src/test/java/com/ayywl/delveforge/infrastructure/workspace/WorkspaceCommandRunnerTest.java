package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceCommandRunnerTest {
    private List<String> probe(String mode) {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-cp",
                Path.of("target/test-classes").toAbsolutePath().toString(), Probe.class.getName(), mode);
    }
    @Test void outputOverflowAndTimeoutAreFailuresInsteadOfSuccessfulTruncation() {
        WorkspaceException output = assertThrows(WorkspaceException.class, () -> WorkspaceCommandRunner.run(
                Path.of("."), probe("output"), Duration.ofSeconds(5), 1024));
        assertTrue(output.getMessage().contains("output budget"));
        WorkspaceException time = assertThrows(WorkspaceException.class, () -> WorkspaceCommandRunner.run(
                Path.of("."), probe("sleep"), Duration.ofMillis(200), 1024));
        assertTrue(time.getMessage().contains("time budget"));
    }
    public static class Probe {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("output")) {
                System.out.print("bounded fixture".repeat(10000));
            } else {
                Thread.sleep(10000);
            }
        }
    }
}
