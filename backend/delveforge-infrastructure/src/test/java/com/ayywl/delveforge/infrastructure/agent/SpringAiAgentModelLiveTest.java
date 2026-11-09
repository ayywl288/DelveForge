package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.infrastructure.ai.DeepSeekProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "DELVEFORGE_AGENT_LIVE", matches = "true")
class SpringAiAgentModelLiveTest {
    @Test
    void thinkingReplaysTwoReadRoundsAndProducesBothNonces() throws Exception {
        SpringAiAgentModel model = new SpringAiAgentModel(new DeepSeekProperties(
                "https://api.deepseek.com", System.getenv("DEEPSEEK_API_KEY"), "deepseek-flash",
                Duration.ofSeconds(90), false, Duration.ofSeconds(90), 65536, 1048576, 65536, 8388608), 65536);
        var history = new ArrayList<AgentProtocol.Message>();
        history.add(AgentProtocol.Message.text("system", "先调用 read_file 读取 first.txt，再根据工具返回的 next 字段读取第二个文件。不要一次请求两个文件。最后回复两个 nonce。"));
        history.add(AgentProtocol.Message.text("user", "开始"));
        var specs = List.of(new AgentProtocol.ToolSpec("read_file", "读取文件",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"));
        for (String file : List.of("first.txt", "second.txt")) {
            var reply = model.call(history, specs);
            assertEquals(1, reply.calls().size());
            assertTrue(reply.reasoningContent() != null && !reply.reasoningContent().isBlank());
            var call = reply.calls().getFirst();
            assertEquals("read_file", call.name());
            assertEquals(file, new com.fasterxml.jackson.databind.ObjectMapper().readTree(call.arguments())
                    .required("path").asText());
            history.add(reply.assistantMessage(reply.text()));
            String result = file.equals("first.txt") ? "{\"nonce\":\"alpha-48179\",\"next\":\"second.txt\"}"
                    : "{\"nonce\":\"beta-76391\"}";
            history.add(new AgentProtocol.Message("tool", result, List.of(), call.id(), call.name()));
        }
        var finalReply = model.call(history, specs);
        assertTrue(finalReply.calls().isEmpty());
        assertTrue(finalReply.text().contains("alpha-48179"));
        assertTrue(finalReply.text().contains("beta-76391"));
    }
}
