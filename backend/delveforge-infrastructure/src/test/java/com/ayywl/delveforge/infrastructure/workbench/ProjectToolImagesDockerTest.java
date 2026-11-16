package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.workbench.Workbench;
import com.ayywl.delveforge.application.workbench.WorkbenchRejection;
import com.ayywl.delveforge.domain.workbench.RunState;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "DF_WORKBENCH_TOOL_FIXTURE", matches = ".+")
class ProjectToolImagesDockerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temp;

    private record Project(String id, String conversation, Path copy) {}

    private Project project(Workbench store, Path root) throws Exception {
        String id = store.beginProject("工具镜像验收", null);
        Path copy = Files.createDirectories(root.resolve("projects").resolve(id));
        store.projectResult(id, copy.toString(), null);
        return new Project(id, store.conversation(id, "APT 验收"), copy);
    }

    private static void run(com.ayywl.delveforge.application.workbench.ConversationAgent agent,
            Workbench store, Project p, String command) throws Exception {
        var ack = agent.receive(p.conversation(), UUID.randomUUID().toString(), command);
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (store.run(ack.runId()).state() == RunState.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(120);
        }
        assertEquals(RunState.COMPLETED, store.run(ack.runId()).state(), store.runs(p.conversation()).toString());
        var receipts = store.timeline(p.conversation()).stream().filter(row -> "tool".equals(row.get("role"))).toList();
        var receipt = JSON.readTree(receipts.getLast().get("text").toString());
        assertEquals(0, receipt.path("exit").asInt(-1), receipt.toString());
    }

    @Test
    void userPublishesOnlyVerifiedAptArchivesAndCanRestoreAnOldProjectLayer() throws Exception {
        Path root = Path.of(System.getenv("DF_WORKBENCH_TOOL_FIXTURE"));
        var store = WorkbenchDockerTest.store(temp.resolve("control.db"));
        var a = project(store, root);
        var b = project(store, root);
        var tools = new DockerProjectTools(root, JSON);
        try (var env = new DockerDevelopmentEnvironment(root, JSON, 270);
                var agent = WorkbenchDockerTest.agent(root, store, env, (messages, schemas) -> {
                    var last = messages.getLast();
                    if (last.role().equals("tool")) {
                        return new AgentProtocol.Reply("已取得命令回执", List.of(), 1L, null, "夹具");
                    }
                    String args = JSON.createObjectNode().put("command", last.text()).toString();
                    return new AgentProtocol.Reply("", List.of(new AgentProtocol.ToolCall("apt-" + UUID.randomUUID(),
                            "Bash", args)), 1L, null, "夹具");
                })) {
            assertFalse((boolean) tools.inventory(a.id()).get("initialized"));
            run(agent, store, a, "set -eu; echo private > workcopy-only; echo confidential > \"$HOME/home-only\"; "
                    + "printf '#!/bin/sh\\necho local\\n' > /usr/local/bin/private-test-tool; chmod +x /usr/local/bin/private-test-tool; "
                    + "apt-get -q -o APT::Update::Error-Mode=any update >apt-install.log 2>&1 && "
                    + "apt-get -qy --no-install-recommends install figlet >>apt-install.log 2>&1 && figlet -I1");
            run(agent, store, a, "figlet -I1 && private-test-tool && "
                    + "setsid -f python3 -m http.server 8072 --bind 127.0.0.1 </dev/null >server.log 2>&1");
            run(agent, store, b, "test -z \"$(command -v figlet)\" && test -z \"$(command -v private-test-tool)\"");
            var originalB = env.registered(b.id());
            var inventory = JSON.valueToTree(tools.inventory(a.id()));
            assertTrue(inventory.path("manualFiles").toString().contains("private-test-tool"));
            assertTrue(inventory.path("installed").toString().contains("figlet"));
            assertThrows(WorkbenchRejection.class, () -> tools.preview(a.id(), "private-test-tool"));
            assertThrows(WorkbenchRejection.class, () -> tools.preview(a.id(), "figlet;echo"));
            var preview = tools.preview(a.id(), "figlet");
            assertThrows(WorkbenchRejection.class, () -> tools.publish(a.id(), "figlet", "0".repeat(64)));
            assertFalse(Files.exists(root.resolve("tool-images.json")));
            Path staged = root.resolve("tool-builds").resolve(a.id());
            var selection = JSON.readTree(staged.resolve("selection.json").toFile());
            Path archive = staged.resolve(selection.path("dir").asText()).resolve("archives")
                    .resolve(selection.path("selected").path("sha256").asText() + ".deb");
            byte[] approvedBytes = Files.readAllBytes(archive);
            Files.writeString(archive, "invalid archive");
            assertThrows(WorkbenchRejection.class, () -> tools.publish(a.id(), "figlet", (String) preview.get("plan")));
            assertFalse(Files.exists(root.resolve("tool-images.json")));
            Files.write(archive, approvedBytes);
            var published = tools.publish(a.id(), "figlet", (String) preview.get("plan"));
            System.out.println("TOOL IMAGE " + published);
            var c = project(store, root);
            run(agent, store, c, "figlet -I1 && test ! -e /usr/local/bin/private-test-tool && "
                    + "test ! -e workcopy-only && test ! -e \"$HOME/home-only\"");
            assertEquals(published.get("image"), env.registered(c.id()).image());
            assertEquals(originalB.containerId(), env.registered(b.id()).containerId());
            run(agent, store, b, "test -z \"$(command -v figlet)\"");
            Path file = root.resolve("tool-images.json");
            String successful = Files.readString(file);
            var registry = (ObjectNode) JSON.readTree(successful);
            ((ObjectNode) registry.path("releases").get(0)).put("image", "sha256:" + "e".repeat(64));
            Files.writeString(file, registry.toString());
            try {
                assertThrows(IllegalStateException.class, () -> agent.maintain(b.id(), "Rebuild"));
                assertEquals(originalB.containerId(), env.registered(b.id()).containerId());
                run(agent, store, b, "echo survived > survives && test -z \"$(command -v figlet)\"");
            } finally {
                Files.writeString(file, successful);
            }
            agent.maintain(b.id(), "Rebuild");
            run(agent, store, b, "figlet -I1 && grep -q survived survives");
            agent.maintain(b.id(), "Rollback");
            assertEquals(originalB.containerId(), env.registered(b.id()).containerId());
            run(agent, store, b, "test -z \"$(command -v figlet)\" && grep -q survived survives");
            run(agent, store, a, "figlet -I1 && private-test-tool && grep -q confidential \"$HOME/home-only\" "
                    + "&& curl --noproxy '*' -fsS http://127.0.0.1:8072/workcopy-only | grep -q private");
        }
    }
}
