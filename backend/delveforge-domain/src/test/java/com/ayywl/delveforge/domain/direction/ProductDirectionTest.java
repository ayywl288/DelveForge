package com.ayywl.delveforge.domain.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Product Direction Aggregate 的领域行为（DOMAIN_MODEL.md §3.5、§6.2、§10.5、§11.6）。
 *
 * <p>覆盖四部分：创建时的不变量、§6.2 定义的三条合法转换、除此之外的非法转换，
 * 以及按已保存的领域事实重建（{@code reconstitute}）。
 */
class ProductDirectionTest {

    private static final ProductDirectionId DIRECTION_ID = new ProductDirectionId("direction-1");

    private static final UserProfileId USER_PROFILE_ID = new UserProfileId("user-profile-1");

    private static final int USER_PROFILE_REVISION = 3;

    private static final RepositoryProfileId REPOSITORY_PROFILE_ID =
            new RepositoryProfileId("repository-profile-1");

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final Evidence EVIDENCE = new Evidence(
            EvidenceSourceType.USER_INPUT, "user-profile-1#interests", "用户长期关注记账工具",
            0.8, true);

    // ---------------------------------------------------------------------
    // 创建
    // ---------------------------------------------------------------------

    @Test
    void createsCandidateDirectionWithItsAnalysisBasisAndRecommendationContent() {
        ProductDirection direction = createDirection();

        assertEquals(DIRECTION_ID, direction.id());
        assertEquals(USER_PROFILE_ID, direction.userProfileId());
        assertEquals(USER_PROFILE_REVISION, direction.userProfileRevision());
        assertEquals(List.of(REPOSITORY_PROFILE_ID), direction.repositoryProfileIds());
        assertEquals("个人记账 + 报表导出", direction.title());
        assertEquals("现有记账工具缺少可导出的报表", direction.problem());
        assertEquals("单用户桌面记账工具 + 报表导出", direction.targetProduct());
        assertEquals("用户已经在用记账工具，且技术栈匹配", direction.userFit());
        assertEquals(List.of(ASSET_ID), direction.candidateAssetIds());
        assertEquals("相比现有工具增加了自定义报表", direction.differentiation());
        assertEquals("可复用现有报表模块的渲染能力", direction.technicalValue());
        assertEquals("中等：主要在导出与模板部分", direction.estimatedComplexity());
        assertEquals(List.of("模板格式复杂度可能超预期"), direction.risks());
        assertEquals(List.of(EVIDENCE), direction.evidence());
    }

    /**
     * §8.4 与 INV-D07：系统生成的方向一律是候选，不能在生成时就替用户选中。
     */
    @Test
    void startsAsCandidate() {
        assertEquals(ProductDirectionStatus.CANDIDATE, createDirection().status());
    }

    @Test
    void rejectsMissingIdentity() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                null, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)));
    }

    /**
     * INV-D01：方向必须能够追溯到确定的 UserProfileId + revision。
     */
    @Test
    void rejectsMissingUserProfile() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, null, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)));
    }

    /**
     * User Profile 的 revision 自 1 开始（§10.3），0 或负数不对应任何版本，
     * 因此不构成 INV-D01 要求的「确定的 revision」。
     */
    @Test
    void rejectsUserProfileRevisionBelowInitial() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, 0, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)));
    }

    /**
     * INV-D05：方向必须能够追溯到至少一个明确的 Repository Profile。
     */
    @Test
    void rejectsDirectionWithoutRepositoryProfile() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)));
    }

    @Test
    void rejectsNullRepositoryProfileList() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, null,
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)));
    }

    @Test
    void rejectsRepositoryProfileListContainingNull() {
        List<RepositoryProfileId> withNull = new ArrayList<>();
        withNull.add(REPOSITORY_PROFILE_ID);
        withNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, withNull,
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)));
    }

    /**
     * INV-D10：一个没有可利用 Software Asset 的方向不是可实施的方向。
     *
     * <p>本 Aggregate 只校验「至少一个」；这些资产是否真的来自该方向引用的
     * Repository Profile，属于后续 ProductDirectionDiscoveryService（Domain Service，
     * DOMAIN_MODEL.md §12.4）的跨 Aggregate 校验，不在本 Task 范围内。
     */
    @Test
    void rejectsDirectionWithoutCandidateAsset() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)));
    }

    @Test
    void rejectsNullCandidateAssetList() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", null,
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)));
    }

    @Test
    void rejectsCandidateAssetListContainingNull() {
        List<SoftwareAssetId> withNull = new ArrayList<>();
        withNull.add(ASSET_ID);
        withNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", withNull,
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)));
    }

    /**
     * INV-D06：一个没有任何 Evidence 的方向无法说明它的关键判断从哪里来。
     */
    @Test
    void rejectsDirectionWithoutEvidence() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of()));
    }

    @Test
    void rejectsNullEvidence() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), null));
    }

    /**
     * 每个方向都必须说明问题、目标产品、匹配关系、差异化、技术价值与复杂度
     * （§3.5、§8.4）：缺少其中任何一项，用户都无法判断这个方向值不值得做。
     */
    @Test
    void rejectsBlankRecommendationContent() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "  ", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)),
                "title 不得为空");

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", null, "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)),
                "problem 不得为 null");

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)),
                "targetProduct 不得为空");

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", " ", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE)),
                "userFit 不得为空");

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                null, "技术价值", "复杂度", List.of(), List.of(EVIDENCE)),
                "differentiation 不得为 null");

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", " ", "复杂度", List.of(), List.of(EVIDENCE)),
                "technicalValue 不得为空");

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", " ", List.of(), List.of(EVIDENCE)),
                "estimatedComplexity 不得为空");
    }

    @Test
    void rejectsRiskSectionContainingBlankEntry() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(" "), List.of(EVIDENCE)));
    }

    /**
     * 一个真实存在的方向可能确实没有已经识别出的主要风险，因此 risks 允许为空。
     */
    @Test
    void allowsEmptyRisks() {
        ProductDirection direction = ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE));

        assertEquals(List.of(), direction.risks());
    }

    /**
     * 创建之后，方向的内容与依据只能通过 Aggregate 自己的操作变化——而当前
     * 除了状态之外没有任何会改变它们的操作。因此内容在创建时必须与调用方
     * 传入的列表脱钩。
     */
    @Test
    void doesNotRetainCallerOwnedLists() {
        List<RepositoryProfileId> repositoryProfileIds = new ArrayList<>();
        repositoryProfileIds.add(REPOSITORY_PROFILE_ID);
        List<SoftwareAssetId> candidateAssetIds = new ArrayList<>();
        candidateAssetIds.add(ASSET_ID);
        List<String> risks = new ArrayList<>();
        risks.add("模板格式复杂度可能超预期");
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(EVIDENCE);

        ProductDirection direction = ProductDirection.create(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, repositoryProfileIds,
                "标题", "问题", "目标产品", "匹配点", candidateAssetIds,
                "差异化", "技术价值", "复杂度", risks, evidence);

        repositoryProfileIds.add(new RepositoryProfileId("other"));
        candidateAssetIds.add(new SoftwareAssetId("other"));
        risks.add("调用方后来追加的风险");
        evidence.add(new Evidence(EvidenceSourceType.REPOSITORY, "pom.xml", "后来追加", null, false));

        assertEquals(List.of(REPOSITORY_PROFILE_ID), direction.repositoryProfileIds());
        assertEquals(List.of(ASSET_ID), direction.candidateAssetIds());
        assertEquals(List.of("模板格式复杂度可能超预期"), direction.risks());
        assertEquals(List.of(EVIDENCE), direction.evidence());
    }

    @Test
    void exposesContentAsUnmodifiableLists() {
        ProductDirection direction = createDirection();

        assertThrows(UnsupportedOperationException.class,
                () -> direction.repositoryProfileIds().add(new RepositoryProfileId("other")));
        assertThrows(UnsupportedOperationException.class,
                () -> direction.candidateAssetIds().add(new SoftwareAssetId("other")));
        assertThrows(UnsupportedOperationException.class, () -> direction.risks().add("追加"));
        assertThrows(UnsupportedOperationException.class, () -> direction.evidence().add(EVIDENCE));
    }

    // ---------------------------------------------------------------------
    // 合法状态转换（§6.2）
    // ---------------------------------------------------------------------

    @Test
    void selectsFromCandidate() {
        ProductDirection direction = createDirection();

        direction.select();

        assertEquals(ProductDirectionStatus.SELECTED, direction.status());
    }

    @Test
    void rejectsFromCandidate() {
        ProductDirection direction = createDirection();

        direction.reject();

        assertEquals(ProductDirectionStatus.REJECTED, direction.status());
    }

    /**
     * SUPERSEDED 的语义是「曾经被选择，之后用户切换到其他方向」（§6.2），
     * 因此它的唯一合法起点是 SELECTED。
     */
    @Test
    void supersedesFromSelected() {
        ProductDirection direction = createDirection();
        direction.select();

        direction.supersede();

        assertEquals(ProductDirectionStatus.SUPERSEDED, direction.status());
    }

    /**
     * §10.5：方向进入 SELECTED 或 SUPERSEDED 时，不得因此丢失最初生成该方向时的
     * 分析依据——历史方向仍然要能回答它当初是凭什么被推荐的。
     */
    @Test
    void keepsAnalysisBasisAndRecommendationContentAcrossLifecycle() {
        ProductDirection direction = createDirection();

        direction.select();
        direction.supersede();

        assertEquals(ProductDirectionStatus.SUPERSEDED, direction.status());
        assertEquals(USER_PROFILE_ID, direction.userProfileId());
        assertEquals(USER_PROFILE_REVISION, direction.userProfileRevision());
        assertEquals(List.of(REPOSITORY_PROFILE_ID), direction.repositoryProfileIds());
        assertEquals(List.of(ASSET_ID), direction.candidateAssetIds());
        assertEquals(List.of(EVIDENCE), direction.evidence());
        assertEquals("个人记账 + 报表导出", direction.title());
        assertEquals("现有记账工具缺少可导出的报表", direction.problem());
        assertEquals("单用户桌面记账工具 + 报表导出", direction.targetProduct());
        assertEquals("用户已经在用记账工具，且技术栈匹配", direction.userFit());
        assertEquals("相比现有工具增加了自定义报表", direction.differentiation());
        assertEquals("可复用现有报表模块的渲染能力", direction.technicalValue());
        assertEquals("中等：主要在导出与模板部分", direction.estimatedComplexity());
        assertEquals(List.of("模板格式复杂度可能超预期"), direction.risks());
    }

    /** 被拒绝的方向同样保留，不因被放弃而抹去历史（§10.5、RULE-DOM-007）。 */
    @Test
    void keepsAnalysisBasisAndRecommendationContentWhenRejected() {
        ProductDirection direction = createDirection();

        direction.reject();

        assertEquals(ProductDirectionStatus.REJECTED, direction.status());
        assertEquals(List.of(REPOSITORY_PROFILE_ID), direction.repositoryProfileIds());
        assertEquals(List.of(EVIDENCE), direction.evidence());
        assertEquals("个人记账 + 报表导出", direction.title());
    }

    // ---------------------------------------------------------------------
    // 非法状态转换（§6.2 未定义的组合）
    // ---------------------------------------------------------------------

    /**
     * 选择只能从 CANDIDATE 出发：§6.2 没有为 REJECTED、SUPERSEDED 或已选中的方向
     * 定义回到 SELECTED 的边。失败不留下部分变化——状态保持原样。
     */
    @Test
    void rejectsSelectFromSelected() {
        ProductDirection direction = createDirection();
        direction.select();

        assertThrows(ProductDirectionStateException.class, direction::select);

        assertEquals(ProductDirectionStatus.SELECTED, direction.status());
    }

    @Test
    void rejectsSelectFromRejected() {
        ProductDirection direction = createDirection();
        direction.reject();

        assertThrows(ProductDirectionStateException.class, direction::select);

        assertEquals(ProductDirectionStatus.REJECTED, direction.status());
    }

    @Test
    void rejectsSelectFromSuperseded() {
        ProductDirection direction = createDirection();
        direction.select();
        direction.supersede();

        assertThrows(ProductDirectionStateException.class, direction::select);

        assertEquals(ProductDirectionStatus.SUPERSEDED, direction.status());
    }

    @Test
    void rejectsRejectFromSelected() {
        ProductDirection direction = createDirection();
        direction.select();

        assertThrows(ProductDirectionStateException.class, direction::reject);

        assertEquals(ProductDirectionStatus.SELECTED, direction.status());
    }

    @Test
    void rejectsRejectFromRejected() {
        ProductDirection direction = createDirection();
        direction.reject();

        assertThrows(ProductDirectionStateException.class, direction::reject);

        assertEquals(ProductDirectionStatus.REJECTED, direction.status());
    }

    @Test
    void rejectsRejectFromSuperseded() {
        ProductDirection direction = createDirection();
        direction.select();
        direction.supersede();

        assertThrows(ProductDirectionStateException.class, direction::reject);

        assertEquals(ProductDirectionStatus.SUPERSEDED, direction.status());
    }

    /**
     * CANDIDATE 从未被选择过，因此不存在「被取代」的语义（§6.2）。
     */
    @Test
    void rejectsSupersedeFromCandidate() {
        ProductDirection direction = createDirection();

        assertThrows(ProductDirectionStateException.class, direction::supersede);

        assertEquals(ProductDirectionStatus.CANDIDATE, direction.status());
    }

    @Test
    void rejectsSupersedeFromRejected() {
        ProductDirection direction = createDirection();
        direction.reject();

        assertThrows(ProductDirectionStateException.class, direction::supersede);

        assertEquals(ProductDirectionStatus.REJECTED, direction.status());
    }

    @Test
    void rejectsSupersedeFromSuperseded() {
        ProductDirection direction = createDirection();
        direction.select();
        direction.supersede();

        assertThrows(ProductDirectionStateException.class, direction::supersede);

        assertEquals(ProductDirectionStatus.SUPERSEDED, direction.status());
    }

    /**
     * 非法转换不得改动任何领域状态，包括内容与依据——失败后的方向必须与
     * 调用前完全一致（AGENTS.md §8.7：失败不能留下部分状态）。
     */
    @Test
    void leavesDirectionUntouchedWhenTransitionIsRejected() {
        ProductDirection direction = createDirection();
        direction.select();
        ProductDirectionId idBefore = direction.id();

        assertThrows(ProductDirectionStateException.class, direction::reject);

        assertEquals(ProductDirectionStatus.SELECTED, direction.status());
        assertEquals(idBefore, direction.id());
        assertEquals(USER_PROFILE_ID, direction.userProfileId());
        assertEquals(USER_PROFILE_REVISION, direction.userProfileRevision());
        assertEquals(List.of(REPOSITORY_PROFILE_ID), direction.repositoryProfileIds());
        assertEquals(List.of(ASSET_ID), direction.candidateAssetIds());
        assertEquals(List.of(EVIDENCE), direction.evidence());
        assertEquals("个人记账 + 报表导出", direction.title());
    }

    /**
     * 状态转换只改变状态本身：它不得替换内容对象，也不得让方向变得「像是另一个方向」。
     */
    @Test
    void keepsSameContentInstancesAcrossTransition() {
        ProductDirection direction = createDirection();
        List<RepositoryProfileId> repositoryProfileIds = direction.repositoryProfileIds();
        List<Evidence> evidence = direction.evidence();

        direction.select();

        assertSame(repositoryProfileIds, direction.repositoryProfileIds());
        assertSame(evidence, direction.evidence());
        assertEquals(ProductDirectionStatus.SELECTED, direction.status());
    }

    // ---------------------------------------------------------------------
    // 按已保存的领域事实重建（reconstitute）
    // ---------------------------------------------------------------------

    /**
     * 四种状态都是已经发生过的领域事实，Persistence 必须能把它们读回来。
     *
     * <p>SELECTED 与 SUPERSEDED 的恢复不违背 INV-D07：那条 Invariant 约束的是
     * 「谁有权把方向变成 SELECTED」，而不是「已经这样发生过的事实能否被读回来」。
     */
    @Test
    void reconstitutesEveryLifecycleStatus() {
        for (ProductDirectionStatus status : ProductDirectionStatus.values()) {
            ProductDirection direction = reconstituteWith(status);

            assertEquals(status, direction.status());
            assertEquals(DIRECTION_ID, direction.id());
            assertEquals(List.of(REPOSITORY_PROFILE_ID), direction.repositoryProfileIds());
            assertEquals(List.of(ASSET_ID), direction.candidateAssetIds());
            assertEquals(List.of(EVIDENCE), direction.evidence());
        }
    }

    @Test
    void reconstitutesFullRecommendationContentAndAnalysisBasis() {
        ProductDirection direction = reconstituteWith(ProductDirectionStatus.SELECTED);

        assertEquals(USER_PROFILE_ID, direction.userProfileId());
        assertEquals(USER_PROFILE_REVISION, direction.userProfileRevision());
        assertEquals(List.of(REPOSITORY_PROFILE_ID), direction.repositoryProfileIds());
        assertEquals("个人记账 + 报表导出", direction.title());
        assertEquals("现有记账工具缺少可导出的报表", direction.problem());
        assertEquals("单用户桌面记账工具 + 报表导出", direction.targetProduct());
        assertEquals("用户已经在用记账工具，且技术栈匹配", direction.userFit());
        assertEquals(List.of(ASSET_ID), direction.candidateAssetIds());
        assertEquals("相比现有工具增加了自定义报表", direction.differentiation());
        assertEquals("可复用现有报表模块的渲染能力", direction.technicalValue());
        assertEquals("中等：主要在导出与模板部分", direction.estimatedComplexity());
        assertEquals(List.of("模板格式复杂度可能超预期"), direction.risks());
        assertEquals(List.of(EVIDENCE), direction.evidence());
    }

    /**
     * 恢复的是状态本身，不是对状态机的豁免：被恢复成 SELECTED 的方向，
     * 再次 select 依旧被拒绝。
     */
    @Test
    void keepsStateMachineRulesAfterRestoration() {
        ProductDirection restored = reconstituteWith(ProductDirectionStatus.SELECTED);

        assertThrows(ProductDirectionStateException.class, restored::select);
        assertThrows(ProductDirectionStateException.class, restored::reject);

        restored.supersede();
        assertEquals(ProductDirectionStatus.SUPERSEDED, restored.status(),
                "恢复后的方向仍可继续走合法转换");
    }

    @Test
    void restoredCandidateCanStillBeSelected() {
        ProductDirection restored = reconstituteWith(ProductDirectionStatus.CANDIDATE);

        restored.select();

        assertEquals(ProductDirectionStatus.SELECTED, restored.status());
    }

    @Test
    void rejectsReconstitutionWithoutStatus() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.reconstitute(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE), null));
    }

    /**
     * 恢复不放宽当前的结构不变量：存储里读出来的内容同样是不可信的输入，
     * 一条不满足 INV-D01 / INV-D05 / INV-D06 / INV-D10 的历史记录无法被重建。
     */
    @Test
    void rejectsReconstitutionThatViolatesStructuralInvariants() {
        assertThrows(IllegalArgumentException.class, () -> ProductDirection.reconstitute(
                null, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE),
                ProductDirectionStatus.CANDIDATE), "缺少 id");

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.reconstitute(
                DIRECTION_ID, USER_PROFILE_ID, 0, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE),
                ProductDirectionStatus.CANDIDATE), "userProfileRevision 小于 1");

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.reconstitute(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE),
                ProductDirectionStatus.CANDIDATE), "没有引用 Repository Profile");

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.reconstitute(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE),
                ProductDirectionStatus.CANDIDATE), "没有标识 Candidate Software Asset");

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.reconstitute(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", " ", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(EVIDENCE),
                ProductDirectionStatus.CANDIDATE), "推荐内容为空");

        assertThrows(IllegalArgumentException.class, () -> ProductDirection.reconstitute(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, List.of(REPOSITORY_PROFILE_ID),
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), List.of(),
                ProductDirectionStatus.CANDIDATE), "没有 Evidence");
    }

    /** 恢复出来的集合与创建时同样不可修改，也不与调用方传入的列表共享状态。 */
    @Test
    void reconstitutedCollectionsAreDefensivelyCopied() {
        List<RepositoryProfileId> repositoryProfileIds = new ArrayList<>();
        repositoryProfileIds.add(REPOSITORY_PROFILE_ID);
        List<SoftwareAssetId> candidateAssetIds = new ArrayList<>();
        candidateAssetIds.add(ASSET_ID);
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(EVIDENCE);

        ProductDirection direction = ProductDirection.reconstitute(
                DIRECTION_ID, USER_PROFILE_ID, USER_PROFILE_REVISION, repositoryProfileIds,
                "标题", "问题", "目标产品", "匹配点", candidateAssetIds,
                "差异化", "技术价值", "复杂度", List.of(), evidence,
                ProductDirectionStatus.SUPERSEDED);

        repositoryProfileIds.add(new RepositoryProfileId("other"));
        candidateAssetIds.add(new SoftwareAssetId("other"));
        evidence.add(new Evidence(EvidenceSourceType.REPOSITORY, "pom.xml", "后来追加", null, false));

        assertEquals(List.of(REPOSITORY_PROFILE_ID), direction.repositoryProfileIds());
        assertEquals(List.of(ASSET_ID), direction.candidateAssetIds());
        assertEquals(List.of(EVIDENCE), direction.evidence());
        assertThrows(UnsupportedOperationException.class,
                () -> direction.repositoryProfileIds().add(new RepositoryProfileId("other")));
        assertThrows(UnsupportedOperationException.class,
                () -> direction.evidence().add(EVIDENCE));
    }

    private static ProductDirection reconstituteWith(ProductDirectionStatus status) {
        return ProductDirection.reconstitute(
                DIRECTION_ID,
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
                List.of(EVIDENCE),
                status);
    }

    private static ProductDirection createDirection() {
        return ProductDirection.create(
                DIRECTION_ID,
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
                List.of(EVIDENCE));
    }
}
