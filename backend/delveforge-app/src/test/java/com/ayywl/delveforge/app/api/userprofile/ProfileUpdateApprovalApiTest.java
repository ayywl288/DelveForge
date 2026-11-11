package com.ayywl.delveforge.app.api.userprofile;

import com.ayywl.delveforge.application.port.ai.AiGateway;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        given(ai.generate(any())).willReturn(EXTRACTION.replace(
                "[\"用户希望调整时间并补充示例\"]", "[{\"description\":\"辅助说明\"}]"), PLAN);
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
        assertEquals(accepted.getBody(), http.getForObject(
                path + "/profile-updates/" + proposal.path("id").asText(), JsonNode.class));
        int revision = accepted.getBody().path("resultingRevision").asInt();
        assertEquals(5, revision);
        assertEquals(accepted.getBody(), http.postForObject(acceptPath, null, JsonNode.class));
        var current = http.getForObject(path, JsonNode.class);
        assertEquals(revision, current.path("revision").asInt());
        assertEquals("约三小时完成", current.path("constraints").get(0).asText());
        assertEquals("整理README使用示例", current.path("behaviors").get(0).asText());
        assertEquals(1, current.path("evidence").size());
        assertEquals("时间改成三小时，另补README示例", current.path("evidence").get(0).path("claim").asText());
        assertEquals("时间改成三小时，另补README示例", current.path("evidence").get(0).path("sourceRef").asText());
        assertEquals(false, current.path("evidence").get(0).path("confirmed").asBoolean());
        assertTrue(http.getForObject(path + "/conversation", JsonNode.class).path("pendingProposal").isNull());
        assertEquals(2, http.getForObject(path + "/conversation", JsonNode.class).path("messages").size());
        assertEquals(HttpStatus.CONFLICT, http.postForEntity(seed() + "/profile-updates/"
                + proposal.path("id").asText() + "/accept", null, JsonNode.class).getStatusCode());
        verify(ai, times(2)).generate(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"evidenceClaims\":[{}]", "\"evidenceClaims\":[null,1,true,[],{},\" \" ]",
            "\"evidenceClaims\":null", "\"evidenceClaims\":{}", "\"evidenceClaims\":\"说明\"",
            "\"evidenceClaims\":42", "\"evidenceClaims\":false", "\"evidenceClaims\":[]",
            "\"hasNewFacts\":{\"unused\":true}"
    })
    void courseNotesAnswerUsesCurrentInputWhenAuxiliaryEvidenceIsUnusable(String auxiliary) {
        String path = createProfile();
        String input = "主要是课件 PDF，再加我随手记在几个文档里的东西。复习时找一段内容，经常得翻好几个地方，挺烦的。";
        given(ai.generate(any())).willReturn("""
                {"behaviors":["课件 PDF 和几个文档里的笔记"],"painPoints":["复习查找内容要翻好几个地方"],%s}
                """.formatted(auxiliary), PLAN);
        String requestId = UUID.randomUUID().toString();
        Map<String, Object> request = Map.of("input", input, "revision", 1, "requestId", requestId);
        var response = http.postForEntity(path + "/discovery-turn", request, JsonNode.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        JsonNode profile = response.getBody().path("profile");
        assertEquals(4, profile.path("revision").asInt());
        assertEquals("复习查找内容要翻好几个地方", profile.path("painPoints").get(0).asText());
        assertEquals(1, profile.path("evidence").size());
        JsonNode evidence = profile.path("evidence").get(0);
        assertEquals("USER_INPUT", evidence.path("sourceType").asText());
        assertEquals(input, evidence.path("sourceRef").asText());
        assertEquals(input, evidence.path("claim").asText());
        assertEquals(false, evidence.path("confirmed").asBoolean());
        assertEquals(profile, http.getForObject(path, JsonNode.class));
        assertEquals(response.getBody(), http.postForObject(path + "/discovery-turn", request, JsonNode.class));
        assertEquals("COMPLETED", http.getForObject(path + "/discovery-requests/" + requestId,
                JsonNode.class).path("state").asText());
        assertEquals(2, http.getForObject(path + "/conversation", JsonNode.class).path("messages").size());
        verify(ai, times(2)).generate(any());
    }

    @Test
    void mixedAuxiliaryClaimsKeepOnlyBoundedTextWithUnconfirmedCurrentInputProvenance() {
        String path = createProfile();
        String input = "我会 Java，最近在用 Spring Boot。";
        given(ai.generate(any())).willReturn("""
                {"technicalCapabilities":["Java","Spring Boot"],
                 "evidenceClaims":[{},"会 Java",null,42,false,[]," ","%s","在用 Spring Boot"]}
                """.formatted("x".repeat(2049)), PLAN);
        JsonNode profile = http.postForObject(path + "/discovery-turn", Map.of("input", input,
                "revision", 1, "requestId", UUID.randomUUID().toString()), JsonNode.class).path("profile");
        assertEquals(2, profile.path("evidence").size());
        assertEquals("会 Java", profile.path("evidence").get(0).path("claim").asText());
        assertEquals("在用 Spring Boot", profile.path("evidence").get(1).path("claim").asText());
        for (JsonNode evidence : profile.path("evidence")) {
            assertEquals("USER_INPUT", evidence.path("sourceType").asText());
            assertEquals(input, evidence.path("sourceRef").asText());
            assertEquals(false, evidence.path("confirmed").asBoolean());
        }
        verify(ai, times(2)).generate(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"hasNewFacts\":true,\"constraints\":[\"约两小时完成\"],\"evidenceClaims\":[{}]}",
            "{\"hasNewFacts\":{},\"evidenceClaims\":[\"额外解释\"]}",
            "{\"hasNewFacts\":false,\"evidenceClaims\":null}"
    })
    void unchangedSectionsNeverCreateEvidenceOrAdvanceRevision(String extraction) {
        String path = seed();
        given(ai.generate(any())).willReturn(extraction, PLAN);
        JsonNode result = http.postForObject(path + "/discovery-turn", Map.of("input", "只是再核对一下",
                "revision", 2, "requestId", UUID.randomUUID().toString()), JsonNode.class);
        assertEquals(2, result.path("profile").path("revision").asInt());
        assertTrue(result.path("profile").path("evidence").isEmpty());
        assertTrue(result.path("pendingProposal").isNull());
        assertEquals(result.path("profile"), http.getForObject(path, JsonNode.class));
        verify(ai, times(2)).generate(any());
    }

    @Test
    void droppingOldProjectWithMalformedAuxiliaryDataDefersEvidenceUntilApproval() {
        String path = createProfile();
        given(ai.generate(any())).willReturn("""
                {"technicalCapabilities":["Java"],"projectGoals":["Coding Agent"],
                 "evidenceClaims":["会 Java，正在做 Coding Agent"]}
                """, PLAN);
        JsonNode original = http.postForObject(path + "/discovery-turn", Map.of("input", "会 Java，正在做 Coding Agent",
                "revision", 1, "requestId", UUID.randomUUID().toString()), JsonNode.class).path("profile");
        String input = "不想再搞那个 coding agent 了。";
        given(ai.generate(any())).willReturn("""
                {"projectGoals":[],"evidenceClaims":[{"description":"放弃旧目标"}]}
                """, PLAN);
        JsonNode turn = http.postForObject(path + "/discovery-turn", Map.of("input", input,
                "revision", original.path("revision").asInt(), "requestId", UUID.randomUUID().toString()), JsonNode.class);
        assertEquals(original, turn.path("profile"));
        assertEquals("PAUSE", turn.path("nextAction").asText());
        JsonNode proposal = turn.path("pendingProposal");
        assertEquals("PENDING", proposal.path("state").asText());
        assertEquals(input, proposal.path("userInput").asText());
        assertEquals(input, proposal.path("evidenceClaims").get(0).asText());
        String acceptPath = path + "/profile-updates/" + proposal.path("id").asText() + "/accept";
        JsonNode accepted = http.postForObject(acceptPath, null, JsonNode.class);
        JsonNode current = http.getForObject(path, JsonNode.class);
        assertTrue(current.path("projectGoals").isEmpty());
        assertEquals(original.path("technicalCapabilities"), current.path("technicalCapabilities"));
        assertEquals(original.path("revision").asInt() + 2, current.path("revision").asInt());
        assertEquals(2, current.path("evidence").size());
        assertEquals(original.path("evidence").get(0), current.path("evidence").get(0));
        assertEquals(input, current.path("evidence").get(1).path("sourceRef").asText());
        assertEquals(false, current.path("evidence").get(1).path("confirmed").asBoolean());
        assertEquals(accepted, http.postForObject(acceptPath, null, JsonNode.class));
        assertEquals(current, http.getForObject(path, JsonNode.class));
        verify(ai, times(4)).generate(any());
    }

    private String createProfile() {
        JsonNode profile = http.postForObject("/api/user-profiles", null, JsonNode.class);
        return "/api/user-profiles/" + profile.path("id").asText();
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

    @Test
    void realHttpJourneyChangesDirectionApprovesOnceAndKeepsApprovalWhenFollowUpFails() {
        JsonNode empty = http.postForObject("/api/user-profiles", null, JsonNode.class);
        String path = "/api/user-profiles/" + empty.path("id").asText();
        String ready = """
                {"sufficient":true,"missingAreas":[],"assistantReply":"可以聊聊你的项目。",
                 "nextAction":"PAUSE","nextQuestion":null,"previousQuestionState":"NONE"}
                """;
        given(ai.generate(any())).willReturn("""
                {"technicalCapabilities":["Java REST 开发"],"projectGoals":["改进 Coding Agent"],
                 "evidenceClaims":["用户已有 Java 经验，想改善 Coding Agent"]}
                """, ready);
        JsonNode first = http.postForObject(path + "/discovery-turn", Map.of("input", "会 Java，想改进 Coding Agent",
                "revision", 1, "requestId", UUID.randomUUID().toString()), JsonNode.class);
        int revision = first.path("profile").path("revision").asInt();
        assertTrue(first.path("sufficient").asBoolean());
        assertTrue(first.path("profile").path("constraints").isEmpty());
        given(ai.generate(any())).willReturn("""
                {"sufficient":true,"missingAreas":[],"assistantReply":"也可以换个话题。",
                 "nextAction":"ASK","nextQuestion":"准备投入多少时间？","questionArea":"constraints"}
                """);
        http.postForObject(path + "/discovery-question", Map.of("revision", revision,
                "requestId", UUID.randomUUID().toString()), JsonNode.class);
        given(ai.generate(any())).willReturn("""
                {"sufficient":true,"missingAreas":[],"assistantReply":"时间未知也没关系，可以稍后再定。",
                 "nextAction":"ASK","nextQuestion":"每周几小时？","questionArea":"constraints",
                 "previousQuestionState":"DEFERRED","deferredAreas":["constraints"]}
                """);
        JsonNode unknown = http.postForObject(path + "/discovery-turn", Map.of("input", "不知道。", "revision", revision,
                "requestId", UUID.randomUUID().toString()), JsonNode.class);
        assertTrue(unknown.path("nextQuestion").isNull());
        assertEquals("REPLACED", unknown.path("previousQuestionState").asText());
        assertEquals(revision, unknown.path("profile").path("revision").asInt());
        given(ai.generate(any())).willReturn("""
                {"hasNewFacts":false,"projectGoals":["探索课程检索工具"],"evidenceClaims":["用户明确放下原项目"]}
                """, """
                {"sufficient":true,"missingAreas":["模型旧缺口"],"assistantReply":"可以，我们放下 Coding Agent，先探索课程资料检索。",
                 "nextAction":"ASK","nextQuestion":"想检索什么资料？","previousQuestionState":"NONE"}
                """);
        String pivotId = UUID.randomUUID().toString();
        JsonNode pivot = http.postForObject(path + "/discovery-turn", Map.of("input", "不想再搞那个 coding agent 了，换成课程检索",
                "revision", revision, "requestId", pivotId), JsonNode.class);
        assertEquals("PAUSE", pivot.path("nextAction").asText());
        assertTrue(pivot.path("assistantReply").asText().contains("放下 Coding Agent"));
        String proposalId = pivot.path("pendingProposal").path("id").asText();
        String approvalPath = path + "/profile-updates/" + proposalId;
        JsonNode approved = http.postForObject(approvalPath + "/accept", null, JsonNode.class);
        assertEquals("ACCEPTED", approved.path("state").asText());
        assertEquals(approved, http.postForObject(approvalPath + "/accept", null, JsonNode.class));
        given(ai.generate(any())).willThrow(new com.ayywl.delveforge.application.port.ai.AiGatewayException("fixture"));
        String followId = UUID.randomUUID().toString();
        assertEquals(HttpStatus.BAD_GATEWAY, http.postForEntity(path + "/discovery-question", Map.of(
                "revision", approved.path("resultingRevision").asInt(), "requestId", followId), JsonNode.class).getStatusCode());
        assertEquals("FAILED", http.getForObject(path + "/discovery-requests/" + followId, JsonNode.class).path("state").asText());
        assertEquals("ACCEPTED", http.getForObject(approvalPath, JsonNode.class).path("state").asText());
        JsonNode current = http.getForObject(path, JsonNode.class);
        assertEquals("探索课程检索工具", current.path("projectGoals").get(0).asText());
        assertEquals("Java REST 开发", current.path("technicalCapabilities").get(0).asText());
        JsonNode saved = http.getForObject(path + "/conversation", JsonNode.class);
        assertTrue(saved.path("messages").toString().contains(pivotId));
        verify(ai, times(7)).generate(any());
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
