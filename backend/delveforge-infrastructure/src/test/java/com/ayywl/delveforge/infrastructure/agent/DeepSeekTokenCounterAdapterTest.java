package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.port.ai.AiMessage;
import com.ayywl.delveforge.application.port.ai.AiRole;
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = Thinking.class, names = {"DISABLED", "LOW"})
    void countsToolFreeDiscoveryHistoryWithGenerationPrefix(Thinking mode) {
        // 基础值来自官方 recipe 0.2.0 的 Chinese / no tools 样本；每对 a/b 是两枚正文、
        // user/assistant 两枚角色标记、历史思考结束标记和 EOS，共六枚 token。
        boolean low = mode == Thinking.LOW;
        var history = new ArrayList<AiMessage>();
        var user = AiRole.USER;
        var assistant = AiRole.ASSISTANT;
        history.add(new AiMessage(
                AiRole.SYSTEM, low ? "Authorized step" : "仅执行已确认步骤。"));
        var latest = new AiMessage(user,
                low ? "Inspect files" : "读取下一页文件并核对行号。");
        try (var counter = new DeepSeekTokenCounterAdapter("deepseek-flash", Thinking.HIGH)) {
            for (int round = 0; round <= 12; round++) {
                history.add(latest);
                assertEquals((low ? 36 : 19) + 6 * round, counter.countDialogue(history, mode));
                history.removeLast();
                history.add(new AiMessage(user, "a"));
                history.add(new AiMessage(assistant, "b"));
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
