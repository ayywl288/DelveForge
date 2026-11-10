package com.ayywl.delveforge.app.api.userprofile;

import com.ayywl.delveforge.application.port.ai.AiGateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 实际监听端口的 HTTP 与 SQLite 集成；Provider 为确定性替身。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProfileUpdateApprovalApiTest {

    private static final Path DATABASE = Path.of("target", "profile-approval-api", UUID.randomUUID() + ".db");
    private static final String EXTRACTION = """
            {"hasNewFacts":true,"constraints":["约三小时完成"],"behaviors":["整理README使用示例"],
             "evidenceClaims":["用户希望调整时间并补充示例"]}
            """;
    private static final String PLAN = """
            {"sufficient":false,"missingAreas":["具体场景"],"assistantReply":"可以核对这次建议。",
             "previousQuestionState":"NONE","nextAction":"PAUSE","nextQuestion":null,
             "questionArea":null,"deferredAreas":[],"resumedAreas":[]}
            """;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE::toString);
    }

    @Autowired private TestRestTemplate http;
    @MockitoBean private AiGateway ai;

    @Test
    void pendingRefreshAndIdempotentApprovalReturnStoredWholeSectionOutcome() {
        String path = seed();
        given(ai.generate(any())).willReturn(EXTRACTION, PLAN);
        String requestId = UUID.randomUUID().toString();
        var staged = http.postForEntity(path + "/discovery-turn",
                Map.of("input", "时间改成三小时，另补README示例", "revision", 2, "requestId", requestId), JsonNode.class);
        assertEquals(HttpStatus.OK, staged.getStatusCode());
        assertEquals("application/json", staged.getHeaders().getContentType().toString());
        JsonNode proposal = staged.getBody().path("pendingProposal");
        assertEquals("PENDING", proposal.path("state").asText());
        assertEquals(2, staged.getBody().path("profile").path("revision").asInt());
        assertTrue(staged.getBody().path("profile").path("behaviors").isEmpty());
        assertTrue(staged.getBody().path("profile").path("evidence").isEmpty());
        assertEquals("PAUSE", staged.getBody().path("nextAction").asText());
        assertEquals(proposal, http.getForObject(path + "/conversation", JsonNode.class).path("pendingProposal"));
        assertEquals(proposal, http.getForObject(path + "/discovery-requests/" + requestId, JsonNode.class).path("proposal"));
        assertEquals(HttpStatus.CONFLICT, http.postForEntity(path + "/discovery-turn",
                Map.of("input", "新消息", "revision", 2, "requestId", UUID.randomUUID().toString()), JsonNode.class).getStatusCode());
        String acceptPath = path + "/profile-updates/" + proposal.path("id").asText() + "/accept";
        var accepted = http.postForEntity(acceptPath, null, JsonNode.class);
        assertEquals(HttpStatus.OK, accepted.getStatusCode());
        assertEquals("ACCEPTED", accepted.getBody().path("state").asText());
        int revision = accepted.getBody().path("resultingRevision").asInt();
        assertEquals(5, revision);
        assertEquals(accepted.getBody(), http.postForObject(acceptPath, null, JsonNode.class));
        var current = http.getForObject(path, JsonNode.class);
        assertEquals(revision, current.path("revision").asInt());
        assertEquals("约三小时完成", current.path("constraints").get(0).asText());
        assertEquals("整理README使用示例", current.path("behaviors").get(0).asText());
        assertEquals(1, current.path("evidence").size());
        assertEquals(false, current.path("evidence").get(0).path("confirmed").asBoolean());
        assertTrue(http.getForObject(path + "/conversation", JsonNode.class).path("pendingProposal").isNull());
        assertEquals(2, http.getForObject(path + "/conversation", JsonNode.class).path("messages").size());
        assertEquals(HttpStatus.CONFLICT, http.postForEntity(seed() + "/profile-updates/"
                + proposal.path("id").asText() + "/accept", null, JsonNode.class).getStatusCode());
        verify(ai, times(2)).generate(any());
    }

    @Test
    void dismissalAndManualEditSupersessionArePersistedWithoutAnotherModelCall() {
        for (boolean manual : List.of(false, true)) {
            String path = seed();
            given(ai.generate(any())).willReturn(EXTRACTION, PLAN);
            JsonNode proposal = http.postForObject(path + "/discovery-turn", Map.of("input", "时间改成三小时",
                    "revision", 2, "requestId", UUID.randomUUID().toString()), JsonNode.class).path("pendingProposal");
            String actionPath = path + "/profile-updates/" + proposal.path("id").asText();
            if (manual) {
                var edit = http.exchange(path, HttpMethod.PATCH,
                        new HttpEntity<>(Map.of("revision", 2, "constraints", List.of("约四小时完成"))), JsonNode.class);
                assertEquals(HttpStatus.OK, edit.getStatusCode());
                assertEquals("EXPLORING", edit.getBody().path("status").asText());
                assertEquals("SUPERSEDED", http.postForObject(actionPath + "/accept", null, JsonNode.class).path("state").asText());
                assertEquals("约四小时完成", http.getForObject(path, JsonNode.class).path("constraints").get(0).asText());
            } else {
                assertEquals("DISMISSED", http.postForObject(actionPath + "/dismiss", null, JsonNode.class).path("state").asText());
                assertEquals("DISMISSED", http.postForObject(actionPath + "/accept", null, JsonNode.class).path("state").asText());
                assertEquals(2, http.getForObject(path, JsonNode.class).path("revision").asInt());
            }
            assertTrue(http.getForObject(path + "/conversation", JsonNode.class).path("pendingProposal").isNull());
            assertTrue(http.getForObject(path, JsonNode.class).path("evidence").isEmpty());
        }
        verify(ai, times(4)).generate(any());
    }

    private String seed() {
        JsonNode profile = http.postForObject("/api/user-profiles", null, JsonNode.class);
        String path = "/api/user-profiles/" + profile.path("id").asText();
        var edit = http.exchange(path, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("revision", 1, "constraints", List.of("约两小时完成"))), JsonNode.class);
        assertEquals(HttpStatus.OK, edit.getStatusCode());
        return path;
    }
}
