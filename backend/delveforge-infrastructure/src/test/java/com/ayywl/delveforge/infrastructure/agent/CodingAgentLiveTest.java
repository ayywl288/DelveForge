package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.CodingAgentService;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyInspectionPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.domain.agent.AgentRunState;
import com.ayywl.delveforge.infrastructure.ai.DeepSeekProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_E2E", matches = "true")
class CodingAgentLiveTest {
    @TempDir
    Path temporary;

    @Test
    void actualModelAutonomouslyRepairsSandboxAndFreezesCandidateInSqlite() throws Exception {
        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = temporary;
        Path official = fixture.fixture();
        String baseline = fixture.git(official, "rev-parse", "HEAD");
        byte[] index = Files.readAllBytes(official.resolve(".git/index"));
        String source = Files.readString(official.resolve("src/main/java/example/Counter.java"));
        var database = new SqliteAgentStoreTest();
        database.temporary = temporary;
        database.setup();
        database.sql("UPDATE working_copy SET current_revision='" + baseline + "',last_verified_revision='" + baseline + "'");
        var inspection = mock(WorkingCopyInspectionPort.class);
        doAnswer(invocation -> {
            assertEquals(baseline, fixture.git(official, "rev-parse", "HEAD"));
            assertTrue(fixture.git(official, "status", "--porcelain").isEmpty());
            requireTrustedMetadata(database, baseline);
            return null;
        }).when(inspection).requireTrustedState(any(), any(), anyString());
        var state = CodingAgentServiceTest.eligibility(baseline, official, inspection);
        var policy = new DeterministicRepositorySecretPolicy();
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")), policy);
        var model = new SpringAiAgentModel(new DeepSeekProperties("https://api.deepseek.com",
                System.getenv("DEEPSEEK_API_KEY"), "deepseek-flash", Duration.ofSeconds(90), false,
                Duration.ofSeconds(90), 8192, 1048576, 65536), 8192);
        try (var service = new CodingAgentService(state, database.store, model, sandbox, policy, AgentProtocol.Limits.defaults())) {
            String id = service.start("plan", "step", 21).id();
            var terminal = CodingAgentServiceTest.awaitTerminal(service, id, 1200);
            assertEquals(AgentRunState.SUCCEEDED, terminal.state(), terminal.reason() + " " + service.events(id, 0, 200));
            var candidate = new SqliteAgentStore(database.source).candidate(terminal.candidateId());
            assertEquals(baseline, candidate.baseline());
            assertTrue(candidate.changedPaths().contains("src/main/java/example/Counter.java"));
            assertTrue(candidate.changedPaths().stream().anyMatch(path -> path.startsWith("src/test/")
                    && !path.endsWith("/CounterTest.java")));
            var events = service.events(id, 0, 200);
            assertTrue(events.stream().anyMatch(event -> event.kind().equals("SANDBOX_READY")
                    && event.summary().contains("sha256:") && event.summary().contains("2147483648")));
            assertTrue(events.stream().anyMatch(event -> event.summary().contains("TESTS_FAILED")));
            assertTrue(events.stream().anyMatch(event -> event.summary().contains("TESTS_PASSED")));
            assertTrue(events.stream().anyMatch(event -> event.summary().contains("read_file")));
            assertTrue(terminal.modelCalls() >= 3);
            assertEquals(source, Files.readString(official.resolve("src/main/java/example/Counter.java")));
            assertArrayEquals(index, Files.readAllBytes(official.resolve(".git/index")));
            assertEquals(baseline, fixture.git(official, "rev-parse", "HEAD"));
            assertTrue(fixture.git(official, "status", "--porcelain").isEmpty());
            requireTrustedMetadata(database, baseline);
            // 只输出安全计数与哈希，供验收报告引用；不输出源码、diff或凭据。
            System.out.println("TASK1_LIVE_EVIDENCE run=" + id + " modelCalls=" + terminal.modelCalls()
                    + " toolCalls=" + terminal.toolCalls() + " candidate=" + candidate.id()
                    + " expectedTree=" + candidate.expectedTree() + " sha256=" + candidate.sha256()
                    + " paths=" + candidate.changedPaths().size() + " testEvidence=" + candidate.testEvidence());
        }
    }

    private static void requireTrustedMetadata(SqliteAgentStoreTest database, String baseline) throws SQLException {
        try (var connection = database.source.getConnection(); var statement = connection.createStatement();
                var metadata = statement.executeQuery("SELECT w.current_revision,w.last_verified_revision,s.status "
                        + "FROM working_copy w JOIN evolution_step s ON s.id='step' WHERE w.id='copy'")) {
            assertTrue(metadata.next());
            assertEquals(baseline, metadata.getString(1));
            assertEquals(baseline, metadata.getString(2));
            assertEquals("READY", metadata.getString(3));
        }
    }
}
