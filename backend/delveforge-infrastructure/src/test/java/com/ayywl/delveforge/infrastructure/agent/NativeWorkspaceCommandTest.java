package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 原生 workspace 链接、冻结锁和生命周期实际边界；没有平台项目适配器。 */
@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_DOCKER", matches = "true")
class NativeWorkspaceCommandTest {
    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode tool(DockerAgentSandbox sandbox, String id, String name, Map<String, ?> arguments) throws Exception {
        return json.readTree(sandbox.tool(id, new AgentProtocol.ToolCall(UUID.randomUUID().toString(), name,
                json.writeValueAsString(arguments)), () -> false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"npm", "pnpm"})
    void workspaceLockLifecycleAndLocalDependenciesUseNativeSemantics(String manager) throws Exception {
        Path original = Files.createDirectories(temporary.resolve("original"));
        for (String directory : List.of("packages/lib", "packages/app", "scripts")) {
            Files.createDirectories(original.resolve(directory));
        }
        String declaration = manager.equals("npm") ? "npm@10.9.2" : "pnpm@12.9.1";
        Files.writeString(original.resolve("package.json"), json.writeValueAsString(Map.of(
                "name", "workspace-fixture", "private", true, "packageManager", declaration,
                "workspaces", List.of("packages/*"), "scripts", Map.of("test", "node packages/app/check.cjs",
                        "postinstall", "node scripts/boundary.cjs"))));
        if (manager.equals("pnpm")) {
            Files.writeString(original.resolve("pnpm-workspace.yaml"), "packages:\n  - 'packages/*'\n");
        }
        Files.writeString(original.resolve("packages/lib/package.json"),
                "{\"name\":\"fixture-lib\",\"version\":\"1.0.0\",\"main\":\"index.cjs\"}");
        Files.writeString(original.resolve("packages/lib/index.cjs"), "exports.value = 0;\n");
        Files.writeString(original.resolve("packages/app/package.json"), json.writeValueAsString(Map.of(
                "name", "fixture-app", "version", "1.0.0", "dependencies", Map.of("fixture-lib", manager.equals("npm") ? "1.0.0" : "workspace:*"))));
        Files.writeString(original.resolve("packages/app/check.cjs"),
                "require('node:assert/strict').equal(require('fixture-lib').value, 1);\n");
        Files.writeString(original.resolve("scripts/boundary.cjs"), """
                const assert = require('node:assert/strict');
                const fs = require('node:fs');
                const http = require('node:http');
                assert.equal(process.env.DEEPSEEK_API_KEY, undefined);
                for (const path of ['/source', '/host', '/var/run/docker.sock']) assert.equal(fs.existsSync(path), false);
                assert.throws(() => fs.writeFileSync('/opt/delveforge/command.py', 'changed'));
                http.get('http://df-artifacts:8080/npm/vue', response => {
                    assert.equal(response.statusCode, 200);
                    response.resume();
                    response.on('end', () => {
                        const denied = http.request({host: 'df-artifacts', port: 8080, method: 'CONNECT', path: 'example.com:443'});
                        denied.on('connect', (reply, socket) => {
                            assert.equal(reply.statusCode, 403); socket.destroy();
                            fs.mkdirSync('.cache', {recursive: true}); fs.writeFileSync('.cache/lifecycle', 'verified');
                        });
                        denied.on('error', error => { throw error; }); denied.end();
                    });
                }).on('error', error => { throw error; });
                """);
        Files.writeString(original.resolve(".gitignore"), "node_modules/\n.cache/\n");
        Files.writeString(original.resolve(".gitattributes"), "* -text\n");
        var git = new DockerAgentSandboxTest();
        git.git(original, "init", "--template=");
        git.git(original, "add", ".");
        git.git(original, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "-qm", "workspace");
        String baseline = git.git(original, "rev-parse", "HEAD");
        String id = UUID.randomUUID().toString();
        var sandbox = new DockerAgentSandbox(DockerAgentSandboxTest.properties(temporary.resolve("runs")),
                new DeterministicRepositorySecretPolicy());
        try {
            sandbox.prepare(new AgentSandboxPort.Task(id, "plan", "step", "copy", original.toString(),
                    "unmounted", baseline, "fix library", "packages", "increment", 21), AgentProtocol.Limits.defaults(), () -> false);
            String install = manager.equals("npm") ? "npm install --package-lock-only --ignore-scripts --no-audit --no-fund && npm ci --no-audit --no-fund"
                    : "pnpm install --lockfile-only --ignore-scripts && pnpm install --frozen-lockfile";
            JsonNode installed = tool(sandbox, id, "exec_command", Map.of("command", install));
            assertTrue(installed.path("ok").asBoolean(), installed.toString());
            assertTrue(tool(sandbox, id, "exec_command", Map.of("command", "test -f .cache/lifecycle")).path("ok").asBoolean());
            Map<String, String> check = Map.of("command", manager + " test", "verification_scope", "Workspace linked library regression", "check_kind", "test");
            assertFalse(tool(sandbox, id, "exec_command", check).path("ok").asBoolean());
            assertTrue(tool(sandbox, id, "edit_file", Map.of("path", "packages/lib/index.cjs", "old_text", "value = 0", "new_text", "value = 1")).path("ok").asBoolean());
            JsonNode passed = tool(sandbox, id, "exec_command", check);
            assertTrue(passed.path("ok").asBoolean(), passed.toString());
            var candidate = sandbox.finish(id, () -> false);
            assertTrue(candidate.changedPaths().contains(manager.equals("npm") ? "package-lock.json" : "pnpm-lock.yaml"));
            assertTrue(candidate.changedPaths().contains("packages/lib/index.cjs"));
            assertFalse(candidate.changedPaths().stream().anyMatch(path -> path.contains("node_modules") || path.startsWith(".cache")));
            assertEquals(baseline, git.git(original, "rev-parse", "HEAD"));
            assertEquals("", git.git(original, "status", "--porcelain"));
        } finally {
            sandbox.cleanup(id);
        }
    }
}
