package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.PreparedWorkspace;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

/** 只替代外部进程结果，实际执行准备守卫与目录补偿；真实 Git 隔离另由集成测试覆盖。 */
@ExtendWith(OutputCaptureExtension.class)
class WorkingCopyProvisioningDiagnosticsTest {

    private static final String REVISION = "a".repeat(40);
    private static final String SENTINEL = "private-provider-and-filename-sentinel";

    @TempDir
    Path temporary;

    @ParameterizedTest
    @CsvSource({"0,false,false", "0,false,true", "0,true,true", "128,false,true", "128,true,true"})
    void statusDiagnosticsPreserveGuardCleanupAndSecretBoundary(
            int statusExit, boolean nonempty, boolean stderrPresent, CapturedOutput logs) throws Exception {
        Path source = Files.createDirectory(temporary.resolve("source-" + SENTINEL)).toRealPath();
        Files.writeString(source.resolve("keep.txt"), "original");
        Path root = temporary.toRealPath().resolve("managed");
        Path copy = root.resolve("copy");
        Files.createDirectories(root.resolve("existing"));
        Files.writeString(root.resolve("existing/keep.txt"), "unrelated");
        String statusOutput = nonempty ? "?? " + SENTINEL + "\n" : "";
        String statusError = stderrPresent ? SENTINEL : "";
        AtomicInteger statusCalls = new AtomicInteger();

        try (var processes = mockConstruction(ProcessBuilder.class, (builder, context) -> {
            @SuppressWarnings("unchecked")
            List<String> command = (List<String>) context.arguments().getFirst();
            String output = "";
            String error = "";
            int exit = 0;
            if (command.contains("--is-inside-work-tree")) {
                output = "true\n";
            } else if (command.contains("--show-toplevel")) {
                output = command.get(2) + "\n";
            } else if (command.contains("rev-parse")) {
                output = REVISION + "\n";
            } else if (command.contains("clone")) {
                Files.createDirectory(copy.resolve(".git"));
            } else if (command.contains("status")) {
                assertEquals(List.of("git", "-C", copy.toString(), "-c", "core.longpaths=true",
                        "status", "--porcelain"), command);
                statusCalls.incrementAndGet();
                output = statusOutput;
                error = statusError;
                exit = statusExit;
            }
            Process process = mock(Process.class);
            when(process.getInputStream()).thenReturn(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
            when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(error.getBytes(StandardCharsets.UTF_8)));
            when(process.waitFor()).thenReturn(exit);
            when(builder.environment()).thenReturn(new HashMap<>());
            when(builder.start()).thenReturn(process);
        })) {
            GitWorkspaceAdapter adapter = new GitWorkspaceAdapter(root);
            if (statusExit == 0 && !nonempty) {
                PreparedWorkspace prepared = adapter.provision(new WorkspaceRef(source.toString()), REVISION, "copy");
                assertEquals(REVISION, prepared.revision());
                assertTrue(Files.exists(copy.resolve(".git/delveforge-preparation")));
                assertFalse(logs.getAll().contains("POST_CHECKOUT_STATUS"));
            } else {
                assertThrows(WorkspaceException.class,
                        () -> adapter.provision(new WorkspaceRef(source.toString()), REVISION, "copy"));
                assertFalse(Files.exists(copy));
                String reason = statusExit == 0 ? "STATUS_OUTPUT_NONEMPTY" : "STATUS_COMMAND_FAILED";
                assertTrue(logs.getAll().contains("phase=POST_CHECKOUT_STATUS result=" + reason));
                assertTrue(logs.getAll().contains("exitCode=" + statusExit + " stdoutBytes="
                        + statusOutput.getBytes(StandardCharsets.UTF_8).length + " stderrPresent=" + stderrPresent));
            }
        }
        assertEquals(1, statusCalls.get());
        assertEquals("original", Files.readString(source.resolve("keep.txt")));
        assertEquals("unrelated", Files.readString(root.resolve("existing/keep.txt")));
        assertFalse(logs.getAll().contains(SENTINEL));
        assertFalse(logs.getAll().contains(temporary.toString()));
    }
}
