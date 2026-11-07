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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        return new AgentSandboxProperties(root, "docker", Map.of(21, System.getenv().getOrDefault("DELVEFORGE_AGENT_IMAGE_21", "delveforge-agent:protocol-v9-jdk21"),
                17, System.getenv().getOrDefault("DELVEFORGE_AGENT_IMAGE_17", "delveforge-agent:protocol-v9-jdk17")), 21, "delveforge-compatibility-tests",
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

    private String tool(DockerAgentSandbox sandbox, String id, String name, Map<String, ?> arguments) throws Exception {
        if (name.equals("shell_patch")) {
            String encoded = java.util.Base64.getEncoder().encodeToString(arguments.get("patch").toString().getBytes(StandardCharsets.UTF_8));
            name = "exec_command";
            arguments = Map.of("command", "printf %s " + encoded + " | base64 -d | git apply --no-index --unidiff-zero --whitespace=nowarn -");
        }
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
    void publicResourcesAreReadableImmutableAndPrivateResourcesRemainExcluded() throws Exception {
        Path official = fixture();
        String privateValue = "fixture-" + UUID.randomUUID();
        Files.writeString(official.resolve(".env.example"), "API_URL=https://example.com\n");
        Files.writeString(official.resolve("private.properties"), "password=" + privateValue + "\n");
        git(official, "add", ".env.example", "private.properties");
        git(official, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "resources");
        String baseline = git(official, "rev-parse", "HEAD");
        var workspace = new com.ayywl.delveforge.infrastructure.workspace.GitWorkspaceAdapter(temporary.resolve("copies"));
        var ref = new com.ayywl.delveforge.application.port.workspace.WorkspaceRef(official.toString());
        assertTrue(workspace.readModelFile(ref, baseline, ".env.example").isPresent());
        assertTrue(workspace.readModelFile(ref, baseline, "private.properties").isEmpty());
        assertTrue(workspace.readModelFile(ref, baseline, "src/main/java/example/Counter.java").isPresent());
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("resource-runs")),
                new DeterministicRepositorySecretPolicy());
        String id = UUID.randomUUID().toString();
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", official.toString(),
                    temporary.resolve("original").toString(), baseline, "fixture", "fixture", "configuration", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            String listing = tool(sandbox, id, "list_files", Map.of());
            assertTrue(listing.contains(".env.example"));
            assertFalse(listing.contains("private.properties"));
            assertFalse(tool(sandbox, id, "search_code", Map.of("query", privateValue)).contains(privateValue));
            for (String path : List.of(".env.example", "private.properties")) {
                if (path.equals("private.properties")) {
                    assertThrows(AgentPolicyRejection.class, () -> tool(sandbox, id, "read_file", Map.of("path", path)));
                } else {
                    assertTrue(tool(sandbox, id, "read_file", Map.of("path", path)).contains("https://example.com"));
                }
                assertThrows(AgentPolicyRejection.class, () -> tool(sandbox, id, "edit_file",
                        Map.of("path", path, "old_text", "", "new_text", "changed")));

            }
            tool(sandbox, id, "edit_file", Map.of("path", "src/main/java/example/Counter.java",
                    "old_text", "return n;", "new_text", "return n + 1;"));
            tool(sandbox, id, "exec_command", Map.of("command", "mvn -B test", "verification_scope", "Repository tests", "check_kind", "test"));
            var candidate = sandbox.finish(id, () -> false);
            assertFalse(candidate.diff().contains(privateValue));
            assertFalse(candidate.changedPaths().contains("private.properties"));
            assertFalse(candidate.changedPaths().contains(".env.example"));
            assertEquals("PROJECTED", json.readTree(candidate.testEvidence()).path("sourceView").path("kind").asText());
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertEquals("", git(official, "status", "--porcelain"));
        } finally {
            sandbox.cleanup(id);
        }
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
                    .contains("\"ok\":true"));
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
                    "path", testPath, "old_text", "", "new_text", generated)).contains("\"ok\":true"));
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
                    "path", htmlPath, "old_text", "", "new_text", form)).contains("\"ok\":true"));
            assertTrue(tool(sandbox, id, "read_file", Map.of("path", htmlPath)).contains("Password:</label>"));
            var htmlRejection = assertThrows(AgentPolicyRejection.class, () -> tool(sandbox, id, "edit_file", Map.of(
                    "path", htmlPath, "old_text", form,
                    "new_text", form.replace("<form>", "<form data-password=\"embedded-private-value\">"))));
            assertEquals(AgentPolicyRejection.Rule.PATCH_CREDENTIALS, htmlRejection.rule());
            assertTrue(tool(sandbox, id, "edit_file", Map.of("path", "src/main/java/example/Counter.java",
                    "old_text", "return n;", "new_text", "return n + 1;")).contains("\"ok\":true"));
            assertTrue(tool(sandbox, id, "exec_command", Map.of("command", "mvn -B test", "verification_scope", "Repository tests", "check_kind", "test")).contains("COMMAND_SUCCEEDED"));
            Candidate candidate = sandbox.finish(id, () -> false);
            assertTrue(candidate.diff().contains("${DB_PASSWORD:}"));
            assertTrue(candidate.diff().contains("UUID.randomUUID()"));
            assertTrue(candidate.diff().contains("Password:</label>"));
            assertFalse(candidate.diff().contains("embedded-private-value"));
            assertTrue(candidate.testEvidence().contains("COMMAND_VERIFIED"));
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
            var conflict = json.readTree(tool(sandbox, id, "edit_file", Map.of("path", path,
                    "old_text", "return n - 1;", "new_text", "return n + 1;")));
            assertEquals("PATCH_CONFLICT", conflict.path("result").path("classification").asText());
            assertTrue(conflict.path("result").path("mutationNotStarted").asBoolean());
            assertEquals("CONTEXT_MISMATCH", conflict.path("result").path("conflictReason").asText());
            assertEquals(before, json.readTree(tool(sandbox, id, "read_file", Map.of("path", path)))
                    .path("result").path("content").asText());
            assertTrue(tool(sandbox, id, "edit_file", Map.of("path", path,
                    "old_text", "return n;", "new_text", "return n + 1;")).contains("\"ok\":true"));
            assertTrue(tool(sandbox, id, "exec_command", Map.of("command", "mvn -B test", "verification_scope", "Repository tests", "check_kind", "test")).contains("COMMAND_SUCCEEDED"));
            assertTrue(sandbox.finish(id, () -> false).testEvidence().contains("COMMAND_VERIFIED"));
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void observedFailedTestCannotBeSkippedOrDeletedToFreeze() throws Exception {
        Path official = fixture();
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        String testPath = "src/test/java/example/CounterTest.java";
        String originalTest = Files.readString(official.resolve(testPath));
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("missing-test-runs")),
                new DeterministicRepositorySecretPolicy());
        Map<String, String> check = Map.of("command", "mvn -B test", "verification_scope", "Counter tests",
                "check_kind", "test");
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", official.toString(),
                    "unmounted", baseline, "increment", "Counter", "fix", 21), AgentProtocol.Limits.defaults(), () -> false);
            assertFalse(json.readTree(tool(sandbox, id, "exec_command", check)).path("ok").asBoolean());
            tool(sandbox, id, "edit_file", Map.of("path", testPath, "old_text", "@Test",
                    "new_text", "@org.junit.jupiter.api.Disabled @Test"));
            assertTrue(json.readTree(tool(sandbox, id, "exec_command", check)).path("ok").asBoolean());
            org.junit.jupiter.api.Assertions.assertNotNull(sandbox.completionFeedback(id, () -> false));
            assertTrue(json.readTree(tool(sandbox, id, "exec_command", Map.of("command",
                    "rm " + testPath + " && rm -rf target"))).path("ok").asBoolean());
            assertTrue(json.readTree(tool(sandbox, id, "exec_command", check)).path("ok").asBoolean());
            org.junit.jupiter.api.Assertions.assertNotNull(sandbox.completionFeedback(id, () -> false));
            tool(sandbox, id, "edit_file", Map.of("path", testPath, "old_text", "", "new_text", originalTest));
            tool(sandbox, id, "edit_file", Map.of("path", "src/main/java/example/Counter.java",
                    "old_text", "return n;", "new_text", "return n + 1;"));
            assertTrue(json.readTree(tool(sandbox, id, "exec_command", check)).path("ok").asBoolean());
            assertEquals(List.of("src/main/java/example/Counter.java"), sandbox.finish(id, () -> false).changedPaths());
            assertEquals("", git(official, "status", "--porcelain"));
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void sourceFixturesExecuteAndOrdinaryEditsFreezeWhileCredentialLinesStayProtected() throws Exception {
        Path official = fixture();
        Path auth = official.resolve("src/main/java/example/Auth.java");
        Files.writeString(auth, """
                package example;
                class Auth {
                    private final String password = "synthetic-private-value";
                    int ordinary = 1;
                }
                """);
        Files.writeString(official.resolve("src/test/java/example/AuthFixtureTest.java"), """
                package example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertFalse;
                class AuthFixtureTest {
                    @Test void fixtureIsAvailableToTheRunner() {
                        String password = "synthetic-fixture-password";
                        assertFalse(password.isBlank());
                        System.out.println("Bearer " + "synthetic-maven-session");
                        System.out.println("sk-" + "a".repeat(32));
                    }
                }
                """);
        git(official, "add", "src/main/java/example/Auth.java");
        git(official, "add", "src/test/java/example/AuthFixtureTest.java");
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
            assertFalse(tool(sandbox, id, "search_code", Map.of("path", "src", "query", "password"))
                    .contains("synthetic-private-value"));
            assertTrue(tool(sandbox, id, "edit_file", Map.of("path", "src/main/java/example/Auth.java",
                    "old_text", "int ordinary = 1;", "new_text", "int ordinary = 2;")).contains("\"ok\":true"));
            assertThrows(AgentPolicyRejection.class, () -> tool(sandbox, id, "edit_file",
                    Map.of("path", "src/main/java/example/Auth.java", "old_text", "synthetic-private-value",
                            "new_text", "different-private-value")));
            assertTrue(tool(sandbox, id, "edit_file", Map.of("path", "src/main/java/example/Counter.java",
                    "old_text", "return n;", "new_text", "return n + 1;")).contains("\"ok\":true"));
            var check = json.readTree(tool(sandbox, id, "exec_command", Map.of("command", "mvn -B verify", "verification_scope", "Repository tests", "check_kind", "test")));
            assertEquals("COMMAND_SUCCEEDED", check.path("result").path("classification").asText());
            assertTrue(check.path("ok").asBoolean());
            assertFalse(check.toString().contains("synthetic-private-value"));
            assertFalse(check.toString().contains("synthetic-maven-session"));
            assertFalse(check.toString().contains("sk-" + "a".repeat(32)));
            assertTrue(check.path("result").path("executedTests").asInt() >= 2);
            Candidate candidate = sandbox.finish(id, () -> false);
            assertFalse(candidate.diff().contains("synthetic-private-value"));
            assertTrue(candidate.changedPaths().contains("src/main/java/example/Auth.java"));
            assertTrue(candidate.changedPaths().contains("src/main/java/example/Counter.java"));
            assertEquals("COMMAND_VERIFIED", json.readTree(candidate.testEvidence()).path("verificationLevel").asText());
            assertTrue(Files.readString(auth).contains("int ordinary = 1;"));
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
                    + "assert p.exists(); p.write_text('package example; class Auth { "
                    + "String password = \"different-private-value\"; }')";
            var result = process.run(List.of("docker", "exec", sandbox.identity(id).containerId(), "python3", "-c", code),
                    temporary, null, Duration.ofSeconds(20), 8192, () -> false, Map.of());
            assertEquals(0, result.exitCode());
            assertEquals("CANDIDATE_POLICY",
                    assertThrows(AgentException.class, () -> sandbox.finish(id, () -> false)).code());
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void withheldHighRiskSourceStaysOutsideExecutionAndBlocksCompletion() throws Exception {
        Path official = fixture();
        Files.writeString(official.resolve("src/main/java/example/Auth.java"),
                "package example; class Auth { String token = \"sk-" + "a".repeat(32) + "\"; }\n");
        git(official, "add", ".");
        git(official, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "high risk fixture");
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("high-risk")),
                new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", official.toString(),
                    temporary.resolve("original").toString(), baseline, "observe", "counter", "checks", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            assertThrows(AgentPolicyRejection.class,
                    () -> tool(sandbox, id, "read_file", Map.of("path", "src/main/java/example/Auth.java")));
            assertTrue(sandbox.preflight(id, () -> false).ready());
            assertTrue(tool(sandbox, id, "exec_command", Map.of("command", "test ! -e src/main/java/example/Auth.java")).contains("COMMAND_SUCCEEDED"));
            assertTrue(sandbox.completionFeedback(id, () -> false).contains("withheld"));

        } finally {
            sandbox.cleanup(id);
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        }
    }

    @Test
    void frontendAuthenticationFixtureExecutesWithSanitizedReadsSearchesAndCheckOutput() throws Exception {
        Path official = Files.createDirectories(temporary.resolve("frontend"));
        Path fixture = Path.of("src/test/resources/agent/native-frontend/npm");
        try (var paths = Files.walk(fixture)) {
            for (Path source : paths.toList()) {
                Path destination = official.resolve(fixture.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(source, destination);
                }
            }
        }
        Path counter = official.resolve("src/counter.js");
        Files.writeString(counter, Files.readString(counter).replace("return value;", "return value + 1;"));
        Path tests = official.resolve("tests/counter.test.mjs");
        Files.writeString(tests, Files.readString(tests) + """
                test('authentication fixture remains executable', () => {
                    const login = { password: 'synthetic-fixture-password' };
                    assert.ok(login.password.length > 0);
                    console.log('Bearer ' + 'synthetic-vue-session');
                    console.log('sk-' + 'c'.repeat(32));
                });
                """);
        Files.writeString(official.resolve(".gitattributes"), "* -text\n");
        git(official, "init", "--template=");
        git(official, "add", ".");
        git(official, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "frontend fixture");
        String baseline = git(official, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("frontend-runs")),
                new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", official.toString(),
                    temporary.resolve("original").toString(), baseline, "observe", "frontend", "checks", 21),
                    AgentProtocol.Limits.defaults(), () -> false);
            assertFalse(tool(sandbox, id, "read_file", Map.of("path", "tests/counter.test.mjs"))
                    .contains("synthetic-fixture-password"));
            assertFalse(tool(sandbox, id, "search_code", Map.of("query", "password"))
                    .contains("synthetic-fixture-password"));
            var checked = json.readTree(tool(sandbox, id, "exec_command", Map.of("command", "npm test", "verification_scope", "Repository tests", "check_kind", "test")));
            assertEquals("COMMAND_SUCCEEDED", checked.path("result").path("classification").asText());
            assertTrue(checked.path("result").path("stdout").asText().contains("pass 3"));
            assertFalse(checked.toString().contains("synthetic-vue-session"));
            assertFalse(checked.toString().contains("sk-" + "c".repeat(32)));
        } finally {
            sandbox.cleanup(id);
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.5.4", "2.22.2"})
    void nativeMavenFailureRepairRetestAndCompleteTreeReplayLeaveOfficialUntouched(String surefire) throws Exception {
        Path official = fixture();
        int jdk = surefire.equals("2.22.2") ? 17 : 21;
        String initialPom = pom(jdk).replace("<version>3.5.4</version>", "<version>" + surefire + "</version>");
        if (jdk == 17) {
            initialPom = initialPom.replace("<version>5.11.4</version>", "<version>5.8.2</version>");
            Files.writeString(official.resolve("pom.xml"), initialPom);
            git(official, "add", "pom.xml");
            git(official, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-m", "legacy runner fixture");
        }
        String baseline = git(official, "rev-parse", "HEAD");
        byte[] index = Files.readAllBytes(official.resolve(".git/index"));
        String source = Files.readString(official.resolve("src/main/java/example/Counter.java"));
        String id = UUID.randomUUID().toString();
        DockerAgentSandbox sandbox = new DockerAgentSandbox(properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", official.toString(),
                    temporary.resolve("original").toString(), baseline, "increment", "counter", "fix", jdk),
                    AgentProtocol.Limits.defaults(), () -> false);
            var identity = sandbox.identity(id);
            assertEquals("none", identity.networkMode());
            assertEquals(2147483648L, identity.memoryBytes());
            assertEquals(2000000000L, identity.nanoCpus());
            assertEquals(256L, identity.pidsLimit());
            assertTrue(identity.readOnlyRoot());
            assertTrue(identity.imageId().matches("sha256:[0-9a-f]{64}"));
            assertTrue(tool(sandbox, id, "list_files", Map.of()).contains("Counter.java"));
            assertTrue(tool(sandbox, id, "read_file", Map.of("path", "missing.java")).contains("PATH_NOT_FOUND"));
            assertTrue(tool(sandbox, id, "search_code", Map.of("query", "return n")).contains("Counter.java"));
            assertEquals("POLICY_REJECTED", assertThrows(AgentException.class,
                    () -> tool(sandbox, id, "read_file", Map.of("path", "../outside"))).code());
            assertEquals("POLICY_REJECTED", assertThrows(AgentException.class,
                    () -> tool(sandbox, id, "read_file", Map.of("path", ".git/config"))).code());
            String failed = tool(sandbox, id, "exec_command", Map.of("command", "mvn -B test", "verification_scope", "Repository tests", "check_kind", "test"));
            assertTrue(failed.contains("COMMAND_FAILED"), failed);
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
                    +++ b/src/main/java/example/Added.java
                    @@ -0,0 +1,2 @@
                    +package example;
                    +class Added {}
                    """;
            String applied = tool(sandbox, id, "shell_patch", Map.of("patch", patch));
            assertTrue(applied.contains("\"ok\":true"), applied);
            String passed = tool(sandbox, id, "exec_command", Map.of("command", "mvn -B test", "verification_scope", "Repository tests", "check_kind", "test"));
            assertTrue(passed.contains("COMMAND_SUCCEEDED"), passed);
            String changedPom = initialPom.replace("</dependencies>", """
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
            assertTrue(tool(sandbox, id, "shell_patch", Map.of("patch", replacement("pom.xml", initialPom, changedPom)
                    + addedTest)).contains("\"ok\":true"));
            String withNewDependency = tool(sandbox, id, "exec_command", Map.of("command", "mvn -B test", "verification_scope", "Repository tests", "check_kind", "test"));
            assertTrue(withNewDependency.contains("COMMAND_SUCCEEDED"), withNewDependency);
            Candidate candidate = sandbox.finish(id, () -> false);
            assertEquals(5, candidate.changedPaths().size());
            assertTrue(candidate.changedPaths().contains("src/main/java/example/Added.java"));
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
    void secretReadsAreWithheldEditsRejectedAndOutputIsBounded() throws Exception {
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
            var read = assertThrows(AgentPolicyRejection.class,
                    () -> tool(sandbox, id, "read_file", Map.of("path", "secret-config.txt")));
            assertEquals(AgentPolicyRejection.Rule.RESOURCE_CAPABILITY, read.rule());
            assertFalse(read.getMessage().contains("fixture-private-value"));
            assertEquals("POLICY_REJECTED", assertThrows(AgentException.class, () -> tool(sandbox, id, "edit_file",
                    Map.of("path", "secret-config.txt", "old_text", "password=\"fixture-private-value\"", "new_text", "ordinary=true"))).code());
            assertTrue(tool(sandbox, id, "read_file", Map.of("path", "large.txt")).contains("OUTPUT_LIMIT"));
            assertEquals("NO_CHANGES", assertThrows(AgentException.class, () -> sandbox.finish(id, () -> false)).code());
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
    }

    @Test
    void missingDockerExecutableReportsSafeStartFailure() {
        var configuration = new AgentSandboxProperties(temporary.resolve("runs"), "no-such-docker-for-task1",
                Map.of(21, "unused"), 21, "delveforge-task1-tests", "delveforge-task1-test-cache", "2g", "2", 256);
        DockerAgentSandbox sandbox = new DockerAgentSandbox(configuration, new DeterministicRepositorySecretPolicy());
        var error = assertThrows(AgentException.class, () -> sandbox.prepare(new AgentSandboxPort.Task(
                UUID.randomUUID().toString(), "p", "s", "w", temporary.toString(), "original",
                "a".repeat(40), "goal", "scope", "changes", null), AgentProtocol.Limits.defaults(), () -> false));
        assertEquals("PROCESS_START_FAILED", error.code());
        assertTrue(error.getMessage().contains("osError="));
        assertFalse(error.getMessage().contains("no-such-docker-for-task1"));
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
            assertTrue(tool(sandbox, id, "shell_patch", Map.of("patch", additions)).contains("\"ok\":true"));
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
            String applied = tool(sandbox, id, "shell_patch", Map.of("patch", patch));
            assertTrue(applied.contains("\"ok\":true"), applied);
            String artifact = sandbox.retainPartial(id);
            String diff = Files.readString(Path.of(artifact).resolveSibling("partial.diff"));
            assertTrue(diff.contains("new file mode 100755"));
            assertTrue(diff.contains("+中文内容\r\n"));
            assertTrue(Files.readString(Path.of(artifact)).contains("UNVERIFIED_NON_DELIVERABLE"));

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
                    new AgentProtocol.ToolCall("cancel-check", "exec_command", "{\"command\":\"mvn test\"}"), () -> true)).code());
            sandbox.stop(id);
            assertEquals(baseline, git(official, "rev-parse", "HEAD"));
            org.junit.jupiter.api.Assertions.assertArrayEquals(index, Files.readAllBytes(official.resolve(".git/index")));
            assertTrue(git(official, "status", "--porcelain").isEmpty());
        } finally {
            sandbox.cleanup(id);
        }
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
            String result = tool(sandbox, id, "exec_command", Map.of("command", "mvn -B test", "verification_scope", "Repository tests", "check_kind", "test"));
            // 此夹具故意有一个失败测试；测试实际执行才证明依赖准备成功，不能把环境问题当源码失败。
            assertTrue(result.contains("COMMAND_FAILED"), result);
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
            String failed = tool(sandbox, id, "exec_command", Map.of("command", "mvn -B test", "verification_scope", "Repository tests", "check_kind", "test"));
            assertTrue(failed.contains("COMMAND_FAILED"), failed);
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
            String applied = tool(sandbox, id, "shell_patch", Map.of("patch", patch.toString()));
            assertTrue(json.readTree(applied).path("ok").asBoolean(), applied);
            String passed = tool(sandbox, id, "exec_command", Map.of("command", "mvn -B test", "directory", "independent", "verification_scope", "Independent tests", "check_kind", "test"));
            assertTrue(passed.contains("COMMAND_SUCCEEDED"), passed);
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
                defaults.checkSeconds());
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
    @Test
    void cleanupRejectsSameOwnerWithAnotherRunLabel() {
        String runId = UUID.randomUUID().toString();
        String resource = "df-" + runId + "-agent";
        String otherRun = UUID.randomUUID().toString();
        process.run(List.of("docker", "create", "--name", resource,
                "--label", "delveforge.owner=delveforge-compatibility-tests", "--label", "delveforge.run=" + otherRun,
                "--network", "none", properties(temporary.resolve("ownership")).images().get(21)), null, null,
                Duration.ofSeconds(30), 8192, () -> false, Map.of());
        var sandbox = new DockerAgentSandbox(properties(temporary.resolve("ownership")), new DeterministicRepositorySecretPolicy());
        try {
            assertEquals("RESOURCE_OWNERSHIP", assertThrows(AgentException.class, () -> sandbox.cleanup(runId)).code());
            assertEquals(0, process.run(List.of("docker", "inspect", resource), null, null, Duration.ofSeconds(10),
                    65536, () -> false, Map.of()).exitCode());
        } finally {
            // 仅销毁本测试刚创建并持有精确身份的合成资源。
            process.run(List.of("docker", "rm", resource), null, null, Duration.ofSeconds(10), 8192, () -> false, Map.of());
        }
    }

}
