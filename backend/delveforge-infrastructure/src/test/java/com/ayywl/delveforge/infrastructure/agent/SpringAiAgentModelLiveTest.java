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
    void nativeToolCallIsReceivedExecutedOnceAndContinued() {
        SpringAiAgentModel model = new SpringAiAgentModel(new DeepSeekProperties(
                "https://api.deepseek.com", System.getenv("DEEPSEEK_API_KEY"), "deepseek-flash",
                Duration.ofSeconds(90), false, Duration.ofSeconds(90), 4096, 1048576, 65536), 4096);
        var history = new ArrayList<AgentProtocol.Message>();
        history.add(AgentProtocol.Message.text("system", "必须先调用 read_file 读取 pom.xml，收到工具结果后只回复其中的 nonce。"));
        history.add(AgentProtocol.Message.text("user", "开始"));
        var specs = List.of(new AgentProtocol.ToolSpec("read_file", "读取文件",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}"));
        var first = model.call(history, specs);
        assertEquals(1, first.calls().size());
        assertEquals("read_file", first.calls().getFirst().name());
        int executed = 0;
        var call = first.calls().getFirst();
        history.add(new AgentProtocol.Message("assistant", first.text(), first.calls(), null, null));
        executed++;
        history.add(new AgentProtocol.Message("tool", "{\"nonce\":\"task1-native-48179\"}",
                List.of(), call.id(), call.name()));
        var second = model.call(history, specs);
        assertTrue(second.calls().isEmpty());
        assertTrue(second.text().contains("task1-native-48179"));
        assertEquals(1, executed);
    }
}
