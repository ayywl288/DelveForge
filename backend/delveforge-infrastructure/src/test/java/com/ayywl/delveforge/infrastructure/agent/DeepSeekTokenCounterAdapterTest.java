package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.port.ai.AiRequest.Thinking;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeepSeekTokenCounterAdapterTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"deepseek-recipe-0.2.0.json", "deepseek-thinking-0.2.0.json"})
    void matchesOfflineRecipe020Fixtures(String resource) throws Exception {
        try (var fixtures = getClass().getResourceAsStream("/" + resource)) {
            for (var fixture : new ObjectMapper().readTree(fixtures)) {
                var messages = new ArrayList<AgentProtocol.Message>();
                for (var m : fixture.path("messages")) {
                    var calls = new ArrayList<AgentProtocol.ToolCall>();
                    for (var c : m.path("tool_calls")) {
                        var fn = c.path("function");
                        calls.add(new AgentProtocol.ToolCall(c.path("id").asText(), fn.path("name").asText(),
                                fn.path("arguments").asText()));
                    }
                    messages.add(new AgentProtocol.Message(m.path("role").asText(), m.path("content").asText(),
                            calls, m.path("tool_call_id").asText(null), null, m.path("reasoning_content").asText(null)));
                }
                var tools = new ArrayList<AgentProtocol.ToolSpec>();
                for (var t : fixture.path("tools")) {
                    var fn = t.path("function");
                    tools.add(new AgentProtocol.ToolSpec(fn.path("name").asText(), fn.path("description").asText(),
                            fn.path("parameters").toString()));
                }
                var mode = Thinking.valueOf(fixture.path("effort").asText("disabled").toUpperCase(java.util.Locale.ROOT));
                try (var counter = new DeepSeekTokenCounterAdapter("deepseek-flash", mode)) {
                    assertEquals(fixture.path("tokens").asLong(), counter.countPromptTokens(messages, tools),
                            fixture.path("name").asText());
                }
            }
        }
    }

    @Test
    void offlineInitializationRestoresTheProcessTokenizerSetting() {
        var previous = System.getProperty("RUST_FLAVOR");
        try (var ignored = new DeepSeekTokenCounterAdapter("deepseek-flash", Thinking.DISABLED)) {
            assertEquals(previous, System.getProperty("RUST_FLAVOR"));
        }
    }

    @Test
    void rejectsUnsupportedModel() {
        assertThrows(IllegalArgumentException.class, () -> new DeepSeekTokenCounterAdapter("unmatched", Thinking.DISABLED));
    }
}
