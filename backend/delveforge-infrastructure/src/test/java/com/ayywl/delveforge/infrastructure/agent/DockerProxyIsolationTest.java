package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/** 临时 Docker client 配置真实注入假代理；运输仍执行实际 CLI，不伪造容器或 Maven 结果。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class DockerProxyIsolationTest {
    @TempDir
    Path temporary;

    private final AgentProcess process = new AgentProcess();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void clientProxyCredentialsAreAbsentFromEveryProjectFacingContainerAndColdMavenStillWorks() throws Exception {
        Path configuration = Files.createDirectory(temporary.resolve("docker-client"));
        String invented = "http://invented-user:invented-password@proxy.invalid:3128";
        Files.writeString(configuration.resolve("config.json"), json.writeValueAsString(Map.of("proxies", Map.of(
                "default", Map.of("httpProxy", invented, "httpsProxy", invented, "ftpProxy", invented,
                        "allProxy", invented, "noProxy", "invented.invalid")))));
        var endpoint = process.run(List.of("docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}"),
                null, null, Duration.ofSeconds(20), 8192, () -> false, Map.of());
        assertEquals(0, endpoint.exitCode(), endpoint.diagnostic());
        Map<String, String> environment = Map.of("DOCKER_CONFIG", configuration.toString(),
                "DOCKER_HOST", endpoint.text().strip(), "DOCKER_CONTEXT", "");
        String id = UUID.randomUUID().toString();
        String cache = "df-proxy-test-" + id;
        var defaults = DockerAgentSandboxTest.properties(temporary.resolve("runs"));
        var properties = new AgentSandboxProperties(defaults.root(), defaults.docker(), defaults.images(),
                defaults.defaultJdk(), defaults.owner(), cache, defaults.memory(), defaults.cpus(), defaults.pids());
        Set<String> inspected = new HashSet<>();
        AgentProcess configured = spy(new AgentProcess());
        doAnswer(invocation -> {
            List<String> command = invocation.getArgument(0);
            Map<String, String> additions = new HashMap<>(invocation.getArgument(6));
            int nameIndex = command.indexOf("--name");
            boolean trustedProxy = nameIndex >= 0 && command.get(nameIndex + 1).equals("df-" + id + "-proxy");
            // 假客户端代理仅用于项目路径；可信 artifact proxy 保留本机实际许可的出站配置。
            if (command.getFirst().equals("docker") && !trustedProxy) {
                additions.putAll(environment);
            }
            var result = process.run(command, invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4), invocation.getArgument(5), additions);
            if (result.exitCode() == 0 && command.contains("run") && nameIndex >= 0) {
                String name = command.get(nameIndex + 1);
                String suffix = name.substring(("df-" + id + "-").length());
                if (Set.of("seed", "agent", "prepare", "capture").contains(suffix)) {
                    assertProjectIsolation(name, suffix, environment);
                    inspected.add(suffix);
                }
            }
            return result;
        }).when(configured).run(any(), any(), any(), any(), anyInt(), any(), any());

        var fixture = new DockerAgentSandboxTest();
        fixture.temporary = temporary;
        Path official = fixture.fixture();
        String baseline = fixture.git(official, "rev-parse", "HEAD");
        var sandbox = new DockerAgentSandbox(properties, new DeterministicRepositorySecretPolicy(), configured);
        String control = "df-proxy-control-" + id;
        try {
            // 先证明同一配置确实被 Docker 自动注入；只输出真假，不把假口令写入测试日志。
            var visible = process.run(List.of("docker", "run", "--rm", "--name", control, "--network", "none",
                    "--label", "delveforge.owner=" + properties.owner(), properties.images().get(21), "python3", "-c",
                    "import os; assert all('invented-password' in os.environ.get(k,'') "
                            + "for k in ['FTP_PROXY','ftp_proxy']); print('CONFIG_INJECTION_CONFIRMED')"),
                    null, null, Duration.ofSeconds(30), 8192, () -> false, environment);
            assertEquals(0, visible.exitCode(), visible.diagnostic());
            assertTrue(visible.text().contains("CONFIG_INJECTION_CONFIRMED"));
            sandbox.prepare(new AgentSandboxPort.Task(id, "p", "s", "w", official.toString(), "original", baseline,
                    "fix Counter", "Counter", "repair", 21), AgentProtocol.Limits.defaults(), () -> false);
            String patch = DockerAgentSandboxTest.replacement("src/main/java/example/Counter.java",
                    Files.readString(official.resolve("src/main/java/example/Counter.java")),
                    "package example;\npublic class Counter { public int next(int n) { return n + 1; } }\n");
            assertTrue(sandbox.tool(id, new AgentProtocol.ToolCall("patch", "apply_patch",
                    json.writeValueAsString(Map.of("patch", patch))), () -> false).contains("PATCH_APPLIED"));
            String checks = sandbox.tool(id, new AgentProtocol.ToolCall("test", "run_checks", "{\"goal\":\"test\"}"),
                    () -> false);
            assertTrue(checks.contains("TESTS_PASSED"), checks);
            assertTrue(sandbox.finish(id, () -> false).changedPaths().contains("src/main/java/example/Counter.java"));
            assertEquals(Set.of("seed", "agent", "prepare", "capture"), inspected);
            assertEquals(baseline, fixture.git(official, "rev-parse", "HEAD"));
            assertTrue(fixture.git(official, "status", "--porcelain").isEmpty());
        } finally {
            try {
                sandbox.cleanup(id);
            } finally {
                var removed = process.run(List.of("docker", "volume", "rm", cache), null, null,
                        Duration.ofSeconds(20), 8192, () -> false, environment);
                assertEquals(0, removed.exitCode(), removed.diagnostic());
                Files.delete(configuration.resolve("config.json"));
                Files.delete(configuration);
            }
        }
        assertFalse(Files.exists(properties.root().resolve(id)));
        var containers = process.run(List.of("docker", "ps", "-aq", "--filter", "label=delveforge.run=" + id),
                null, null, Duration.ofSeconds(20), 8192, () -> false, environment);
        assertEquals(0, containers.exitCode());
        assertTrue(containers.text().isBlank());
    }

    private void assertProjectIsolation(String name, String suffix, Map<String, String> environment) throws Exception {
        var result = process.run(List.of("docker", "inspect", name), null, null, Duration.ofSeconds(20),
                65536, () -> false, environment);
        assertEquals(0, result.exitCode(), result.diagnostic());
        JsonNode container = json.readTree(result.bytes()).get(0);
        Map<String, String> values = new HashMap<>();
        for (JsonNode entry : container.path("Config").path("Env")) {
            String[] pair = entry.asText().split("=", 2);
            values.put(pair[0], pair.length == 2 ? pair[1] : "");
        }
        for (String proxy : List.of("HTTP_PROXY", "HTTPS_PROXY", "FTP_PROXY", "ALL_PROXY", "NO_PROXY",
                "http_proxy", "https_proxy", "ftp_proxy", "all_proxy", "no_proxy")) {
            assertTrue(values.containsKey(proxy) && values.get(proxy).isEmpty(), "Proxy isolation failed: " + proxy);
        }
        JsonNode host = container.path("HostConfig");
        assertTrue(host.path("ReadonlyRootfs").asBoolean());
        assertTrue(host.path("CapDrop").toString().contains("ALL"));
        assertTrue(host.path("SecurityOpt").toString().contains("no-new-privileges"));
        assertEquals(2147483648L, host.path("Memory").asLong());
        assertEquals(2000000000L, host.path("NanoCpus").asLong());
        assertEquals(256L, host.path("PidsLimit").asLong());
        assertEquals("11000:11000", container.path("Config").path("User").asText());
        if (suffix.equals("prepare")) {
            assertTrue(host.path("NetworkMode").asText().endsWith("-network"));
        } else {
            assertEquals("none", host.path("NetworkMode").asText());
        }
    }
}
