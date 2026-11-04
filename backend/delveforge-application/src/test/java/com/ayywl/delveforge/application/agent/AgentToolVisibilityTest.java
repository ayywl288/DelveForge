package com.ayywl.delveforge.application.agent;

import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentToolVisibilityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void checkResponsesRemoveBareAuthenticationSchemesBeforeModelOrActivityConsumption() {
        try (var service = new CodingAgentService(null, null, null, null,
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            String output = json.createObjectNode().put("output", "failure at line 12; Bearer synthetic-bearer-value")
                    .toString();
            String visible = service.safeToolResult("run_frontend_checks", output);
            assertTrue(visible.contains("failure at line 12"));
            assertFalse(visible.contains("synthetic-bearer-value"));
        }
    }

    @Test
    void sourceViewsRetainRuntimeExpressionsAndDropUnrecognizedPrivateFields() throws Exception {
        try (var service = new CodingAgentService(null, null, null, null,
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            String content = "const token = this.getToken();\n";
            var result = json.createObjectNode().put("path", "src/Auth.js").put("startLine", 1)
                    .put("totalLines", 1).put("content", content).put("_source", "private-field-never-forwarded");
            var raw = json.createObjectNode().put("ok", true).set("result", result);
            String safe = service.safeToolResult("read_file", raw.toString());
            assertEquals(content, json.readTree(safe).path("result").path("content").asText());
            assertFalse(safe.contains("private-field-never-forwarded"));
        }
    }

    @Test
    void actualCredentialsAreStillRemovedAndOtherJsonKeepsCredentialKeyContext() throws Exception {
        try (var service = new CodingAgentService(null, null, null, null,
                new DeterministicRepositorySecretPolicy(), AgentProtocol.Limits.defaults())) {
            var payload = json.createObjectNode().put("path", "src/Auth.js").put("startLine", 1)
                    .put("totalLines", 1).put("content", "const token = 'synthetic-private-value';");
            String safe = service.safeToolResult("read_file", json.createObjectNode().put("ok", true)
                    .set("result", payload).toString());
            assertFalse(safe.contains("synthetic-private-value"));
            assertTrue(safe.contains("redacted-credential"));
            var unrelated = json.createObjectNode().put("password", "synthetic-private-value");
            assertFalse(service.safeToolResult("run_checks", unrelated.toString()).contains("synthetic-private-value"));
        }
    }
}
