package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentPolicyRejection;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 直接验证在线准备边界，不调用模型，也不修改公共仓库或历史 Run。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class DockerDependencyPreparationTest {
    @TempDir
    Path temporary;

    private final ObjectMapper json = new ObjectMapper();

    private DockerAgentSandbox sandbox() {
        var properties = new AgentSandboxProperties(temporary.resolve("runs"), "docker",
                Map.of(17, System.getenv("DELVEFORGE_AGENT_IMAGE_17"),
                        21, System.getenv("DELVEFORGE_AGENT_IMAGE_21")),
                21, "df-preparation-repair-tests", "df-preparation-repair-tests-cache", "2g", "2", 256);
        return new DockerAgentSandbox(properties, new DeterministicRepositorySecretPolicy());
    }

    private Object session(DockerAgentSandbox sandbox, String id) throws Exception {
        var sessions = DockerAgentSandbox.class.getDeclaredField("sessions");
        sessions.setAccessible(true);
        return ((Map<?, ?>) sessions.get(sandbox)).get(id);
    }

    private Method method(String name) {
        Method method = Arrays.stream(DockerAgentSandbox.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        return method;
    }

    private JsonNode snapshot(DockerAgentSandbox sandbox, String id) throws Exception {
        JsonNode response = (JsonNode) method("invoke").invoke(sandbox, session(sandbox, id), "snapshot",
                json.createObjectNode(), (BooleanSupplier) () -> false);
        assertTrue(response.path("ok").asBoolean());
        return response.path("result");
    }

    private void prepareDependencies(DockerAgentSandbox sandbox, String id) throws Exception {
        try {
            method("prepareDependencies").invoke(sandbox, session(sandbox, id),
                    json.createObjectNode().put("goal", "test"), (BooleanSupplier) () -> false);
            var diagnostic = session(sandbox, id).getClass().getDeclaredField("lastPreparation");
            diagnostic.setAccessible(true);
            assertTrue(diagnostic.get(session(sandbox, id)).toString().contains("BUILD SUCCESS"),
                    "Preparation must actually complete Maven; a failed download is not a passing regression");
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw failure;
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "delveforge.preparation.repository", matches = ".+")
    void h2ErrorTraceAppendDuringMavenIsolatedCheckKeepsSourceAndFrozenEvidence() throws Exception {
        Path retained = Path.of(System.getProperty("delveforge.preparation.repository"));
        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = temporary;
        Path official = fixture.fixture();
        Files.writeString(official.resolve("pom.xml"), DockerAgentSandboxTest.pom(21).replace("</dependencies>",
                "<dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId>"
                        + "<version>2.2.224</version><scope>test</scope></dependency></dependencies>"));
        Files.createDirectories(official.resolve("database"));
        for (String name : new String[]{"taskarium_db.mv.db", "taskarium_db.trace.db"}) {
            Files.copy(retained.resolve("database").resolve(name), official.resolve("database").resolve(name));
        }
        Files.writeString(official.resolve("src/test/java/example/H2TraceTest.java"), """
                package example;
                import java.sql.DriverManager;
                import java.sql.SQLException;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertThrows;
                class H2TraceTest {
                    @Test void expectedFailureWritesAnH2Diagnostic() throws Exception {
                        try (var connection = DriverManager.getConnection(
                                "jdbc:h2:file:./database/taskarium_db", "sa", "")) {
                            assertThrows(SQLException.class, () -> connection.createStatement()
                                    .execute("SELECT * FROM DF_ABSENT_ACCEPTANCE_TABLE_8B742"));
                        }
                    }
                }
                """);
        fixture.git(official, "add", ".");
        fixture.git(official, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "trace fixture");
        String baseline = fixture.git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        DockerAgentSandbox sandbox = sandbox();
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original", baseline,
                    "trace fixture", "trace fixture", "verify runtime trace", 21), AgentProtocol.Limits.defaults(), () -> false);
            var edit = new AgentProtocol.ToolCall(UUID.randomUUID().toString(), "edit_file", json.writeValueAsString(Map.of(
                    "path", "src/main/java/example/Counter.java", "old_text", "return n;", "new_text", "return n + 1;")));
            assertTrue(sandbox.tool(id, edit, () -> false).contains("PATCH_APPLIED"));
            JsonNode before = snapshot(sandbox, id);
            var check = new AgentProtocol.ToolCall(UUID.randomUUID().toString(), "run_checks", "{\"goal\":\"test\"}");
            JsonNode result = json.readTree(sandbox.tool(id, check, () -> false)).path("result");
            assertEquals("TESTS_PASSED", result.path("classification").asText());
            assertEquals(2, result.path("executedTests").asInt());
            assertTrue(result.path("runtimeChanges").toString().contains("H2_TRACE_APPEND"));
            assertEquals(before.path("snapshot").asText(), result.path("snapshot").asText());
            assertEquals(before, snapshot(sandbox, id));
            var candidate = sandbox.finish(id, () -> false);
            assertTrue(candidate.testEvidence().contains("H2_TRACE_APPEND"));
            assertFalse(candidate.diff().contains("taskarium_db.trace.db"));
            assertEquals(baseline, fixture.git(official, "rev-parse", "HEAD"));
            assertTrue(fixture.git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "delveforge.preparation.repository", matches = ".+")
    void existingFileDatabaseTestCannotMutateTheAgentSourceDuringPreparation() throws Exception {
        Path official = Path.of(System.getProperty("delveforge.preparation.repository"));
        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = temporary;
        String revision = fixture.git(official, "rev-parse", "HEAD");
        assertEquals("91f69ab1d7ab3a654814a1971cd3871ab0dffb1d", revision);
        byte[] database = Files.readAllBytes(official.resolve("database/taskarium_db.mv.db"));
        String id = UUID.randomUUID().toString();
        DockerAgentSandbox sandbox = sandbox();
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(),
                    "original", revision, "dependency preparation", "existing tests", "no code changes", 17),
                    AgentProtocol.Limits.defaults(), () -> false);
            JsonNode before = snapshot(sandbox, id);
            prepareDependencies(sandbox, id);
            assertEquals(before, snapshot(sandbox, id));
            JsonNode check = json.readTree(sandbox.tool(id,
                    new AgentProtocol.ToolCall("formal-test", "run_checks", "{\"goal\":\"test\"}"), () -> false));
            assertEquals("TESTS_PASSED", check.path("result").path("classification").asText());
            assertEquals(before.path("snapshot"), check.path("result").path("snapshot"));
            assertEquals(1, check.path("result").path("executedTests").asInt());
            assertEquals(1, check.path("result").path("runtimeChanges").size());
            assertEquals("database/taskarium_db.mv.db",
                    check.path("result").path("runtimeChanges").get(0).path("path").asText());
            assertEquals(before, snapshot(sandbox, id));
            org.junit.jupiter.api.Assertions.assertArrayEquals(database,
                    Files.readAllBytes(official.resolve("database/taskarium_db.mv.db")));
            assertTrue(fixture.git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void preparationDiscardsSourceEditsAndFormalChecksStillRejectThem() throws Exception {
        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = temporary;
        Path official = fixture.fixture();
        Files.writeString(official.resolve("src/test/java/example/CounterTest.java"), """
                package example;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import org.junit.jupiter.api.Test;
                class CounterTest {
                    @Test void unexpectedSourceWrite() throws Exception {
                        Files.writeString(Path.of("src/main/java/example/Counter.java"), "unexpected source change");
                        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                                () -> Files.writeString(Path.of("/source/src/main/java/example/Counter.java"),
                                        "forbidden input write"));
                    }
                }
                """);
        fixture.git(official, "add", ".");
        fixture.git(official, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost",
                "commit", "-m", "source mutation fixture");
        String id = UUID.randomUUID().toString();
        DockerAgentSandbox sandbox = sandbox();
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(),
                    "original", fixture.git(official, "rev-parse", "HEAD"), "dependency preparation",
                    "source mutation fixture", "keep source guard", 21), AgentProtocol.Limits.defaults(), () -> false);
            JsonNode before = snapshot(sandbox, id);
            prepareDependencies(sandbox, id);
            assertEquals(before, snapshot(sandbox, id));
            AgentPolicyRejection failure = assertThrows(AgentPolicyRejection.class, () -> sandbox.tool(id,
                    new AgentProtocol.ToolCall("formal-test", "run_checks", "{\"goal\":\"test\"}"), () -> false));
            assertEquals("CHECK_MUTATED_SOURCE", failure.rule().name());
            assertEquals(1, failure.sourceChanges().unexpectedChanges());
            assertEquals("src/main/java/example/Counter.java", failure.sourceChanges().files().getFirst().path());
            assertEquals(AgentPolicyRejection.SourceChangeType.CONTENT,
                    failure.sourceChanges().files().getFirst().changeType());
            assertEquals(false, failure.sourceChanges().files().getFirst().runtimeData());
            assertEquals(before, snapshot(sandbox, id));
            assertTrue(fixture.git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void ordinaryMavenTargetOutputsAndPositiveTestsRemainSupported() throws Exception {
        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = temporary;
        Path official = fixture.fixture();
        String id = UUID.randomUUID().toString();
        DockerAgentSandbox sandbox = sandbox();
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original",
                    fixture.git(official, "rev-parse", "HEAD"), "positive test fixture", "target outputs",
                    "verify existing Maven checks", 21), AgentProtocol.Limits.defaults(), () -> false);
            String edited = sandbox.tool(id, new AgentProtocol.ToolCall("edit", "edit_file",
                    "{\"path\":\"src/main/java/example/Counter.java\",\"old_text\":\"return n;\","
                            + "\"new_text\":\"return n + 1;\"}"), () -> false);
            assertTrue(edited.contains("PATCH_APPLIED"));
            JsonNode before = snapshot(sandbox, id);
            prepareDependencies(sandbox, id);
            assertEquals(before, snapshot(sandbox, id));
            JsonNode result = json.readTree(sandbox.tool(id,
                    new AgentProtocol.ToolCall("test", "run_checks", "{\"goal\":\"test\"}"), () -> false));
            assertEquals("TESTS_PASSED", result.path("result").path("classification").asText());
            assertTrue(result.path("result").path("executedTests").asInt() > 0);
            assertEquals(before, snapshot(sandbox, id));
            var candidate = sandbox.finish(id, () -> false);
            JsonNode evidence = json.readTree(candidate.testEvidence());
            assertEquals(0, evidence.path("checks").get(0).path("failures").asInt(-1));
            assertEquals(0, evidence.path("checks").get(0).path("errors").asInt(-1));
            assertTrue(fixture.git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }
}
