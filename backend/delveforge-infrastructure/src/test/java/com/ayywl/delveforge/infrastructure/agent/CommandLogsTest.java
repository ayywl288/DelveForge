package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLogsTest {
    @TempDir Path run;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void terminalOutputIsSanitizedBeforeBoundedPreviewAndPagedRetention() throws Exception {
        String plain = CommandLogs.readable("\u001b[31merror\u001b[0m\rnext\nAuthorization: Bearer private-token-value\n"
                + "x".repeat(10000), new DeterministicRepositorySecretPolicy());
        assertFalse(plain.contains("\u001b"));
        assertFalse(plain.contains("private-token-value"));
        assertTrue(plain.startsWith("error\nnext"));
        assertTrue(CommandLogs.preview(plain, 3000).length() <= 3000);
        String id = UUID.randomUUID().toString();
        assertTrue(CommandLogs.save(run, json.createObjectNode().put("invocationId", id).put("stdout", plain)
                .put("stderr", "diagnostic").put("logDiscarded", true)));
        StringBuilder restored = new StringBuilder();
        int offset = 0;
        do {
            var page = json.readTree(CommandLogs.read(run, id, "stdout", offset, 1000)).path("result");
            restored.append(page.path("content").asText());
            offset = page.path("nextOffset").asInt();
            assertTrue(page.path("logDiscarded").asBoolean());
            if (!page.path("hasMore").asBoolean()) {
                break;
            }
        } while (true);
        assertEquals(plain, restored.toString());
        assertThrows(AgentException.class, () -> CommandLogs.read(run, "../host", "stdout", 0, 1000));
        assertThrows(AgentException.class, () -> CommandLogs.read(run, UUID.randomUUID().toString(), "stdout", 0, 1000));
    }

    @Test
    void runRetentionBudgetDoesNotGrowWithoutBound() throws Exception {
        Files.createDirectories(run.resolve("command-logs"));
        Files.write(run.resolve("command-logs/existing.json"), new byte[CommandLogs.RUN_BYTES]);
        assertFalse(CommandLogs.save(run, json.createObjectNode().put("invocationId", UUID.randomUUID().toString())
                .put("stdout", "more")));
    }

    @Test
    void anOversizedReceiptUpdateKeepsTheAlreadyRetainedDiagnostic() throws Exception {
        String id = UUID.randomUUID().toString();
        var original = json.createObjectNode().put("invocationId", id).put("stdout", "diagnostic");
        assertTrue(CommandLogs.save(run, original));
        assertFalse(CommandLogs.save(run, original.deepCopy().put("details", "x".repeat(CommandLogs.RUN_BYTES))));
        assertEquals("diagnostic", json.readTree(CommandLogs.read(run, id, "stdout", 0, 100))
                .path("result").path("content").asText());
    }
}
