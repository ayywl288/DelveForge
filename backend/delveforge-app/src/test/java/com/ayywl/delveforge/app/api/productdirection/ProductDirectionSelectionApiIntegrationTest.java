package com.ayywl.delveforge.app.api.productdirection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.UserProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证用户显式选择 / 拒绝方向的 HTTP 端点，以及 INV-D09 在真实链路上的协调。
 *
 * <p>使用完整 Spring 上下文与真实 SQLite：Controller 映射、Composition Root 装配、
 * Use Case 编排、Domain 状态机、Persistence Adapter 全部是真实实现。
 *
 * <p>方向直接用 Persistence Port 落库，而不是走一次完整发现：本类验证的是「方向已经存在
 * 之后用户能做什么」，与方向从哪来无关。选择与拒绝都不调用 AI。
 *
 * <p>{@code @Transactional} 使每个测试方法结束后回滚，保证方法之间状态隔离。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductDirectionSelectionApiIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final ProductDirectionId FIRST_ID = new ProductDirectionId("direction-1");

    private static final ProductDirectionId SECOND_ID = new ProductDirectionId("direction-2");

    private static final UserProfileId USER_PROFILE_ID = new UserProfileId("user-profile-1");

    private static final int USER_PROFILE_REVISION = 3;

    private static final RepositoryProfileId REPOSITORY_PROFILE_ID =
            new RepositoryProfileId("repository-profile-1");

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final Evidence EVIDENCE = new Evidence(
            EvidenceSourceType.USER_INPUT, "user-profile-1#interests", "用户长期关注记账工具",
            0.8, true);

    private static final DirectionEvidenceSupport SUPPORT = new DirectionEvidenceSupport(
            List.of(new EvidenceBasis(EVIDENCE,
                    new UserProfileEvidenceOrigin(USER_PROFILE_ID, USER_PROFILE_REVISION))),
            List.of(), List.of());

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProductDirectionRepository productDirectionRepository;

    /**
     * 真实 Persistence Adapter 的 spy：用来复现并发选择的形状。
     *
     * <p>「读出当前方向」与「写入新方向」之间存在窗口，另一个请求可能恰好在这中间完成了
     * 自己的选择。真实竞态无法在单线程测试里重放，因此这里让这一次读取返回「没有当前方向」，
     * 而库里其实已经有一个——写入时就会撞上存储层的唯一约束。
     */
    @MockitoSpyBean
    private ProductDirectionRepository spiedRepository;

    // ---------------------------------------------------------------------
    // 选择
    // ---------------------------------------------------------------------

    @Test
    void selectsACandidateDirection() throws Exception {
        productDirectionRepository.save(direction(FIRST_ID));

        mockMvc.perform(post("/api/product-directions/{id}/select", FIRST_ID.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FIRST_ID.value()))
                .andExpect(jsonPath("$.status").value("SELECTED"))
                .andExpect(jsonPath("$.userProfileId").value(USER_PROFILE_ID.value()))
                .andExpect(jsonPath("$.userProfileRevision").value(USER_PROFILE_REVISION))
                .andExpect(jsonPath("$.repositoryProfileIds[0]")
                        .value(REPOSITORY_PROFILE_ID.value()))
                .andExpect(jsonPath("$.candidateAssetIds[0]").value(ASSET_ID.value()))
                .andExpect(jsonPath("$.evidenceSupport.userNeed[0].evidence.claim")
                        .value(EVIDENCE.claim()));

        assertEquals(ProductDirectionStatus.SELECTED, statusOf(FIRST_ID),
                "返回状态必须与落库状态一致");
    }

    /**
     * 切换方向：原方向在同一次操作中进入 SUPERSEDED，两条都持久化。
     */
    @Test
    void switchesTheCurrentSelectionAndSupersedesThePreviousOne() throws Exception {
        productDirectionRepository.save(selectedDirection(FIRST_ID));
        productDirectionRepository.save(direction(SECOND_ID));

        mockMvc.perform(post("/api/product-directions/{id}/select", SECOND_ID.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SECOND_ID.value()))
                .andExpect(jsonPath("$.status").value("SELECTED"));

        assertEquals(ProductDirectionStatus.SUPERSEDED, statusOf(FIRST_ID));
        assertEquals(ProductDirectionStatus.SELECTED, statusOf(SECOND_ID));
        assertEquals(SECOND_ID, productDirectionRepository.findCurrentSelected()
                .orElseThrow().id());
    }

    /**
     * 被取代的方向仍然读得回来，内容与依据不变（§10.5、RULE-DOM-007）。
     */
    @Test
    void keepsTheSupersededDirectionReadable() throws Exception {
        productDirectionRepository.save(selectedDirection(FIRST_ID));
        productDirectionRepository.save(direction(SECOND_ID));

        mockMvc.perform(post("/api/product-directions/{id}/select", SECOND_ID.value()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/product-directions/{id}", FIRST_ID.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUPERSEDED"))
                .andExpect(jsonPath("$.evidenceSupport.userNeed[0].evidence.claim")
                        .value(EVIDENCE.claim()));
    }

    // ---------------------------------------------------------------------
    // 拒绝
    // ---------------------------------------------------------------------

    @Test
    void rejectsACandidateDirection() throws Exception {
        productDirectionRepository.save(direction(FIRST_ID));

        mockMvc.perform(post("/api/product-directions/{id}/reject", FIRST_ID.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FIRST_ID.value()))
                .andExpect(jsonPath("$.status").value("REJECTED"));

        assertEquals(ProductDirectionStatus.REJECTED, statusOf(FIRST_ID));
    }

    /**
     * 已经选中的方向不能被直接拒绝：「不想继续用这个方向」属于切换（§6.2）。
     */
    @Test
    void rejectsRejectingASelectedDirection() throws Exception {
        productDirectionRepository.save(selectedDirection(FIRST_ID));

        mockMvc.perform(post("/api/product-directions/{id}/reject", FIRST_ID.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertEquals(ProductDirectionStatus.SELECTED, statusOf(FIRST_ID),
                "被拒绝的操作不得改变状态");
    }

    // ---------------------------------------------------------------------
    // 失败语义
    // ---------------------------------------------------------------------

    @Test
    void returnsNotFoundForUnknownDirection() throws Exception {
        mockMvc.perform(post("/api/product-directions/{id}/select", "unknown-direction"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(post("/api/product-directions/{id}/reject", "unknown-direction"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void returnsConflictWhenSelectingAnAlreadySelectedDirection() throws Exception {
        productDirectionRepository.save(selectedDirection(FIRST_ID));

        mockMvc.perform(post("/api/product-directions/{id}/select", FIRST_ID.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        assertEquals(ProductDirectionStatus.SELECTED, statusOf(FIRST_ID));
    }

    /**
     * 并发选择的形状：本次读取没有看到当前方向，但写入时库里已经有一个。
     *
     * <p>存储层拒绝它，Adapter 把唯一约束翻译成 Application 的冲突语义，最终是 409——
     * 响应里不出现任何 SQL、索引名或约束文本。
     */
    @Test
    void returnsConflictWhenAnotherSelectionWinsTheRace() throws Exception {
        productDirectionRepository.save(selectedDirection(FIRST_ID));
        doReturn(Optional.empty()).when(spiedRepository).findCurrentSelected();
        productDirectionRepository.save(direction(SECOND_ID));

        String body = mockMvc.perform(post("/api/product-directions/{id}/select", SECOND_ID.value()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(!body.contains("ux_product_direction") && !body.contains("SQLITE")
                        && !body.contains("constraint"),
                "响应不得泄漏存储层细节: " + body);

        assertEquals(ProductDirectionStatus.SELECTED, statusOf(FIRST_ID), "已存在的那条不受影响");
    }

    private ProductDirectionStatus statusOf(ProductDirectionId id) {
        return productDirectionRepository.findById(id).orElseThrow().status();
    }

    // ---------------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------------

    private static ProductDirection direction(ProductDirectionId id) {
        return ProductDirection.create(
                id,
                USER_PROFILE_ID,
                USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                "个人记账 + 报表导出",
                "现有记账工具缺少可导出的报表",
                "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配",
                List.of(ASSET_ID),
                "相比现有工具增加了自定义报表",
                "可复用现有报表模块的渲染能力",
                "中等：主要在导出与模板部分",
                List.of("模板格式复杂度可能超预期"),
                SUPPORT);
    }

    private static ProductDirection selectedDirection(ProductDirectionId id) {
        ProductDirection direction = direction(id);
        direction.select();
        return direction;
    }
}
