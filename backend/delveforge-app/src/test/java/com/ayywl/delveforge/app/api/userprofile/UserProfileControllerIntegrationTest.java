package com.ayywl.delveforge.app.api.userprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayywl.delveforge.app.api.evidence.EvidencePayload;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证 User Profile REST 端点在真实 HTTP → Application → Domain → SQLite 链路上的行为。
 *
 * <p>使用完整 Spring 上下文与真实 SQLite（临时数据库），因此覆盖 Controller 映射、
 * Use Case 编排、Domain 规则与 Persistence Adapter 的整条链路，而不是切片替身。
 *
 * <p>{@code @Transactional} 使每个测试方法结束后回滚，保证方法之间状态隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserProfileControllerIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsEmptyExploringProfile() throws Exception {
        mockMvc.perform(post("/api/user-profiles"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("EXPLORING"))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.interests").isEmpty())
                .andExpect(jsonPath("$.behaviors").isEmpty())
                .andExpect(jsonPath("$.painPoints").isEmpty())
                .andExpect(jsonPath("$.technicalCapabilities").isEmpty())
                .andExpect(jsonPath("$.projectGoals").isEmpty())
                .andExpect(jsonPath("$.constraints").isEmpty())
                .andExpect(jsonPath("$.evidence").isEmpty());
    }

    /**
     * 创建 → 查询 → 更新 → 再查询：验证四步都在同一条真实链路上完成。
     */
    @Test
    void completesCreateGetUpdateGetFlow() throws Exception {
        String id = createProfile();

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.interests").isEmpty());

        mockMvc.perform(patch("/api/user-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody(new UserProfilePatchRequest(
                                List.of("兴趣 A", "兴趣 B"),
                                List.of("行为"),
                                List.of("痛点"),
                                null, null, null, null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.revision").value(4))
                .andExpect(jsonPath("$.interests[0]").value("兴趣 A"))
                .andExpect(jsonPath("$.interests[1]").value("兴趣 B"))
                .andExpect(jsonPath("$.behaviors[0]").value("行为"))
                .andExpect(jsonPath("$.painPoints[0]").value("痛点"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.revision").value(4))
                .andExpect(jsonPath("$.interests[0]").value("兴趣 A"))
                .andExpect(jsonPath("$.behaviors[0]").value("行为"))
                .andExpect(jsonPath("$.painPoints[0]").value("痛点"))
                .andExpect(jsonPath("$.projectGoals").isEmpty());
    }

    @Test
    void replacesProvidedSectionInsteadOfMerging() throws Exception {
        String id = createProfile();
        patchProfile(id, new UserProfilePatchRequest(
                List.of("兴趣 A", "兴趣 B"), null, null, null, null, null, null));

        patchProfile(id, new UserProfilePatchRequest(
                List.of("兴趣 C"), null, null, null, null, null, null));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.interests.length()").value(1))
                .andExpect(jsonPath("$.interests[0]").value("兴趣 C"));
    }

    @Test
    void clearsSectionWhenProvidedEmptyList() throws Exception {
        String id = createProfile();
        patchProfile(id, new UserProfilePatchRequest(
                List.of("兴趣"), null, null, null, null, null, null));

        patchProfile(id, new UserProfilePatchRequest(
                List.of(), null, null, null, null, null, null));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.interests").isEmpty());
    }

    /**
     * 未提供的区保持原值：后续 PATCH 不得清掉之前提交的区。
     */
    @Test
    void keepsSectionsThatWereNotProvided() throws Exception {
        String id = createProfile();
        patchProfile(id, new UserProfilePatchRequest(
                List.of("兴趣"), null, null, null, null, null, null));

        patchProfile(id, new UserProfilePatchRequest(
                null, List.of("行为"), null, null, null, null, null));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.interests[0]").value("兴趣"))
                .andExpect(jsonPath("$.behaviors[0]").value("行为"));
    }

    @Test
    void appendsEvidenceWithoutReplacingExistingOnes() throws Exception {
        String id = createProfile();
        patchProfile(id, new UserProfilePatchRequest(
                null, null, null, null, null, null,
                List.of(evidence("user-answer-1", "用户长期自己找图片做头像"))));

        patchProfile(id, new UserProfilePatchRequest(
                null, null, null, null, null, null,
                List.of(evidence("user-answer-2", "用户关注图片处理"))));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.evidence.length()").value(2))
                .andExpect(jsonPath("$.evidence[0].claim").value("用户长期自己找图片做头像"))
                .andExpect(jsonPath("$.evidence[0].sourceType").value("USER_INPUT"))
                .andExpect(jsonPath("$.evidence[0].confidence").doesNotExist())
                .andExpect(jsonPath("$.evidence[0].confirmed").value(true))
                .andExpect(jsonPath("$.evidence[1].claim").value("用户关注图片处理"));
    }

    /**
     * 内容与当前完全一致的 PATCH 不推进 revision：是否变化由 Domain 判断。
     */
    @Test
    void doesNotAdvanceRevisionWhenContentIsUnchanged() throws Exception {
        String id = createProfile();
        UserProfilePatchRequest request = new UserProfilePatchRequest(
                List.of("兴趣"), null, null, null, null, null, null);
        patchProfile(id, request);

        mockMvc.perform(patch("/api/user-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(2));
    }

    /**
     * 内容不合法由 Domain 判定，统一错误映射把它翻译为 400。
     */
    @Test
    void rejectsBlankSectionEntry() throws Exception {
        String id = createProfile();

        mockMvc.perform(patch("/api/user-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody(new UserProfilePatchRequest(
                                List.of("兴趣", "  "), null, null, null, null, null, null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.interests").isEmpty());
    }

    /**
     * 未知的 Evidence 来源类型在反序列化阶段失败，同样走统一错误映射。
     */
    @Test
    void rejectsUnknownEvidenceSourceType() throws Exception {
        String id = createProfile();
        String body = """
                {"additionalEvidence":[{"sourceType":"NOT_A_SOURCE","sourceRef":"ref",\
                "claim":"claim","confirmed":true}]}""";

        mockMvc.perform(patch("/api/user-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    /**
     * 回归：数组中的 null 元素属于请求形状问题，必须是 400，而不是 500。
     */
    @Test
    void rejectsNullEvidenceElement() throws Exception {
        String id = createProfile();

        mockMvc.perform(patch("/api/user-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"additionalEvidence\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.evidence").isEmpty());
    }

    /**
     * 回归：内容区里的 null 元素同样必须是 400——该路径由 Domain 的空值校验拒绝。
     */
    @Test
    void rejectsNullSectionElement() throws Exception {
        String id = createProfile();

        mockMvc.perform(patch("/api/user-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"interests\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/user-profiles/{id}", id))
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.interests").isEmpty());
    }

    @Test
    void returnsNotFoundForUnknownProfile() throws Exception {
        mockMvc.perform(get("/api/user-profiles/{id}", "unknown-profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/user-profiles/unknown-profile"));
    }

    @Test
    void returnsNotFoundWhenPatchingUnknownProfile() throws Exception {
        mockMvc.perform(patch("/api/user-profiles/{id}", "unknown-profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody(new UserProfilePatchRequest(
                                List.of("兴趣"), null, null, null, null, null, null))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    private String createProfile() throws Exception {
        String body = mockMvc.perform(post("/api/user-profiles"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String id = objectMapper.readTree(body).path("id").asText();
        assertNotEquals("", id, "创建响应必须包含标识");
        return id;
    }

    private void patchProfile(String id, UserProfilePatchRequest request) throws Exception {
        mockMvc.perform(patch("/api/user-profiles/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody(request)))
                .andExpect(status().isOk());
    }

    /**
     * 用 DTO 序列化请求体：字段值为 {@code null} 时同样会出现在 JSON 中，
     * 服务端据此判定「本次不更新该区」。
     */
    private String patchBody(UserProfilePatchRequest request) throws Exception {
        return objectMapper.writeValueAsString(request);
    }

    private static EvidencePayload evidence(String sourceRef, String claim) {
        return new EvidencePayload(
                EvidenceSourceType.USER_INPUT, sourceRef, claim, null, true);
    }
}
