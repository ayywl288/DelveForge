package com.ayywl.delveforge.infrastructure.persistence.productdirection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionContentConflictException;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionSelectionConflictException;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.UserProfileEvidenceOrigin;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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
 * {@code V6__product_direction_evidence_support.sql} 在真实 SQLite 上的可执行性。
 *
 * <p>重点在依据部分：Product Direction 长期保存的是「关键判断 → 依据 → 依据出自哪里」
 * 这条链，而不只是一份扁平列表。往返之后三组判断的划分、组内顺序与两种来源都必须还在。
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
            EvidenceSourceType.USER_INPUT, "用户输入：但导出报表很麻烦", "用户对报表导出的不满",
            0.8, true);

    private static final Evidence REPOSITORY_EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "src/main/report", "已有报表渲染模块", null, false);

    private static final EvidenceBasis USER_BASIS = new EvidenceBasis(
            USER_EVIDENCE, new UserProfileEvidenceOrigin(USER_PROFILE_ID, USER_PROFILE_REVISION));

    private static final EvidenceBasis REPOSITORY_BASIS = new EvidenceBasis(
            REPOSITORY_EVIDENCE, new RepositoryProfileEvidenceOrigin(REPOSITORY_PROFILE_ID));

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
    private ProductDirectionEvidenceSupportMapper evidenceSupportMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ---------------------------------------------------------------------
    // 往返
    // ---------------------------------------------------------------------

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

        DirectionEvidenceSupport support = reloaded.evidenceSupport();
        assertEquals(List.of(USER_BASIS), support.userNeed());
        assertEquals(List.of(USER_BASIS, REPOSITORY_BASIS), support.userFit());
        assertEquals(List.of(REPOSITORY_BASIS), support.reusableCapability());
    }

    /** 依据自身的字段完整往返，包括可为 null 的 confidence 与 0 / 1 保存的 confirmed。 */
    @Test
    void roundTripsEveryEvidenceField() {
        repository.save(candidateDirection());

        DirectionEvidenceSupport support =
                repository.findById(DIRECTION_ID).orElseThrow().evidenceSupport();

        Evidence user = support.userNeed().get(0).evidence();
        assertEquals(EvidenceSourceType.USER_INPUT, user.sourceType());
        assertEquals("用户输入：但导出报表很麻烦", user.sourceRef());
        assertEquals("用户对报表导出的不满", user.claim());
        assertEquals(0.8, user.confidence());
        assertTrue(user.confirmed());

        Evidence fromRepository = support.reusableCapability().get(0).evidence();
        assertEquals(EvidenceSourceType.REPOSITORY, fromRepository.sourceType());
        assertEquals("src/main/report", fromRepository.sourceRef());
        assertNull(fromRepository.confidence());
        assertFalse(fromRepository.confirmed());
    }

    /** User Profile 侧的来源必须带上确定的 revision（INV-D01、INV-D08）。 */
    @Test
    void roundTripsUserProfileEvidenceOrigin() {
        repository.save(candidateDirection());

        assertEquals(
                new UserProfileEvidenceOrigin(USER_PROFILE_ID, USER_PROFILE_REVISION),
                repository.findById(DIRECTION_ID).orElseThrow()
                        .evidenceSupport().userNeed().get(0).origin());
    }

    @Test
    void roundTripsRepositoryProfileEvidenceOrigin() {
        repository.save(candidateDirection());

        assertEquals(
                new RepositoryProfileEvidenceOrigin(REPOSITORY_PROFILE_ID),
                repository.findById(DIRECTION_ID).orElseThrow()
                        .evidenceSupport().reusableCapability().get(0).origin());
    }

    /** 同一份依据同时支撑多个判断时，这条关系必须完整保留。 */
    @Test
    void keepsOneBasisSupportingSeveralJudgements() {
        repository.save(candidateDirection());

        DirectionEvidenceSupport support =
                repository.findById(DIRECTION_ID).orElseThrow().evidenceSupport();

        assertEquals(List.of(USER_BASIS), support.userNeed());
        assertEquals(List.of(USER_BASIS, REPOSITORY_BASIS), support.userFit(),
                "同一条 userNeed 依据也出现在 userFit 里");
        assertEquals(1, rowsOfCategory("userNeed").size());
        assertEquals(2, rowsOfCategory("userFit").size(), "同一条依据在两个判断下各有一行");
        assertEquals(1, rowsOfCategory("reusableCapability").size());
    }

    /** 三组判断各自的组内顺序必须原样保留。 */
    @Test
    void keepsOrderWithinEachJudgement() {
        ProductDirection direction = ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                TITLE, "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(),
                new DirectionEvidenceSupport(
                        List.of(REPOSITORY_BASIS, USER_BASIS),
                        List.of(USER_BASIS, REPOSITORY_BASIS, USER_BASIS),
                        List.of(REPOSITORY_BASIS)));

        repository.save(direction);

        DirectionEvidenceSupport support =
                repository.findById(DIRECTION_ID).orElseThrow().evidenceSupport();
        assertEquals(List.of(REPOSITORY_BASIS, USER_BASIS), support.userNeed(),
                "顺序必须原样保留，而不是按内容排序");
        assertEquals(List.of(USER_BASIS, REPOSITORY_BASIS, USER_BASIS), support.userFit(),
                "同一组内允许重复出现同一条依据");
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
        assertEquals(List.of(USER_BASIS, REPOSITORY_BASIS),
                reloaded.evidenceSupport().userFit());
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
        assertEquals(List.of(USER_BASIS), reloaded.evidenceSupport().userNeed());
    }

    // ---------------------------------------------------------------------
    // 状态更新与内容保护
    // ---------------------------------------------------------------------

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
        assertEquals(List.of(USER_BASIS), reloaded.evidenceSupport().userNeed());
        assertEquals(List.of(USER_BASIS, REPOSITORY_BASIS),
                reloaded.evidenceSupport().userFit());
        assertEquals(List.of(REPOSITORY_BASIS),
                reloaded.evidenceSupport().reusableCapability());
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
    // ---------------------------------------------------------------------
    // 依据的数值往返：±0.0
    // ---------------------------------------------------------------------

    /**
     * confidence 为负零时，一次合法的状态更新不得被误判成内容冲突。
     *
     * <p>SQLite 的 REAL 不保留负零：{@code -0.0} 写入后读回是 {@code 0.0}。
     * 而 {@code Evidence} 是 record，它的相等性按 {@code Double.equals} 比较 confidence，
     * 会区分正零与负零。若两侧不统一比较语义，下面的第二次 save 会抛
     * {@link ProductDirectionContentConflictException}——调用方没有改动任何推荐内容。
     *
     * <p>这条回归在依据从扁平列表改为 support 结构之后依然必须成立：
     * 它保护的是「存储往返不制造调用方从未提交过的差异」这个性质本身。
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
        assertEquals(1, rowsOfCategory("userNeed").size());
    }

    /** 存储不保留负零，读回的是正零。 */
    @Test
    void restoresNegativeZeroConfidenceAsPositiveZero() {
        repository.save(directionWithConfidence(-0.0));

        Double restored = repository.findById(DIRECTION_ID).orElseThrow()
                .evidenceSupport().userNeed().get(0).evidence().confidence();

        assertEquals(0, Double.compare(0.0, restored), "读回的 confidence 应是正零");
    }

    /** 内容完全没变的重复保存同样是幂等的，不论 confidence 是否为零。 */
    @Test
    void keepsRepeatedSaveOfNegativeZeroConfidenceIdempotent() {
        ProductDirection direction = directionWithConfidence(-0.0);
        repository.save(direction);
        repository.save(direction);

        assertEquals(1, directionMapper.selectCount(null).intValue());
        assertEquals(1, rowsOfCategory("userNeed").size());
    }

    // ---------------------------------------------------------------------
    // 存储一致性
    // ---------------------------------------------------------------------

    /** schema 层：来源类型与专属列不一致的行根本写不进去。 */
    @Test
    void schemaRejectsContradictoryOriginRows() {
        assertThrows(DataAccessException.class,
                () -> insertPreparedBasisRow("userProfile", USER_PROFILE_ID.value(), 3,
                        REPOSITORY_PROFILE_ID.value()),
                "userProfile 类型不得同时带 repositoryProfile 的列");

        assertThrows(DataAccessException.class,
                () -> insertPreparedBasisRow("repositoryProfile", USER_PROFILE_ID.value(), 3,
                        REPOSITORY_PROFILE_ID.value()),
                "repositoryProfile 类型不得带 User Profile 的列");

        assertThrows(DataAccessException.class,
                () -> insertPreparedBasisRow("userProfile", USER_PROFILE_ID.value(), null, null),
                "userProfile 类型必须给出 revision");
    }

    /**
     * Adapter 层：即使存储没有约束过，矛盾来源也必须被拒绝而不是只读其中一种。
     *
     * <p>表级 CHECK 已经挡住这种写入，因此这里先造一张不带 CHECK 的同名表，
     * 模拟「数据由更早的迁移、手工修改或绕过的写入产生」——读路径仍然必须失败。
     */
    @Test
    void rejectsRowsCarryingBothOriginKinds() {
        repository.save(candidateDirection());
        recreateEvidenceSupportTableWithoutCheck();
        insertPreparedBasisRow("userProfile", USER_PROFILE_ID.value(), 3,
                REPOSITORY_PROFILE_ID.value());

        assertThrows(IllegalStateException.class,
                () -> repository.findById(DIRECTION_ID));
    }

    /** 同上，但方向反：repositoryProfile 类型带着 User Profile 的列。 */
    @Test
    void rejectsRepositoryOriginCarryingUserProfileColumns() {
        repository.save(candidateDirection());
        recreateEvidenceSupportTableWithoutCheck();
        insertPreparedBasisRow("repositoryProfile", USER_PROFILE_ID.value(), 3,
                REPOSITORY_PROFILE_ID.value());

        assertThrows(IllegalStateException.class,
                () -> repository.findById(DIRECTION_ID));
    }

    /**
     * 无法识别的判断分组必须报错，而不是被默默丢掉。
     *
     * <p>只读取三个已知分组、把其余行忽略，会让方向读出来「看起来是好的」，
     * 只是少了一条它本来持有的依据。
     */
    @Test
    void rejectsUnknownEvidenceCategory() {
        repository.save(candidateDirection());

        jdbcTemplate.update(
                "UPDATE product_direction_evidence_support SET category = ? WHERE category = ?",
                "someOtherJudgement", "userNeed");

        assertThrows(IllegalStateException.class,
                () -> repository.findById(DIRECTION_ID));
    }

    @Test
    void rejectsSavingDifferentRecommendationForTheSameDirection() {
        repository.save(candidateDirection());
        int contentRowsBefore = contentRowCount();

        ProductDirection rewritten = ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                "换成另一个方向", "另一个问题", "另一个目标产品", "另一个匹配点",
                List.of(ASSET_ID), "另一个差异化", "另一个技术价值", "另一个复杂度",
                List.of(), support());

        assertThrows(ProductDirectionContentConflictException.class,
                () -> repository.save(rewritten));

        ProductDirection stored = repository.findById(DIRECTION_ID).orElseThrow();
        assertEquals(TITLE, stored.title(), "已保存的推荐内容不得被改写");
        assertEquals(ProductDirectionStatus.CANDIDATE, stored.status());
        assertEquals(1, directionMapper.selectCount(null).intValue());
        assertEquals(contentRowsBefore, contentRowCount(), "失败后不得留下半写入的内容行");
    }

    /**
     * 「关键判断 → 依据」这个结构本身也是受保护内容。
     *
     * <p>把一条依据从 userNeed 移到 reusableCapability，依据一个字没变，
     * 但这条方向对「凭什么这么说」的回答变了，因此同样是内容冲突。
     */
    @Test
    void rejectsSavingWhenABasisMovesToAnotherJudgement() {
        repository.save(candidateDirection());

        ProductDirection moved = ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                TITLE, "现有记账工具缺少可导出的报表", "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配", List.of(ASSET_ID),
                "相比现有工具增加了自定义报表", "可复用现有报表模块的渲染能力",
                "中等：主要在导出与模板部分",
                List.of("模板格式复杂度可能超预期"),
                new DirectionEvidenceSupport(
                        List.of(), List.of(USER_BASIS, REPOSITORY_BASIS),
                        List.of(REPOSITORY_BASIS, USER_BASIS)));

        assertThrows(ProductDirectionContentConflictException.class,
                () -> repository.save(moved));

        assertEquals(List.of(USER_BASIS),
                repository.findById(DIRECTION_ID).orElseThrow()
                        .evidenceSupport().userNeed(),
                "已保存的判断划分不得被改写");
    }

    /** 依据的出处变了也是内容变化：同一条依据换了一份分析来源就不再是同一条。 */
    @Test
    void rejectsSavingWhenABasisOriginChanges() {
        repository.save(candidateDirection());

        ProductDirection otherOrigin = ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                TITLE, "现有记账工具缺少可导出的报表", "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配", List.of(ASSET_ID),
                "相比现有工具增加了自定义报表", "可复用现有报表模块的渲染能力",
                "中等：主要在导出与模板部分",
                List.of("模板格式复杂度可能超预期"),
                new DirectionEvidenceSupport(
                        List.of(new EvidenceBasis(USER_EVIDENCE,
                                new UserProfileEvidenceOrigin(USER_PROFILE_ID, 4))),
                        List.of(USER_BASIS, REPOSITORY_BASIS),
                        List.of(REPOSITORY_BASIS)));

        assertThrows(ProductDirectionContentConflictException.class,
                () -> repository.save(otherOrigin));
    }

    @Test
    void rejectsSavingDifferentAnalysisBasisForTheSameDirection() {
        repository.save(candidateDirection());

        ProductDirection otherUserProfileRevision = ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION + 1,
                List.of(REPOSITORY_PROFILE_ID),
                TITLE, "现有记账工具缺少可导出的报表", "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配", List.of(ASSET_ID),
                "相比现有工具增加了自定义报表", "可复用现有报表模块的渲染能力",
                "中等：主要在导出与模板部分",
                List.of("模板格式复杂度可能超预期"), support());

        assertThrows(ProductDirectionContentConflictException.class,
                () -> repository.save(otherUserProfileRevision));

        assertEquals(USER_PROFILE_REVISION,
                repository.findById(DIRECTION_ID).orElseThrow().userProfileRevision(),
                "历史记录的用户侧追溯点不得被改写（INV-D02）");
    }

    // ---------------------------------------------------------------------
    // 整批写入
    // ---------------------------------------------------------------------

    /** 一次发现产生的多条候选方向一起写入。 */
    @Test
    void writesTheWholeBatch() {
        ProductDirection first = candidateDirection(new ProductDirectionId("direction-1"));
        ProductDirection second = candidateDirection(new ProductDirectionId("direction-2"));

        repository.saveAll(List.of(first, second));

        assertTrue(repository.findById(first.id()).isPresent());
        assertTrue(repository.findById(second.id()).isPresent());
        assertEquals(2, directionMapper.selectCount(null).intValue());
    }

    @Test
    void returnsEmptyWhenDirectionDoesNotExist() {
        assertTrue(repository.findById(new ProductDirectionId("unknown-direction")).isEmpty());
    }

    /** 一个真实存在的方向可能确实没有已识别的主要风险，此时该字段没有任何行。 */
    @Test
    void restoresDirectionWithoutRisks() {
        ProductDirection direction = ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                TITLE, "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), support());

        repository.save(direction);

        assertEquals(List.of(), repository.findById(DIRECTION_ID).orElseThrow().risks());
    }

    // ---------------------------------------------------------------------
    // 当前 SELECTED 方向与 INV-D09 的存储层守卫
    // ---------------------------------------------------------------------

    @Test
    void findsNoCurrentSelectedDirectionWhenNothingIsSelected() {
        repository.save(candidateDirection());

        assertTrue(repository.findCurrentSelected().isEmpty(),
                "只有候选方向时，当前 SELECTED 是空的");
    }

    @Test
    void restoresTheExactlyOneCurrentSelectedDirection() {
        ProductDirection selected = candidateDirection();
        selected.select();
        repository.save(selected);

        ProductDirection current = repository.findCurrentSelected().orElseThrow();

        assertEquals(DIRECTION_ID, current.id());
        assertEquals(ProductDirectionStatus.SELECTED, current.status());
        assertEquals(TITLE, current.title());
        assertEquals(USER_PROFILE_REVISION, current.userProfileRevision());
        assertEquals(List.of(REPOSITORY_PROFILE_ID), current.repositoryProfileIds());
        assertEquals(List.of(ASSET_ID), current.candidateAssetIds());
        assertEquals(List.of(USER_BASIS, REPOSITORY_BASIS),
                current.evidenceSupport().userFit());
    }

    /**
     * 存储里已经有两条 SELECTED 时，查询失败，而不是挑一条返回。
     *
     * <p>这种状态在正常写入路径下不存在——V7 的部分唯一索引只允许一行。它可能来自更早的
     * 数据、被绕过的写入或迁移中的中间态，因此这里先把那条索引去掉，再直接写入两行，
     * 复现「存储与领域模型不一致」的形状。
     */
    @Test
    void refusesToPickOneWhenSeveralSelectedRowsExist() {
        jdbcTemplate.execute("DROP INDEX ux_product_direction_current_selected");
        insertSelectedRow("direction-a");
        insertSelectedRow("direction-b");

        assertThrows(IllegalStateException.class, () -> repository.findCurrentSelected());
    }

    /**
     * 存储层不允许同时存在两个当前方向（INV-D09）。
     *
     * <p>这是「先查再写」挡不住并发时的最终守卫：两个选择请求可能都读到「当前没有
     * SELECTED 方向」，后提交的那个在这里被拒绝。
     *
     * <p>同时验证 Adapter 把存储层的唯一性冲突翻译成了 Port 能表达的语义——
     * 抛出的不是 Spring 的数据访问异常，也不是 SQLite 的错误文本。
     */
    @Test
    void refusesASecondCurrentSelectedDirection() {
        ProductDirection first = candidateDirection();
        first.select();
        repository.save(first);

        ProductDirection second = candidateDirection(new ProductDirectionId("direction-2"));
        second.select();

        assertThrows(ProductDirectionSelectionConflictException.class,
                () -> repository.save(second));

        assertEquals(1, countSelectedRows(), "库里仍然只有一个当前方向");
        assertEquals(ProductDirectionStatus.SELECTED,
                repository.findById(DIRECTION_ID).orElseThrow().status(),
                "已经存在的那个方向不受影响");
        assertTrue(repository.findById(new ProductDirectionId("direction-2")).isEmpty(),
                "被拒绝的方向没有留下身份行");
    }

    /**
     * 约束只作用于「当前」，不作用于历史：CANDIDATE / REJECTED / SUPERSEDED 可以有任意多条。
     */
    @Test
    void allowsAnyNumberOfHistoricalDirections() {
        ProductDirection candidate = candidateDirection(new ProductDirectionId("direction-1"));
        ProductDirection rejected = candidateDirection(new ProductDirectionId("direction-2"));
        rejected.reject();
        ProductDirection superseded = candidateDirection(new ProductDirectionId("direction-3"));
        superseded.select();
        superseded.supersede();

        repository.saveAll(List.of(candidate, rejected, superseded));

        assertEquals(3, directionMapper.selectCount(null).intValue());
        assertEquals(0, countSelectedRows());
        assertTrue(repository.findById(new ProductDirectionId("direction-2")).isPresent(),
                "被拒绝的方向仍然保留");
        assertEquals(ProductDirectionStatus.SUPERSEDED,
                repository.findById(new ProductDirectionId("direction-3")).orElseThrow().status());
    }

    private long countSelectedRows() {
        return directionMapper.selectCount(
                new LambdaQueryWrapper<ProductDirectionDO>()
                        .eq(ProductDirectionDO::getStatus,
                                ProductDirectionStatus.SELECTED.name()));
    }

    /** 直接写入一行 SELECTED 方向，绕过 Adapter：用于构造存储层才会出现的形状。 */
    private void insertSelectedRow(String directionId) {
        jdbcTemplate.update("""
                        INSERT INTO product_direction
                            (id, user_profile_id, user_profile_revision, title, problem,
                             target_product, user_fit, differentiation, technical_value,
                             estimated_complexity, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SELECTED')
                        """,
                directionId, USER_PROFILE_ID.value(), USER_PROFILE_REVISION,
                TITLE, "现有记账工具缺少可导出的报表", "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配", "相比现有工具增加了自定义报表",
                "可复用现有报表模块的渲染能力", "中等：主要在导出与模板部分");
    }

    @Test
    void writesExactlyOneDirectionWithItsContent() {
        repository.save(candidateDirection());

        assertEquals(1, directionMapper.selectCount(null).intValue());
        assertEquals(1, repositoryProfileRows().size());
        assertEquals(1, candidateAssetRows().size());
        assertEquals(1, riskRows().size());
        // userNeed 1 + userFit 2 + reusableCapability 1
        assertEquals(4, evidenceSupportRows().size());
    }

    // ---------------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------------

    /**
     * 一份覆盖三组判断、两种来源，并且同一条依据支撑多个判断的 support。
     *
     * <pre>
     * userNeed           [user]
     * userFit            [user, repository]
     * reusableCapability [repository]
     * </pre>
     */
    private static DirectionEvidenceSupport support() {
        return new DirectionEvidenceSupport(
                List.of(USER_BASIS),
                List.of(USER_BASIS, REPOSITORY_BASIS),
                List.of(REPOSITORY_BASIS));
    }

    /** 一条 userNeed 依据的 confidence 由调用方给定的方向，其余内容与 {@link #candidateDirection()} 相同。 */
    private static ProductDirection directionWithConfidence(Double confidence) {
        Evidence evidence = new Evidence(
                EvidenceSourceType.USER_INPUT, "用户输入：但导出报表很麻烦",
                "用户对报表导出的不满", confidence, true);

        return ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION,
                List.of(REPOSITORY_PROFILE_ID),
                TITLE, "现有记账工具缺少可导出的报表", "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配", List.of(ASSET_ID),
                "相比现有工具增加了自定义报表", "可复用现有报表模块的渲染能力",
                "中等：主要在导出与模板部分",
                List.of("模板格式复杂度可能超预期"),
                new DirectionEvidenceSupport(
                        List.of(new EvidenceBasis(evidence,
                                new UserProfileEvidenceOrigin(
                                        USER_PROFILE_ID, USER_PROFILE_REVISION))),
                        List.of(),
                        List.of()));
    }

    /** 直接写入一行依据记录，用于构造存储层才会出现的、不符合约束的数据。 */
    private void insertPreparedBasisRow(String originKind,
                                        String userProfileId,
                                        Integer userProfileRevision,
                                        String repositoryProfileId) {
        jdbcTemplate.update("""
                        INSERT INTO product_direction_evidence_support
                            (direction_id, category, position, source_type, source_ref, claim,
                             confidence, confirmed, origin_kind,
                             origin_user_profile_id, origin_user_profile_revision,
                             origin_repository_profile_id)
                        VALUES (?, 'userNeed', 0, 'USER_INPUT', 'user-answer-1', '一条依据',
                                NULL, 0, ?, ?, ?, ?)
                        """,
                DIRECTION_ID.value(), originKind, userProfileId, userProfileRevision,
                repositoryProfileId);
    }

    /**
     * 造一张同名但不带 origin 一致性 CHECK 的表。
     *
     * <p>用于验证 Adapter 的读路径自身也会拒绝矛盾来源——不能只依赖存储曾经约束过它。
     * DDL 与后续写入都在测试事务内，方法结束即回滚。
     */
    private void recreateEvidenceSupportTableWithoutCheck() {
        jdbcTemplate.execute("DROP TABLE product_direction_evidence_support");
        jdbcTemplate.execute("""
                CREATE TABLE product_direction_evidence_support (
                    direction_id                  TEXT    NOT NULL,
                    category                      TEXT    NOT NULL,
                    position                      INTEGER NOT NULL,
                    source_type                   TEXT    NOT NULL,
                    source_ref                    TEXT    NOT NULL,
                    claim                         TEXT    NOT NULL,
                    confidence                    REAL,
                    confirmed                     INTEGER NOT NULL,
                    origin_kind                   TEXT    NOT NULL,
                    origin_user_profile_id        TEXT,
                    origin_user_profile_revision  INTEGER,
                    origin_repository_profile_id  TEXT,
                    PRIMARY KEY (direction_id, category, position)
                )
                """);
    }

    private static ProductDirection candidateDirection(ProductDirectionId id) {
        return ProductDirection.create(
                id,
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
                support());
    }

    private static ProductDirection candidateDirection() {
        return candidateDirection(DIRECTION_ID);
    }


    /** 四个内容子表在当前方向下的总行数，用于验证更新路径不会改动内容行。 */
    private int contentRowCount() {
        return repositoryProfileRows().size()
                + candidateAssetRows().size()
                + riskRows().size()
                + evidenceSupportRows().size();
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

    private List<ProductDirectionEvidenceSupportDO> evidenceSupportRows() {
        return evidenceSupportMapper.selectList(
                new LambdaQueryWrapper<ProductDirectionEvidenceSupportDO>()
                        .eq(ProductDirectionEvidenceSupportDO::getDirectionId,
                                DIRECTION_ID.value()));
    }

    private List<ProductDirectionEvidenceSupportDO> rowsOfCategory(String category) {
        return evidenceSupportRows().stream()
                .filter(row -> row.getCategory().equals(category))
                .toList();
    }
}
