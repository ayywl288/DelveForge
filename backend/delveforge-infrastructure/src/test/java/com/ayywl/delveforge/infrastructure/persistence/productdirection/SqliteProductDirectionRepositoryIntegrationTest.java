package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionContentConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.infrastructure.persistence.SqliteDataSourceConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 验证 {@link SqliteProductDirectionRepository} 与真实 SQLite + Flyway + MyBatis-Plus 的集成。
 *
 * <p>使用测试专用的临时数据库，不接触开发者本地数据库。{@code @Transactional}
 * 使每个测试方法结束后回滚，保证方法之间状态隔离。
 *
 * <p>本类只使用生产迁移（{@code classpath:db/migration}），因此同时验证了
 * {@code V5__product_direction.sql} 在真实 SQLite 上的可执行性。
 */
@SpringBootTest(
        classes = SqliteProductDirectionRepositoryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SqliteProductDirectionRepositoryIntegrationTest {

    private static final Path DATABASE_FILE =
            Path.of("target", "test-databases", UUID.randomUUID().toString(), "delveforge.db");

    private static final ProductDirectionId DIRECTION_ID = new ProductDirectionId("direction-1");

    private static final UserProfileId USER_PROFILE_ID = new UserProfileId("user-profile-1");

    private static final int USER_PROFILE_REVISION = 3;

    private static final RepositoryProfileId REPOSITORY_PROFILE_ID =
            new RepositoryProfileId("repository-profile-1");

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final String TITLE = "个人记账 + 报表导出";

    private static final Evidence USER_EVIDENCE = new Evidence(
            EvidenceSourceType.USER_INPUT, "user-profile-1#interests", "用户长期关注记账工具",
            0.8, true);

    private static final Evidence REPOSITORY_EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "src/main/java/report", "已有报表渲染模块", null, false);

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("delveforge.persistence.database-file", DATABASE_FILE::toString);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({SqliteDataSourceConfiguration.class, SqliteProductDirectionRepository.class})
    @MapperScan("com.ayywl.delveforge.infrastructure.persistence.productdirection")
    static class TestApplication {
    }

    @Autowired
    private ProductDirectionRepository repository;

    @Autowired
    private ProductDirectionMapper directionMapper;

    @Autowired
    private ProductDirectionRepositoryProfileMapper repositoryProfileMapper;

    @Autowired
    private ProductDirectionCandidateAssetMapper candidateAssetMapper;

    @Autowired
    private ProductDirectionRiskMapper riskMapper;

    @Autowired
    private ProductDirectionEvidenceMapper evidenceMapper;

    @Test
    void savesAndReloadsEveryDomainFieldOfACandidateDirection() {
        repository.save(candidateDirection());

        ProductDirection reloaded = repository.findById(DIRECTION_ID).orElseThrow();

        assertEquals(DIRECTION_ID, reloaded.id());
        assertEquals(ProductDirectionStatus.CANDIDATE, reloaded.status());
        assertEquals(USER_PROFILE_ID, reloaded.userProfileId());
        assertEquals(USER_PROFILE_REVISION, reloaded.userProfileRevision());
        assertEquals(List.of(REPOSITORY_PROFILE_ID), reloaded.repositoryProfileIds());
        assertEquals(TITLE, reloaded.title());
        assertEquals("现有记账工具缺少可导出的报表", reloaded.problem());
        assertEquals("单用户桌面记账工具 + 报表导出", reloaded.targetProduct());
        assertEquals("用户已经在用记账工具，且技术栈匹配", reloaded.userFit());
        assertEquals(List.of(ASSET_ID), reloaded.candidateAssetIds());
        assertEquals("相比现有工具增加了自定义报表", reloaded.differentiation());
        assertEquals("可复用现有报表模块的渲染能力", reloaded.technicalValue());
        assertEquals("中等：主要在导出与模板部分", reloaded.estimatedComplexity());
        assertEquals(List.of("模板格式复杂度可能超预期"), reloaded.risks());
        assertEquals(List.of(USER_EVIDENCE, REPOSITORY_EVIDENCE), reloaded.evidence());
    }

    @Test
    void restoresSelectedDirection() {
        ProductDirection direction = candidateDirection();
        direction.select();

        repository.save(direction);

        assertEquals(ProductDirectionStatus.SELECTED,
                repository.findById(DIRECTION_ID).orElseThrow().status());
    }

    /**
     * REJECTED 的方向不进入 Evolution Planning，但它作为历史方向必须保留下来，
     * 不能因为状态而被删除（§10.5、RULE-DOM-007）。
     */
    @Test
    void restoresRejectedDirection() {
        ProductDirection direction = candidateDirection();
        direction.reject();

        repository.save(direction);

        ProductDirection reloaded = repository.findById(DIRECTION_ID).orElseThrow();
        assertEquals(ProductDirectionStatus.REJECTED, reloaded.status());
        assertEquals(TITLE, reloaded.title());
        assertEquals(List.of(USER_EVIDENCE, REPOSITORY_EVIDENCE), reloaded.evidence());
    }

    @Test
    void restoresSupersededDirection() {
        ProductDirection direction = candidateDirection();
        direction.select();
        direction.supersede();

        repository.save(direction);

        ProductDirection reloaded = repository.findById(DIRECTION_ID).orElseThrow();
        assertEquals(ProductDirectionStatus.SUPERSEDED, reloaded.status());
        assertEquals(USER_PROFILE_REVISION, reloaded.userProfileRevision());
        assertEquals(List.of(ASSET_ID), reloaded.candidateAssetIds());
    }

    /**
     * 生命周期状态变化是这条方向自己的领域行为，因此同一标识必须允许再次保存。
     *
     * <p>同时验证 §10.5：状态更新前后，discovery basis 与 recommendation content
     * 逐项保持一致。内容行在更新路径上根本不会被写入，因此这不是比较通过后的默契。
     */
    @Test
    void updatesLifecycleStatusWithoutRewritingRecommendation() {
        ProductDirection direction = candidateDirection();
        repository.save(direction);
        int contentRowsBefore = contentRowCount();

        direction.select();
        repository.save(direction);
        direction.supersede();
        repository.save(direction);

        ProductDirection reloaded = repository.findById(DIRECTION_ID).orElseThrow();

        assertEquals(ProductDirectionStatus.SUPERSEDED, reloaded.status());
        assertEquals(USER_PROFILE_ID, reloaded.userProfileId());
        assertEquals(USER_PROFILE_REVISION, reloaded.userProfileRevision());
        assertEquals(List.of(REPOSITORY_PROFILE_ID), reloaded.repositoryProfileIds());
        assertEquals(TITLE, reloaded.title());
        assertEquals("现有记账工具缺少可导出的报表", reloaded.problem());
        assertEquals("单用户桌面记账工具 + 报表导出", reloaded.targetProduct());
        assertEquals("用户已经在用记账工具，且技术栈匹配", reloaded.userFit());
        assertEquals(List.of(ASSET_ID), reloaded.candidateAssetIds());
        assertEquals("相比现有工具增加了自定义报表", reloaded.differentiation());
        assertEquals("可复用现有报表模块的渲染能力", reloaded.technicalValue());
        assertEquals("中等：主要在导出与模板部分", reloaded.estimatedComplexity());
        assertEquals(List.of("模板格式复杂度可能超预期"), reloaded.risks());
        assertEquals(List.of(USER_EVIDENCE, REPOSITORY_EVIDENCE), reloaded.evidence());
        assertEquals(1, directionMapper.selectCount(null).intValue(), "状态更新不得产生第二行");
        assertEquals(contentRowsBefore, contentRowCount(), "状态更新不得改动内容行");
    }

    /** 内容不变、状态也不变的重复保存是幂等的。 */
    @Test
    void savingTheSameDirectionTwiceKeepsOneDirectionWithItsContent() {
        ProductDirection direction = candidateDirection();
        repository.save(direction);
        int contentRowsBefore = contentRowCount();

        repository.save(direction);

        assertEquals(1, directionMapper.selectCount(null).intValue());
        assertEquals(contentRowsBefore, contentRowCount());
        assertEquals(ProductDirectionStatus.CANDIDATE,
                repository.findById(DIRECTION_ID).orElseThrow().status());
    }

    /**
     * Product Direction 允许更新的是生命周期状态，不是推荐语义。
     *
     * <p>同一个标识提交另一条内容不同的方向时保存被拒绝：覆盖会静默改写这条方向
     * 当初凭什么被推荐的依据（§10.5、RULE-DOM-007），静默忽略则会让调用方以为
     * 改动已经生效。
     */
    @Test
    void rejectsSavingDifferentRecommendationForTheSameDirection() {
        repository.save(candidateDirection());
        int contentRowsBefore = contentRowCount();

        ProductDirection rewritten = ProductDirection.create(
                DIRECTION_ID,
                USER_PROFILE_ID,
                USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                "换成另一个方向",
                "另一个问题",
                "另一个目标产品",
                "另一个匹配点",
                List.of(ASSET_ID),
                "另一个差异化",
                "另一个技术价值",
                "另一个复杂度",
                List.of(),
                List.of(USER_EVIDENCE));

        assertThrows(ProductDirectionContentConflictException.class,
                () -> repository.save(rewritten));

        ProductDirection stored = repository.findById(DIRECTION_ID).orElseThrow();
        assertEquals(TITLE, stored.title(), "已保存的推荐内容不得被改写");
        assertEquals(ProductDirectionStatus.CANDIDATE, stored.status());
        assertEquals(1, directionMapper.selectCount(null).intValue());
        assertEquals(contentRowsBefore, contentRowCount(), "失败后不得留下半写入的内容行");
    }

    /** 只有状态变化不算内容冲突；内容里任何一项变化都算。 */
    @Test
    void rejectsSavingDifferentAnalysisBasisForTheSameDirection() {
        repository.save(candidateDirection());

        ProductDirection otherUserProfileRevision = ProductDirection.create(
                DIRECTION_ID,
                USER_PROFILE_ID,
                USER_PROFILE_REVISION + 1,
                List.of(REPOSITORY_PROFILE_ID),
                TITLE,
                "现有记账工具缺少可导出的报表",
                "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配",
                List.of(ASSET_ID),
                "相比现有工具增加了自定义报表",
                "可复用现有报表模块的渲染能力",
                "中等：主要在导出与模板部分",
                List.of("模板格式复杂度可能超预期"),
                List.of(USER_EVIDENCE, REPOSITORY_EVIDENCE));

        assertThrows(ProductDirectionContentConflictException.class,
                () -> repository.save(otherUserProfileRevision));

        assertEquals(USER_PROFILE_REVISION,
                repository.findById(DIRECTION_ID).orElseThrow().userProfileRevision(),
                "历史记录的用户侧追溯点不得被改写（INV-D02）");
    }

    @Test
    void returnsEmptyWhenDirectionDoesNotExist() {
        assertTrue(repository.findById(new ProductDirectionId("unknown-direction")).isEmpty());
    }

    /** 多值字段的顺序具有领域含义，读取时必须按原顺序还原。 */
    @Test
    void keepsOrderOfMultiValuedFields() {
        ProductDirection direction = ProductDirection.create(
                DIRECTION_ID,
                USER_PROFILE_ID,
                USER_PROFILE_REVISION,
                List.of(new RepositoryProfileId("profile-2"), new RepositoryProfileId("profile-1")),
                TITLE,
                "问题", "目标产品", "匹配点",
                List.of(new SoftwareAssetId("asset-2"), new SoftwareAssetId("asset-1")),
                "差异化", "技术价值", "复杂度",
                List.of("第三个风险", "第一个风险", "第二个风险"),
                List.of(REPOSITORY_EVIDENCE, USER_EVIDENCE));

        repository.save(direction);

        ProductDirection reloaded = repository.findById(DIRECTION_ID).orElseThrow();

        assertEquals(List.of(new RepositoryProfileId("profile-2"), new RepositoryProfileId("profile-1")),
                reloaded.repositoryProfileIds(), "顺序必须原样保留，而不是按内容排序");
        assertEquals(List.of(new SoftwareAssetId("asset-2"), new SoftwareAssetId("asset-1")),
                reloaded.candidateAssetIds());
        assertEquals(List.of("第三个风险", "第一个风险", "第二个风险"), reloaded.risks());
        assertEquals(List.of(REPOSITORY_EVIDENCE, USER_EVIDENCE), reloaded.evidence());
    }

    /** 一个真实存在的方向可能确实没有已识别的主要风险，此时该字段没有任何行。 */
    @Test
    void restoresDirectionWithoutRisks() {
        ProductDirection direction = ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                TITLE, "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(USER_EVIDENCE));

        repository.save(direction);

        assertEquals(List.of(), repository.findById(DIRECTION_ID).orElseThrow().risks());
    }

    @Test
    void restoresEvidenceWithoutConfidenceAndUnconfirmed() {
        repository.save(candidateDirection());

        Evidence restored = repository.findById(DIRECTION_ID).orElseThrow().evidence().get(1);

        assertNull(restored.confidence());
        assertFalse(restored.confirmed());
    }

    /**
     * confidence 为负零时，一次合法的状态更新不得被误判成内容冲突。
     *
     * <p>SQLite 的 REAL 不保留负零：{@code -0.0} 写入后读回是 {@code 0.0}。
     * 而 {@code Evidence} 是 record，它的相等性按 {@code Double.equals} 比较 confidence，
     * 会区分正零与负零。若两侧不统一比较语义，下面的第二次 save 会抛
     * {@link ProductDirectionContentConflictException}——调用方没有改动任何推荐内容。
     */
    @Test
    void keepsDirectionWithNegativeZeroConfidenceSavable() {
        ProductDirection direction = directionWithConfidence(-0.0);
        repository.save(direction);

        direction.select();
        repository.save(direction);

        ProductDirection reloaded = repository.findById(DIRECTION_ID).orElseThrow();
        assertEquals(ProductDirectionStatus.SELECTED, reloaded.status(), "状态更新应被接受");
        assertEquals(1, directionMapper.selectCount(null).intValue());
    }

    /**
     * 存储不保留负零，读回的是正零。
     *
     * <p>把正负零统一成正零是 Adapter 针对 SQLite REAL 往返行为做的实现选择，
     * 不是领域模型定义的等价语义（§3.6 没有规定正负零是否等价）。
     * 本测试固定的是这层存储的当前行为，而不是一条领域规则。
     */
    @Test
    void restoresNegativeZeroConfidenceAsPositiveZero() {
        repository.save(directionWithConfidence(-0.0));

        Double restored = repository.findById(DIRECTION_ID).orElseThrow()
                .evidence().get(0).confidence();

        assertEquals(0, Double.compare(0.0, restored), "读回的 confidence 应是正零");
    }

    /** 内容完全没变的重复保存同样是幂等的，不论 confidence 是否为零。 */
    @Test
    void keepsRepeatedSaveOfNegativeZeroConfidenceIdempotent() {
        ProductDirection direction = directionWithConfidence(-0.0);
        repository.save(direction);
        repository.save(direction);

        assertEquals(1, directionMapper.selectCount(null).intValue());
        assertEquals(1, evidenceRows().size());
    }

    @Test
    void writesExactlyOneDirectionWithItsContent() {
        repository.save(candidateDirection());

        assertEquals(1, directionMapper.selectCount(null).intValue());
        assertEquals(1, repositoryProfileRows().size());
        assertEquals(1, candidateAssetRows().size());
        assertEquals(1, riskRows().size());
        assertEquals(2, evidenceRows().size());
    }

    /** 一条 Evidence 的 confidence 由调用方给定的方向，其余内容与 {@link #candidateDirection()} 相同。 */
    private static ProductDirection directionWithConfidence(Double confidence) {
        return ProductDirection.create(
                DIRECTION_ID,
                USER_PROFILE_ID,
                USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                TITLE,
                "现有记账工具缺少可导出的报表",
                "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配",
                List.of(ASSET_ID),
                "相比现有工具增加了自定义报表",
                "可复用现有报表模块的渲染能力",
                "中等：主要在导出与模板部分",
                List.of(),
                List.of(new Evidence(EvidenceSourceType.USER_INPUT, "user-profile-1#interests",
                        "用户长期关注记账工具", confidence, true)));
    }

    private static ProductDirection candidateDirection() {
        return ProductDirection.create(
                DIRECTION_ID,
                USER_PROFILE_ID,
                USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                TITLE,
                "现有记账工具缺少可导出的报表",
                "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配",
                List.of(ASSET_ID),
                "相比现有工具增加了自定义报表",
                "可复用现有报表模块的渲染能力",
                "中等：主要在导出与模板部分",
                List.of("模板格式复杂度可能超预期"),
                List.of(USER_EVIDENCE, REPOSITORY_EVIDENCE));
    }

    /** 四个子表在当前方向下的总行数，用于验证更新路径不会改动内容行。 */
    private int contentRowCount() {
        return repositoryProfileRows().size()
                + candidateAssetRows().size()
                + riskRows().size()
                + evidenceRows().size();
    }

    private List<ProductDirectionRepositoryProfileDO> repositoryProfileRows() {
        return repositoryProfileMapper.selectList(
                new LambdaQueryWrapper<ProductDirectionRepositoryProfileDO>()
                        .eq(ProductDirectionRepositoryProfileDO::getDirectionId,
                                DIRECTION_ID.value()));
    }

    private List<ProductDirectionCandidateAssetDO> candidateAssetRows() {
        return candidateAssetMapper.selectList(
                new LambdaQueryWrapper<ProductDirectionCandidateAssetDO>()
                        .eq(ProductDirectionCandidateAssetDO::getDirectionId,
                                DIRECTION_ID.value()));
    }

    private List<ProductDirectionRiskDO> riskRows() {
        return riskMapper.selectList(
                new LambdaQueryWrapper<ProductDirectionRiskDO>()
                        .eq(ProductDirectionRiskDO::getDirectionId, DIRECTION_ID.value()));
    }

    private List<ProductDirectionEvidenceDO> evidenceRows() {
        return evidenceMapper.selectList(
                new LambdaQueryWrapper<ProductDirectionEvidenceDO>()
                        .eq(ProductDirectionEvidenceDO::getDirectionId, DIRECTION_ID.value()));
    }
}
