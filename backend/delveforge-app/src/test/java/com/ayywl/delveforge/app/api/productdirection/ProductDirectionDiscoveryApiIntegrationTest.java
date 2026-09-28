package com.ayywl.delveforge.app.api.productdirection;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayywl.delveforge.application.opportunitydiscovery.direction.DiscoverProductDirectionsRequest;
import com.ayywl.delveforge.application.opportunitydiscovery.direction.DiscoverProductDirectionsUseCase;
import com.ayywl.delveforge.application.port.ai.AiGateway;
import com.ayywl.delveforge.application.port.ai.AiGatewayException;
import com.ayywl.delveforge.application.port.ai.AiRequest;
import com.ayywl.delveforge.application.port.persistence.RepositoryProfileRepository;
import com.ayywl.delveforge.application.port.persistence.UserProfileRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.infrastructure.persistence.productdirection.ProductDirectionEvidenceSupportMapper;
import com.ayywl.delveforge.infrastructure.persistence.productdirection.ProductDirectionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证 Product Direction Discovery 与读取端点。
 *
 * <p>使用完整 Spring 上下文与真实 SQLite：Controller 映射、请求校验、Composition Root
 * 装配（含方向标识生成）、Use Case 编排、解析与引用解析、Domain Service 校验、
 * Persistence Adapter 都是真实实现。只有 AI 在 Port 边界用替身替代——它返回的是
 * 真实形状的模型输出，因此这条链路上除了模型本身之外没有别的环节被绕过。
 *
 * <p>{@code @Transactional} 使每个测试方法结束后回滚，保证方法之间状态隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductDirectionDiscoveryApiIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final UserProfileId USER_PROFILE_ID = new UserProfileId("user-profile-1");

    private static final RepositoryProfileId REPOSITORY_PROFILE_ID =
            new RepositoryProfileId("repository-profile-1");

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final Evidence USER_EVIDENCE = new Evidence(
            EvidenceSourceType.USER_INPUT, "用户输入：导出报表很麻烦",
            "用户对报表导出的不满", 0.8, true);

    private static final Evidence REPOSITORY_EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "src/main/report", "已有报表渲染模块", null, false);

    private static final StubAiGateway AI_GATEWAY = new StubAiGateway();

    /** 本次种子 Profile 停在的版本；发现请求必须依据这一版。 */
    private int revision;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @TestConfiguration
    static class StubbedCapabilities {

        @Bean
        @Primary
        AiGateway aiGateway() {
            return AI_GATEWAY;
        }
    }

    /**
     * 真实 Use Case 的 spy：既照常执行整条链路，又能观察它「有没有被调用、被传了什么」。
     *
     * <p>两类性质只能靠观察调用本身来验证，光看响应码与数据库是看不出来的：
     *
     * <pre>
     * 非法请求被挡在业务代码之外   否则它可能在 Use Case 内部才被拒绝，结论完全一样
     * 合法请求恰好被调用一次       并且拿到的是正确映射过的参数
     * </pre>
     */
    @MockitoSpyBean
    private DiscoverProductDirectionsUseCase discoverProductDirectionsUseCase;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserProfileRepository userProfileRepository;

    @Autowired
    private RepositoryProfileRepository repositoryProfileRepository;

    @Autowired
    private ProductDirectionMapper productDirectionMapper;

    @Autowired
    private ProductDirectionEvidenceSupportMapper productDirectionEvidenceSupportMapper;

    @BeforeEach
    void seedConfirmedInputs() {
        UserProfile profile = UserProfile.create(USER_PROFILE_ID);
        profile.updateInterests(List.of("个人记账", "数据可视化"));
        profile.recordEvidence(USER_EVIDENCE);
        profile.beginReview();
        profile.confirm(profile.revision());
        userProfileRepository.save(profile);
        revision = profile.revision();

        repositoryProfileRepository.save(RepositoryProfile.create(
                REPOSITORY_PROFILE_ID,
                ASSET_ID,
                "abc123",
                "个人记账工具",
                List.of("Java 21", "Spring Boot"),
                List.of("accounting", "report"),
                List.of("记账", "报表渲染"),
                List.of("报表导出"),
                List.of("没有自动化测试"),
                List.of("模块耦合"),
                List.of(REPOSITORY_EVIDENCE)));

        AI_GATEWAY.respond(proposals("可导出的记账工具", "报表订阅服务", "个人财务看板"));
    }

    // ---------------------------------------------------------------------
    // 成功路径
    // ---------------------------------------------------------------------

    @Test
    void discoversAndReturnsPersistedCandidateDirections() throws Exception {
        mockMvc.perform(post("/api/product-directions/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discoveryBody(quoted(USER_PROFILE_ID.value()),
                                String.valueOf(revision),
                                idArray(REPOSITORY_PROFILE_ID.value()))))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.directions.length()").value(3))
                // 顺序与模型给出的一致：用户看到的是模型这次发现的那几条，不是重排过的
                .andExpect(jsonPath("$.directions[0].title").value("可导出的记账工具"))
                .andExpect(jsonPath("$.directions[1].title").value("报表订阅服务"))
                .andExpect(jsonPath("$.directions[2].title").value("个人财务看板"))
                // 状态由服务端决定：新方向一律是 CANDIDATE，模型无从影响
                .andExpect(jsonPath("$.directions[*].status", everyItem(is("CANDIDATE"))))
                // 追溯点来自服务端加载的那一版 Profile，而不是请求里的字面值
                .andExpect(jsonPath("$.directions[*].userProfileId",
                        everyItem(is(USER_PROFILE_ID.value()))))
                .andExpect(jsonPath("$.directions[*].userProfileRevision", everyItem(is(revision))))
                .andExpect(jsonPath("$.directions[*].repositoryProfileIds[0]",
                        everyItem(is(REPOSITORY_PROFILE_ID.value()))))
                .andExpect(jsonPath("$.directions[*].candidateAssetIds[0]",
                        everyItem(is(ASSET_ID.value()))))
                .andExpect(jsonPath("$.directions[0].evidenceSupport.userNeed.length()").value(1))
                .andExpect(jsonPath("$.directions[0].evidenceSupport.userNeed[0].evidence.claim")
                        .value(USER_EVIDENCE.claim()))
                .andExpect(jsonPath("$.directions[0].evidenceSupport.userNeed[0].origin.kind")
                        .value("USER_PROFILE"))
                .andExpect(jsonPath("$.directions[0].evidenceSupport.userNeed[0].origin.userProfileId")
                        .value(USER_PROFILE_ID.value()))
                .andExpect(jsonPath(
                        "$.directions[0].evidenceSupport.userNeed[0].origin.userProfileRevision")
                        .value(revision))
                .andExpect(jsonPath(
                        "$.directions[0].evidenceSupport.reusableCapability[0].origin.kind")
                        .value("REPOSITORY_PROFILE"))
                .andExpect(jsonPath(
                        "$.directions[0].evidenceSupport.reusableCapability[0]"
                                + ".origin.repositoryProfileId")
                        .value(REPOSITORY_PROFILE_ID.value()))
                .andExpect(jsonPath("$.directions[0].evidenceSupport.userFit.length()").value(1))
                // 一次 AI 调用里的临时引用不得出现在响应里
                .andExpect(content().string(not(containsString("U-E1"))))
                .andExpect(content().string(not(containsString("R1-E1"))));

        assertEquals(3, productDirectionMapper.selectCount(null).intValue(),
                "本次发现的全部候选方向都应当被保存");

        // 恰好调用一次，且拿到的是映射后的参数——接口层的职责就是把请求变成它。
        ArgumentCaptor<DiscoverProductDirectionsRequest> captured =
                ArgumentCaptor.forClass(DiscoverProductDirectionsRequest.class);
        verify(discoverProductDirectionsUseCase, times(1)).discover(captured.capture());

        DiscoverProductDirectionsRequest invoked = captured.getValue();
        assertEquals(new UserProfileId(USER_PROFILE_ID.value()), invoked.userProfileId());
        assertEquals(revision, invoked.expectedRevision());
        assertEquals(List.of(new RepositoryProfileId(REPOSITORY_PROFILE_ID.value())),
                invoked.repositoryProfileIds());
    }

    /**
     * 发现之后按标识读回：内容、依据与出处都经真实 SQLite 往返一次。
     */
    @Test
    void returnsDiscoveredDirectionByIdWithItsEvidenceTraceability() throws Exception {
        String id = discoverFirstDirectionId();

        mockMvc.perform(get("/api/product-directions/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("CANDIDATE"))
                .andExpect(jsonPath("$.userProfileId").value(USER_PROFILE_ID.value()))
                .andExpect(jsonPath("$.userProfileRevision").value(revision))
                .andExpect(jsonPath("$.repositoryProfileIds[0]")
                        .value(REPOSITORY_PROFILE_ID.value()))
                .andExpect(jsonPath("$.candidateAssetIds[0]").value(ASSET_ID.value()))
                .andExpect(jsonPath("$.evidenceSupport.userNeed[0].origin.kind")
                        .value("USER_PROFILE"))
                .andExpect(jsonPath("$.evidenceSupport.userNeed[0].origin.userProfileRevision")
                        .value(revision))
                .andExpect(jsonPath("$.evidenceSupport.reusableCapability[0].evidence.sourceType")
                        .value("REPOSITORY"))
                .andExpect(jsonPath("$.evidenceSupport.reusableCapability[0].evidence.sourceRef")
                        .value(REPOSITORY_EVIDENCE.sourceRef()))
                .andExpect(jsonPath("$.evidenceSupport.reusableCapability[0]"
                        + ".origin.repositoryProfileId")
                        .value(REPOSITORY_PROFILE_ID.value()));
    }

    /**
     * 同一次发现产生的方向标识互不相同。
     *
     * <p>标识相同就是同一条方向：三条候选会变成一条方向的三个副本，它们无法构成三个
     * 独立选择，保存时还会撞上同标识内容冲突。标识由 Composition Root 提供的生成器产生，
     * 因此这里也是在验证那套运行时策略。
     */
    @Test
    void issuesDistinctDirectionIdsWithinOneDiscovery() throws Exception {
        String body = mockMvc.perform(post("/api/product-directions/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discoveryBody(quoted(USER_PROFILE_ID.value()),
                                String.valueOf(revision),
                                idArray(REPOSITORY_PROFILE_ID.value()))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<String> ids = new ArrayList<>();
        for (JsonNode direction : objectMapper.readTree(body).get("directions")) {
            ids.add(direction.get("id").asText());
        }

        assertEquals(3, ids.size());
        assertEquals(3, Set.copyOf(ids).size(), "同一次发现不应产生重复标识: " + ids);
    }

    @Test
    void returnsNotFoundForUnknownDirection() throws Exception {
        mockMvc.perform(get("/api/product-directions/{id}", "unknown-direction"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    /**
     * 方向标识、状态与依据都是服务端事实：请求体里给出它们也不会生效。
     *
     * <p>它们不是这个端点的输入，因此客户端无法通过它们凭空制造领域状态。
     */
    @Test
    void ignoresClientSuppliedIdentityStatusAndEvidence() throws Exception {
        String body = """
                {
                  "userProfileId": "%s",
                  "expectedRevision": %d,
                  "repositoryProfileIds": ["%s"],
                  "id": "client-chosen-direction",
                  "status": "SELECTED",
                  "evidence": { "userNeed": ["client-chosen-evidence"] },
                  "directions": []
                }
                """.formatted(USER_PROFILE_ID.value(), revision, REPOSITORY_PROFILE_ID.value());

        mockMvc.perform(post("/api/product-directions/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.directions.length()").value(3))
                .andExpect(jsonPath("$.directions[*].id",
                        everyItem(not(is("client-chosen-direction")))))
                .andExpect(jsonPath("$.directions[*].status", everyItem(is("CANDIDATE"))));
    }

    // ---------------------------------------------------------------------
    // 请求形状
    // ---------------------------------------------------------------------

    /**
     * 请求体形状不合法的各种情形一律 400，且不留下任何方向。
     *
     * <p>共同点是「字段缺失」不能被静默当成一个合法取值：{@code expectedRevision} 缺失时
     * 若被当成 0，调用方就凭空声明了一个不对应任何版本的依据；{@code repositoryProfileIds}
     * 缺失或为空时若被当成「没有依据」，后续链路会去执行一次注定失败的发现。
     *
     * <p>这些请求都必须被挡在业务代码之外：整轮跑完，Use Case 一次都不应被调用。
     */
    @Test
    void rejectsMalformedDiscoveryRequestsWithoutWritingAnything() throws Exception {
        for (String body : List.of(
                // userProfileId 缺失 / null / 空白
                """
                { "expectedRevision": 2, "repositoryProfileIds": ["repository-profile-1"] }
                """,
                """
                { "userProfileId": null, "expectedRevision": 2,
                  "repositoryProfileIds": ["repository-profile-1"] }
                """,
                """
                { "userProfileId": "  ", "expectedRevision": 2,
                  "repositoryProfileIds": ["repository-profile-1"] }
                """,
                // expectedRevision 缺失 / null / 非正数
                """
                { "userProfileId": "user-profile-1", "repositoryProfileIds": ["repository-profile-1"] }
                """,
                """
                { "userProfileId": "user-profile-1", "expectedRevision": null,
                  "repositoryProfileIds": ["repository-profile-1"] }
                """,
                """
                { "userProfileId": "user-profile-1", "expectedRevision": 0,
                  "repositoryProfileIds": ["repository-profile-1"] }
                """,
                """
                { "userProfileId": "user-profile-1", "expectedRevision": -1,
                  "repositoryProfileIds": ["repository-profile-1"] }
                """,
                // repositoryProfileIds 缺失 / null / 空 / 含 null / 含空白
                """
                { "userProfileId": "user-profile-1", "expectedRevision": 2 }
                """,
                """
                { "userProfileId": "user-profile-1", "expectedRevision": 2,
                  "repositoryProfileIds": null }
                """,
                """
                { "userProfileId": "user-profile-1", "expectedRevision": 2,
                  "repositoryProfileIds": [] }
                """,
                """
                { "userProfileId": "user-profile-1", "expectedRevision": 2,
                  "repositoryProfileIds": [null] }
                """,
                """
                { "userProfileId": "user-profile-1", "expectedRevision": 2,
                  "repositoryProfileIds": ["  "] }
                """,
                // 单个标识而不是标识数组：形状不对，不能被当成「只有一个元素的列表」
                """
                { "userProfileId": "user-profile-1", "expectedRevision": 2,
                  "repositoryProfileIds": "repository-profile-1" }
                """,
                // expectedRevision 的 JSON 写法不是整数。
                //
                // 3.9 是其中最关键的一条：Jackson 默认把浮点数有损地读成整数，字段会在
                // 构造器校验之前就变成 3——一个看起来完全合法的版本号。若不在反序列化边界
                // 拦住，这次针对「第 3.9 版」的请求会照着第 3 版执行并成功返回候选方向。
                """
                { "userProfileId": "user-profile-1", "expectedRevision": 3.9,
                  "repositoryProfileIds": ["repository-profile-1"] }
                """,
                """
                { "userProfileId": "user-profile-1", "expectedRevision": 3.0,
                  "repositoryProfileIds": ["repository-profile-1"] }
                """,
                """
                { "userProfileId": "user-profile-1", "expectedRevision": 1e2,
                  "repositoryProfileIds": ["repository-profile-1"] }
                """,
                """
                { "userProfileId": "user-profile-1", "expectedRevision": "3",
                  "repositoryProfileIds": ["repository-profile-1"] }
                """,
                """
                { "userProfileId": "user-profile-1", "expectedRevision": 99999999999,
                  "repositoryProfileIds": ["repository-profile-1"] }
                """,
                // 请求体不是合法 json
                "{not json")) {

            mockMvc.perform(post("/api/product-directions/discovery")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }

        // 只断言 400 与「没写入」不足以说明这些请求被挡在业务代码之外：请求即使穿过了
        // 这一层、在 Use Case 内部才被拒绝（例如被当成版本过期），那两条断言同样会通过。
        // 因此这里直接观察 Use Case 本身。
        verify(discoverProductDirectionsUseCase, never()).discover(any());

        assertEquals(0, productDirectionMapper.selectCount(null).intValue(),
                "被拒绝的请求不得写入任何方向");
    }

    // ---------------------------------------------------------------------
    // 前置条件与失败路径
    // ---------------------------------------------------------------------

    /**
     * 调用方依据的那一版已经不是当前版本：拒绝，而不是改用当前版本。
     */
    @Test
    void rejectsStaleUserProfileRevision() throws Exception {
        mockMvc.perform(post("/api/product-directions/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discoveryBody(quoted(USER_PROFILE_ID.value()),
                                String.valueOf(revision + 1),
                                idArray(REPOSITORY_PROFILE_ID.value()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertNothingPersisted();
    }

    /**
     * 用于发现的 Profile 尚未确认：拒绝。
     *
     * <p>未确认的画像仍在变化，基于它得出的方向没有稳定的追溯点（§8.4、INV-D08）。
     */
    @Test
    void rejectsUserProfileThatIsNotConfirmed() throws Exception {
        UserProfileId reviewingId = new UserProfileId("user-profile-reviewing");
        UserProfile reviewing = UserProfile.create(reviewingId);
        reviewing.updateInterests(List.of("个人记账"));
        reviewing.beginReview();
        userProfileRepository.save(reviewing);

        mockMvc.perform(post("/api/product-directions/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discoveryBody(quoted(reviewingId.value()),
                                String.valueOf(reviewing.revision()),
                                idArray(REPOSITORY_PROFILE_ID.value()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertNothingPersisted();
    }

    @Test
    void returnsNotFoundForUnknownUserProfile() throws Exception {
        mockMvc.perform(post("/api/product-directions/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discoveryBody(quoted("unknown-user-profile"),
                                String.valueOf(revision),
                                idArray(REPOSITORY_PROFILE_ID.value()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertNothingPersisted();
    }

    @Test
    void returnsNotFoundForUnknownRepositoryProfile() throws Exception {
        mockMvc.perform(post("/api/product-directions/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discoveryBody(quoted(USER_PROFILE_ID.value()),
                                String.valueOf(revision),
                                idArray("unknown-repository-profile"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertNothingPersisted();
    }

    /**
     * 外部 AI 能力调用失败：502，且不留下任何方向。
     */
    @Test
    void propagatesAiGatewayFailureWithoutWritingAnything() throws Exception {
        AI_GATEWAY.failWith(new AiGatewayException("provider 不可用", new RuntimeException("boom")));

        mockMvc.perform(post("/api/product-directions/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discoveryBody(quoted(USER_PROFILE_ID.value()),
                                String.valueOf(revision),
                                idArray(REPOSITORY_PROFILE_ID.value()))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_CAPABILITY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("外部 AI 能力调用失败，详细信息见服务端日志"));

        assertNothingPersisted();
    }

    /**
     * 模型返回的内容不符合约定（缺少 {@code directions}）：与调用失败走同一条失败路径。
     */
    @Test
    void rejectsMalformedModelOutput() throws Exception {
        AI_GATEWAY.respond("{}");

        mockMvc.perform(post("/api/product-directions/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discoveryBody(quoted(USER_PROFILE_ID.value()),
                                String.valueOf(revision),
                                idArray(REPOSITORY_PROFILE_ID.value()))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_CAPABILITY_UNAVAILABLE"));

        assertNothingPersisted();
    }

    /**
     * 模型给出的方向数量不满足要求：领域拒绝，同样是外部能力一侧的失败。
     *
     * <p>它与「内容不满足约定」分开归类的原因在服务端，对调用方而言都是「这一次拿不到
     * 可用的候选方向」——它没有写错请求，重试也仍然取决于模型这次给出什么。
     */
    @Test
    void rejectsModelOutputThatTheDomainRefuses() throws Exception {
        AI_GATEWAY.respond(proposals("只有一个方向"));

        mockMvc.perform(post("/api/product-directions/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discoveryBody(quoted(USER_PROFILE_ID.value()),
                                String.valueOf(revision),
                                idArray(REPOSITORY_PROFILE_ID.value()))))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_CAPABILITY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("AI 返回的候选方向不满足领域要求，详细信息见服务端日志"));

        assertNothingPersisted();
    }

    // ---------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------

    private String discoverFirstDirectionId() throws Exception {
        String body = mockMvc.perform(post("/api/product-directions/discovery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(discoveryBody(quoted(USER_PROFILE_ID.value()),
                                String.valueOf(revision),
                                idArray(REPOSITORY_PROFILE_ID.value()))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode directions = objectMapper.readTree(body).get("directions");
        return directions.get(0).get("id").asText();
    }

    /**
     * 失败的一次发现不留下任何方向，也不留下任何依附于方向的依据行。
     *
     * <p>只数身份行不够：如果写入顺序是「先写依据、后写身份」，一次失败的发现可能留下
     * 一批指向不存在方向的依据，之后被别的方向无意捡走。
     */
    private void assertNothingPersisted() {
        assertEquals(0, productDirectionMapper.selectCount(null).intValue(),
                "失败的一次发现不得留下任何方向");
        assertEquals(0, productDirectionEvidenceSupportMapper.selectCount(null).intValue(),
                "失败的一次发现不得留下任何依据行");
    }

    /** 三个方向，引用同一批本次输入中提供的依据。 */
    private static String proposals(String... titles) {
        StringBuilder json = new StringBuilder("{\"directions\":[");
        for (int index = 0; index < titles.length; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("""
                    {
                      "title": "%s",
                      "problem": "现有记账工具的报表导出很麻烦",
                      "targetProduct": "单用户桌面记账工具 + 自定义报表导出",
                      "userFit": "用户长期自己维护记账工具，且技术栈匹配",
                      "candidateAssetIds": ["%s"],
                      "differentiation": "相比现有工具增加了自定义报表",
                      "technicalValue": "复用现有报表渲染能力",
                      "estimatedComplexity": "中等：主要在导出与模板部分",
                      "risks": [],
                      "evidence": {
                        "userNeed": ["U-E1"],
                        "userFit": ["U-E1"],
                        "reusableCapability": ["R1-E1"]
                      }
                    }
                    """.formatted(titles[index], ASSET_ID.value()));
        }
        return json.append("]}").toString();
    }

    /** 参数是原始 json 片段，便于表达「字段缺失」与「字段为 null」两种形状。 */
    private static String discoveryBody(String userProfileId, String expectedRevision,
                                        String repositoryProfileIds) {
        return """
                {
                  "userProfileId": %s,
                  "expectedRevision": %s,
                  "repositoryProfileIds": %s
                }
                """.formatted(userProfileId, expectedRevision, repositoryProfileIds);
    }

    private static String quoted(String value) {
        return "\"" + value + "\"";
    }

    /** {@code repositoryProfileIds} 是标识数组，不是单个标识。 */
    private static String idArray(String... ids) {
        StringBuilder array = new StringBuilder("[");
        for (int index = 0; index < ids.length; index++) {
            if (index > 0) {
                array.append(", ");
            }
            array.append(quoted(ids[index]));
        }
        return array.append(']').toString();
    }

    /** AI Gateway 替身：返回预设内容，或按预设抛错。 */
    private static final class StubAiGateway implements AiGateway {

        private String response = "{}";

        private RuntimeException failure;

        void respond(String rawResponse) {
            this.response = rawResponse;
            this.failure = null;
        }

        void failWith(RuntimeException exception) {
            this.failure = exception;
        }

        @Override
        public String generate(AiRequest request) {
            if (failure != null) {
                throw failure;
            }
            return response;
        }
    }
}
