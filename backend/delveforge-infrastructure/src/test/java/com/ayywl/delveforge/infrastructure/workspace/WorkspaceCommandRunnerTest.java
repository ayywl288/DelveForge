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
                System.getProperty("java.class.path"), Probe.class.getName(), mode);
    }
    @Test void outputOverflowAndTimeoutAreFailuresInsteadOfSuccessfulTruncation() {
        WorkspaceException output = assertThrows(WorkspaceException.class, () -> WorkspaceCommandRunner.run(
                Path.of("."), probe("output"), Duration.ofSeconds(5), 1024));
        assertTrue(output.getMessage().contains("output budget"));
        WorkspaceException time = assertThrows(WorkspaceException.class, () -> WorkspaceCommandRunner.run(
                Path.of("."), probe("sleep"), Duration.ofMillis(200), 1024));
        assertTrue(time.getMessage().contains("time budget"));
    }
    @Test void testSelectorsCannotEnterThroughInheritedMavenOrJavaVariablesOrRc() {
        java.util.Map<String, String> environment = new java.util.HashMap<>(java.util.Map.of(
                "MAVEN_ARGS", "-Dtest=PassingTest", "maven_opts", "-Dgroups=fast",
                "JAVA_TOOL_OPTIONS", "-Dtest=PassingTest", "_JAVA_OPTIONS", "-Dtest=PassingTest",
                "JDK_JAVA_OPTIONS", "-Dtest=PassingTest", "MAVEN_SKIP_RC", "false", "PATH", "existing-path"));
        WorkspaceCommandRunner.isolateEnvironment(environment);
        assertEquals(java.util.Map.of("MAVEN_SKIP_RC", "true", "PATH", "existing-path"), environment);
    }

    @Test
    void providerCredentialAliasesAndAggregateConfigurationAreRemovedWithoutChangingParent() {
        java.util.Map<String, String> parent = new java.util.HashMap<>();
        for (String name : List.of("DEEPSEEK_API_KEY", "deepseek_api_key", "DELVEFORGE_AI_DEEPSEEK_APIKEY",
                "DELVEFORGE_AI_DEEPSEEK_API_KEY", "delveforge.ai.deepseek.api-key", "SPRING_APPLICATION_JSON")) {
            parent.put(name, "nonsecret-sentinel");
        }
        parent.put("JAVA_HOME", "jdk");
        parent.put("PATH", "tools");
        parent.put("GIT_DIR", "existing-git-location");
        var before = java.util.Map.copyOf(parent);
        var child = new java.util.HashMap<>(parent);
        WorkspaceCommandRunner.removeProviderCredentials(child);
        assertEquals(java.util.Map.of("JAVA_HOME", "jdk", "PATH", "tools", "GIT_DIR", "existing-git-location"), child);
        assertEquals(before, parent);
    }

    @Test
    void controlledSubprocessDoesNotInheritProviderCredentialsFromItsActualParent() throws Exception {
        ProcessBuilder parent = new ProcessBuilder(probe("credential-parent"));
        parent.environment().put("DEEPSEEK_API_KEY", "nonsecret-sentinel");
        parent.environment().put("DELVEFORGE_AI_DEEPSEEK_APIKEY", "nonsecret-sentinel");
        parent.environment().put("SPRING_APPLICATION_JSON", "{}");
        Process process = parent.start();
        process.getOutputStream().close();
        assertTrue(process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue());
        assertEquals("parent=true;child=false", new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("output")) {
                System.out.print("bounded fixture".repeat(10000));
            } else if (args[0].equals("credentials")) {
                System.out.print(providerCredentialsPresent());
            } else if (args[0].equals("credential-parent")) {
                System.out.print("parent=" + providerCredentialsPresent() + ";child=");
                List<String> command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), Probe.class.getName(), "credentials");
                var result = WorkspaceCommandRunner.run(Path.of("."), command, Duration.ofSeconds(5), 1024);
                if (!result.succeeded()) {
                    throw new IllegalStateException("Credential probe failed");
                }
                System.out.print(result.output());
            } else {
                Thread.sleep(10000);
            }
        }

        private static boolean providerCredentialsPresent() {
            return System.getenv("DEEPSEEK_API_KEY") != null
                    || System.getenv("DELVEFORGE_AI_DEEPSEEK_APIKEY") != null
                    || System.getenv("SPRING_APPLICATION_JSON") != null;
        }
    }
}
