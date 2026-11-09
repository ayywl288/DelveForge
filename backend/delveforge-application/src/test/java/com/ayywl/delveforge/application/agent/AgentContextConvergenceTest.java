package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextConvergenceTest {
    @Test
    void constrainedSummaryChoosesFactsBeforeStatementsButDisplaysNewestFirst() throws Exception {
        var history = new ArrayList<>(List.of(AgentProtocol.Message.text("system", "fixed"),
                AgentProtocol.Message.text("user", "confirmed scope")));
        var service = summaryService(1600);
        addInteraction(history, "edit", "edit_file", "{\"path\":\"A\",\"old_text\":\"private-patch\"}", true,
                "{\"paths\":[\"A\"]}");
        addInteraction(history, "exec", "exec_command", "{\"command\":\"check\"}", true,
                "{\"invocationId\":\"receipt\",\"stdout\":\"private-output\"}");
        addInteraction(history, "failed", "read_log", "{}", false, "{\"content\":\"private-log\"}");
        history.add(AgentProtocol.Message.text("user", "Completion rejected; recheck the receipt."));
        for (int i = 10; i < 30; i++) {
            addInteraction(history, "list-" + i, "list_files", "{}", true, "{\"files\":[\"A\"]}");
        }
        addInteraction(history, "search", "search_code", "{\"query\":\"next\"}", true,
                "{\"matches\":[{\"path\":\"A\",\"line\":7,\"excerpt\":\"private-match\"}]}");
        addInteraction(history, "read", "read_file", "{\"path\":\"A\"}", true,
                "{\"startLine\":2,\"lineCount\":3,\"content\":\"private-source\"}");
        for (int i = 0; i < 8; i++) {
            history.add(AgentProtocol.Message.text("assistant", i + " unverified " + "x".repeat(600)));
        }
        var recent = new ArrayList<AgentProtocol.Message>();
        for (int i = 0; i < 6; i++) {
            addInteraction(recent, "recent-" + i, "read_file", "{}", true, "{}");
        }
        history.addAll(recent);
        service.compress(history, "", List.of(), true, false);
        assertEquals(recent, history.subList(3, history.size()));
        String summary = history.get(2).text();
        assertTrue(summary.length() <= 1600);
        assertFalse(summary.contains("private-"));
        assertFalse(summary.contains("unverified"));

        var json = new ObjectMapper();
        var ids = new ArrayList<String>();
        for (String line : summary.lines().skip(1).toList()) {
            var record = json.readTree(line);
            ids.add(record.path("callId").asText(record.path("kind").asText()));
        }
        assertEquals(List.of("read", "search", "list-29"), ids.subList(0, 3));
        assertFalse(ids.contains("list-10"));
        assertEquals(List.of("platform_feedback", "failed", "exec", "edit"), ids.subList(ids.size() - 4, ids.size()));
        assertTrue(summary.contains("\"startLine\":2,\"lineCount\":3"));
        assertTrue(summary.contains("\"path\":\"A\",\"line\":7"));
        assertTrue(summary.contains("\"invocationId\":\"receipt\""));

        // 再加入一组，使原摘要和最早的近期组一起被压缩。
        addInteraction(history, "recent-6", "read_file", "{}", true, "{}");
        var expectedSuffix = List.copyOf(history.subList(history.size() - 12, history.size()));
        service.compress(history, "", List.of(), true, false);
        assertEquals(expectedSuffix, history.subList(3, history.size()));
        var lines = history.get(2).text().lines().toList();
        assertEquals(lines.size(), new HashSet<>(lines).size());
        assertTrue(history.get(2).text().length() <= 1600);
        assertTrue(history.get(2).text().contains("\"callId\":\"recent-0\""));
        assertTrue(history.get(2).text().contains("\"invocationId\":\"receipt\""));
    }

    @Test
    void spareSummarySpaceRetainsTheMostRecentUnverifiedStatement() {
        var history = new ArrayList<>(List.of(AgentProtocol.Message.text("system", "fixed"),
                AgentProtocol.Message.text("user", "confirmed scope")));
        var service = summaryService(1024);
        history.add(AgentProtocol.Message.text("user", "Correction"));
        for (int i = 0; i < 4; i++) {
            history.add(AgentProtocol.Message.text("assistant", "statement-" + i + " " + "x".repeat(550)));
        }
        for (int i = 0; i < 6; i++) {
            addInteraction(history, "recent-" + i, "read_file", "{}", true, "{}");
        }
        service.compress(history, "", List.of(), true, false);
        String summary = history.get(2).text();
        assertTrue(summary.contains("platform_feedback"));
        assertTrue(summary.contains("statement-3"));
        assertFalse(summary.contains("statement-2"));
        assertTrue(summary.indexOf("statement-3") < summary.indexOf("platform_feedback"));
    }

    private CodingAgentService summaryService(int maxSummaryChars) {
        var defaults = AgentProtocol.Limits.defaults();
        var limits = new AgentProtocol.Limits(maxSummaryChars * 4, defaults.maxOutputBytes(), defaults.maxPatchBytes(),
                defaults.maxFileBytes(), defaults.maxCandidateBytes(), defaults.maxFiles(), defaults.checkSeconds());
        return new CodingAgentService(null, null, null, null, null, new DeterministicRepositorySecretPolicy(), limits,
                (messages, tools) -> messages.stream().mapToLong(m -> m.text().length()).sum(),
                new AgentContextBudget(50000, 100, 0.90));
    }

    private void addInteraction(List<AgentProtocol.Message> history, String id, String tool, String arguments,
            boolean ok, String result) {
        history.add(new AgentProtocol.Message("assistant", "", List.of(new AgentProtocol.ToolCall(id, tool, arguments)),
                null, null));
        history.add(new AgentProtocol.Message("tool", "{\"ok\":" + ok + ",\"result\":" + result + "}",
                List.of(), id, tool));
    }

    @Test
    void pairsReorderedResultsAndWhitelistsAllHistoricalTools() throws Exception {
        var json = new ObjectMapper();
        var limits = AgentProtocol.Limits.defaults();
        var service = new CodingAgentService(null, null, null, null, null, new DeterministicRepositorySecretPolicy(),
                limits, (messages, tools) -> messages.stream().mapToLong(m -> m.text().length()).sum(),
                new AgentContextBudget(50000, 100, 0.90));
        var calls = List.of(
                new AgentProtocol.ToolCall("list", "list_files", "{\"path\":\"src\",\"offset\":4,\"limit\":2}"),
                new AgentProtocol.ToolCall("find", "search_code", "{\"query\":\"next\",\"path\":\"src\"}"),
                new AgentProtocol.ToolCall("read", "read_file", "{\"path\":\"src/A\",\"start_line\":2,\"line_count\":4}"),
                new AgentProtocol.ToolCall("edit", "edit_file", "{\"path\":\"src/B\",\"old_text\":\"private-before\",\"new_text\":\"private-after\"}"),
                new AgentProtocol.ToolCall("exec", "exec_command", "{\"command\":\"echo Authorization: Bearer synthetic-secret-token\",\"directory\":\"app\",\"timeout_seconds\":8}"),
                new AgentProtocol.ToolCall("log", "read_log", "{\"invocation_id\":\"exec-7\",\"stream\":\"stderr\",\"offset\":10,\"limit\":3}"),
                new AgentProtocol.ToolCall("plan", "update_plan", "{}"));
        var details = Map.of(
                "list", "{\"files\":[\"src/A\",{\"path\":\"src/B\",\"sizeBytes\":90}],\"directories\":[\"src\"],\"nextOffset\":6,\"total\":9}",
                "find", "{\"matches\":[{\"path\":\"src/A\",\"line\":7,\"excerpt\":\"private-match\"}],\"truncated\":true}",
                "read", "{\"path\":\"src/A\",\"startLine\":2,\"lineCount\":3,\"hasMore\":true,\"nextStartLine\":5,\"content\":\"private-source\"}",
                "edit", "{\"classification\":\"PATCH_CONFLICT\",\"paths\":[\"src/B\"],\"conflictReason\":\"CONTEXT_MISMATCH\",\"mutationNotStarted\":true}",
                "exec", "{\"classification\":\"COMMAND_FAILED\",\"kind\":\"shell\",\"exitCode\":1,\"timedOut\":false,\"cancelled\":false,\"sourceChanged\":true,\"snapshot\":\"a\",\"afterSnapshot\":\"b\",\"invocationId\":\"exec-7\",\"logRef\":\"ref7\",\"failures\":2,\"stdout\":\"private-out\",\"stderr\":\"private-err\"}",
                "log", "{\"invocationId\":\"exec-7\",\"stream\":\"stderr\",\"offset\":10,\"nextOffset\":13,\"logDiscarded\":false,\"outputTruncated\":true,\"content\":\"private-log\"}",
                "plan", "{\"notes\":\"private-old-notes\"}");
        var history = new ArrayList<>(List.of(AgentProtocol.Message.text("system", "fixed"),
                AgentProtocol.Message.text("user", "confirmed scope")));
        history.add(new AgentProtocol.Message("assistant", "needs another check", calls, null, null));
        for (var call : calls.reversed()) {
            history.add(new AgentProtocol.Message("tool", "{\"ok\":true,\"result\":" + details.get(call.id()) + "}",
                    List.of(), call.id(), call.name()));
        }
        history.add(AgentProtocol.Message.text("user", "Completion rejected; previous response discarded."));
        var actual = service.compress(history, "latest plan", List.of(), true, false);
        assertTrue(actual.messages().getLast().text().contains("latest plan"));
        assertEquals(3, history.size());
        String summary = history.getLast().text();
        assertFalse(summary.contains("private-"));
        assertFalse(summary.contains("synthetic-secret-token"));
        assertFalse(summary.contains("sizeBytes"));
        assertFalse(summary.contains("update_plan"));

        var records = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        for (String line : summary.lines().skip(1).toList()) {
            var record = json.readTree(line);
            records.put(record.path("callId").asText(record.path("kind").asText()), record);
        }
        assertEquals(8, records.size());
        assertEquals("src/B", records.get("list").at("/files/1").asText());
        assertEquals(4, records.get("list").at("/arguments/offset").asInt());
        assertEquals(9, records.get("list").path("total").asInt());
        assertEquals("next", records.get("find").at("/arguments/query").asText());
        assertEquals("src/A", records.get("find").at("/matches/0/path").asText());
        assertEquals(7, records.get("find").at("/matches/0/line").asInt());
        assertEquals(4, records.get("read").at("/arguments/line_count").asInt());
        assertEquals(5, records.get("read").path("nextStartLine").asInt());
        assertTrue(records.get("edit").path("mutationNotStarted").asBoolean());
        assertEquals("src/B", records.get("edit").at("/arguments/path").asText());
        assertEquals("tool", records.get("exec").path("kind").asText());
        assertEquals("shell", records.get("exec").path("executionKind").asText());
        assertEquals("app", records.get("exec").at("/arguments/directory").asText());
        assertTrue(records.get("exec").path("sourceChanged").asBoolean());
        assertEquals("ref7", records.get("exec").path("logRef").asText());
        assertEquals(2, records.get("exec").path("failures").asInt());
        assertEquals(13, records.get("log").path("nextOffset").asInt());
        assertEquals(3, records.get("log").at("/arguments/limit").asInt());
        assertTrue(records.get("platform_feedback").path("text").asText().contains("Completion rejected"));
        assertTrue(records.get("agent_statement").path("attribution").asText().contains("not verified"));
        service.compress(history, "latest plan", List.of(), true, false);
        assertEquals(summary, history.getLast().text());

        String prefix = summary.substring(0, summary.indexOf('\n') + 1);
        var legacy = json.createObjectNode().put("tool", "exec_command").put("kind", "shell")
                .put("classification", "COMMAND_FAILED").put("observedExcerpt", "private-legacy-stdout")
                .put("logRef", "legacy-log");
        history.set(2, AgentProtocol.Message.text("user", prefix + legacy + "\n"));
        service.compress(history, "latest plan", List.of(), true, false);
        String migrated = history.getLast().text();
        assertTrue(migrated.contains("legacy-log"));
        assertFalse(migrated.contains("private-legacy-stdout"));
        assertFalse(migrated.contains("observedExcerpt"));
    }

    @Test
    void compactionPreservesBothStreamsReceiptsAndLaterExplanationWithoutBreakingPairs() throws Exception {
        var defaults = AgentProtocol.Limits.defaults();
        var limits = new AgentProtocol.Limits(18000, defaults.maxOutputBytes(), defaults.maxPatchBytes(),
                defaults.maxFileBytes(), defaults.maxCandidateBytes(), defaults.maxFiles(), defaults.checkSeconds());
        var service = new CodingAgentService(null, null, null, null, null, new DeterministicRepositorySecretPolicy(), limits,
                (messages, tools) -> messages.stream().mapToLong(m -> m.text().length()).sum(),
                new AgentContextBudget(11000, 1, 0.90));
        var json = new ObjectMapper();
        List<AgentProtocol.Message> history = new ArrayList<>();
        history.add(AgentProtocol.Message.text("system", "Stable authorization"));
        history.add(AgentProtocol.Message.text("user", "Confirmed goal"));
        history.add(AgentProtocol.Message.text("user", "执行环境（能力与限制，不是任务完成证据或额外授权）：\ncontrolled network"));
        for (int i = 0; i < 10; i++) {
            String identity = "call-" + i;
            String tool = i == 6 ? "exec_command" : "read_file";
            history.add(new AgentProtocol.Message("assistant", i == 7 ? "Agent: earlier failure still needs a recheck" : "",
                    List.of(new AgentProtocol.ToolCall(identity, tool, "{}")), null, null));
            var root = json.createObjectNode().put("ok", i != 6);
            var result = root.putObject("result").put("path", "README.md").put("content", "x".repeat(2600));
            if (i == 6) {
                result.put("classification", "COMMAND_FAILED").put("invocationId", "recorded-execution")
                        .put("logRef", "recorded-execution").put("stdout", "compilation failed\n" + "x".repeat(2600))
                        .put("stderr", "missing dependency\nAuthorization: Bearer private-token-value");
            }
            history.add(new AgentProtocol.Message("tool", root.toString(), List.of(), identity, tool));
        }
        service.compress(history, "", List.of(), false, false);
        String summary = history.stream().filter(message -> message.text().contains("较早工具上下文已压缩"))
                .map(AgentProtocol.Message::text).findFirst().orElseThrow();
        assertFalse(summary.contains("compilation failed"));
        assertFalse(summary.contains("missing dependency"));
        assertTrue(summary.contains("recorded-execution"));
        assertFalse(summary.contains("private-token-value"));
        assertTrue(history.get(1).text().contains("Confirmed goal"));
        assertTrue(history.get(2).text().contains("controlled network"));
        HashSet<String> pending = new HashSet<>();
        for (var message : history) {
            message.calls().forEach(call -> pending.add(call.id()));
            if (message.role().equals("tool")) {
                assertTrue(pending.remove(message.callId()));
            }
        }
        assertTrue(pending.isEmpty());
    }
}
