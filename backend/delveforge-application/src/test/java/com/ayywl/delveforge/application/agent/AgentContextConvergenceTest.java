package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextConvergenceTest {
    @Test
    void compactionPreservesBothStreamsReceiptsAndLaterExplanationWithoutBreakingPairs() throws Exception {
        var defaults = AgentProtocol.Limits.defaults();
        var limits = new AgentProtocol.Limits(18000, defaults.maxOutputBytes(), defaults.maxPatchBytes(),
                defaults.maxFileBytes(), defaults.maxCandidateBytes(), defaults.maxFiles(), defaults.checkSeconds());
        var service = new CodingAgentService(null, null, null, null, new DeterministicRepositorySecretPolicy(), limits);
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
        var compact = CodingAgentService.class.getDeclaredMethod("compress", List.class, boolean.class);
        compact.setAccessible(true);
        compact.invoke(service, history, true);
        String summary = history.stream().filter(message -> message.text().contains("较早工具上下文已压缩"))
                .map(AgentProtocol.Message::text).findFirst().orElseThrow();
        assertTrue(summary.contains("compilation failed"));
        assertTrue(summary.contains("missing dependency"));
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
