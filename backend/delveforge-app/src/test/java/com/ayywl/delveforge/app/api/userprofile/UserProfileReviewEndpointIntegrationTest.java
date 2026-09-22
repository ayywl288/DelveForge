package com.ayywl.delveforge.app.api.userprofile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证 Review / Correct / Confirm 的人类决策闭环在真实
 * HTTP → Application → Domain → SQLite 链路上的行为。
 *
 * <p>{@link AiGateway} 用替身替换（只有进入 REVIEWING 需要经过一次充分性评估），
 * 其余都是真实实现，因此不产生真实 LLM 调用。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserProfileReviewEndpointIntegrationTest {

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

    /**
     * 完整闭环：EXPLORING → 评估 → REVIEWING → 修正（仍 REVIEWING）→ 确认 → CONFIRMED。
     */
    @Test
    void completesReviewCorrectConfirmLoop() throws Exception {
        String id = createProfile();

        assessSufficient(id);

        // Correct：实际变化推进 revision，状态保持 REVIEWING
        patchInterests(id, List.of("兴趣 A"));
        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("REVIEWING"))
                .andExpect(jsonPath("$.revision").value(2));

        // 再次修正
        patchInterests(id, List.of("兴趣 B"));
        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("REVIEWING"))
                .andExpect(jsonPath("$.revision").value(3));

        // Confirm：状态变化，revision 不变。确认必须携带用户所看的 revision
        confirmProfile(id, 3)
                .andExpect(jsonPath("$.revision").value(3))
                .andExpect(jsonPath("$.interests[0]").value("兴趣 B"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.revision").value(3));
    }

    /**
     * 修正提交的内容与当前完全一致时不推进 revision——是否有实际变化由 Domain 判断。
     */
    @Test
    void correctionWithIdenticalContentDoesNotAdvanceRevision() throws Exception {
        String id = createProfile();
        assessSufficient(id);
        patchInterests(id, List.of("兴趣 A"));

        patchInterests(id, List.of("兴趣 A"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("REVIEWING"))
                .andExpect(jsonPath("$.revision").value(2));
    }

    @Test
    void continueDiscoveryReturnsReviewingProfileToExploring() throws Exception {
        String id = createProfile();
        assessSufficient(id);

        mockMvc.perform(action(id, "continue-discovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPLORING"))
                .andExpect(jsonPath("$.revision").value(1));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("EXPLORING"));
    }

    @Test
    void reopenDiscoveryReturnsConfirmedProfileToExploring() throws Exception {
        String id = createProfile();
        assessSufficient(id);
        confirmProfile(id, currentRevision(id));

        mockMvc.perform(action(id, "reopen-discovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPLORING"))
                .andExpect(jsonPath("$.revision").value(1));
    }

    /**
     * 确认之后内容重新允许修改，并且从确认时的 revision 继续推进。
     */
    @Test
    void allowsCorrectionAgainAfterReopeningDiscovery() throws Exception {
        String id = createProfile();
        assessSufficient(id);
        confirmProfile(id, currentRevision(id));
        mockMvc.perform(action(id, "reopen-discovery")).andExpect(status().isOk());

        patchInterests(id, List.of("重新探索后的兴趣"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("EXPLORING"))
                .andExpect(jsonPath("$.revision").value(2));
    }

    /**
     * 非法起始状态统一是 409，而不是 500。
     */
    @Test
    void rejectsConfirmOutsideReviewingWithConflict() throws Exception {
        String id = createProfile();

        confirmRequest(id, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("EXPLORING"));
    }

    /**
     * 回归：用户查看之后、提交确认之前内容又变化过。
     *
     * <p>此时确认代表的已不是用户的真实决定，必须拒绝而不是默默确认服务端的最新版本；
     * 用当前 revision 重新确认才是有效的。
     */
    @Test
    void rejectsConfirmBasedOnStaleRevision() throws Exception {
        String id = createProfile();
        assessSufficient(id);
        int reviewedRevision = currentRevision(id);

        patchInterests(id, List.of("用户在旧页面上没有看到的内容"));

        confirmRequest(id, reviewedRevision)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.status").value("REVIEWING"));

        confirmProfile(id, currentRevision(id))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    /**
     * 缺少 revision 属于请求形状错误，与版本不匹配（409）分开。
     */
    @Test
    void rejectsConfirmWithoutRevision() throws Exception {
        String id = createProfile();
        assessSufficient(id);

        mockMvc.perform(post("/api/user-profiles/{id}/confirm", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void rejectsContinueDiscoveryOutsideReviewingWithConflict() throws Exception {
        String id = createProfile();

        mockMvc.perform(action(id, "continue-discovery"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void rejectsReopenDiscoveryOutsideConfirmedWithConflict() throws Exception {
        String id = createProfile();
        assessSufficient(id);

        mockMvc.perform(action(id, "reopen-discovery"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    /**
     * 已确认的 Profile 在重新开启探索之前不接受内容修改。
     */
    @Test
    void rejectsCorrectionWhileConfirmedWithConflict() throws Exception {
        String id = createProfile();
        assessSufficient(id);
        confirmProfile(id, currentRevision(id));

        mockMvc.perform(patch("/api/user-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody(List.of("不应生效"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void returnsNotFoundForUnknownProfile() throws Exception {
        mockMvc.perform(action("unknown-profile", "continue-discovery"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private String createProfile() throws Exception {
        String body = mockMvc.perform(post("/api/user-profiles"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("id").asText();
    }

    private void assessSufficient(String id) throws Exception {
        given(aiGateway.generate(any())).willReturn(SUFFICIENT_RESPONSE);
        mockMvc.perform(post("/api/user-profiles/{id}/sufficiency-assessment", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileStatus").value("REVIEWING"));
    }

    /** 发起确认请求，不断言结果——成功与冲突两种情形都由调用方断言。 */
    private ResultActions confirmRequest(String id, int revision) throws Exception {
        return mockMvc.perform(post("/api/user-profiles/{id}/confirm", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        new ConfirmUserProfileRequest(revision))));
    }

    private ResultActions confirmProfile(String id, int revision) throws Exception {
        return confirmRequest(id, revision)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    /** 读取当前 revision，模拟用户查看页面的那一次请求。 */
    private int currentRevision(String id) throws Exception {
        String body = mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(body).path("revision").asInt();
    }

    private void patchInterests(String id, List<String> interests) throws Exception {
        mockMvc.perform(patch("/api/user-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody(interests)))
                .andExpect(status().isOk());
    }

    private String patchBody(List<String> interests) throws Exception {
        return objectMapper.writeValueAsString(new UserProfilePatchRequest(
                interests, null, null, null, null, null, null));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder action(
            String id, String action) {
        return post("/api/user-profiles/{id}/" + action, id)
                .contentType(MediaType.APPLICATION_JSON);
    }
}
