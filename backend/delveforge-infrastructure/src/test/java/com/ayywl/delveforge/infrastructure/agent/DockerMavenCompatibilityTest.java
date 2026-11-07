package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_COMPATIBILITY", matches = "true")
class DockerMavenCompatibilityTest {
    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void releaseEightProjectCompilesOnControlledJdk21WithoutPomRewrites() throws Exception {
        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = temporary;
        Path official = fixture.fixture();
        Files.writeString(official.resolve("pom.xml"), DockerAgentSandboxTest.pom(8));
        fixture.git(official, "add", "pom.xml");
        fixture.git(official, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "Java 8 source fixture");
        String revision = fixture.git(official, "rev-parse", "HEAD");
        byte[] index = Files.readAllBytes(official.resolve(".git/index"));
        String pom = Files.readString(official.resolve("pom.xml"));
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        String id = UUID.randomUUID().toString();
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original", revision,
                    "compatibility", "compile", "build", null), AgentProtocol.Limits.defaults(), () -> false);
            assertEquals(21, sandbox.identity(id).targetJdk());
            String result = sandbox.tool(id, new AgentProtocol.ToolCall("java8-compile", "exec_command",
                    "{\"command\":\"mvn -B compile\",\"verification_scope\":\"Java 8 source compatibility\",\"check_kind\":\"build\"}"), () -> false);
            assertTrue(result.contains("COMMAND_SUCCEEDED"), result);
            assertEquals(pom, Files.readString(official.resolve("pom.xml")));
            org.junit.jupiter.api.Assertions.assertArrayEquals(index, Files.readAllBytes(official.resolve(".git/index")));
            assertEquals(revision, fixture.git(official, "rev-parse", "HEAD"));
            assertTrue(fixture.git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void unmodifiedPinnedPublicSpringBootReactorBuildsWithJdk17WithoutUsingWrapper() throws Exception {
        Path official = Path.of(System.getProperty("delveforge.compatibility.repository")).toAbsolutePath();
        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = temporary;
        String revision = fixture.git(official, "rev-parse", "HEAD");
        assertEquals("d88a2b721bda3798a6a934987157498e66da06c5", revision);
        assertTrue(fixture.git(official, "status", "--porcelain").isEmpty());
        byte[] index = Files.readAllBytes(official.resolve(".git/index"));
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        String id = UUID.randomUUID().toString();
        var defaults = AgentProtocol.Limits.defaults();
        var limits = new AgentProtocol.Limits(defaults.maxContextChars(), defaults.maxOutputBytes(), defaults.maxPatchBytes(),
                defaults.maxFileBytes(), defaults.maxCandidateBytes(), defaults.maxFiles(),
                600);
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(),
                    temporary.resolve("original").toString(), revision, "compatibility", "reactor", "build", 17), limits, () -> false);
            String wrongRoot = sandbox.tool(id, new AgentProtocol.ToolCall("wrong-root", "exec_command", "{\"command\":\"mvn -B test\",\"directory\":\"missing-directory\"}"), () -> false);
            assertEquals("INVALID_ARGUMENTS", json.readTree(wrongRoot).path("classification").asText(), wrongRoot);
            assertFalse(json.readTree(wrongRoot).path("ok").asBoolean());
            assertFalse(json.readTree(wrongRoot).path("checkStarted").asBoolean());
            String result = sandbox.tool(id, new AgentProtocol.ToolCall("reactor-test", "exec_command",
                    json.writeValueAsString(Map.of("command", "mvn -B test", "directory", "complete", "verification_scope", "Reactor tests", "check_kind", "test"))), () -> false);
            assertTrue(result.contains("COMMAND_SUCCEEDED"), result);
            assertTrue(json.readTree(result).path("result").path("executedTests").asInt() >= 2, result);
            // 在同一真实 reactor 的两个模块增加源码，验证最终捕获不局限于单模块或工具名单。
            String libraryPath = "complete/library/src/main/java/fixture/probe/LibraryCandidateProbe.java";
            String applicationPath = "complete/application/src/main/java/fixture/probe/ApplicationCandidateProbe.java";
            for (String path : java.util.List.of(libraryPath, applicationPath)) {
                String type = path.equals(libraryPath) ? "LibraryCandidateProbe" : "ApplicationCandidateProbe";
                String applied = sandbox.tool(id, new AgentProtocol.ToolCall(UUID.randomUUID().toString(), "edit_file",
                        json.writeValueAsString(Map.of("path", path, "old_text", "", "new_text",
                                "package fixture.probe;\npublic class " + type + " {}\n"))), () -> false);
                assertTrue(json.readTree(applied).path("ok").asBoolean(), applied);
            }
            String rechecked = sandbox.tool(id, new AgentProtocol.ToolCall("reactor-retest", "exec_command",
                    json.writeValueAsString(Map.of("command", "mvn -B test", "directory", "complete", "verification_scope", "Reactor tests", "check_kind", "test"))), () -> false);
            assertTrue(rechecked.contains("COMMAND_SUCCEEDED"), rechecked);
            var candidate = sandbox.finish(id, () -> false);
            assertEquals(java.util.Set.of(libraryPath, applicationPath), java.util.Set.copyOf(candidate.changedPaths()));
            assertEquals(revision, fixture.git(official, "rev-parse", "HEAD"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(index, Files.readAllBytes(official.resolve(".git/index")));
            assertTrue(fixture.git(official, "status", "--porcelain").isEmpty());
            System.out.println("TASK1_COMPATIBILITY repository=spring-guides/gs-multi-module revision=" + revision
                    + " jdk=17 directory=complete result=" + json.readTree(result).path("result").path("classification")
                    + " tests=" + json.readTree(result).path("result").path("executedTests"));
        } finally {
            sandbox.cleanup(id);
        }
    }
}
