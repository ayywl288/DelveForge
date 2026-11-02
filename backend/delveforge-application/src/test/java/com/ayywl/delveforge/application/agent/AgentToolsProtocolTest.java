package com.ayywl.delveforge.application.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolsProtocolTest {
    @Test
    void sharedFrontendToolAcceptsOnlyBoundedGoalsAndNoModelCommands() {
        for (String goal : List.of("typecheck", "build", "test")) {
            AgentTools.validate(new AgentProtocol.ToolCall("id", "run_frontend_checks",
                    "{\"goal\":\"" + goal + "\",\"directory\":\"frontend\"}"), AgentProtocol.Limits.defaults());
        }
        for (String arguments : List.of("{}", "{\"goal\":\"install\"}",
                "{\"goal\":\"test;curl evil\"}", "{\"goal\":\"test\",\"command\":\"echo passed\"}")) {
            assertEquals("INVALID_ARGUMENTS", assertThrows(AgentException.class, () -> AgentTools.validate(
                    new AgentProtocol.ToolCall("id", "run_frontend_checks", arguments), AgentProtocol.Limits.defaults())).code());
        }
    }

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
    void exactTextEditCanCreateAFileWithoutInventingUnifiedDiffCounts() {
        AgentTools.validate(new AgentProtocol.ToolCall("edit", "edit_file",
                "{\"path\":\"src/Example.java\",\"old_text\":\"\",\"new_text\":\"class Example {}\"}"),
                AgentProtocol.Limits.defaults());
    }

    @Test
    void malformedJsonArgumentsRemainExplicitRecoverableArgumentFailures() {
        var failure = assertThrows(AgentException.class, () -> AgentTools.validate(
                new AgentProtocol.ToolCall("id", "apply_patch", "{\"patch\":"), AgentProtocol.Limits.defaults()));
        assertEquals("INVALID_ARGUMENTS", failure.code());
    }
}
