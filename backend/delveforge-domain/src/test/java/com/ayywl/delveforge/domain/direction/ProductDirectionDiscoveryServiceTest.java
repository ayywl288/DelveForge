package com.ayywl.delveforge.domain.direction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceSourceType;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.UserProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Product Direction Discovery 的领域校验（DOMAIN_MODEL.md §12.4、INV-D05 / INV-D06 / INV-D08 / INV-D10）。
 *
 * <p>负向用例一律从 {@link #validProposals()} 这份完整合法的基线出发，只破坏一个条件：
 * 若基线本身不合法，用例会因为别的原因失败，测不到真正想验证的那条规则。
 *
 * <p>本类只依赖 Domain，不需要 Spring、数据库、AI 或任何 Infrastructure。
 */
class ProductDirectionDiscoveryServiceTest {

    private static final UserProfileId USER_PROFILE_ID = new UserProfileId("user-profile-1");

    private static final int USER_PROFILE_REVISION = 3;

    private static final RepositoryProfileId ACCOUNTING_PROFILE_ID =
            new RepositoryProfileId("repository-profile-1");

    private static final RepositoryProfileId CHART_PROFILE_ID =
            new RepositoryProfileId("repository-profile-2");

    private static final SoftwareAssetId ACCOUNTING_ASSET_ID =
            new SoftwareAssetId("software-asset-1");

    private static final SoftwareAssetId CHART_ASSET_ID = new SoftwareAssetId("software-asset-2");

    private static final Evidence USER_EVIDENCE = new Evidence(
            EvidenceSourceType.USER_INPUT, "用户输入：导出报表很麻烦", "用户对报表导出的不满",
            0.8, true);

    private static final Evidence ACCOUNTING_EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "src/main/report", "已有报表渲染模块", null, false);

    private static final Evidence CHART_EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "src/main/chart", "已有图表组件", null, false);

    /** 本次输入里任何 Profile 都没有的依据。 */
    private static final Evidence FOREIGN_EVIDENCE = new Evidence(
            EvidenceSourceType.REPOSITORY, "src/main/other", "另一份分析里的结论", null, false);

    private static final EvidenceBasis USER_BASIS = new EvidenceBasis(
            USER_EVIDENCE, new UserProfileEvidenceOrigin(USER_PROFILE_ID, USER_PROFILE_REVISION));

    private static final EvidenceBasis ACCOUNTING_BASIS = new EvidenceBasis(
            ACCOUNTING_EVIDENCE, new RepositoryProfileEvidenceOrigin(ACCOUNTING_PROFILE_ID));

    private static final EvidenceBasis CHART_BASIS = new EvidenceBasis(
            CHART_EVIDENCE, new RepositoryProfileEvidenceOrigin(CHART_PROFILE_ID));

    private final CountingIdGenerator idGenerator = new CountingIdGenerator();

    private final ProductDirectionDiscoveryService service =
            new ProductDirectionDiscoveryService(idGenerator);

    // ---------------------------------------------------------------------
    // 基线：三条合法提案 → 三条 CANDIDATE
    // ---------------------------------------------------------------------

    @Test
    void producesOneCandidateDirectionPerProposal() {
        List<ProductDirection> directions = service.discover(
                confirmedUserProfile(), repositoryProfiles(), validProposals());

        assertEquals(3, directions.size());
        assertEquals(
                List.of("方向一", "方向二", "方向三"),
                directions.stream().map(ProductDirection::title).toList(),
                "顺序与提案一致");
    }

    /** 每个方向都从可信输入取得身份与分析来源，而不是来自提案。 */
    @Test
    void fillsIdentityAndBasisFromTheDomainInputs() {
        ProductDirection direction = service.discover(
                confirmedUserProfile(), repositoryProfiles(), validProposals()).get(0);

        assertEquals(USER_PROFILE_ID, direction.userProfileId());
        assertEquals(USER_PROFILE_REVISION, direction.userProfileRevision());
        assertEquals(List.of(ACCOUNTING_PROFILE_ID), direction.repositoryProfileIds());
        assertEquals(List.of(ACCOUNTING_ASSET_ID), direction.candidateAssetIds());
        assertEquals(List.of(USER_BASIS), direction.evidenceSupport().userNeed());
        assertTrue(direction.id().value().startsWith("direction-"), "标识由注入的生成器给出");
    }

    @Test
    void alwaysProducesCandidateStatus() {
        for (ProductDirection direction
                : service.discover(confirmedUserProfile(), repositoryProfiles(), validProposals())) {
            assertEquals(ProductDirectionStatus.CANDIDATE, direction.status());
        }
    }

    // ---------------------------------------------------------------------
    // User Profile 前置条件与来源
    // ---------------------------------------------------------------------

    /** §8.4 与 INV-D08：未确认的 Profile 不能作为发现的依据。 */
    @Test
    void rejectsNonConfirmedUserProfile() {
        for (UserProfileStatus status : List.of(
                UserProfileStatus.EXPLORING, UserProfileStatus.REVIEWING)) {
            UserProfile profile = userProfile(status);
            assertThrows(ProductDirectionDiscoveryException.class,
                    () -> service.discover(profile, repositoryProfiles(), validProposals()),
                    "状态 " + status + " 不应被接受");
        }
    }

    @Test
    void rejectsUserProfileOriginWithAnotherIdentity() {
        DirectionEvidenceSupport support = new DirectionEvidenceSupport(
                List.of(basisWithUserOrigin(new UserProfileId("someone-else"),
                        USER_PROFILE_REVISION, USER_EVIDENCE)),
                List.of(USER_BASIS),
                List.of(ACCOUNTING_BASIS));

        assertRejected(support);
    }

    @Test
    void rejectsUserProfileOriginWithAnotherRevision() {
        DirectionEvidenceSupport support = new DirectionEvidenceSupport(
                List.of(basisWithUserOrigin(USER_PROFILE_ID, USER_PROFILE_REVISION + 1, USER_EVIDENCE)),
                List.of(USER_BASIS),
                List.of(ACCOUNTING_BASIS));

        assertRejected(support);
    }

    /** 出处说得对，但那条依据并不在 Profile 里——同样不接受。 */
    @Test
    void rejectsUserEvidenceThatIsNotInTheProfile() {
        DirectionEvidenceSupport support = new DirectionEvidenceSupport(
                List.of(basisWithUserOrigin(USER_PROFILE_ID, USER_PROFILE_REVISION,
                        FOREIGN_EVIDENCE)),
                List.of(USER_BASIS),
                List.of(ACCOUNTING_BASIS));

        assertRejected(support);
    }

    // ---------------------------------------------------------------------
    // Repository Profile 来源
    // ---------------------------------------------------------------------

    @Test
    void rejectsUnknownRepositoryProfileOrigin() {
        DirectionEvidenceSupport support = new DirectionEvidenceSupport(
                List.of(USER_BASIS),
                List.of(USER_BASIS),
                List.of(new EvidenceBasis(ACCOUNTING_EVIDENCE,
                        new RepositoryProfileEvidenceOrigin(
                                new RepositoryProfileId("repository-profile-unknown")))));

        assertRejected(support);
    }

    @Test
    void rejectsRepositoryEvidenceThatIsNotInThatProfile() {
        DirectionEvidenceSupport support = new DirectionEvidenceSupport(
                List.of(USER_BASIS),
                List.of(USER_BASIS),
                List.of(new EvidenceBasis(ACCOUNTING_EVIDENCE,
                        new RepositoryProfileEvidenceOrigin(CHART_PROFILE_ID))));

        assertRejected(support);
    }

    // ---------------------------------------------------------------------
    // INV-D06：三类关键判断都必须有依据
    // ---------------------------------------------------------------------

    @Test
    void rejectsEmptyUserNeed() {
        assertRejected(new DirectionEvidenceSupport(
                List.of(), List.of(USER_BASIS), List.of(ACCOUNTING_BASIS)));
    }

    @Test
    void rejectsEmptyUserFit() {
        assertRejected(new DirectionEvidenceSupport(
                List.of(USER_BASIS), List.of(), List.of(ACCOUNTING_BASIS)));
    }

    /**
     * 资产侧的依据放在 userNeed 里，因此这条方向并非没有实际依据——被拒只能是因为
     * {@code reusableCapability} 本身为空。这样用例才真的证明了这条规则，
     * 而不是被「没有实际依据」那条兜住。
     */
    @Test
    void rejectsEmptyReusableCapability() {
        assertRejected(new DirectionEvidenceSupport(
                List.of(USER_BASIS, ACCOUNTING_BASIS),
                List.of(USER_BASIS),
                List.of()));
    }

    /** 可复用能力是软件资产侧的事实，不能只凭用户侧的依据断言。 */
    @Test
    void rejectsReusableCapabilityWithoutRepositoryBasis() {
        assertRejected(new DirectionEvidenceSupport(
                List.of(USER_BASIS, ACCOUNTING_BASIS),
                List.of(USER_BASIS),
                List.of(USER_BASIS)));
    }

    /**
     * userNeed 非空还不够：它必须至少有一条来自用户画像的依据。
     *
     * <p>全是代码里读出来的事实，回答不了「这个用户要解决什么问题」。
     */
    @Test
    void rejectsUserNeedWithoutUserProfileBasis() {
        // 对照组：只要有一条用户侧依据就成立，混入资产侧依据不影响。
        assertEquals(3, service.discover(
                confirmedUserProfile(), repositoryProfiles(),
                List.of(proposal("一", List.of(ACCOUNTING_ASSET_ID), new DirectionEvidenceSupport(
                                List.of(USER_BASIS, ACCOUNTING_BASIS),
                                List.of(USER_BASIS),
                                List.of(ACCOUNTING_BASIS))),
                        validProposal("二"), validProposal("三"))).size(),
                "至少一条来自用户画像即可，不排斥同时引用资产侧依据");

        // 全部来自资产侧 → 拒绝。
        assertRejected(new DirectionEvidenceSupport(
                List.of(ACCOUNTING_BASIS),
                List.of(USER_BASIS),
                List.of(ACCOUNTING_BASIS)));
    }

    /** userFit 没有来源要求，但本身不能为空——两条依据都来自资产侧也是合法的。 */
    @Test
    void acceptsUserFitWithEitherSideOfBasis() {
        assertEquals(3, service.discover(
                confirmedUserProfile(), repositoryProfiles(),
                List.of(proposal("一", List.of(ACCOUNTING_ASSET_ID), new DirectionEvidenceSupport(
                                List.of(USER_BASIS), List.of(ACCOUNTING_BASIS),
                                List.of(ACCOUNTING_BASIS))),
                        validProposal("二"), validProposal("三"))).size());
    }

    // ---------------------------------------------------------------------
    // 候选资产
    // ---------------------------------------------------------------------

    /**
     * 候选资产必须由这条方向<b>实际依据</b>的 Repository Profile 支撑。
     *
     * <p>本次输入里确实存在一个带图表资产的 Profile，但这条方向一条图表依据都没用，
     * 因此不能声称要基于它演化（INV-D10）。
     */
    @Test
    void rejectsCandidateAssetNotSupportedByTheActualBasis() {
        assertRejected(List.of(CHART_ASSET_ID), validSupport());
    }

    // ---------------------------------------------------------------------
    // actual repository basis
    // ---------------------------------------------------------------------

    /** 本次可见但这条方向没用到的 Profile 不得进入最终 basis。 */
    @Test
    void doesNotIncludeUnusedInputRepositoryProfiles() {
        ProductDirection direction = service.discover(
                confirmedUserProfile(), repositoryProfiles(), validProposals()).get(0);

        assertEquals(List.of(ACCOUNTING_PROFILE_ID), direction.repositoryProfileIds(),
                "图表 Profile 在输入里，但这条方向没有依据它");
    }

    /** 用到了几份就记几份。 */
    @Test
    void includesEveryActuallyUsedRepositoryProfile() {
        DirectionEvidenceSupport bothUsed = new DirectionEvidenceSupport(
                List.of(USER_BASIS),
                List.of(USER_BASIS, ACCOUNTING_BASIS),
                List.of(ACCOUNTING_BASIS, CHART_BASIS));

        ProductDirection direction = service.discover(
                confirmedUserProfile(), repositoryProfiles(),
                List.of(proposal("方向一", List.of(ACCOUNTING_ASSET_ID, CHART_ASSET_ID), bothUsed),
                        validProposal("方向二"), validProposal("方向三"))).get(0);

        assertEquals(List.of(ACCOUNTING_PROFILE_ID, CHART_PROFILE_ID),
                direction.repositoryProfileIds(),
                "按依据出现的顺序记录，且两份都在");
    }

    /**
     * 内容相同的依据由出处区分，而不是由值猜。
     *
     * <p>两份 Profile 各有一条一模一样的依据；一条方向依据其中一份，最终 basis 就必须是
     * 那一份——若实现靠 Evidence 的值去找来源，这里就会挑错或两份都算上。
     */
    @Test
    void distinguishesSameEvidenceFromDifferentProfilesByItsOrigin() {
        Evidence shared = new Evidence(
                EvidenceSourceType.REPOSITORY, "README.md", "使用 Spring Boot", null, false);

        RepositoryProfile first = RepositoryProfile.create(
                ACCOUNTING_PROFILE_ID, ACCOUNTING_ASSET_ID, "abc123", "个人记账工具",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(shared));
        RepositoryProfile second = RepositoryProfile.create(
                CHART_PROFILE_ID, CHART_ASSET_ID, "def456", "图表组件库",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(shared));

        EvidenceBasis fromFirst = new EvidenceBasis(
                shared, new RepositoryProfileEvidenceOrigin(ACCOUNTING_PROFILE_ID));
        EvidenceBasis fromSecond = new EvidenceBasis(
                shared, new RepositoryProfileEvidenceOrigin(CHART_PROFILE_ID));

        List<ProductDirection> directions = service.discover(
                confirmedUserProfile(), List.of(first, second),
                List.of(
                        proposal("依据第一份", List.of(ACCOUNTING_ASSET_ID), supportOn(fromFirst)),
                        proposal("依据第二份", List.of(CHART_ASSET_ID), supportOn(fromSecond)),
                        proposal("仍依据第一份", List.of(ACCOUNTING_ASSET_ID), supportOn(fromFirst))));

        assertEquals(List.of(ACCOUNTING_PROFILE_ID), directions.get(0).repositoryProfileIds());
        assertEquals(List.of(CHART_PROFILE_ID), directions.get(1).repositoryProfileIds(),
                "两条依据的值完全相同，出处不同，因此归属不同");
        assertEquals(List.of(ACCOUNTING_PROFILE_ID), directions.get(2).repositoryProfileIds());
    }

    /** 一条以给定资产侧依据为支撑的合法 support。 */
    private static DirectionEvidenceSupport supportOn(EvidenceBasis repositoryBasis) {
        return new DirectionEvidenceSupport(
                List.of(USER_BASIS),
                List.of(USER_BASIS, repositoryBasis),
                List.of(repositoryBasis));
    }

    // ---------------------------------------------------------------------
    // 批量与输入形状
    // ---------------------------------------------------------------------

    @Test
    void rejectsTooFewProposals() {
        List<DirectionProposal> tooFew = validProposals().subList(0, 2);

        assertThrows(ProductDirectionDiscoveryException.class,
                () -> service.discover(confirmedUserProfile(), repositoryProfiles(), tooFew));
    }

    @Test
    void rejectsTooManyProposals() {
        List<DirectionProposal> tooMany = List.of(
                validProposal("方向一"), validProposal("方向二"), validProposal("方向三"),
                validProposal("方向四"), validProposal("方向五"), validProposal("方向六"));

        assertThrows(ProductDirectionDiscoveryException.class,
                () -> service.discover(confirmedUserProfile(), repositoryProfiles(), tooMany));
    }

    @Test
    void acceptsTheBoundaryProposalCounts() {
        assertEquals(5, service.discover(confirmedUserProfile(), repositoryProfiles(),
                List.of(validProposal("一"), validProposal("二"), validProposal("三"),
                        validProposal("四"), validProposal("五"))).size());
        assertEquals(3, service.discover(confirmedUserProfile(), repositoryProfiles(),
                List.of(validProposal("一"), validProposal("二"), validProposal("三"))).size());
    }

    /** INV-D05 的输入侧：没有任何 Repository Profile 时不可能产出合法方向。 */
    @Test
    void rejectsMissingRepositoryProfiles() {
        assertThrows(IllegalArgumentException.class,
                () -> service.discover(confirmedUserProfile(), List.of(), validProposals()));
        assertThrows(IllegalArgumentException.class,
                () -> service.discover(confirmedUserProfile(), null, validProposals()));
    }

    @Test
    void rejectsNullInputs() {
        assertThrows(IllegalArgumentException.class,
                () -> service.discover(null, repositoryProfiles(), validProposals()));
        assertThrows(IllegalArgumentException.class,
                () -> service.discover(confirmedUserProfile(), repositoryProfiles(), null));
        assertThrows(IllegalArgumentException.class,
                () -> new ProductDirectionDiscoveryService(null));
    }

    /**
     * 同一个标识不能对应两份内容不同的快照。
     *
     * <p>静默留下其中一份会让两条校验看到不同的输入：来源核对只认得留下的那份，
     * 而按原始列表去找候选资产又会把两份的资产都算作受支持——于是一条方向可以选一个
     * 只存在于冲突快照里的资产。
     */
    @Test
    void rejectsDuplicateRepositoryProfileIdentity() {
        RepositoryProfile first = RepositoryProfile.create(
                ACCOUNTING_PROFILE_ID, ACCOUNTING_ASSET_ID, "abc123", "个人记账工具",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(ACCOUNTING_EVIDENCE));
        RepositoryProfile second = RepositoryProfile.create(
                ACCOUNTING_PROFILE_ID, CHART_ASSET_ID, "def456", "另一份快照",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(ACCOUNTING_EVIDENCE));

        assertThrows(IllegalArgumentException.class,
                () -> service.discover(confirmedUserProfile(), List.of(first, second),
                        validProposals()));
    }

    /**
     * 生成器给出重复标识时必须失败。
     *
     * <p>ProductDirection 是 Entity：标识相同就是同一条方向，一批里出现重复标识意味着
     * 「三条候选」其实是一条方向的三个副本。
     */
    @Test
    void rejectsDuplicateDirectionIdentities() {
        ProductDirectionDiscoveryService fixedIdService = new ProductDirectionDiscoveryService(
                () -> new ProductDirectionId("always-the-same"));

        assertThrows(IllegalStateException.class,
                () -> fixedIdService.discover(
                        confirmedUserProfile(), repositoryProfiles(), validProposals()));
    }

    @Test
    void rejectsListsContainingNull() {
        List<DirectionProposal> withNull = new ArrayList<>(validProposals());
        withNull.add(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.discover(confirmedUserProfile(), repositoryProfiles(), withNull));

        List<RepositoryProfile> profilesWithNull = new ArrayList<>(repositoryProfiles());
        profilesWithNull.add(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.discover(confirmedUserProfile(), profilesWithNull, validProposals()));
    }

    // ---------------------------------------------------------------------
    // 失败语义
    // ---------------------------------------------------------------------

    /** 某条不合法时整次失败，不返回剩下的几条。 */
    @Test
    void failsTheWholeDiscoveryWhenOneProposalIsInvalid() {
        List<DirectionProposal> mixed = List.of(
                validProposal("方向一"),
                proposal("方向二", List.of(ACCOUNTING_ASSET_ID), new DirectionEvidenceSupport(
                        List.of(), List.of(USER_BASIS), List.of(ACCOUNTING_BASIS))),
                validProposal("方向三"));

        assertThrows(ProductDirectionDiscoveryException.class,
                () -> service.discover(confirmedUserProfile(), repositoryProfiles(), mixed));
    }

    /**
     * 失败发生在构造之前：一个标识都不会被取走。
     *
     * <p>「不产生半合法结果」在这里是可观察的——若实现边校验边构造，错误的那条之前
     * 已经消耗掉了标识。
     */
    @Test
    void consumesNoIdentityWhenValidationFails() {
        List<DirectionProposal> mixed = List.of(
                validProposal("方向一"),
                validProposal("方向二"),
                proposal("方向三", List.of(ACCOUNTING_ASSET_ID), new DirectionEvidenceSupport(
                        List.of(USER_BASIS), List.of(), List.of(ACCOUNTING_BASIS))));

        assertThrows(ProductDirectionDiscoveryException.class,
                () -> service.discover(confirmedUserProfile(), repositoryProfiles(), mixed));

        assertEquals(0, idGenerator.count(), "失败时不得构造任何方向");
    }

    /** 服务不修改它的输入。 */
    @Test
    void doesNotModifyItsInputs() {
        UserProfile profile = confirmedUserProfile();
        List<RepositoryProfile> profiles = repositoryProfiles();
        List<DirectionProposal> proposals = validProposals();

        int revisionBefore = profile.revision();
        List<Evidence> evidenceBefore = profile.evidence();

        service.discover(profile, profiles, proposals);

        assertEquals(UserProfileStatus.CONFIRMED, profile.status());
        assertEquals(revisionBefore, profile.revision());
        assertEquals(evidenceBefore, profile.evidence());
        assertEquals(proposals, validProposals(), "提案本身未被改动");
    }

    // ---------------------------------------------------------------------
    // 夹具
    // ---------------------------------------------------------------------

    /** 从完整合法基线出发，只替换 support 或候选资产，其余 3 条提案保持合法。 */
    private void assertRejected(DirectionEvidenceSupport support) {
        assertRejected(List.of(ACCOUNTING_ASSET_ID), support);
    }

    private void assertRejected(List<SoftwareAssetId> candidateAssetIds,
                                DirectionEvidenceSupport support) {
        List<DirectionProposal> mixed = List.of(
                validProposal("方向一"),
                proposal("被破坏的方向", candidateAssetIds, support),
                validProposal("方向三"));

        assertThrows(ProductDirectionDiscoveryException.class,
                () -> service.discover(confirmedUserProfile(), repositoryProfiles(), mixed));
    }

    private static EvidenceBasis basisWithUserOrigin(UserProfileId id,
                                                     int revision,
                                                     Evidence evidence) {
        return new EvidenceBasis(evidence, new UserProfileEvidenceOrigin(id, revision));
    }

    private static UserProfile confirmedUserProfile() {
        return userProfile(UserProfileStatus.CONFIRMED);
    }

    private static UserProfile userProfile(UserProfileStatus status) {
        return UserProfile.reconstitute(
                USER_PROFILE_ID,
                status,
                USER_PROFILE_REVISION,
                List.of("个人记账"),
                List.of("长期自己维护小工具"),
                List.of("导出报表很麻烦"),
                List.of("Java"),
                List.of("做一个自己每天都会用的工具"),
                List.of("业余时间推进"),
                List.of(USER_EVIDENCE));
    }

    private static List<RepositoryProfile> repositoryProfiles() {
        return List.of(
                RepositoryProfile.create(
                        ACCOUNTING_PROFILE_ID, ACCOUNTING_ASSET_ID, "abc123", "个人记账工具",
                        List.of("Java 21"), List.of("accounting"), List.of("记账"),
                        List.of("报表导出"), List.of("没有自动化测试"), List.of("模块耦合"),
                        List.of(ACCOUNTING_EVIDENCE)),
                RepositoryProfile.create(
                        CHART_PROFILE_ID, CHART_ASSET_ID, "def456", "图表组件库",
                        List.of("TypeScript"), List.of("chart"), List.of("图表渲染"),
                        List.of("折线图组件"), List.of(), List.of(),
                        List.of(CHART_EVIDENCE)));
    }

    private static List<DirectionProposal> validProposals() {
        return List.of(validProposal("方向一"), validProposal("方向二"), validProposal("方向三"));
    }

    /** 一条合法提案：三个判断都有依据，候选资产由实际依据的那份 Profile 支撑。 */
    private static DirectionProposal validProposal(String title) {
        return proposal(title, List.of(ACCOUNTING_ASSET_ID), validSupport());
    }

    /**
     * 一份覆盖三类判断的合法 support。
     *
     * <pre>
     * userNeed           用户侧
     * userFit            用户侧 + 资产侧
     * reusableCapability 资产侧
     * </pre>
     */
    private static DirectionEvidenceSupport validSupport() {
        return new DirectionEvidenceSupport(
                List.of(USER_BASIS),
                List.of(USER_BASIS, ACCOUNTING_BASIS),
                List.of(ACCOUNTING_BASIS));
    }

    private static DirectionProposal proposal(String title,
                                              List<SoftwareAssetId> candidateAssetIds,
                                              DirectionEvidenceSupport support) {
        return new DirectionProposal(
                title, "现有工具缺少可导出的报表", "桌面记账工具 + 报表导出", "用户已经在用记账工具",
                candidateAssetIds, "相比现有工具增加了自定义报表", "可复用报表渲染能力",
                "中等：主要在导出与模板部分", List.of("模板复杂度可能超预期"), support);
    }

    /** 可观察的标识生成器：既给出确定的标识，也记录被取用过几次。 */
    private static final class CountingIdGenerator implements Supplier<ProductDirectionId> {

        private final AtomicInteger issued = new AtomicInteger();

        @Override
        public ProductDirectionId get() {
            return new ProductDirectionId("direction-" + issued.incrementAndGet());
        }

        int count() {
            return issued.get();
        }
    }
}
