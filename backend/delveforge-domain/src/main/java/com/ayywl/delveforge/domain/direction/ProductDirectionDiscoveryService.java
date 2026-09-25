package com.ayywl.delveforge.domain.direction;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evidence.EvidenceBasis;
import com.ayywl.delveforge.domain.evidence.EvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.RepositoryProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.evidence.UserProfileEvidenceOrigin;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 把 AI 提出的方向提案变成合法的候选 Product Direction（DOMAIN_MODEL.md §12.4、§13.1）。
 *
 * <pre>
 * Confirmed UserProfile @ revision
 *         +
 * RepositoryProfile 1..N
 *         +
 * DirectionProposal 3..5
 *         ↓
 * Candidate ProductDirection 3..5
 * </pre>
 *
 * <p>它是当前第一个明确的 Domain Service：判断依赖多个 Aggregate，而这件事本身就是核心
 * 领域行为，因此不能放进任何一个 Aggregate（§12.3 Case 3）。它本身没有长期状态，
 * 也不需要被持久化（§12.14）。
 *
 * <h2>它不认识 AI</h2>
 *
 * <p>进入本类的提案已经完成了引用解析：{@code U-E1} / {@code R2-E3} 这类只在一次调用中
 * 有效的编号在 Application 侧就被换成了真实依据与它们的出处。因此本类不依赖、也不需要
 * 理解 {@code AiDirectionProposal}、{@code EvidenceReference}、Prompt、JSON 或 AI Gateway
 * ——它只处理已经成立的事实。
 *
 * <h2>它判断什么</h2>
 *
 * <pre>
 * 输入是否构成一次合法的发现      User Profile 已确认、至少一个 Repository Profile
 * 依据是否真的来自它声称的地方    以 EvidenceOrigin 为准，不以 Evidence 的值猜来源
 * 三个关键判断是否都有依据        INV-D06
 * 这条方向实际依据了哪些分析      从使用到的 RepositoryProfileEvidenceOrigin 得出
 * 候选资产是否站得住              必须由实际依据的 Repository Profile 支撑
 * </pre>
 *
 * <p>它不判断「这几条方向之间是否足够不同」：那需要相似度模型，本 Task 不做，留给 Prompt
 * 与后续的验证。它也不判断依据在语义上是否**足以**支撑某条判断——本类只要求可追溯。
 *
 * <h2>失败是一次的，不是逐条的</h2>
 *
 * <p>整次调用要么产出一批合法的候选方向，要么明确失败。某条提案不满足规则时不会静默丢弃、
 * 然后返回剩下的几条——那会让调用方以为「系统只发现了这几条」。校验全部完成之后才构造
 * 对象，因此不存在「构造了一半又被丢弃」的中间产物。
 *
 * <p>本类不修改任何输入对象。
 */
public final class ProductDirectionDiscoveryService {

    /** 一次发现要求的候选方向数量下界。 */
    private static final int MINIMUM_PROPOSALS = 3;

    /** 一次发现要求的候选方向数量上界。 */
    private static final int MAXIMUM_PROPOSALS = 5;

    private final Supplier<ProductDirectionId> idGenerator;

    /**
     * @param idGenerator 方向标识的生成方式；由调用方提供
     *                    （{@code ProductDirectionId} 的取值与生成方式属于
     *                    Application / Persistence，领域模型不规定其格式，
     *                    因此本服务只负责「要一个新标识」，不规定怎么造）
     */
    public ProductDirectionDiscoveryService(Supplier<ProductDirectionId> idGenerator) {
        if (idGenerator == null) {
            throw new IllegalArgumentException(
                    "ProductDirectionDiscoveryService 必须指定 idGenerator");
        }
        this.idGenerator = idGenerator;
    }

    /**
     * 校验一批提案，并把它们转换成候选 Product Direction。
     *
     * @param userProfile        本次发现所依据的 User Profile，必须处于 {@code CONFIRMED}
     * @param repositoryProfiles 本次发现可见的 Repository Profile，至少一个
     * @param proposals          模型提出的候选方向，数量在 3 到 5 之间
     * @return 全部初始为 {@code CANDIDATE} 的候选方向，顺序与提案一致
     * @throws IllegalArgumentException           参数为 {@code null} 或含 {@code null} 元素
     *                                            （调用方把不可能的形状传了进来）
     * @throws ProductDirectionDiscoveryException 结果不满足领域要求
     */
    public List<ProductDirection> discover(UserProfile userProfile,
                                           List<RepositoryProfile> repositoryProfiles,
                                           List<DirectionProposal> proposals) {

        requireInputShape(userProfile, repositoryProfiles, proposals);
        requireConfirmed(userProfile);
        requireProposalCount(proposals);

        Map<RepositoryProfileId, RepositoryProfile> profilesById = byId(repositoryProfiles);

        // 先全部校验并算出每一批的领域结论，再构造对象——不留下构造了一半的结果。
        List<ValidatedProposal> validated = new ArrayList<>(proposals.size());
        for (DirectionProposal proposal : proposals) {
            validated.add(validate(proposal, userProfile, profilesById));
        }

        List<ProductDirection> directions = new ArrayList<>(validated.size());
        Set<ProductDirectionId> issued = new LinkedHashSet<>();
        for (ValidatedProposal each : validated) {
            ProductDirectionId id = idGenerator.get();
            requireUnusedIdentity(id, issued);
            directions.add(toProductDirection(each, userProfile, id));
        }
        return List.copyOf(directions);
    }

    /**
     * 同一次发现里的方向标识必须互不相同。
     *
     * <p>ProductDirection 是 Entity：标识相同就是同一条方向，无论内容是否一样。一批里出现
     * 重复标识意味着「三条候选」其实是一条方向的三个副本，它们无法构成三个独立选择，之后
     * 保存时还会撞上同标识内容冲突。
     *
     * <p>标识由调用方注入的生成器提供，因此重复是那个协作者违反了自己的契约，属于服务端
     * 故障而不是用户可理解的领域拒绝：这里用 {@code IllegalStateException}，不借用
     * {@link ProductDirectionDiscoveryException}——后者描述的是提案本身不合领域要求。
     */
    private static void requireUnusedIdentity(ProductDirectionId id,
                                              Set<ProductDirectionId> issued) {
        if (!issued.add(id)) {
            throw new IllegalStateException(
                    "同一次发现产生了重复的 Product Direction 标识: " + id.value()
                            + "；标识相同即同一条方向，无法构成多个独立候选");
        }
    }

    // ---------------------------------------------------------------------
    // 输入形状与前置条件
    // ---------------------------------------------------------------------

    private static void requireInputShape(UserProfile userProfile,
                                          List<RepositoryProfile> repositoryProfiles,
                                          List<DirectionProposal> proposals) {
        if (userProfile == null) {
            throw new IllegalArgumentException(
                    "Product Direction Discovery 必须指定 userProfile");
        }
        if (repositoryProfiles == null || repositoryProfiles.isEmpty()) {
            throw new IllegalArgumentException(
                    "Product Direction Discovery 至少需要一个 Repository Profile");
        }
        for (RepositoryProfile profile : repositoryProfiles) {
            if (profile == null) {
                throw new IllegalArgumentException(
                        "Product Direction Discovery 的 repositoryProfiles 不能包含 null");
            }
        }
        if (proposals == null) {
            throw new IllegalArgumentException(
                    "Product Direction Discovery 必须指定 proposals");
        }
        for (DirectionProposal proposal : proposals) {
            if (proposal == null) {
                throw new IllegalArgumentException(
                        "Product Direction Discovery 的 proposals 不能包含 null");
            }
        }
    }

    /**
     * §8.4 的前置条件：用于生成 Product Direction 的 User Profile 必须处于
     * {@code CONFIRMED}（INV-D08）。
     *
     * <p>未确认的 Profile 仍在变化，基于它得出的方向没有稳定的追溯点——记录下来的
     * {@code userProfileRevision} 会指向一版用户从未认可过的画像。
     */
    private static void requireConfirmed(UserProfile userProfile) {
        if (userProfile.status() != UserProfileStatus.CONFIRMED) {
            throw new ProductDirectionDiscoveryException(
                    "Product Direction Discovery 要求 User Profile 处于 CONFIRMED，当前为: "
                            + userProfile.status());
        }
    }

    /**
     * 一次发现要求 3 到 5 条候选方向。
     *
     * <p>这是当前 M2 的要求，不是 DOMAIN_MODEL 里的 Invariant：太少说明模型没有真正展开
     * 可能性，太多则会让用户无从比较。数量本身是结构约束，不涉及方向的内容质量。
     */
    private static void requireProposalCount(List<DirectionProposal> proposals) {
        if (proposals.size() < MINIMUM_PROPOSALS || proposals.size() > MAXIMUM_PROPOSALS) {
            throw new ProductDirectionDiscoveryException(
                    "一次 Product Direction Discovery 需要 " + MINIMUM_PROPOSALS + " 到 "
                            + MAXIMUM_PROPOSALS + " 条候选方向，实际为: " + proposals.size());
        }
    }

    /**
     * 按标识索引本次输入的 Repository Profile，重复标识直接拒绝。
     *
     * <p>Repository Profile 是不可改写的分析快照，一个标识就代表一份确定的快照。两组
     * 内容不同的快照声称自己是同一个标识，输入本身就是矛盾的——静默留下其中一份会让
     * 后面的校验各自看到不同的输入：来源核对只认得留下的那份，而按原始列表去找候选资产
     * 又会把两份的资产都算作受支持，于是一条方向可以混用两份冲突快照的事实。
     *
     * <p>因此这里不覆盖、不忽略，直接失败。
     *
     * @throws IllegalArgumentException 同一个标识出现了不止一次
     */
    private static Map<RepositoryProfileId, RepositoryProfile> byId(
            List<RepositoryProfile> repositoryProfiles) {
        Map<RepositoryProfileId, RepositoryProfile> byId = new LinkedHashMap<>();
        for (RepositoryProfile profile : repositoryProfiles) {
            RepositoryProfile previous = byId.putIfAbsent(profile.id(), profile);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "本次输入的 Repository Profile 标识重复: " + profile.id().value()
                                + "；一个标识代表一份确定的分析快照，"
                                + "两组快照不能共用同一个标识");
            }
        }
        return byId;
    }

    // ---------------------------------------------------------------------
    // 逐条校验
    // ---------------------------------------------------------------------

    private static ValidatedProposal validate(DirectionProposal proposal,
                                              UserProfile userProfile,
                                              Map<RepositoryProfileId, RepositoryProfile> byId) {

        DirectionEvidenceSupport support = proposal.evidenceSupport();

        requireEveryBasisIsTraceable(support, userProfile, byId);
        requireRequiredJudgements(support);

        List<RepositoryProfileId> actualBasis =
                actualRepositoryBasis(support, proposal);
        requireCandidateAssetsAreSupported(proposal, actualBasis, byId);

        return new ValidatedProposal(proposal, actualBasis);
    }

    /**
     * 每条依据都必须真的来自它声称的地方。
     *
     * <p>来源以 {@link EvidenceBasis#origin()} 为准，<b>不靠 Evidence 的值去猜</b>：
     * 两个 Repository Profile 完全可能各有一条内容相同的依据，值相等并不能说明它属于谁。
     * 出处说明「应该在哪」，再去那个集合里核对「是否真的在」——{@code Evidence} 没有
     * 独立身份，这一步只能按值比较。
     */
    private static void requireEveryBasisIsTraceable(
            DirectionEvidenceSupport support,
            UserProfile userProfile,
            Map<RepositoryProfileId, RepositoryProfile> byId) {

        for (EvidenceBasis basis : support.allBases()) {
            if (basis.origin() instanceof UserProfileEvidenceOrigin origin) {
                requireUserProfileOrigin(origin, basis, userProfile);
            } else if (basis.origin() instanceof RepositoryProfileEvidenceOrigin origin) {
                requireRepositoryProfileOrigin(origin, basis, byId);
            }
        }
    }

    private static void requireUserProfileOrigin(UserProfileEvidenceOrigin origin,
                                                 EvidenceBasis basis,
                                                 UserProfile userProfile) {
        if (!origin.userProfileId().equals(userProfile.id())
                || origin.userProfileRevision() != userProfile.revision()) {
            throw new ProductDirectionDiscoveryException(
                    "依据指向的不是本次发现所依据的 User Profile 版本：依据声明为 "
                            + origin.userProfileId().value() + " @" + origin.userProfileRevision()
                            + "，本次为 " + userProfile.id().value() + " @" + userProfile.revision()
                            + "（claim: " + basis.evidence().claim() + "）");
        }
        if (!userProfile.evidence().contains(basis.evidence())) {
            throw new ProductDirectionDiscoveryException(
                    "依据并不存在于本次 User Profile 的 Evidence 集合中: "
                            + basis.evidence().claim());
        }
    }

    private static void requireRepositoryProfileOrigin(RepositoryProfileEvidenceOrigin origin,
                                                       EvidenceBasis basis,
                                                       Map<RepositoryProfileId, RepositoryProfile> byId) {
        RepositoryProfile profile = byId.get(origin.repositoryProfileId());
        if (profile == null) {
            throw new ProductDirectionDiscoveryException(
                    "依据指向了本次输入中不存在的 Repository Profile: "
                            + origin.repositoryProfileId().value()
                            + "（claim: " + basis.evidence().claim() + "）");
        }
        if (!profile.evidence().contains(basis.evidence())) {
            throw new ProductDirectionDiscoveryException(
                    "依据并不存在于它声称的 Repository Profile "
                            + profile.id().value() + " 中: " + basis.evidence().claim());
        }
    }

    /**
     * INV-D06：三类关键判断都必须具有可追溯的依据。
     *
     * <p>{@code DirectionEvidenceSupport} 本身允许空槽位——那表示提案自己承认「这条判断
     * 没有依据」。但这样一条方向不能被接受：它无法回答用户「你凭什么这么说」。因此这道
     * 检查在 Service 这一层，不下沉到那个值对象。
     *
     * <p>规则保持最小：{@code userNeed} 至少有一条来自用户画像的依据，{@code
     * reusableCapability} 至少有一条来自软件资产的依据，{@code userFit} 非空即可
     * （它两侧的依据都合理，已通过上面的来源核对）。这里不建立复杂的来源矩阵。
     */
    private static void requireRequiredJudgements(DirectionEvidenceSupport support) {
        requireAtLeastOne(
                support.userNeed(),
                "userNeed",
                "一条方向必须说明「用户要解决什么问题」，且这条判断要有可追溯的依据");
        requireSidePresent(
                support.userNeed(),
                UserProfileEvidenceOrigin.class,
                "本次 User Profile",
                "userNeed",
                "用户要解决什么问题，是用户画像侧的事实，不能只凭软件资产的依据断言");

        requireAtLeastOne(
                support.userFit(),
                "userFit",
                "一条方向必须说明「为什么适合这个用户」，且这条判断要有可追溯的依据");

        requireAtLeastOne(
                support.reusableCapability(),
                "reusableCapability",
                "一条方向必须说明「可复用哪些软件能力」，且这条判断要有可追溯的依据");
        requireSidePresent(
                support.reusableCapability(),
                RepositoryProfileEvidenceOrigin.class,
                "本次 Repository Profile",
                "reusableCapability",
                "可复用能力是软件资产侧的事实，不能只凭用户侧的依据断言");
    }

    private static void requireAtLeastOne(List<EvidenceBasis> bases,
                                          String judgement,
                                          String reason) {
        if (bases.isEmpty()) {
            throw new ProductDirectionDiscoveryException(judgement + " 不能为空：" + reason);
        }
    }

    /**
     * 这一组判断必须至少有一条依据来自指定的那一侧。
     *
     * <p>非空还不够：{@code userNeed} 里全是从代码里读出来的事实、或者
     * {@code reusableCapability} 里全是用户的说法，都无法回答这条判断想问的问题。
     * 依据的出处已经在上一步核对过，因此这里只看出处的类型。
     */
    private static void requireSidePresent(List<EvidenceBasis> bases,
                                           Class<? extends EvidenceOrigin> side,
                                           String sideName,
                                           String judgement,
                                           String reason) {
        if (bases.stream().noneMatch(basis -> side.isInstance(basis.origin()))) {
            throw new ProductDirectionDiscoveryException(
                    judgement + " 不能只由其它来源的依据支撑：" + reason
                            + "（" + judgement + " 至少需要一条来自 " + sideName + " 的依据）");
        }
    }

    /**
     * 这条方向实际依据了哪些 Repository Profile。
     *
     * <p>取自它真正使用过的 {@link RepositoryProfileEvidenceOrigin}，而不是本次可见的全部
     * 输入：AI 可能看过三份 Profile，但一条方向未必用得上每一份，把它们都记进
     * {@code repositoryProfileIds} 会让这个字段不再表示「这条方向凭什么成立」。
     *
     * <p>顺序按依据出现顺序（三组判断、组内按位置），因此对同一份提案是确定的。
     */
    private static List<RepositoryProfileId> actualRepositoryBasis(
            DirectionEvidenceSupport support,
            DirectionProposal proposal) {

        Set<RepositoryProfileId> actual = new LinkedHashSet<>();
        for (EvidenceBasis basis : support.allBases()) {
            if (basis.origin() instanceof RepositoryProfileEvidenceOrigin origin) {
                actual.add(origin.repositoryProfileId());
            }
        }

        if (actual.isEmpty()) {
            throw new ProductDirectionDiscoveryException(
                    "方向「" + proposal.title() + "」没有实际依据任何 Repository Profile，"
                            + "无法追溯它基于什么软件能力（INV-D05）");
        }
        return List.copyOf(actual);
    }

    /**
     * 候选 Software Asset 必须能被这条方向实际依据的 Repository Profile 支撑。
     *
     * <p>只是「本次可见的某份 Profile 里有这个资产」不够：那会让一个方向声称使用一份它
     * 从未依据过的资产（INV-D10）。
     */
    private static void requireCandidateAssetsAreSupported(
            DirectionProposal proposal,
            List<RepositoryProfileId> actualBasis,
            Map<RepositoryProfileId, RepositoryProfile> byId) {

        // 走与来源核对同一个索引，而不是重新遍历原始列表：两条路径必须看到同一份输入，
        // 否则它们对「哪份快照是这个标识」的判断可能不一致。
        Set<SoftwareAssetId> supported = new LinkedHashSet<>();
        for (RepositoryProfileId profileId : actualBasis) {
            supported.add(byId.get(profileId).assetId());
        }

        for (SoftwareAssetId assetId : proposal.candidateAssetIds()) {
            if (!supported.contains(assetId)) {
                throw new ProductDirectionDiscoveryException(
                        "方向「" + proposal.title() + "」的候选资产 " + assetId.value()
                                + " 无法由它实际依据的 Repository Profile 支撑");
            }
        }
    }

    // ---------------------------------------------------------------------
    // 构造
    // ---------------------------------------------------------------------

    /**
     * 把校验通过的提案变成候选方向。
     *
     * <p>标识、User Profile 身份与版本、最终的 repositoryProfileIds 与初始状态全部来自
     * 可信的领域输入，而不是提案——模型没有资格决定这些事实。
     */
    private ProductDirection toProductDirection(ValidatedProposal validated,
                                                UserProfile userProfile,
                                                ProductDirectionId id) {
        DirectionProposal proposal = validated.proposal();

        return ProductDirection.create(
                id,
                userProfile.id(),
                userProfile.revision(),
                validated.actualRepositoryProfileIds(),
                proposal.title(),
                proposal.problem(),
                proposal.targetProduct(),
                proposal.userFit(),
                proposal.candidateAssetIds(),
                proposal.differentiation(),
                proposal.technicalValue(),
                proposal.estimatedComplexity(),
                proposal.risks(),
                proposal.evidenceSupport());
    }

    /** 一条已经通过校验、并算出了领域结论的提案。 */
    private record ValidatedProposal(
            DirectionProposal proposal,
            List<RepositoryProfileId> actualRepositoryProfileIds) {
    }
}
