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
 * 验证 {@code POST /api/user-profiles/{id}/explore} 在真实 HTTP → Application → Domain → SQLite
 * 链路上的行为。
 *
 * <p>{@link AiGateway} 用替身替换：被替换的只有 Provider 边界，Application、Domain 与
 * Persistence 都是真实实现。因此本类不产生任何真实 LLM 调用（AGENTS.md §10.6）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserProfileExploreEndpointIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final String USER_INPUT = "我平时喜欢自己找图片做头像，但每次都要手动裁剪很麻烦";

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
    void exploresProfileFromUserInput() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn("""
                {
                  "interests": ["图片处理"],
                  "evidenceClaims": ["用户需要频繁处理头像图片"]
                }
                """);

        mockMvc.perform(explore(id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("EXPLORING"))
                .andExpect(jsonPath("$.interests[0]").value("图片处理"))
                .andExpect(jsonPath("$.evidence.length()").value(1))
                .andExpect(jsonPath("$.evidence[0].sourceType").value("USER_INPUT"))
                .andExpect(jsonPath("$.evidence[0].sourceRef").value(USER_INPUT))
                .andExpect(jsonPath("$.evidence[0].claim").value("用户需要频繁处理头像图片"))
                .andExpect(jsonPath("$.evidence[0].confirmed").value(false))
                // 一个内容区 + 一条 Evidence
                .andExpect(jsonPath("$.revision").value(3));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interests[0]").value("图片处理"))
                .andExpect(jsonPath("$.evidence[0].claim").value("用户需要频繁处理头像图片"))
                .andExpect(jsonPath("$.revision").value(3));
    }

    @Test
    void returnsNotFoundForUnknownProfile() throws Exception {
        mockMvc.perform(explore("unknown-profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsBlankUserInput() throws Exception {
        String id = createProfile();

        mockMvc.perform(post("/api/user-profiles/{id}/explore", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /**
     * AI 调用失败返回 502，且不得写入任何内容。
     */
    @Test
    void failsWithoutHalfUpdatingWhenAiCallFails() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willThrow(new AiGatewayException("模型不可用"));

        mockMvc.perform(explore(id))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_CAPABILITY_UNAVAILABLE"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.interests").isEmpty());
    }

    /**
     * 建议中前面几个区合法、后面一个区被 Aggregate 拒绝时，整个请求失败，
     * 且前面已经应用到内存对象上的内容不得落库。
     *
     * <p>这里读取的是真实 SQLite，因此它验证的是「没有半更新被写入」，
     * 而不是某个内存替身的状态。
     */
    @Test
    void failsWithoutHalfUpdatingWhenProposalBreaksDomainRules() throws Exception {
        String id = createProfile();
        given(aiGateway.generate(any())).willReturn("""
                {
                  "interests": ["这个区会先被应用"],
                  "behaviors": ["  "],
                  "evidenceClaims": ["这条不应该留下"]
                }
                """);

        mockMvc.perform(explore(id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.interests").isEmpty())
                .andExpect(jsonPath("$.behaviors").isEmpty())
                .andExpect(jsonPath("$.evidence").isEmpty());
    }

    private String createProfile() throws Exception {
        String body = mockMvc.perform(post("/api/user-profiles"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("id").asText();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder explore(String id)
            throws Exception {
        return post("/api/user-profiles/{id}/explore", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new ExploreUserProfileRequest(USER_INPUT)));
    }
}
