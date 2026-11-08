package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.agent.AgentStore;
import com.ayywl.delveforge.application.agent.CodingAgentService;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.domain.agent.Candidate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/** 生产 SQLite、Docker 和启动协调的持久化截面测试；故障注入模拟退出，不声称强杀 OS 进程。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class DockerRetentionRecoveryTest {
    @TempDir
    Path temporary;

    private SqliteAgentStoreTest database;
    private DockerAgentSandboxTest fixture;
    private AgentSandboxProperties properties;
    private DockerAgentSandbox sandbox;
    private Path official;
    private String baseline;
    private AgentProtocol.Run run;

    @BeforeEach
    void setup() throws Exception {
        database = new SqliteAgentStoreTest();
        database.temporary = temporary;
        fixture = new DockerAgentSandboxTest();
        fixture.temporary = temporary;
        official = fixture.fixture();
        baseline = fixture.git(official, "rev-parse", "HEAD");
        database.setup(baseline);
        run = new AgentProtocol.Run(UUID.randomUUID().toString(), "plan", "step", "copy", baseline,
                AgentRunState.CREATED, Instant.now(), null, null, null, false, false, 0, 0, null);
        properties = DockerAgentSandboxTest.properties(temporary.resolve("runs"));
        sandbox = restartedSandbox();
        database.store.reserve(run);
        database.store.advance(run.id(), AgentRunState.CREATED, AgentRunState.PREPARING, null);
        sandbox.prepare(new AgentSandboxPort.Task(run.id(), "plan", "step", "copy", official.toString(),
                "original", baseline, "Counter", "Counter", "repair", 21), AgentProtocol.Limits.defaults(), () -> false);
        database.store.advance(run.id(), AgentRunState.PREPARING, AgentRunState.RUNNING, null);
    }

    private DockerAgentSandbox restartedSandbox() {
        return new DockerAgentSandbox(properties, new DeterministicRepositorySecretPolicy());
    }

    @AfterEach
    void cleanupTestResourcesEvenIfPreparationFails() {
        if (sandbox != null && run != null && Files.exists(properties.root().resolve(run.id()))) {
            sandbox.cleanup(run.id());
        }
    }

    private CodingAgentService service(AgentStore store, AgentSandboxPort adapter) {
        return new CodingAgentService(null, store, (history, tools) -> {
            throw new AssertionError("Restart must never resume the model");
        }, adapter, new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults());
    }

    private Path material() {
        return properties.root().resolve(run.id()).resolve("candidate.diff");
    }

    private Candidate finish() throws Exception {
        assertTrue(sandbox.tool(run.id(), new AgentProtocol.ToolCall("edit", "edit_file",
                new ObjectMapper().writeValueAsString(Map.of("path", "src/main/java/example/Counter.java",
                        "old_text", "return n;", "new_text", "return n + 1;"))), () -> false).contains("PATCH_APPLIED"));
        var checked = new ObjectMapper().readTree(sandbox.tool(run.id(), new AgentProtocol.ToolCall("verify", "exec_command",
                new ObjectMapper().writeValueAsString(Map.of("command", "mvn -B test"))), () -> false));
        assertEquals("COMMAND_SUCCEEDED", checked.path("result").path("classification").asText());
        org.junit.jupiter.api.Assertions.assertNull(FixtureCompletion.submit(sandbox, run.id(), checked.path("result")));
        database.store.advance(run.id(), AgentRunState.RUNNING, AgentRunState.FINALIZING, null);
        Candidate candidate = sandbox.finish(run.id(), () -> false);
        assertEquals(candidate.diff(), Files.readString(material()));
        return candidate;
    }

    private void assertStopped() {
        var result = new AgentProcess().run(List.of("docker", "inspect", "--format", "{{.State.Running}}",
                "df-" + run.id() + "-agent"), null, null, Duration.ofSeconds(20), 8192, () -> false, Map.of());
        assertEquals(0, result.exitCode(), result.diagnostic());
        assertEquals("false", result.text().strip());
    }

    private void assertOfficialUnchanged() {
        assertEquals(baseline, fixture.git(official, "rev-parse", "HEAD"));
        assertTrue(fixture.git(official, "status", "--porcelain").isEmpty());
    }

    @Test
    void failedInsertTerminalCrashAndInterruptedReleasePreserveMaterialOnEveryRestart() throws Exception {
        try {
            Candidate candidate = finish();
            database.sql("CREATE TRIGGER fail_candidate BEFORE INSERT ON agent_candidate "
                    + "BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertEquals("PERSISTENCE_FAILURE", assertThrows(AgentException.class,
                    () -> database.store.freeze(candidate)).code());
            database.store.advance(run.id(), AgentRunState.FINALIZING, AgentRunState.FAILED, "PERSISTENCE_FAILURE");
            assertTrue(new SqliteAgentStore(database.source).get(run.id()).retained());

            DockerAgentSandbox unavailable = spy(restartedSandbox());
            doThrow(new AgentException("DOCKER_UNAVAILABLE", "injected unknown resource state"))
                    .when(unavailable).stop(run.id());
            try (var restart = service(new SqliteAgentStore(database.source), unavailable)) {
                restart.reconcile();
            }
            assertEquals(1, database.store.abandoned().size());
            assertEquals(candidate.diff(), Files.readString(material()));

            SqliteAgentStore interrupted = spy(new SqliteAgentStore(database.source));
            doAnswer(invocation -> {
                assertStopped();
                assertTrue(Files.exists(material()));
                throw new AgentException("PERSISTENCE_FAILURE", "simulated interruption before release commit");
            }).when(interrupted).release(anyString(), anyBoolean());
            try (var restart = service(interrupted, restartedSandbox())) {
                restart.reconcile();
            }
            assertEquals(1, database.store.abandoned().size());
            assertTrue(new SqliteAgentStore(database.source).get(run.id()).retained());
            assertEquals(candidate.diff(), Files.readString(material()));

            try (var restart = service(new SqliteAgentStore(database.source), restartedSandbox())) {
                restart.reconcile();
            }
            assertStopped();
            assertTrue(database.store.abandoned().isEmpty());
            assertTrue(database.store.get(run.id()).retained());
            assertEquals(candidate.diff(), Files.readString(material()));
            assertTrue(Files.exists(material().resolveSibling("candidate-metadata.json")));
            assertEquals("NOT_FOUND", assertThrows(AgentException.class,
                    () -> database.store.candidate(candidate.id())).code());
            assertOfficialUnchanged();
        } finally {
            // 验证完保留事实后，测试夹具才显式销毁自己的临时材料，不是产品启动清理。
            sandbox.cleanup(run.id());
        }
    }

    @Test
    void secondCrashAfterInterruptedCommitStillRetainsOnAnotherRestart() throws Exception {
        try {
            Candidate candidate = finish();
            SqliteAgentStore interrupted = spy(new SqliteAgentStore(database.source));
            doAnswer(invocation -> {
                invocation.callRealMethod();
                assertStopped();
                throw new SimulatedExit();
            }).when(interrupted).advance(run.id(), AgentRunState.FINALIZING, AgentRunState.INTERRUPTED, "PROCESS_RESTART");
            try (var restart = service(interrupted, restartedSandbox())) {
                assertThrows(SimulatedExit.class, restart::reconcile);
            }
            SqliteAgentStore reloaded = new SqliteAgentStore(database.source);
            assertEquals(AgentRunState.INTERRUPTED, reloaded.get(run.id()).state());
            assertTrue(reloaded.get(run.id()).retained());
            assertEquals(1, reloaded.abandoned().size());
            try (var restart = service(reloaded, restartedSandbox())) {
                restart.reconcile();
            }
            assertStopped();
            assertTrue(reloaded.abandoned().isEmpty());
            assertTrue(reloaded.get(run.id()).retained());
            assertEquals(candidate.diff(), Files.readString(material()));
            assertOfficialUnchanged();
        } finally {
            sandbox.cleanup(run.id());
        }
    }

    @Test
    void frozenCandidateSurvivesRestartWhileDisposableSandboxIsRemoved() throws Exception {
        try {
            Candidate candidate = finish();
            database.store.freeze(candidate);
            assertFalse(database.store.get(run.id()).retained());
            try (var restart = service(new SqliteAgentStore(database.source), restartedSandbox())) {
                restart.reconcile();
            }
            try (var remaining = Files.list(properties.root().resolve(run.id()))) {
                assertEquals(List.of("command-logs"), remaining.map(path -> path.getFileName().toString()).toList());
            }
            assertTrue(database.store.abandoned().isEmpty());
            assertEquals(candidate, new SqliteAgentStore(database.source).candidate(candidate.id()));
            assertEquals(AgentRunState.SUCCEEDED, database.store.get(run.id()).state());
            assertOfficialUnchanged();
        } finally {
            sandbox.cleanup(run.id());
        }
    }

    @Test
    void ordinaryFailureCleansResourcesAndAllowsFreshReservation() {
        try {
            database.store.advance(run.id(), AgentRunState.RUNNING, AgentRunState.FAILED, "MODEL_CALL_LIMIT");
            try (var restart = service(new SqliteAgentStore(database.source), restartedSandbox())) {
                restart.reconcile();
            }
            assertFalse(database.store.get(run.id()).retained());
            assertTrue(database.store.abandoned().isEmpty());
            assertFalse(Files.exists(properties.root().resolve(run.id())));
            var retry = new AgentProtocol.Run(UUID.randomUUID().toString(), "plan", "step", "copy", baseline,
                    AgentRunState.CREATED, Instant.now(), null, null, null, false, false, 0, 0, null);
            database.store.reserve(retry);
            assertEquals(AgentRunState.CREATED, database.store.get(retry.id()).state());
            assertOfficialUnchanged();
        } finally {
            sandbox.cleanup(run.id());
        }
    }

    private static final class SimulatedExit extends Error {
    }
}
