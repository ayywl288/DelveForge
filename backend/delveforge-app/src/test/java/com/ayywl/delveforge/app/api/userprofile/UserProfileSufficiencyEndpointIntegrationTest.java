package com.ayywl.delveforge.app.api.userprofile;

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
 * 验证 {@code POST /api/user-profiles/{id}/sufficiency-assessment} 在真实
 * HTTP → Application → Domain → SQLite 链路上的行为。
 *
 * <p>{@link AiGateway} 用替身替换：被替换的只有 Provider 边界，Application、Domain 与
 * Persistence 都是真实实现，因此不产生任何真实 LLM 调用。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserProfileSufficiencyEndpointIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final String SUFFICIENT_RESPONSE =
            "{\"sufficient\":true,\"missingAreas\":[],\"nextQuestion\":\"\"}";

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
    void keepsExploringAndReturnsQuestionWhenInsufficient() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn("""
                {
                  "sufficient": false,
                  "missingAreas": ["缺少真实行为", "缺少技术能力"],
                  "nextQuestion": "你最近有没有反复做过、觉得麻烦的事情？"
                }
                """);

        mockMvc.perform(assess(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficient").value(false))
                .andExpect(jsonPath("$.missingAreas.length()").value(2))
                .andExpect(jsonPath("$.missingAreas[0]").value("缺少真实行为"))
                .andExpect(jsonPath("$.nextQuestion").value("你最近有没有反复做过、觉得麻烦的事情？"))
                .andExpect(jsonPath("$.profileStatus").value("EXPLORING"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("EXPLORING"))
                .andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void movesToReviewingWhenSufficient() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn(SUFFICIENT_RESPONSE);

        mockMvc.perform(assess(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sufficient").value(true))
                .andExpect(jsonPath("$.missingAreas").isEmpty())
                .andExpect(jsonPath("$.nextQuestion").doesNotExist())
                .andExpect(jsonPath("$.profileStatus").value("REVIEWING"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("REVIEWING"))
                .andExpect(jsonPath("$.revision").value(1));
    }

    /**
     * AI 调用失败时不改变状态，也不写入。
     */
    @Test
    void failsWithoutChangingProfileWhenAiCallFails() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willThrow(new AiGatewayException("模型不可用"));

        mockMvc.perform(assess(id))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_CAPABILITY_UNAVAILABLE"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("EXPLORING"))
                .andExpect(jsonPath("$.revision").value(1));
    }

    @Test
    void returnsNotFoundForUnknownProfile() throws Exception {
        mockMvc.perform(assess("unknown-profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * 回归：对已经进入 REVIEWING 的 Profile 再次评估，属于可预期的状态冲突。
     *
     * <p>它必须返回 409，而不是被兜底处理成 500 服务端错误；状态与 revision 都不得改变。
     */
    @Test
    void rejectsRepeatedAssessmentWithConflict() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn(SUFFICIENT_RESPONSE);

        mockMvc.perform(assess(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileStatus").value("REVIEWING"));

        mockMvc.perform(assess(id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.path")
                        .value("/api/user-profiles/" + id + "/sufficiency-assessment"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("REVIEWING"))
                .andExpect(jsonPath("$.revision").value(1));
    }

    private String createProfile() throws Exception {
        String body = mockMvc.perform(post("/api/user-profiles"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("id").asText();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder assess(String id) {
        return post("/api/user-profiles/{id}/sufficiency-assessment", id)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
