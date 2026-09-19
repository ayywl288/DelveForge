package com.ayywl.delveforge.app.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
              "interests": ["图片处理"],
              "painPoints": ["头像裁剪麻烦"],
              "evidenceClaims": ["用户需要频繁处理头像图片"]
            }
            """;

    private static final String SUFFICIENT_RESPONSE =
            "{\"sufficient\":true,\"missingAreas\":[],\"nextQuestion\":\"\"}";

    private static final String INSUFFICIENT_RESPONSE = """
            {
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
                .andExpect(jsonPath("$.revision").value(4));
    }

    @Test
    void movesToReviewingWhenSufficient() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn(EXTRACTION_RESPONSE, SUFFICIENT_RESPONSE);

        mockMvc.perform(turn(id, USER_INPUT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficient").value(true))
                .andExpect(jsonPath("$.missingAreas").isEmpty())
                .andExpect(jsonPath("$.nextQuestion").doesNotExist())
                .andExpect(jsonPath("$.profile.status").value("REVIEWING"))
                .andExpect(jsonPath("$.profile.revision").value(4));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("REVIEWING"))
                .andExpect(jsonPath("$.revision").value(4));
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
                "{\"technicalCapabilities\":[\"Java\"]}", SUFFICIENT_RESPONSE);

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
                .andExpect(jsonPath("$.profile.status").value("REVIEWING"))
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

        mockMvc.perform(turn(id, "已经进入 Review 了，还想再来一轮"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("REVIEWING"))
                .andExpect(jsonPath("$.revision").value(4))
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
    void returnsNotFoundForUnknownProfile() throws Exception {
        mockMvc.perform(turn("unknown-profile", USER_INPUT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private String createProfile() throws Exception {
        String body = mockMvc.perform(post("/api/user-profiles"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("id").asText();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder turn(
            String id, String input) throws Exception {
        return post("/api/user-profiles/{id}/discovery-turn", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ExploreUserProfileRequest(input)));
    }
}
