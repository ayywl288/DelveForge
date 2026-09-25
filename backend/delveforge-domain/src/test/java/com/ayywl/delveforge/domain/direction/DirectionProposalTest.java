package com.ayywl.delveforge.domain.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.UserProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Domain 侧的 Direction Proposal：结构约束，以及「关键判断 → 真实依据」的对应关系。
 *
 * <p>本类固定的是「给出的值是不是一个真实的值」。一条提议在业务上是否成立——依据是否
 * 足以支撑判断、方向是否真的适合这个用户——不在这一层判定。
 *
 * <p>这里的依据已经不是 AI 侧的临时引用，而是真实的 {@link EvidenceBasis}：带内容、
 * 也带它出自哪一份分析。引用（{@code U-E1}）属于 Application 的 AI 通信协议，
 * 不会出现在 Domain 中。
 */
class DirectionProposalTest {

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final UserProfileId USER_PROFILE_ID = new UserProfileId("user-profile-1");

    private static final RepositoryProfileId REPOSITORY_PROFILE_ID =
            new RepositoryProfileId("repository-profile-1");

    private static final Evidence USER_EVIDENCE = new Evidence(
            EvidenceSourceType.USER_INPUT, "用户输入：但导出报表很麻烦", "用户对报表导出的不满",
            0.8, true);

    private static final Evidence REPOSITORY_EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "src/main/report", "已有报表渲染模块", null, false);

    private static final EvidenceBasis USER_BASIS = new EvidenceBasis(
            USER_EVIDENCE, new UserProfileEvidenceOrigin(USER_PROFILE_ID, 3));

    private static final EvidenceBasis REPOSITORY_BASIS = new EvidenceBasis(
            REPOSITORY_EVIDENCE, new RepositoryProfileEvidenceOrigin(REPOSITORY_PROFILE_ID));

    @Test
    void holdsEveryPartOfAProposal() {
        DirectionProposal proposal = proposal();

        assertEquals("个人记账 + 报表导出", proposal.title());
        assertEquals("现有记账工具缺少可导出的报表", proposal.problem());
        assertEquals("单用户桌面记账工具 + 报表导出", proposal.targetProduct());
        assertEquals("用户已经在用记账工具，且技术栈匹配", proposal.userFit());
        assertEquals(List.of(ASSET_ID), proposal.candidateAssetIds());
        assertEquals("相比现有工具增加了自定义报表", proposal.differentiation());
        assertEquals("可复用现有报表模块的渲染能力", proposal.technicalValue());
        assertEquals("中等：主要在导出与模板部分", proposal.estimatedComplexity());
        assertEquals(List.of("模板格式复杂度可能超预期"), proposal.risks());

        DirectionEvidenceSupport support = proposal.evidenceSupport();
        assertEquals(List.of(USER_BASIS), support.userNeed());
        assertEquals(List.of(USER_BASIS), support.userFit());
        assertEquals(List.of(REPOSITORY_BASIS), support.reusableCapability());
    }

    /**
     * 同一条依据可以同时支撑多个判断：support 是「判断 → 依据」的对应关系，
     * 不是对依据的分类。
     */
    @Test
    void allowsTheSameBasisToSupportMoreThanOneJudgement() {
        DirectionProposal proposal = new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(),
                new DirectionEvidenceSupport(
                        List.of(USER_BASIS), List.of(USER_BASIS, REPOSITORY_BASIS),
                        List.of(USER_BASIS)));

        DirectionEvidenceSupport support = proposal.evidenceSupport();

        assertEquals(List.of(USER_BASIS), support.userNeed());
        assertEquals(List.of(USER_BASIS, REPOSITORY_BASIS), support.userFit());
        assertEquals(List.of(USER_BASIS), support.reusableCapability());
        assertEquals(
                List.of(USER_BASIS, REPOSITORY_BASIS),
                support.allBases(),
                "扁平视图里同一条依据只出现一次，且保持槽位顺序");
    }

    @Test
    void rejectsBlankRecommendationContent() {
        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "  ", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), support()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", null, "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), support()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), support()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", " ", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), support()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                " ", "技术价值", "复杂度", List.of(), support()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", null, "复杂度", List.of(), support()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", " ", List.of(), support()));
    }

    /** 一个没有可利用资产的方向不是可实施的方向（INV-D10）。 */
    @Test
    void rejectsEmptyCandidateAssets() {
        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(),
                "差异化", "技术价值", "复杂度", List.of(), support()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", null,
                "差异化", "技术价值", "复杂度", List.of(), support()));
    }

    @Test
    void rejectsCandidateAssetsContainingNull() {
        List<SoftwareAssetId> withNull = new ArrayList<>();
        withNull.add(ASSET_ID);
        withNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", withNull,
                "差异化", "技术价值", "复杂度", List.of(), support()));
    }

    /** 风险可以为空——一个真实存在的方向可能确实没有已识别的主要风险。 */
    @Test
    void acceptsEmptyRisksButRejectsNullAndBlank() {
        assertEquals(List.of(), new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), support()).risks());

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", null, support()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(" "), support()));
    }

    @Test
    void rejectsMissingEvidenceSupport() {
        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), null));
    }

    // ---------------------------------------------------------------------
    // 三组判断的结构
    // ---------------------------------------------------------------------

    /** 三个槽位都必须存在；空的槽位表示「这条判断没有可追溯的依据」。 */
    @Test
    void requiresEveryEvidenceSlotToBePresent() {
        assertThrows(IllegalArgumentException.class,
                () -> new DirectionEvidenceSupport(null, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DirectionEvidenceSupport(List.of(), null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DirectionEvidenceSupport(List.of(), List.of(), null));
    }

    @Test
    void rejectsEvidenceSlotContainingNull() {
        List<EvidenceBasis> withNull = new ArrayList<>();
        withNull.add(USER_BASIS);
        withNull.add(null);

        assertThrows(IllegalArgumentException.class,
                () -> new DirectionEvidenceSupport(withNull, List.of(), List.of()));
    }

    @Test
    void acceptsSupportWithoutAnyBasis() {
        DirectionEvidenceSupport empty =
                new DirectionEvidenceSupport(List.of(), List.of(), List.of());

        assertEquals(List.of(), empty.userNeed());
        assertEquals(List.of(), empty.userFit());
        assertEquals(List.of(), empty.reusableCapability());
        assertEquals(List.of(), empty.allBases());
        assertEquals(true, empty.isEmpty());
    }

    /** 集合在构造时固化，不与调用方共享状态，也不可再修改。 */
    @Test
    void copiesSupportCollectionsDefensively() {
        List<EvidenceBasis> bases = new ArrayList<>();
        bases.add(USER_BASIS);

        DirectionEvidenceSupport support =
                new DirectionEvidenceSupport(bases, List.of(), List.of());
        bases.add(REPOSITORY_BASIS);

        assertEquals(List.of(USER_BASIS), support.userNeed());
        assertThrows(UnsupportedOperationException.class,
                () -> support.userNeed().add(REPOSITORY_BASIS));
        assertThrows(UnsupportedOperationException.class,
                () -> support.allBases().add(USER_BASIS));
    }

    // ---------------------------------------------------------------------
    // EvidenceBasis 与来源
    // ---------------------------------------------------------------------

    @Test
    void requiresBothEvidenceAndOrigin() {
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceBasis(null, new UserProfileEvidenceOrigin(USER_PROFILE_ID, 3)));
        assertThrows(IllegalArgumentException.class,
                () -> new EvidenceBasis(USER_EVIDENCE, null));
    }

    /** User Profile 侧的来源必须带上确定的 revision（INV-D01、INV-D08）。 */
    @Test
    void rejectsUserProfileOriginWithoutAConcreteRevision() {
        assertThrows(IllegalArgumentException.class,
                () -> new UserProfileEvidenceOrigin(USER_PROFILE_ID, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new UserProfileEvidenceOrigin(null, 3));
    }

    @Test
    void rejectsRepositoryProfileOriginWithoutIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> new RepositoryProfileEvidenceOrigin(null));
    }

    /**
     * 内容相同但出处不同的依据是两个不同的 basis：两个 Repository Profile 完全可能各有
     * 一条 README.md /「使用 Spring Boot」，它们不是同一条依据。
     */
    @Test
    void keepsSameEvidenceFromDifferentOriginsApart() {
        EvidenceBasis fromFirst = new EvidenceBasis(
                REPOSITORY_EVIDENCE, new RepositoryProfileEvidenceOrigin(
                        new RepositoryProfileId("repository-profile-1")));
        EvidenceBasis fromSecond = new EvidenceBasis(
                REPOSITORY_EVIDENCE, new RepositoryProfileEvidenceOrigin(
                        new RepositoryProfileId("repository-profile-2")));

        DirectionEvidenceSupport support =
                new DirectionEvidenceSupport(List.of(fromFirst, fromSecond), List.of(), List.of());

        assertEquals(2, support.userNeed().size());
        assertEquals(List.of(fromFirst, fromSecond), support.allBases(),
                "内容相同，扁平视图里只留一条");
    }

    private static DirectionEvidenceSupport support() {
        return new DirectionEvidenceSupport(
                List.of(USER_BASIS), List.of(USER_BASIS), List.of(REPOSITORY_BASIS));
    }

    private static DirectionProposal proposal() {
        return new DirectionProposal(
                "个人记账 + 报表导出",
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
}
