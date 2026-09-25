package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.domain.direction.DirectionEvidenceSupport;
import com.ayywl.delveforge.domain.direction.DirectionProposal;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.UserProfileEvidenceOrigin;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证「AI 侧提议 → 领域侧提议」这一步：临时引用被换回真实依据与它们的来源。
 *
 * <p>本类只依赖 {@link DirectionDiscoveryInputs}，不需要 Spring、数据库或真实 LLM。
 *
 * <p>这里固定的是「恢复已经存在的事实」。至于依据在业务上是否足以支撑那条判断、
 * 方向是否真的适合用户，属于 {@code ProductDirectionDiscoveryService}，本层不判。
 */
class DirectionProposalResolverTest {

    private static final DirectionDiscoveryInputs INPUTS = DirectionDiscoveryFixtures.inputs();

    private final DirectionProposalResolver resolver = new DirectionProposalResolver();

    @Test
    void resolvesEverySlotToRealEvidenceWithItsOrigin() {
        DirectionProposal proposal = resolver.resolve(aiProposal(), INPUTS);

        DirectionEvidenceSupport support = proposal.evidenceSupport();

        assertEquals(
                List.of(DirectionDiscoveryFixtures.USER_BASIS_2), support.userNeed());
        assertEquals(
                List.of(DirectionDiscoveryFixtures.USER_BASIS_1), support.userFit());
        assertEquals(
                List.of(DirectionDiscoveryFixtures.ACCOUNTING_BASIS_2),
                support.reusableCapability());
    }

    /** 用户侧依据的来源必须是本次所依据的那一版 Profile。 */
    @Test
    void fixesUserEvidenceOriginToTheSnapshotRevision() {
        EvidenceBasis basis = resolver.resolve(aiProposal(), INPUTS)
                .evidenceSupport().userFit().get(0);

        assertEquals(
                new UserProfileEvidenceOrigin(DirectionDiscoveryFixtures.USER_PROFILE_ID, 3),
                basis.origin());
    }

    /** 资产侧依据的来源必须指向它真正出自的那一份 Repository Profile。 */
    @Test
    void fixesRepositoryEvidenceOriginToItsRepositoryProfile() {
        EvidenceBasis basis = resolver.resolve(aiProposal(), INPUTS)
                .evidenceSupport().reusableCapability().get(0);

        assertEquals(
                new RepositoryProfileEvidenceOrigin(
                        DirectionDiscoveryFixtures.ACCOUNTING_PROFILE_ID),
                basis.origin());
    }

    /**
     * 领域侧提议里不存在 AI 通信协议的东西：它只有真实依据，没有 {@code U-E1} 这类引用。
     *
     * <p>域类型里根本没有承载引用的字段，因此这条断言同时也是编译期的保证。
     */
    @Test
    void producesADomainProposalWithoutAnyTemporaryReference() {
        DirectionProposal proposal = resolver.resolve(aiProposal(), INPUTS);

        assertTrue(proposal.evidenceSupport().allBases().stream()
                        .allMatch(basis -> basis.evidence() != null && basis.origin() != null),
                "每条依据都带内容与来源");
        assertEquals(
                List.of(DirectionDiscoveryFixtures.USER_BASIS_2,
                        DirectionDiscoveryFixtures.USER_BASIS_1,
                        DirectionDiscoveryFixtures.ACCOUNTING_BASIS_2),
                proposal.evidenceSupport().allBases(),
                "扁平视图按槽位顺序给出真实依据，且每条都带来源");
    }

    /** 内容层面的其余字段原样带过去。 */
    @Test
    void carriesRecommendationContentUnchanged() {
        DirectionProposal proposal = resolver.resolve(aiProposal(), INPUTS);

        assertEquals("个人记账 + 报表导出", proposal.title());
        assertEquals("现有记账工具缺少可导出的报表", proposal.problem());
        assertEquals("单用户桌面记账工具 + 报表导出", proposal.targetProduct());
        assertEquals("相比现有工具增加了自定义报表", proposal.differentiation());
        assertEquals("可复用现有报表模块的渲染能力", proposal.technicalValue());
        assertEquals("中等：主要在导出与模板部分", proposal.estimatedComplexity());
        assertEquals(List.of("模板格式复杂度可能超预期"), proposal.risks());
        assertEquals(List.of(DirectionDiscoveryFixtures.ACCOUNTING_ASSET_ID),
                proposal.candidateAssetIds());
    }

    /**
     * 不判断依据是否足够支撑判断。
     *
     * <p>一条只引用单条依据、且三个槽位都指向同一条依据的提议照样解析成功——
     * 语义充分性是 {@code ProductDirectionDiscoveryService} 的判断，不是这里的。
     */
    @Test
    void doesNotJudgeWhetherTheEvidenceIsSufficient() {
        AiDirectionProposal thin = new AiDirectionProposal(
                "标题", "问题", "目标产品", "匹配点",
                List.of(DirectionDiscoveryFixtures.ACCOUNTING_ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(),
                new AiDirectionEvidenceLinkage(
                        List.of(new EvidenceReference("U-E1")),
                        List.of(new EvidenceReference("U-E1")),
                        List.of(new EvidenceReference("U-E1"))));

        DirectionProposal proposal = resolver.resolve(thin, INPUTS);

        assertEquals(
                List.of(DirectionDiscoveryFixtures.USER_BASIS_1),
                proposal.evidenceSupport().userNeed());
        assertEquals(
                List.of(DirectionDiscoveryFixtures.USER_BASIS_1),
                proposal.evidenceSupport().reusableCapability());
    }

    /** 三个槽位都为空也照样解析：那是「这条判断没有可引用的依据」，不是解析错误。 */
    @Test
    void resolvesProposalWithoutAnyEvidenceSlot() {
        AiDirectionProposal empty = new AiDirectionProposal(
                "标题", "问题", "目标产品", "匹配点",
                List.of(DirectionDiscoveryFixtures.ACCOUNTING_ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(),
                new AiDirectionEvidenceLinkage(List.of(), List.of(), List.of()));

        DirectionProposal proposal = resolver.resolve(empty, INPUTS);

        assertEquals(List.of(), proposal.evidenceSupport().allBases());
    }

    /**
     * 提议与输入对不上时拒绝。
     *
     * <p>strict parsing 已经核对过引用是否存在于本次输入，因此走到这里还解析不出来，
     * 说明传给 resolver 的 inputs 根本不是产生这条提议的那一次，属于调用方的错误。
     */
    @Test
    void rejectsProposalWhoseReferenceIsNotInTheGivenInputs() {
        AiDirectionProposal foreign = new AiDirectionProposal(
                "标题", "问题", "目标产品", "匹配点",
                List.of(DirectionDiscoveryFixtures.ACCOUNTING_ASSET_ID),
                "差异化", "技术价值", "复杂度", List.of(),
                new AiDirectionEvidenceLinkage(
                        List.of(new EvidenceReference("U-E9")), List.of(), List.of()));

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(foreign, INPUTS));
    }

    @Test
    void resolvesEveryProposalInOrder() {
        AiDirectionProposal first = aiProposal("第一个");
        AiDirectionProposal second = aiProposal("第二个");

        List<DirectionProposal> resolved = resolver.resolve(List.of(first, second), INPUTS);

        assertEquals(2, resolved.size());
        assertEquals("第一个", resolved.get(0).title());
        assertEquals("第二个", resolved.get(1).title());
    }

    @Test
    void rejectsMissingDependencies() {
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve((AiDirectionProposal) null, INPUTS));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(aiProposal(), null));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve((List<AiDirectionProposal>) null, INPUTS));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(List.of(aiProposal()), null));
    }

    private static AiDirectionProposal aiProposal() {
        return aiProposal("个人记账 + 报表导出");
    }

    private static AiDirectionProposal aiProposal(String title) {
        return new AiDirectionProposal(
                title,
                "现有记账工具缺少可导出的报表",
                "单用户桌面记账工具 + 报表导出",
                "用户已经在用记账工具，且技术栈匹配",
                List.of(DirectionDiscoveryFixtures.ACCOUNTING_ASSET_ID),
                "相比现有工具增加了自定义报表",
                "可复用现有报表模块的渲染能力",
                "中等：主要在导出与模板部分",
                List.of("模板格式复杂度可能超预期"),
                new AiDirectionEvidenceLinkage(
                        List.of(new EvidenceReference("U-E2")),
                        List.of(new EvidenceReference("U-E1")),
                        List.of(new EvidenceReference("R1-E2"))));
    }
}
