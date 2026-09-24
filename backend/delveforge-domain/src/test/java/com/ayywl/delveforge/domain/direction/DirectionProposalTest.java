package com.ayywl.delveforge.domain.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Direction Proposal 的结构约束，以及 Evidence linkage 的三个槽位。
 *
 * <p>本类固定的是「给出的值是不是一个真实的值」。一条提议在业务上是否成立——
 * 依据是否足以支撑判断、方向是否真的适合这个用户——不在这一层判定。
 */
class DirectionProposalTest {

    private static final SoftwareAssetId ASSET_ID = new SoftwareAssetId("software-asset-1");

    private static final EvidenceReference USER_NEED_REFERENCE = new EvidenceReference("U-E1");

    private static final EvidenceReference USER_FIT_REFERENCE = new EvidenceReference("U-E2");

    private static final EvidenceReference CAPABILITY_REFERENCE = new EvidenceReference("R1-E1");

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
        assertEquals(
                List.of(USER_NEED_REFERENCE),
                proposal.evidenceLinkage().userNeed());
        assertEquals(List.of(USER_FIT_REFERENCE), proposal.evidenceLinkage().userFit());
        assertEquals(
                List.of(CAPABILITY_REFERENCE),
                proposal.evidenceLinkage().reusableCapability());
    }

    @Test
    void rejectsBlankRecommendationContent() {
        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "  ", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), linkage()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", null, "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), linkage()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), linkage()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", " ", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), linkage()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                " ", "技术价值", "复杂度", List.of(), linkage()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", null, "复杂度", List.of(), linkage()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", " ", List.of(), linkage()));
    }

    /** 一个没有可利用资产的方向不是可实施的方向（INV-D10）。 */
    @Test
    void rejectsEmptyCandidateAssets() {
        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(),
                "差异化", "技术价值", "复杂度", List.of(), linkage()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", null,
                "差异化", "技术价值", "复杂度", List.of(), linkage()));
    }

    @Test
    void rejectsCandidateAssetsContainingNull() {
        List<SoftwareAssetId> withNull = new ArrayList<>();
        withNull.add(ASSET_ID);
        withNull.add(null);

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", withNull,
                "差异化", "技术价值", "复杂度", List.of(), linkage()));
    }

    /** 风险可以为空——一个真实存在的方向可能确实没有已识别的主要风险。 */
    @Test
    void acceptsEmptyRisksButRejectsNullAndBlank() {
        assertEquals(List.of(), new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), linkage()).risks());

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", null, linkage()));

        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(" "), linkage()));
    }

    @Test
    void rejectsMissingEvidenceLinkage() {
        assertThrows(IllegalArgumentException.class, () -> new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", List.of(ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(), null));
    }

    /** 三个槽位都必须存在；空的槽位表示「这条判断没有可引用的依据」。 */
    @Test
    void requiresEveryEvidenceSlotToBePresent() {
        assertThrows(IllegalArgumentException.class,
                () -> new DirectionEvidenceLinkage(null, List.of(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DirectionEvidenceLinkage(List.of(), null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DirectionEvidenceLinkage(List.of(), List.of(), null));

        DirectionEvidenceLinkage empty =
                new DirectionEvidenceLinkage(List.of(), List.of(), List.of());
        assertEquals(List.of(), empty.userNeed());
        assertEquals(List.of(), empty.userFit());
        assertEquals(List.of(), empty.reusableCapability());
    }

    @Test
    void rejectsEvidenceReferenceThatIsBlank() {
        assertThrows(IllegalArgumentException.class, () -> new EvidenceReference(null));
        assertThrows(IllegalArgumentException.class, () -> new EvidenceReference("  "));
    }

    /** 集合在构造时固化，不与调用方共享状态，也不可再修改。 */
    @Test
    void copiesCollectionsDefensively() {
        List<SoftwareAssetId> assetIds = new ArrayList<>();
        assetIds.add(ASSET_ID);
        List<String> risks = new ArrayList<>();
        risks.add("模板格式复杂度可能超预期");
        List<EvidenceReference> references = new ArrayList<>();
        references.add(USER_NEED_REFERENCE);

        DirectionProposal proposal = new DirectionProposal(
                "标题", "问题", "目标产品", "匹配点", assetIds,
                "差异化", "技术价值", "复杂度", risks,
                new DirectionEvidenceLinkage(references, List.of(), List.of()));

        assetIds.add(new SoftwareAssetId("other"));
        risks.add("调用方后来追加的风险");
        references.add(new EvidenceReference("U-E9"));

        assertEquals(List.of(ASSET_ID), proposal.candidateAssetIds());
        assertEquals(List.of("模板格式复杂度可能超预期"), proposal.risks());
        assertEquals(List.of(USER_NEED_REFERENCE), proposal.evidenceLinkage().userNeed());

        assertThrows(UnsupportedOperationException.class,
                () -> proposal.candidateAssetIds().add(ASSET_ID));
        assertThrows(UnsupportedOperationException.class,
                () -> proposal.risks().add("追加"));
        assertThrows(UnsupportedOperationException.class,
                () -> proposal.evidenceLinkage().userNeed().add(USER_FIT_REFERENCE));
    }

    private static DirectionEvidenceLinkage linkage() {
        return new DirectionEvidenceLinkage(
                List.of(USER_NEED_REFERENCE),
                List.of(USER_FIT_REFERENCE),
                List.of(CAPABILITY_REFERENCE));
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
                linkage());
    }
}
