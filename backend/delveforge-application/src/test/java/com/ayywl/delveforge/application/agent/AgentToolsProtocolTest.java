package com.ayywl.delveforge.application.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolsProtocolTest {
    @Test
    void fileToolGuidanceDescribesSizeContinuationAndBoundedExcerpts() {
        var tools = AgentTools.definitions();
        assertTrue(tools.get(0).description().contains("sizeBytes"));
        assertTrue(tools.get(1).description().contains("400"));
        assertTrue(tools.get(2).description().contains("nextStartLine"));
        assertFalse(tools.get(2).schema().contains("maxOutputBytes"));
    }

    @Test
    void structuredChecklistIsOptionalAndCannotChangeAuthorization() {
        AgentTools.validate(new AgentProtocol.ToolCall("plan", "update_plan",
                "{\"tasks\":[{\"title\":\"Inspect repository\",\"status\":\"in_progress\"}]}"), AgentProtocol.Limits.defaults());
        AgentTools.validate(new AgentProtocol.ToolCall("plan", "update_plan", "{\"tasks\":[]}"), AgentProtocol.Limits.defaults());
        for (String invalid : List.of("{\"notes\":\"free text\"}", "{\"tasks\":[{\"title\":\"x\",\"status\":\"approved\"}]}",
                "{\"tasks\":[],\"authorizedStep\":\"another\"}")) {
            assertThrows(AgentException.class, () -> AgentTools.validate(new AgentProtocol.ToolCall("plan", "update_plan", invalid),
                    AgentProtocol.Limits.defaults()));
        }
        assertFalse(AgentTools.mutation("update_plan"));
        String guidance = AgentTools.definitions().stream().filter(tool -> tool.name().equals("update_plan"))
                .findFirst().orElseThrow().description();
        assertTrue(guidance.contains("若本次维护过清单") && guidance.contains("收尾时"));
        assertTrue(guidance.contains("有证据") && guidance.contains("未完成") && guidance.contains("受阻"));
    }

    @Test
    void setupNeedsNoVerificationMetadataAndInvalidPairsExplainHowToRecover() throws Exception {
        var limits = AgentProtocol.Limits.defaults();
        for (String command : List.of("npm ci", "pnpm install --frozen-lockfile")) {
            AgentTools.validate(new AgentProtocol.ToolCall("id", "exec_command",
                    "{\"command\":\"" + command + "\",\"directory\":\"frontend\"}"), limits);
        }
        var cases = java.util.Map.of(
                "\"verification_scope\":\"frontend tests\"", "INVALID_ARGUMENTS",
                "\"check_kind\":\"test\"", "INVALID_ARGUMENTS",
                "\"verification_scope\":\"tests\",\"check_kind\":\"install\"", "INVALID_ARGUMENTS",
                "\"verification_scope\":\"\",\"check_kind\":\"test\"", "INVALID_ARGUMENTS",
                "\"timeout_seconds\":999", "INVALID_TIMEOUT",
                "\"directory\":\"\"", "INVALID_DIRECTORY");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (var item : cases.entrySet()) {
            var call = new AgentProtocol.ToolCall("id", "exec_command", "{\"command\":\"npm ci\"," + item.getKey() + "}");
            var failure = assertThrows(AgentException.class, () -> AgentTools.validate(call, limits));
            var feedback = json.readTree(AgentTools.argumentError(call, failure, limits));
            assertEquals(item.getValue(), feedback.path("reason").asText());
            assertEquals(true, feedback.path("mutationNotStarted").asBoolean());
            assertFalse(feedback.path("instruction").asText().isBlank());
            assertFalse(feedback.has("exitCode"));
        }
        var custom = new AgentProtocol.Limits(500000, 24000, 131072, 262144, 2097152, 10000, 90);
        var schema = json.readTree(AgentTools.definitions(custom).getLast().schema());
        assertEquals(90, schema.path("properties").path("timeout_seconds").path("maximum").asInt());
    }

    @Test
    void commandProtocolAllowsNativeCompositionButNoHostOrSecuritySettings() {
        AgentTools.validate(new AgentProtocol.ToolCall("id", "exec_command",
                "{\"command\":\"./check.sh && ./build.sh\",\"directory\":\"packages/core\"}"), AgentProtocol.Limits.defaults());
        for (String arguments : List.of("{}", "{\"command\":\"ls\",\"mount\":\"/host\"}",
                "{\"command\":\"ls\",\"timeout_seconds\":0}", "{\"command\":\"ls\",\"network\":\"host\"}")) {
            assertEquals("INVALID_ARGUMENTS", assertThrows(AgentException.class, () -> AgentTools.validate(
                    new AgentProtocol.ToolCall("id", "exec_command", arguments), AgentProtocol.Limits.defaults())).code());
        }
        assertEquals(List.of("list_files", "search_code", "read_file", "edit_file", "update_plan", "read_log", "exec_command"),
                AgentTools.definitions().stream().map(AgentProtocol.ToolSpec::name).toList());
        for (String retired : List.of("apply_patch", "run_checks", "run_frontend_checks")) {
            assertThrows(AgentModelFailure.class, () -> AgentTools.validate(
                    new AgentProtocol.ToolCall("id", retired, "{}"), AgentProtocol.Limits.defaults()));
        }
    }

    @Test
    void invalidToolProtocolHasSpecificReasonsIncludingNullNameWithoutNullPointerFailure() {
        var calls = List.of(new AgentProtocol.ToolCall(null, "read_file", "{}"),
                new AgentProtocol.ToolCall("id", null, "{}"), new AgentProtocol.ToolCall("id", "unknown", "{}"),
                new AgentProtocol.ToolCall("id", "read_file", null),
                new AgentProtocol.ToolCall("id", "edit_file", "x".repeat(262145)));
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
                new AgentProtocol.ToolCall("id", "edit_file", "{\"path\":"), AgentProtocol.Limits.defaults()));
        assertEquals("INVALID_ARGUMENTS", failure.code());
    }
}
