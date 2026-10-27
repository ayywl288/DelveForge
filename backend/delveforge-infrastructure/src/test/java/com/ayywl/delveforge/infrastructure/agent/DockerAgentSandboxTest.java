package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentPolicyRejection;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.domain.agent.Candidate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class DockerAgentSandboxTest {
    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private final AgentProcess process = new AgentProcess();

    static AgentSandboxProperties properties(Path root) {
        return new AgentSandboxProperties(root, "docker", Map.of(21, System.getenv().getOrDefault("DELVEFORGE_AGENT_IMAGE_21", "delveforge-agent:protocol-v3-jdk21"),
                17, System.getenv().getOrDefault("DELVEFORGE_AGENT_IMAGE_17", "delveforge-agent:protocol-v3-jdk17")), 21, "delveforge-compatibility-tests",
                "delveforge-compatibility-20261024-cache", "2g", "2", 256);
    }

    static String pom(int jdk) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>fixture</groupId><artifactId>ordinary-maven</artifactId><version>1</version>
                <properties><maven.compiler.release>%d</maven.compiler.release>
                <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId>
                <version>5.11.4</version><scope>test</scope></dependency></dependencies>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId>
                <version>3.5.4</version></plugin></plugins></build></project>
                """.formatted(jdk);
    }

    Path fixture() throws Exception {
        Path root = temporary.resolve("official");
        Files.createDirectories(root.resolve("src/main/java/example"));
        Files.createDirectories(root.resolve("src/test/java/example"));
        Files.writeString(root.resolve("pom.xml"), pom(21));
        Files.writeString(root.resolve(".gitignore"), "target/\nIgnored.java\n");
        Files.writeString(root.resolve("src/main/java/example/Counter.java"),
                "package example;\npublic class Counter { public int next(int n) { return n; } }\n");
        Files.writeString(root.resolve("src/main/java/example/Obsolete.java"), "package example;\nclass Obsolete {}\n");
        Files.createDirectories(root.resolve("src/main/java/example/target"));
        Files.writeString(root.resolve("src/main/java/example/target/Existing.java"), "package example.target;\nclass Existing {}\n");
        Files.writeString(root.resolve("src/test/java/example/CounterTest.java"), """
                package example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertEquals;
                class CounterTest { @Test void increments() { assertEquals(2, new Counter().next(1)); } }
                """);
        git(root, "init", "--template=");
        git(root, "add", ".");
        git(root, "add", "-f", "src/main/java/example/target/Existing.java");
        git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "baseline");
        return root;
    }

    String git(Path directory, String... arguments) {
        var command = new java.util.ArrayList<>(List.of("git", "-c", "core.autocrlf=false", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        var result = process.run(command, directory, null, Duration.ofSeconds(30), 1048576, () -> false, Map.of());
        assertEquals(0, result.exitCode(), result.diagnostic());
        return result.text().strip();
    }

    private String tool(DockerAgentSandbox sandbox, String id, String name, Map<String, Object> arguments) throws Exception {
        return sandbox.tool(id, new AgentProtocol.ToolCall(UUID.randomUUID().toString(), name,
                json.writeValueAsString(arguments)), () -> false);
    }

    static String replacement(String path, String before, String after) {
        String[] oldLines = before.split("\n", -1);
        String[] newLines = after.split("\n", -1);
        StringBuilder patch = new StringBuilder("--- a/" + path + "\n+++ b/" + path + "\n@@ -1,"
                + (oldLines.length - 1) + " +1," + (newLines.length - 1) + " @@\n");
        for (int i = 0; i < oldLines.length - 1; i++) {
            patch.append('-').append(oldLines[i]).append('\n');
        }
        for (int i = 0; i < newLines.length - 1; i++) {
            patch.append('+').append(newLines[i]).append('\n');
        }
        return patch.toString();
    }

    @Test
    void configurationReferencesAndGeneratedTestValuesFreezeWhileActualCredentialsStayBlocked() throws Exception {
        Path official = fixture();
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        String path = "src/main/resources/application.properties";
        String configuration = "spring.datasource.password=${DB_PASSWORD:}\napp.jwt.secret=${JWT_SECRET}\n";
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("configuration-runs")),
                new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", official.toString(),
                    temporary.resolve("original").toString(), baseline, "fixture", "fixture", "configuration", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            assertTrue(tool(sandbox, id, "edit_file", Map.of("path", path, "old_text", "", "new_text", configuration))
                    .contains("PATCH_APPLIED"));
            assertTrue(tool(sandbox, id, "read_file", Map.of("path", path)).contains("${DB_PASSWORD:}"));
            var rejection = assertThrows(AgentPolicyRejection.class, () -> tool(sandbox, id, "edit_file", Map.of(
                    "path", path, "old_text", "${DB_PASSWORD:}", "new_text", "embedded-private-value")));
            assertEquals(AgentPolicyRejection.Rule.PATCH_CREDENTIALS, rejection.rule());
            assertTrue(rejection.sourceEditRejectedBeforeMutation());
            String unchanged = tool(sandbox, id, "read_file", Map.of("path", path));
            assertTrue(unchanged.contains("${DB_PASSWORD:}"));
            assertFalse(unchanged.contains("embedded-private-value"));
            String testPath = "src/test/java/example/GeneratedCredentialTest.java";
            String generated = """
                    package example;
                    class GeneratedCredentialTest {
                        @org.junit.jupiter.api.Test void eachFixtureValueIsGeneratedAtRuntime() {
                            String password = "case-" + java.util.UUID.randomUUID().toString();
                            String anotherPassword = "case-" + java.util.UUID.randomUUID().toString();
                            org.junit.jupiter.api.Assertions.assertTrue(password.startsWith("case-"));
                            org.junit.jupiter.api.Assertions.assertNotEquals(password, anotherPassword);
                        }
                    }
                    """;
            assertTrue(tool(sandbox, id, "edit_file", Map.of(
                    "path", testPath, "old_text", "", "new_text", generated)).contains("PATCH_APPLIED"));
            var literalRejection = assertThrows(AgentPolicyRejection.class, () -> tool(sandbox, id, "edit_file", Map.of(
                    "path", testPath, "old_text", generated,
                    "new_text", generated.replace("\"case-\" + java.util.UUID.randomUUID().toString()",
                            "\"embedded-private-value\""))));
            assertEquals(AgentPolicyRejection.Rule.PATCH_CREDENTIALS, literalRejection.rule());
            assertTrue(tool(sandbox, id, "read_file", Map.of("path", testPath)).contains("UUID.randomUUID()"));
            String htmlPath = "src/main/resources/templates/login.html";
            String form = "<form><label for=\"password\">Password:</label>"
                    + "<input type=\"password\" name=\"password\"></form>\n";
            assertTrue(tool(sandbox, id, "edit_file", Map.of(
                    "path", htmlPath, "old_text", "", "new_text", form)).contains("PATCH_APPLIED"));
            assertTrue(tool(sandbox, id, "read_file", Map.of("path", htmlPath)).contains("Password:</label>"));
            var htmlRejection = assertThrows(AgentPolicyRejection.class, () -> tool(sandbox, id, "edit_file", Map.of(
                    "path", htmlPath, "old_text", form,
                    "new_text", form.replace("<form>", "<form data-password=\"embedded-private-value\">"))));
            assertEquals(AgentPolicyRejection.Rule.PATCH_CREDENTIALS, htmlRejection.rule());
            assertTrue(tool(sandbox, id, "edit_file", Map.of("path", "src/main/java/example/Counter.java",
                    "old_text", "return n;", "new_text", "return n + 1;")).contains("PATCH_APPLIED"));
            assertTrue(tool(sandbox, id, "run_checks", Map.of("goal", "test")).contains("TESTS_PASSED"));
            Candidate candidate = sandbox.finish(id, () -> false);
            assertTrue(candidate.diff().contains("${DB_PASSWORD:}"));
            assertTrue(candidate.diff().contains("UUID.randomUUID()"));
            assertTrue(candidate.diff().contains("Password:</label>"));
            assertFalse(candidate.diff().contains("embedded-private-value"));
            assertTrue(candidate.testEvidence().contains("\"status\":\"TESTED\""));
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void failedReadOnlyPatchInspectionCanBeCorrectedWithoutLosingTheRun() throws Exception {
        Path official = fixture();
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("inspection-runs")),
                new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", official.toString(),
                    temporary.resolve("original").toString(), baseline, "fixture", "fixture", "inspection", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            String path = "src/main/java/example/Counter.java";
            String before = json.readTree(tool(sandbox, id, "read_file", Map.of("path", path)))
                    .path("result").path("content").asText();
            var failed = json.readTree(tool(sandbox, id, "apply_patch", Map.of(
                    "patch", "diff --git \"a/src/main/java/example/Counter.java b/src/main/java/example/Counter.java\n")));
            assertEquals("PATCH_CONFLICT", failed.path("result").path("classification").asText());
            assertTrue(failed.path("result").path("mutationNotStarted").asBoolean());
            assertEquals("INSPECTION_UNAVAILABLE", failed.path("result").path("patchDiagnostic").asText());
            String stale = before.replace("return n;", "return n - 1;");
            String contextPatch = ExactEditPatch.create(path, stale, "return n - 1;", "return n + 1;", 131072);
            var conflict = json.readTree(tool(sandbox, id, "apply_patch", Map.of("patch", contextPatch)));
            assertEquals("PATCH_CONFLICT", conflict.path("result").path("classification").asText());
            assertTrue(conflict.path("result").path("mutationNotStarted").asBoolean());
            assertEquals("CONTEXT_MISMATCH", conflict.path("result").path("conflictReason").asText());
            assertEquals(before, json.readTree(tool(sandbox, id, "read_file", Map.of("path", path)))
                    .path("result").path("content").asText());
            assertTrue(tool(sandbox, id, "edit_file", Map.of("path", path,
                    "old_text", "return n;", "new_text", "return n + 1;")).contains("PATCH_APPLIED"));
            assertTrue(tool(sandbox, id, "run_checks", Map.of("goal", "test")).contains("TESTS_PASSED"));
            assertTrue(sandbox.finish(id, () -> false).testEvidence().contains("\"status\":\"TESTED\""));
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void safeChangesBesideCredentialFreezeWithoutSecretInCandidateDiff() throws Exception {
        Path official = fixture();
        Path auth = official.resolve("src/main/java/example/Auth.java");
        Files.writeString(auth, """
                package example;
                class Auth {
                    private final String password = "synthetic-private-value";
                    int ordinary = 1;
                }
                """);
        git(official, "add", "src/main/java/example/Auth.java");
        git(official, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "credential fixture");
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("credential-runs")),
                new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", official.toString(),
                    temporary.resolve("original").toString(), baseline, "increment", "counter", "fix", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            String visible = tool(sandbox, id, "read_file", Map.of("path", "src/main/java/example/Auth.java"));
            assertFalse(visible.contains("synthetic-private-value"));
            assertTrue(visible.contains("redacted-credential"));
            assertTrue(tool(sandbox, id, "edit_file", Map.of("path", "src/main/java/example/Auth.java",
                    "old_text", "int ordinary = 1;", "new_text", "int ordinary = 2;")).contains("PATCH_APPLIED"));
            assertTrue(tool(sandbox, id, "edit_file", Map.of("path", "src/main/java/example/Counter.java",
                    "old_text", "return n;", "new_text", "return n + 1;")).contains("PATCH_APPLIED"));
            var check = json.readTree(tool(sandbox, id, "run_checks", Map.of("goal", "verify")));
            assertEquals("TESTS_PASSED", check.path("result").path("classification").asText());
            assertTrue(check.path("result").path("executedTests").asInt() > 0);
            Candidate candidate = sandbox.finish(id, () -> false);
            assertFalse(candidate.diff().contains("synthetic-private-value"));
            assertTrue(candidate.changedPaths().contains("src/main/java/example/Auth.java"));
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void finalCaptureRejectsCredentialMutationOutsideTheEditingTool() throws Exception {
        Path official = fixture();
        Files.writeString(official.resolve("src/main/java/example/Auth.java"), """
                package example;
                class Auth { String password = "synthetic-private-value"; }
                """);
        git(official, "add", "src/main/java/example/Auth.java");
        git(official, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "credential fixture");
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("credential-bypass")),
                new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", official.toString(),
                    temporary.resolve("original").toString(), baseline, "increment", "counter", "fix", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            String code = "from pathlib import Path; p=Path('/workspace/src/main/java/example/Auth.java'); "
                    + "p.write_bytes(p.read_bytes().replace(b'synthetic-private-value', b'different-private-value'))";
            var result = process.run(List.of("docker", "exec", sandbox.identity(id).containerId(), "python3", "-c", code),
                    temporary, null, Duration.ofSeconds(20), 8192, () -> false, Map.of());
            assertEquals(0, result.exitCode());
            assertEquals("CANDIDATE_POLICY", assertThrows(AgentException.class,
                    () -> sandbox.finish(id, () -> false)).code());
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void realOfflineMavenFailureRepairRetestAndCompleteTreeReplayLeaveOfficialUntouched() throws Exception {
        Path official = fixture();
        String baseline = git(official, "rev-parse", "HEAD");
        byte[] index = Files.readAllBytes(official.resolve(".git/index"));
        String source = Files.readString(official.resolve("src/main/java/example/Counter.java"));
        String id = UUID.randomUUID().toString();
        DockerAgentSandbox sandbox = new DockerAgentSandbox(properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", official.toString(),
                    temporary.resolve("original").toString(), baseline, "increment", "counter", "fix", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            var identity = sandbox.identity(id);
            assertEquals("none", identity.networkMode());
            assertEquals(2147483648L, identity.memoryBytes());
            assertEquals(2000000000L, identity.nanoCpus());
            assertEquals(256L, identity.pidsLimit());
            assertTrue(identity.readOnlyRoot());
            assertTrue(identity.imageId().matches("sha256:[0-9a-f]{64}"));
            assertTrue(tool(sandbox, id, "list_files", Map.of()).contains("Counter.java"));
            assertTrue(tool(sandbox, id, "read_file", Map.of("path", "missing.java")).contains("TOOL_ERROR"));
            assertTrue(tool(sandbox, id, "search_code", Map.of("query", "return n")).contains("Counter.java"));
            assertEquals("POLICY_REJECTED", assertThrows(AgentException.class,
                    () -> tool(sandbox, id, "read_file", Map.of("path", "../outside"))).code());
            assertEquals("POLICY_REJECTED", assertThrows(AgentException.class,
                    () -> tool(sandbox, id, "read_file", Map.of("path", ".git/config"))).code());
            assertTrue(tool(sandbox, id, "apply_patch", Map.of("patch", "invalid patch")).contains("TOOL_ERROR"));
            String failed = tool(sandbox, id, "run_checks", Map.of("goal", "test"));
            assertTrue(failed.contains("TESTS_FAILED"), failed);
            String patch = """
                    --- a/src/main/java/example/Counter.java
                    +++ b/src/main/java/example/Counter.java
                    @@ -1,2 +1,2 @@
                     package example;
                    -public class Counter { public int next(int n) { return n; } }
                    +public class Counter { public int next(int n) { return n + 1; } }
                    --- a/src/main/java/example/Obsolete.java
                    +++ /dev/null
                    @@ -1,2 +0,0 @@
                    -package example;
                    -class Obsolete {}
                    --- /dev/null
                    +++ b/src/main/java/example/target/Ignored.java
                    @@ -0,0 +1,2 @@
                    +package example.target;
                    +class Ignored {}
                    """;
            String applied = tool(sandbox, id, "apply_patch", Map.of("patch", patch));
            assertTrue(applied.contains("PATCH_APPLIED"), applied);
            String passed = tool(sandbox, id, "run_checks", Map.of("goal", "test"));
            assertTrue(passed.contains("TESTS_PASSED"), passed);
            String changedPom = pom(21).replace("</dependencies>", """
                    <dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId>
                    <version>3.17.0</version></dependency></dependencies>""");
            String addedTest = """
                    --- /dev/null
                    +++ b/src/test/java/example/BoundaryTest.java
                    @@ -0,0 +1,5 @@
                    +package example;
                    +import org.junit.jupiter.api.Test;
                    +import static org.junit.jupiter.api.Assertions.assertEquals;
                    +class BoundaryTest { @Test void negative() { assertEquals(0, new Counter().next(-1)); }
                    +@Test void dependency() { assertEquals(\"ab\", org.apache.commons.lang3.StringUtils.join(\"a\", \"b\")); } }
                    """;
            assertTrue(tool(sandbox, id, "apply_patch", Map.of("patch", replacement("pom.xml", pom(21), changedPom)
                    + addedTest)).contains("PATCH_APPLIED"));
            String withNewDependency = tool(sandbox, id, "run_checks", Map.of("goal", "test"));
            assertTrue(withNewDependency.contains("TESTS_PASSED"), withNewDependency);
            Candidate candidate = sandbox.finish(id, () -> false);
            assertEquals(5, candidate.changedPaths().size());
            assertTrue(candidate.changedPaths().contains("src/main/java/example/target/Ignored.java"));
            assertTrue(candidate.diff().contains("deleted file mode"));
            assertTrue(candidate.testEvidence().contains("TESTED"));
            assertTrue(candidate.testEvidence().contains("\"stale\":true"));
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertEquals(source, Files.readString(official.resolve("src/main/java/example/Counter.java")));
            org.junit.jupiter.api.Assertions.assertArrayEquals(index, Files.readAllBytes(official.resolve(".git/index")));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
            assertFalse(Files.exists(official.resolve("src/main/java/example/target/Ignored.java")));
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void secretReadsAreMarkedEditsRejectedAndOutputIsBounded() throws Exception {
        Path official = fixture();
        Files.writeString(official.resolve("secret-config.txt"), "password=\"fixture-private-value\"\n");
        Files.writeString(official.resolve("large.txt"), "x".repeat(30000));
        git(official, "add", ".");
        git(official, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "boundary fixtures");
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("runs")), new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original", baseline,
                    "goal", "scope", "changes", 21), AgentProtocol.Limits.defaults(), () -> false);
            String read = tool(sandbox, id, "read_file", Map.of("path", "secret-config.txt"));
            assertFalse(read.contains("fixture-private-value"));
            assertTrue(read.contains("\"sanitized\":true"), read);
            assertEquals("POLICY_REJECTED", assertThrows(AgentException.class, () -> tool(sandbox, id, "apply_patch",
                    Map.of("patch", replacement("secret-config.txt", "password=\"fixture-private-value\"\n", "ordinary=true\n")))).code());
            assertTrue(tool(sandbox, id, "read_file", Map.of("path", "large.txt")).contains("OUTPUT_LIMIT"));
            assertEquals("NO_CHANGES", assertThrows(AgentException.class, () -> sandbox.finish(id, () -> false)).code());
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void engineUnavailableIsAnEnvironmentFailure() {
        var configuration = new AgentSandboxProperties(temporary.resolve("runs"), "no-such-docker-for-task1",
                Map.of(21, "unused"), 21, "delveforge-task1-tests", "delveforge-task1-test-cache", "2g", "2", 256);
        DockerAgentSandbox sandbox = new DockerAgentSandbox(configuration, new DeterministicRepositorySecretPolicy());
        var error = assertThrows(AgentException.class, () -> sandbox.prepare(new AgentSandboxPort.Task(
                UUID.randomUUID().toString(), "p", "s", "w", temporary.toString(), "original",
                "a".repeat(40), "goal", "scope", "changes", null), AgentProtocol.Limits.defaults(), () -> false));
        assertEquals("ENVIRONMENT_FAILURE", error.code());
    }

    @Test
    void actualReplayRejectsAnIncompleteDiff() throws Exception {
        Path official = fixture();
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        AgentProcess corruptingTransport = spy(new AgentProcess());
        doAnswer(invocation -> {
            AgentProcess.Result result = (AgentProcess.Result) invocation.callRealMethod();
            List<String> command = invocation.getArgument(0);
            if (command.contains("diff-tree")) {
                String diff = result.text();
                int next = diff.indexOf("\ndiff --git ");
                assertTrue(next > 0, diff);
                return new AgentProcess.Result(0, diff.substring(0, next + 1).getBytes(StandardCharsets.UTF_8), "");
            }
            return result;
        }).when(corruptingTransport).run(any(), any(), any(), any(), any(Integer.class), any(), any());
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy(), corruptingTransport);
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original", baseline,
                    "goal", "scope", "changes", 21), AgentProtocol.Limits.defaults(), () -> false);
            String additions = """
                    --- /dev/null
                    +++ b/NewOne.java
                    @@ -0,0 +1 @@
                    +class NewOne {}
                    --- /dev/null
                    +++ b/NewTwo.java
                    @@ -0,0 +1 @@
                    +class NewTwo {}
                    """;
            assertTrue(tool(sandbox, id, "apply_patch", Map.of("patch", additions)).contains("PATCH_APPLIED"));
            assertEquals("REPLAY_MISMATCH", assertThrows(AgentException.class, () -> sandbox.finish(id, () -> false)).code());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void symlinkEscapeAndFinalSpecialFilesAreRejectedWithoutOfficialMutation() throws Exception {
        Path official = fixture();
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("runs")), new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original", baseline,
                    "goal", "scope", "changes", 21), AgentProtocol.Limits.defaults(), () -> false);
            // 测试夹具模拟不可信构建留下的 symlink；这不是模型可用的命令工具。
            var injected = process.run(List.of("docker", "exec", "df-" + id + "-agent", "python3", "-c",
                    "import os; os.symlink('/etc/passwd','/workspace/escape.txt')"), null, null,
                    Duration.ofSeconds(20), 8192, () -> false, Map.of());
            assertEquals(0, injected.exitCode());
            assertEquals("POLICY_REJECTED", assertThrows(AgentException.class, () -> tool(sandbox, id, "read_file",
                    Map.of("path", "escape.txt"))).code());
            assertEquals("POLICY_REJECTED", assertThrows(AgentException.class, () -> sandbox.finish(id, () -> false)).code());
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void crlfUtf8AndExecutableModeAreReplayedFromActualBytes() throws Exception {
        Path official = fixture();
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("runs")), new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original", baseline,
                    "goal", "scope", "changes", 21), AgentProtocol.Limits.defaults(), () -> false);
            String patch = "diff --git a/run-example.sh b/run-example.sh\nnew file mode 100755\n"
                    + "--- /dev/null\n+++ b/run-example.sh\n@@ -0,0 +1,2 @@\n+#!/bin/sh\n+echo example\n"
                    + "--- /dev/null\n+++ b/unicode.txt\n@@ -0,0 +1 @@\n+中文内容\r\n";
            String applied = tool(sandbox, id, "apply_patch", Map.of("patch", patch));
            assertTrue(applied.contains("PATCH_APPLIED"), applied);
            var candidate = sandbox.finish(id, () -> false);
            assertTrue(candidate.diff().contains("new file mode 100755"));
            assertTrue(candidate.diff().contains("+中文内容\r\n"));
            assertTrue(candidate.testEvidence().contains("NOT_TESTED"));
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void realCancellationStopsOwnedSandboxWithoutChangingOfficialIndexOrSources() throws Exception {
        Path official = fixture();
        String baseline = git(official, "rev-parse", "HEAD");
        byte[] index = Files.readAllBytes(official.resolve(".git/index"));
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("runs")), new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original", baseline,
                    "goal", "scope", "changes", 21), AgentProtocol.Limits.defaults(), () -> false);
            assertEquals("CANCELLED", assertThrows(AgentException.class, () -> sandbox.tool(id,
                    new AgentProtocol.ToolCall("cancel-check", "run_checks", "{\"goal\":\"test\"}"), () -> true)).code());
            sandbox.stop(id);
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(index, Files.readAllBytes(official.resolve(".git/index")));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void testEvidenceCannotTurnSkippedOrContradictoryReportsIntoSuccess() {
        String script = """
                import pathlib,sys,tempfile
                sys.path.insert(0,'/opt/delveforge')
                import sandbox
                with tempfile.TemporaryDirectory() as directory:
                    sandbox.ROOT=pathlib.Path(directory)
                    (sandbox.ROOT/'pom.xml').write_text('<project/>')
                    cases=[('1','0','1','BUILD_PASSED_NOT_TESTED'),('1','1','0','TESTS_FAILED')]
                    for tests,failures,skipped,expected in cases:
                        def fake(*args,**kwargs):
                            reports=sandbox.ROOT/'target/surefire-reports'
                            reports.mkdir(parents=True,exist_ok=True)
                            (reports/'TEST-example.xml').write_text('<testsuite tests="'+tests+'" failures="'+failures+'" errors="0" skipped="'+skipped+'"/>')
                            return 0,'BUILD SUCCESS'
                        sandbox.run_bounded=fake
                        result=sandbox.checks({'goal':'test'})
                        assert result['classification']==expected,result
                    reports=sandbox.ROOT/'target/surefire-reports'
                    (reports/'TEST-example.xml').write_text('<testsuite tests="0" failures="1"/>')
                    try:
                        sandbox.report()
                        raise AssertionError('Contradictory report accepted')
                    except sandbox.PolicyError:
                        pass
                    def jdk_mismatch(*args,**kwargs):
                        return 1,'Fatal error compiling: error: release version 25 not supported'
                    sandbox.run_bounded=jdk_mismatch
                    assert sandbox.checks({'goal':'compile'})['classification']=='JDK_ENVIRONMENT_MISMATCH'
                print('REPORT_BOUNDARIES_PASS')
                """;
        var result = process.run(List.of("docker", "run", "-i", "--rm", "--network", "none", "--user", "11000:11000",
                "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--memory", "256m",
                "--cpus", "1", "--pids-limit", "64", "--tmpfs", "/tmp:rw,exec,nosuid,size=32m",
                properties(temporary.resolve("report-fixture")).images().get(21), "python3", "-"), null, script.getBytes(StandardCharsets.UTF_8),
                Duration.ofSeconds(20), 8192, () -> false, Map.of());
        assertEquals(0, result.exitCode(), result.diagnostic());
        assertTrue(result.text().contains("REPORT_BOUNDARIES_PASS"));
    }

    @Test
    void emptySharedCacheIsPreparedWithoutExposingProjectToExternalNetworking() throws Exception {
        Path official = fixture();
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        String cache = "df-task1-cold-" + UUID.randomUUID();
        var defaults = properties(temporary.resolve("runs"));
        var configuration = new AgentSandboxProperties(defaults.root(), defaults.docker(), defaults.images(),
                defaults.defaultJdk(), defaults.owner(), cache, defaults.memory(), defaults.cpus(), defaults.pids());
        var sandbox = new DockerAgentSandbox(configuration, new DeterministicRepositorySecretPolicy());
        var empty = process.run(List.of("docker", "volume", "inspect", cache), null, null,
                Duration.ofSeconds(20), 8192, () -> false, Map.of());
        assertNotEquals(0, empty.exitCode());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original", baseline,
                    "goal", "scope", "changes", 21), AgentProtocol.Limits.defaults(), () -> false);
            String result = tool(sandbox, id, "run_checks", Map.of("goal", "test"));
            // 此夹具故意有一个失败测试；测试实际执行才证明依赖准备成功，不能把环境问题当源码失败。
            assertTrue(result.contains("TESTS_FAILED"), result);
            assertEquals(1, json.readTree(result).path("result").path("executedTests").asInt());
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
            var labels = process.run(List.of("docker", "volume", "inspect", cache), null, null,
                    Duration.ofSeconds(20), 16384, () -> false, Map.of());
            if (labels.exitCode() == 0) {
                assertEquals(defaults.owner(), json.readTree(labels.bytes()).get(0).path("Labels").path("delveforge.owner").asText());
                assertEquals(0, process.run(List.of("docker", "volume", "rm", cache), null, null,
                        Duration.ofSeconds(20), 8192, () -> false, Map.of()).exitCode());
            }
        }
    }

    @Test
    void passingUnrelatedMavenDirectoryCannotClearKnownFailure() throws Exception {
        Path official = fixture();
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("runs")), new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original", baseline,
                    "goal", "scope", "changes", 21), AgentProtocol.Limits.defaults(), () -> false);
            String failed = tool(sandbox, id, "run_checks", Map.of("goal", "test"));
            assertTrue(failed.contains("TESTS_FAILED"), failed);
            assertEquals(".", json.readTree(failed).path("result").path("directory").asText());
            Map<String, String> files = Map.of("independent/pom.xml", pom(21),
                    "independent/src/test/java/PassingTest.java", """
                    class PassingTest { @org.junit.jupiter.api.Test void passes() {} }
                    """);
            StringBuilder patch = new StringBuilder();
            for (var entry : files.entrySet()) {
                patch.append("--- /dev/null\n+++ b/").append(entry.getKey()).append("\n@@ -0,0 +1,")
                        .append(entry.getValue().lines().count()).append(" @@\n");
                entry.getValue().lines().forEach(line -> patch.append('+').append(line).append('\n'));
            }
            String applied = tool(sandbox, id, "apply_patch", Map.of("patch", patch.toString()));
            assertTrue(json.readTree(applied).path("ok").asBoolean(), applied);
            String passed = tool(sandbox, id, "run_checks", Map.of("goal", "test", "directory", "independent"));
            assertTrue(passed.contains("TESTS_PASSED"), passed);
            assertEquals("independent", json.readTree(passed).path("result").path("directory").asText());
            assertEquals("UNRESOLVED_CHECK_FAILURE",
                    assertThrows(AgentException.class, () -> sandbox.finish(id, () -> false)).code());
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void finalizationRejectsBinaryDeletionEvenWhenItBypassesTextToolHistory() throws Exception {
        Path official = fixture();
        Files.write(official.resolve("asset.bin"), new byte[] {0, 1, 2, 3});
        git(official, "add", "asset.bin");
        git(official, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "binary fixture");
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("runs")), new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original", baseline,
                    "goal", "scope", "changes", 21), AgentProtocol.Limits.defaults(), () -> false);
            var injected = process.run(List.of("docker", "exec", "df-" + id + "-agent", "python3", "-c",
                    "import os; os.unlink('/workspace/asset.bin')"), null, null, Duration.ofSeconds(20),
                    8192, () -> false, Map.of());
            assertEquals(0, injected.exitCode());
            assertEquals("CANDIDATE_POLICY", assertThrows(AgentException.class, () -> sandbox.finish(id, () -> false)).code());
            assertTrue(Files.exists(official.resolve("asset.bin")));
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void sourceBudgetIsCheckedBeforeHostObjectCopyAndContainerMaterialization() throws Exception {
        Path official = fixture();
        Files.write(official.resolve("oversized.txt"), new byte[16777217]);
        git(official, "add", "oversized.txt");
        git(official, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "oversized source fixture");
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var defaults = AgentProtocol.Limits.defaults();
        var limits = new AgentProtocol.Limits(defaults.maxContextChars(), defaults.maxOutputBytes(), defaults.maxPatchBytes(),
                1024, defaults.maxCandidateBytes(), defaults.maxFiles(),
                defaults.checkSeconds(), 0);
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("runs")), new DeterministicRepositorySecretPolicy());
        try {
            assertEquals("SOURCE_LIMIT", assertThrows(AgentException.class, () -> sandbox.prepare(
                    new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original", baseline,
                            "goal", "scope", "changes", 21), limits, () -> false)).code());
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }
}
