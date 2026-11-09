package com.ayywl.delveforge.app.api.userprofile;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.userdiscovery.shared.DiscoveryContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 验证一轮 User Discovery 在真实 HTTP → Application → Domain → SQLite 链路上的行为。
 *
 * <p>{@link AiGateway} 用替身替换（一轮需要提取与评估两次调用），其余都是真实实现，
 * 因此不产生真实 LLM 调用。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserProfileDiscoveryTurnEndpointIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final String USER_INPUT = "我平时喜欢自己找图片做头像，但每次都要手动裁剪很麻烦";

    private static final String EXTRACTION_RESPONSE = """
            {
              "hasNewFacts": true,
              "interests": ["图片处理"],
              "painPoints": ["头像裁剪麻烦"],
              "technicalCapabilities": ["Java图像处理"],
              "projectGoals": ["给每周更换的头像批量裁剪"],
              "constraints": ["每周三小时"],
              "evidenceClaims": ["用户需要频繁处理头像图片"]
            }
            """;

    private static final String QUESTION_RESPONSE =
            "{\"previousQuestionState\":\"NONE\",\"nextAction\":\"ASK\",\"sufficient\":true,\"missingAreas\":[],\"nextQuestion\":\"希望先支持哪一种裁剪场景？\",\"assistantReply\":\"可以继续细化裁剪体验。\",\"questionArea\":\"exploration\",\"deferredAreas\":[]}";

    private static final String SUFFICIENT_RESPONSE = """
            {"previousQuestionState":"NONE","nextAction":"PAUSE","sufficient":true,"missingAreas":[],
             "nextQuestion":null,"assistantReply":"可以继续细化裁剪体验。","questionArea":null,"deferredAreas":[]}
            """;

    private static final String INSUFFICIENT_RESPONSE = """
            {
              "nextAction": "ASK", "previousQuestionState": "NONE",
              "assistantReply": "为了选择合适的方向，还需要了解技术经验。",
              "questionArea": "technicalCapabilities",
              "deferredAreas": [],
              "sufficient": false,
              "missingAreas": ["缺少技术能力"],
              "nextQuestion": "你平时主要用什么语言写代码？"
            }
            """;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AiGateway aiGateway;

    @Test
    void returnsNextQuestionAndKeepsExploringWhenInsufficient() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn(EXTRACTION_RESPONSE, INSUFFICIENT_RESPONSE);

        mockMvc.perform(turn(id, USER_INPUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficient").value(false))
                .andExpect(jsonPath("$.missingAreas[0]").value("缺少技术能力"))
                .andExpect(jsonPath("$.nextQuestion").value("你平时主要用什么语言写代码？"))
                .andExpect(jsonPath("$.profile.status").value("EXPLORING"))
                .andExpect(jsonPath("$.profile.interests[0]").value("图片处理"))
                .andExpect(jsonPath("$.profile.painPoints[0]").value("头像裁剪麻烦"))
                .andExpect(jsonPath("$.profile.evidence[0].sourceType").value("USER_INPUT"))
                .andExpect(jsonPath("$.profile.evidence[0].sourceRef").value(USER_INPUT));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("EXPLORING"))
                .andExpect(jsonPath("$.revision").value(7));
    }

    @Test
    void staysExploringWhenMinimumReviewConditionsAreMet() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn(EXTRACTION_RESPONSE, SUFFICIENT_RESPONSE);

        mockMvc.perform(turn(id, USER_INPUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficient").value(true))
                .andExpect(jsonPath("$.missingAreas").isEmpty())
                .andExpect(jsonPath("$.nextQuestion").doesNotExist())
                .andExpect(jsonPath("$.reviewInvitation").value(true))
                .andExpect(jsonPath("$.profile.status").value("EXPLORING"))
                .andExpect(jsonPath("$.profile.revision").value(7));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("EXPLORING"))
                .andExpect(jsonPath("$.revision").value(7));
    }

    /**
     * 多轮推进：第一轮不足并给出下一问，调用方用该问题向用户提问，
     * 拿到回答后发起第二轮并进入 REVIEWING。循环跨 HTTP 请求完成。
     */
    @Test
    void advancesAcrossTurnsThroughHttp() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn(
                EXTRACTION_RESPONSE, INSUFFICIENT_RESPONSE,
                "{\"hasNewFacts\":true,\"technicalCapabilities\":[\"Java\"],\"evidenceClaims\":[\"用户主要使用Java\"]}", SUFFICIENT_RESPONSE.replace("NONE", "ANSWERED"));

        String nextQuestion = objectMapper.readTree(
                        mockMvc.perform(turn(id, USER_INPUT))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.sufficient").value(false))
                                .andReturn().getResponse()
                                .getContentAsString(StandardCharsets.UTF_8))
                .path("nextQuestion").asText();

        mockMvc.perform(turn(id, nextQuestion + " —— 主要用 Java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficient").value(true))
                .andExpect(jsonPath("$.profile.status").value("EXPLORING"))
                .andExpect(jsonPath("$.profile.interests[0]").value("图片处理"))
                .andExpect(jsonPath("$.profile.technicalCapabilities[0]").value("Java"));
    }

    /**
     * 评估阶段失败时，本轮已经提取出的内容也不得落库——一轮要么整体成功，要么什么都不变。
     */
    @Test
    void failsWithoutHalfUpdateWhenSufficiencyCallFails() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn(EXTRACTION_RESPONSE)
                .willThrow(new AiGatewayException("模型不可用"));

        mockMvc.perform(turn(id, USER_INPUT))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_CAPABILITY_UNAVAILABLE"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("EXPLORING"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.interests").isEmpty());
    }

    /**
     * 回归：一轮探索只能从 EXPLORING 开始，REVIEWING / CONFIRMED 下调用应当被 Domain 拒绝。
     *
     * <p>否则同一轮输入会因为模型恰好判断「足够」或「不足」而走向不同结局，
     * 等于让 AI 的结论决定领域状态是否被接受。
     */
    @Test
    void rejectsTurnOutsideExploringWithConflict() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn(EXTRACTION_RESPONSE, SUFFICIENT_RESPONSE);
        mockMvc.perform(turn(id, USER_INPUT)).andExpect(status().isOk());
        given(aiGateway.generate(any())).willReturn(SUFFICIENT_RESPONSE);
        mockMvc.perform(post("/api/user-profiles/{id}/sufficiency-assessment", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileStatus").value("REVIEWING"));

        mockMvc.perform(turn(id, "已经进入 Review 了，还想再来一轮"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("REVIEWING"))
                .andExpect(jsonPath("$.revision").value(7))
                .andExpect(jsonPath("$.interests[0]").value("图片处理"));
    }

    @Test
    void rejectsBlankInput() throws Exception {
        String id = createProfile();

        mockMvc.perform(turn(id, "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void explicitContinueAllowsMultipleTurnsAndOnlyExplicitReviewEnablesConfirm() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn(EXTRACTION_RESPONSE, SUFFICIENT_RESPONSE);
        mockMvc.perform(turn(id, USER_INPUT)).andExpect(status().isOk());
        given(aiGateway.generate(any())).willReturn(SUFFICIENT_RESPONSE);
        mockMvc.perform(post("/api/user-profiles/{id}/sufficiency-assessment", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profileStatus").value("REVIEWING"));
        mockMvc.perform(post("/api/user-profiles/{id}/continue-discovery", id)).andExpect(status().isOk());
        for (String input : List.of("希望输出两个尺寸", "还希望保留原图")) {
            given(aiGateway.generate(any())).willReturn("{\"hasNewFacts\":false}", SUFFICIENT_RESPONSE);
            mockMvc.perform(turn(id, input)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.sufficient").value(true))
                    .andExpect(jsonPath("$.profile.status").value("EXPLORING"));
        }
        mockMvc.perform(post("/api/user-profiles/{id}/confirm", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":7}"))
                .andExpect(status().isConflict());
        given(aiGateway.generate(any())).willReturn(SUFFICIENT_RESPONSE);
        mockMvc.perform(post("/api/user-profiles/{id}/sufficiency-assessment", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profileStatus").value("REVIEWING"));
        mockMvc.perform(post("/api/user-profiles/{id}/confirm", id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void questionEndpointReadsTheCurrentProfileWithoutUpdatingIt() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn(EXTRACTION_RESPONSE, SUFFICIENT_RESPONSE);
        mockMvc.perform(turn(id, USER_INPUT)).andExpect(status().isOk());
        String before = mockMvc.perform(get("/api/user-profiles/{id}", id)).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        int revision = objectMapper.readTree(before).path("revision").asInt();
        given(aiGateway.generate(any())).willReturn(QUESTION_RESPONSE);

        mockMvc.perform(post("/api/user-profiles/{id}/discovery-question", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":" + revision + ",\"previousQuestions\":[\"之前的问题？\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(revision))
                .andExpect(jsonPath("$.sufficient").value(true))
                .andExpect(jsonPath("$.nextQuestion").value("希望先支持哪一种裁剪场景？"));
        Assertions.assertEquals(before,
                mockMvc.perform(get("/api/user-profiles/{id}", id)).andReturn()
                        .getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void questionEndpointRejectsInvalidAndStaleRevisionAndNonExploringStates() throws Exception {
        String id = createProfile();
        for (String body : List.of("{}", "{\"revision\":1.5}", "{\"revision\":\"1\"}")) {
            mockMvc.perform(post("/api/user-profiles/{id}/discovery-question", id)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(post("/api/user-profiles/{id}/discovery-question", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":2}"))
                .andExpect(status().isConflict());
        Mockito.verifyNoInteractions(aiGateway);

        given(aiGateway.generate(any())).willReturn(EXTRACTION_RESPONSE, SUFFICIENT_RESPONSE);
        mockMvc.perform(turn(id, USER_INPUT)).andExpect(status().isOk());
        given(aiGateway.generate(any())).willReturn(SUFFICIENT_RESPONSE);
        mockMvc.perform(post("/api/user-profiles/{id}/sufficiency-assessment", id)).andExpect(status().isOk());
        mockMvc.perform(post("/api/user-profiles/{id}/discovery-question", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":7}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/user-profiles/{id}/confirm", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":7}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/user-profiles/{id}/discovery-question", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":7}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/user-profiles/{id}/reopen-discovery", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(7));
        given(aiGateway.generate(any())).willReturn(QUESTION_RESPONSE);
        mockMvc.perform(post("/api/user-profiles/{id}/discovery-question", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextQuestion").isString());
        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("EXPLORING"))
                .andExpect(jsonPath("$.revision").value(7));
        Mockito.verify(aiGateway, Mockito.times(3)).generate(any());
        given(aiGateway.generate(any())).willReturn("{\"hasNewFacts\":false}",
                SUFFICIENT_RESPONSE.replace("NONE", "ANSWERED"));
        mockMvc.perform(turn(id, "先做头像裁剪"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewInvitation").value(true));
    }

    @Test
    void invalidNextQuestionAndTransportFailureDoNotSaveAHalfTurn() throws Exception {
        String id = createProfile();
        for (String response : List.of("{\"sufficient\":true}", SUFFICIENT_RESPONSE.replace("可以继续细化裁剪体验。", "a".repeat(2049)))) {
            given(aiGateway.generate(any())).willReturn(EXTRACTION_RESPONSE, response);
            mockMvc.perform(post("/api/user-profiles/{id}/discovery-turn", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new DiscoveryTurnRequest(
                                    USER_INPUT, List.of("希望先支持哪一种裁剪场景？")))))
                    .andExpect(status().isBadGateway());
            mockMvc.perform(get("/api/user-profiles/{id}", id))
                    .andExpect(jsonPath("$.revision").value(1))
                    .andExpect(jsonPath("$.evidence").isEmpty());
        }
        given(aiGateway.generate(any())).willThrow(new AiGatewayException("读取正文失败"));
        mockMvc.perform(post("/api/user-profiles/{id}/discovery-question", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isBadGateway());
        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.status").value("EXPLORING"));
    }

    @Test
    void returnsNotFoundForUnknownProfile() throws Exception {
        mockMvc.perform(turn("unknown-profile", USER_INPUT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void reviewReusesRealTurnAssessmentAndRejectsStaleRevision() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn(EXTRACTION_RESPONSE, SUFFICIENT_RESPONSE);
        mockMvc.perform(turn(id, USER_INPUT)).andExpect(status().isOk());
        mockMvc.perform(get("/api/user-profiles/{id}/review-readiness", id))
                .andExpect(jsonPath("$.revision").value(7)).andExpect(jsonPath("$.sufficient").value(true));
        Mockito.reset(aiGateway);
        given(aiGateway.generate(any())).willThrow(new AiGatewayException("不应重新评估"));
        mockMvc.perform(post("/api/user-profiles/{id}/sufficiency-assessment", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":6}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/user-profiles/{id}/sufficiency-assessment", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":7}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nextQuestion").doesNotExist());
        Mockito.verifyNoInteractions(aiGateway);
    }

    @Test
    void responseOnlyTurnReturnsReplyWithoutInventingFactsOrForcingAQuestion() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn("{\"hasNewFacts\":false}", """
                {"sufficient":false,"missingAreas":["还需明确目标"],"assistantReply":"技术背景用于判断可行范围，可以先跳过。",
                 "previousQuestionState":"NONE","nextAction":"PAUSE","nextQuestion":null,"questionArea":null,"deferredAreas":[]}
                """);
        mockMvc.perform(post("/api/user-profiles/{id}/discovery-turn", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input":"为什么需要技术背景？","recentConversation":[
                                  {"role":"agent","text":"做过什么项目？"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assistantReply").value("技术背景用于判断可行范围，可以先跳过。"))
                .andExpect(jsonPath("$.nextQuestion").doesNotExist())
                .andExpect(jsonPath("$.sufficient").value(false))
                .andExpect(jsonPath("$.profile.revision").value(1))
                .andExpect(jsonPath("$.profile.evidence").isEmpty())
                .andExpect(jsonPath("$.profile.status").value("EXPLORING"));
        Mockito.verify(aiGateway, Mockito.times(2)).generate(any());
    }

    @Test
    void invalidConversationIsRejectedBeforeAi() throws Exception {
        String id = createProfile();
        for (String role : Arrays.asList("system", null)) {
            mockMvc.perform(post("/api/user-profiles/{id}/discovery-turn", id)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(new DiscoveryTurnRequest("消息", null,
                                    List.of(new DiscoveryContext.Message(role, "历史文本"))))))
                    .andExpect(status().isBadRequest());
        }
        Mockito.verifyNoInteractions(aiGateway);
    }

    @Test
    void explicitQuestionWithActionableGapsReplansAQuestionlessModelResponse() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn("""
                {"sufficient":false,"missingAreas":["需要明确目标"],"assistantReply":"我们可以开始探索。",
                 "nextQuestion":null,"questionArea":null,"deferredAreas":[]}
                """, INSUFFICIENT_RESPONSE);
        mockMvc.perform(post("/api/user-profiles/{id}/discovery-question", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficient").value(false))
                .andExpect(jsonPath("$.nextQuestion").value("你平时主要用什么语言写代码？"));
        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.evidence").isEmpty())
                .andExpect(jsonPath("$.status").value("EXPLORING"));
        mockMvc.perform(get("/api/user-profiles/{id}/review-readiness", id))
                .andExpect(jsonPath("$.sufficient").value(false));
        Mockito.verify(aiGateway, Mockito.times(2)).generate(any());
    }

    @Test
    void explicitQuestionWithoutAnyUsableTopicFailsRatherThanReturningEmptySuccess() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn("""
                {"sufficient":false,"missingAreas":["目标待补充"],"assistantReply":"可以先不讨论这些。",
                 "nextQuestion":"  ","questionArea":null,
                 "deferredAreas":["projectGoals","technicalCapabilities","constraints","evidence"]}
                """);
        mockMvc.perform(post("/api/user-profiles/{id}/discovery-question", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"revision\":1}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_CAPABILITY_UNAVAILABLE"));
        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.revision").value(1)).andExpect(jsonPath("$.evidence").isEmpty());
        Mockito.verify(aiGateway, Mockito.times(2)).generate(any());
    }

    @Test
    void recordedTurnCanBeRetriedAndReadWithoutAnotherAiCallOrDuplicateMessages() throws Exception {
        String id = createProfile();
        String requestId = UUID.randomUUID().toString();
        given(aiGateway.generate(any())).willReturn(EXTRACTION_RESPONSE, SUFFICIENT_RESPONSE);
        String body = objectMapper.writeValueAsString(Map.of(
                "input", USER_INPUT, "requestId", requestId, "revision", 1));
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/user-profiles/{id}/discovery-turn", id)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.profile.revision").value(7));
        }
        mockMvc.perform(get("/api/user-profiles/{id}/conversation", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].text").value(USER_INPUT))
                .andExpect(jsonPath("$.messages[1].createdAt").isNotEmpty())
                .andExpect(jsonPath("$.unansweredQuestion").doesNotExist())
                .andExpect(jsonPath("$.invitationRequestId").value(requestId));
        mockMvc.perform(get("/api/user-profiles/{id}/discovery-requests/{requestId}", id, requestId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.revision").value(7)).andExpect(jsonPath("$.result.sufficient").value(true))
                .andExpect(jsonPath("$.fingerprint").doesNotExist());
        mockMvc.perform(patch("/api/user-profiles/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":7,\"constraints\":[\"调整为每周一小时\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(8));
        mockMvc.perform(post("/api/user-profiles/{id}/discovery-turn", id)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/user-profiles/{id}/discovery-requests/{requestId}", id, requestId))
                .andExpect(jsonPath("$.state").value("COMPLETED")).andExpect(jsonPath("$.revision").value(7));
        Mockito.verify(aiGateway, Mockito.times(2)).generate(any());
    }

    @Test
    void unknownRequestCanBeExplicitlyCancelledBeforeItArrives() throws Exception {
        String id = createProfile();
        String requestId = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/user-profiles/{id}/discovery-requests/{requestId}", id, requestId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("NOT_FOUND"));
        mockMvc.perform(post("/api/user-profiles/{id}/discovery-requests/{requestId}/cancel", id, requestId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("CANCELLED"));
        mockMvc.perform(post("/api/user-profiles/{id}/discovery-turn", id)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(
                                Map.of("input", USER_INPUT, "requestId", requestId, "revision", 1))))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/user-profiles/{id}/conversation", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages").isEmpty());
        Mockito.verifyNoInteractions(aiGateway);
    }

    @Test
    void correctionFromAnOlderReviewRevisionIsRejected() throws Exception {
        String id = createProfile();
        mockMvc.perform(MockMvcRequestBuilders.patch(
                        "/api/user-profiles/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1,\"interests\":[\"阅读\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mockMvc.perform(MockMvcRequestBuilders.patch(
                        "/api/user-profiles/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"revision\":1,\"interests\":[\"旧视图覆盖\"]}"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.interests[0]").value("阅读"));
        Mockito.verifyNoInteractions(aiGateway);
    }

    private String createProfile() throws Exception {
        String body = mockMvc.perform(post("/api/user-profiles"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("id").asText();
    }

    private MockHttpServletRequestBuilder turn(
            String id, String input) throws Exception {
        return post("/api/user-profiles/{id}/discovery-turn", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ExploreUserProfileRequest(input)));
    }
}
