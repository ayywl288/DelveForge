package com.ayywl.delveforge.application.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolsProtocolTest {
    @Test
    void invalidToolProtocolHasSpecificReasonsIncludingNullNameWithoutNullPointerFailure() {
        var calls = List.of(new AgentProtocol.ToolCall(null, "read_file", "{}"),
                new AgentProtocol.ToolCall("id", null, "{}"), new AgentProtocol.ToolCall("id", "unknown", "{}"),
                new AgentProtocol.ToolCall("id", "read_file", null),
                new AgentProtocol.ToolCall("id", "apply_patch", "x".repeat(262145)));
        var reasons = List.of(AgentModelFailure.Reason.TOOL_ID_INVALID, AgentModelFailure.Reason.TOOL_NAME_INVALID,
                AgentModelFailure.Reason.TOOL_NAME_INVALID, AgentModelFailure.Reason.TOOL_ARGUMENTS_INVALID,
                AgentModelFailure.Reason.TOOL_ARGUMENTS_LIMIT);
        for (int i = 0; i < calls.size(); i++) {
            var call = calls.get(i);
            var failure = assertThrows(AgentModelFailure.class, () -> AgentTools.validate(call, AgentProtocol.Limits.defaults()));
            assertEquals("MODEL_PROTOCOL", failure.code());
            assertEquals(reasons.get(i), failure.reason());
            assertFalse(failure.getMessage().contains("unknown"));
        }
    }

    @Test
    void malformedJsonArgumentsRemainExplicitRecoverableArgumentFailures() {
        var failure = assertThrows(AgentException.class, () -> AgentTools.validate(
                new AgentProtocol.ToolCall("id", "apply_patch", "{\"patch\":"), AgentProtocol.Limits.defaults()));
        assertEquals("INVALID_ARGUMENTS", failure.code());
    }
}
