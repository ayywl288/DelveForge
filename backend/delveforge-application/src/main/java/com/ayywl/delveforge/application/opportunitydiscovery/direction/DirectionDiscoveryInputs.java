package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.direction.EvidenceReference;
import com.ayywl.delveforge.domain.evidence.Evidence;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Product Direction Discovery 的可信输入，以及模型在本次调用中<b>被允许引用</b>的东西。
 *
 * <pre>
 * Confirmed User Profile
 * +
 * Repository Profile 1..N
 *         ↓
 * DirectionDiscoveryInputs
 * ├── 本次的用户侧输入快照（内容、revision 与它们的 Evidence）
 * ├── 本次的资产侧输入（Repository Profile 本身）
 * ├── 模型可以引用的 Evidence 引用（U-E1、R1-E2 …）
 * └── 模型可以标识的 Software Asset
 * </pre>
 *
 * <p>它把「模型能引用什么」这件事收在一处：解析回来的引用拿这里校验，而 Prompt 由
 * {@code DirectionDiscoveryExtraction} 从这里取数据后自行渲染（AI 只允许引用本次输入中
 * 提供的东西）。两侧读的是同一份引用表，因此不可能不一致——模型无法凭空指出一条不存在的
 * 依据，也无法引用一个本次没有提供过的资产。
 *
 * <p>本类型不负责「怎么把输入变成请求」：请求的形状属于 AI 边界所在的流程，
 * 因此渲染留在 {@code DirectionDiscoveryExtraction}。
 *
 * <h2>输入在构造时固定</h2>
 *
 * <p>本次发现必须依据一份确定的输入：Prompt 展示的内容、引用目录、以及后续要记录到
 * Product Direction 上的 {@code userProfileRevision}，三者必须来自同一个时点。否则一次
 * 发现可能「按新内容推荐、却引用旧依据」，或者记录下一个与内容不对应的 revision。
 *
 * <p>因此用户侧在构造时被拍成不可变快照（{@link UserProfileSnapshot}）：{@code UserProfile}
 * 是可变的 Aggregate，探索的下一轮随时会改它的内容与 Evidence，直接持有它的引用无法保证
 * 三者一致。
 *
 * <p>资产侧不需要同样处理：{@code RepositoryProfile} 是不可改写的分析快照，本身没有修改
 * 入口，持有它即可。这个不对称来自两类 Aggregate 的语义不同，不是疏漏。
 *
 * <h2>引用只在本次调用中有效</h2>
 *
 * <p>引用是「本次提示里第几个依据」的编号，不是 Evidence 的持久身份
 * （见 {@link EvidenceReference}）。同一个 {@code U-E1} 在另一次调用中可能指向完全不同的
 * 依据，因此它既不进入持久化状态，也不该被跨调用复用。
 *
 * <h2>本类型不做领域判断</h2>
 *
 * <p>它只反映输入：输入里有哪些 Evidence、哪些资产。它不判断这些依据是否足以支撑某个
 * 推荐判断（INV-D06 的语义充分性），也不判断某个资产在业务上是否适合作为某个方向的
 * 基础——那是 {@code ProductDirectionDiscoveryService} 的事。
 *
 * <p>它同样不判断「现在是不是该做 Product Direction Discovery」：User Profile 是否已
 * {@code CONFIRMED}（§8.4 的前置条件、INV-D08）由调用本流程的 Use Case 决定，
 * 本类型不代替它做这个判断。
 */
public final class DirectionDiscoveryInputs {

    private final UserProfileSnapshot userProfileSnapshot;
    private final List<RepositoryProfile> repositoryProfiles;
    private final List<List<ReferencedEvidence>> repositoryEvidence;
    private final Set<String> evidenceReferences;
    private final Set<String> assetIds;

    private DirectionDiscoveryInputs(UserProfileSnapshot userProfileSnapshot,
                                     List<RepositoryProfile> repositoryProfiles,
                                     List<List<ReferencedEvidence>> repositoryEvidence) {
        this.userProfileSnapshot = userProfileSnapshot;
        this.repositoryProfiles = List.copyOf(repositoryProfiles);
        this.repositoryEvidence = List.copyOf(repositoryEvidence);

        Set<String> references = new LinkedHashSet<>();
        for (ReferencedEvidence evidence : userProfileSnapshot.evidence()) {
            references.add(evidence.reference().value());
        }
        for (List<ReferencedEvidence> section : this.repositoryEvidence) {
            for (ReferencedEvidence evidence : section) {
                references.add(evidence.reference().value());
            }
        }
        this.evidenceReferences = Collections.unmodifiableSet(references);

        Set<String> assets = new LinkedHashSet<>();
        for (RepositoryProfile profile : this.repositoryProfiles) {
            assets.add(profile.assetId().value());
        }
        this.assetIds = Collections.unmodifiableSet(assets);
    }

    /**
     * 从一个 Confirmed User Profile 与一个或多个 Repository Profile 建立本次调用的输入。
     *
     * <p>用户侧的内容与 Evidence 在此时被固定下来，之后调用方再修改原 Profile 不会影响
     * 本次输入。
     *
     * <p>引用按输入顺序分配，对同一组输入是确定的：用户侧 Evidence 依次是
     * {@code U-E1}、{@code U-E2}…，第 <i>i</i> 个 Repository Profile 的 Evidence 依次是
     * {@code R{i}-E1}、{@code R{i}-E2}…（{@code i} 从 1 开始，与
     * {@link #repositoryProfiles()} 的顺序一致）。顺序具有意义：同一份 Profile 的 Evidence
     * 顺序会如实反映到引用编号上。
     *
     * <p>可标识的 Software Asset 来自这些 Repository Profile 的 {@code assetId}。它们是
     * 跨 Aggregate 的身份引用，因此按身份去重——同一个资产在不同 revision 上被分析过多次时，
     * 模型仍然只看到一个可选的资产。
     *
     * @param userProfile        本次发现的用户侧依据，不得为 {@code null}
     * @param repositoryProfiles 本次发现的资产侧依据；不得为 {@code null} 或空
     *                           （INV-D05 要求每个方向至少能追溯到一个 Repository Profile，
     *                           没有任何 Profile 时不可能产出合法方向）
     * @throws IllegalArgumentException 任一参数不满足上述约束
     */
    public static DirectionDiscoveryInputs of(UserProfile userProfile,
                                              List<RepositoryProfile> repositoryProfiles) {
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

        List<List<ReferencedEvidence>> repositoryEvidence =
                new ArrayList<>(repositoryProfiles.size());
        for (int index = 0; index < repositoryProfiles.size(); index++) {
            repositoryEvidence.add(
                    reference(repositoryProfiles.get(index).evidence(), "R" + (index + 1)));
        }

        return new DirectionDiscoveryInputs(
                UserProfileSnapshot.capture(userProfile), repositoryProfiles, repositoryEvidence);
    }

    /** 按顺序给一组 Evidence 分配引用：{@code U-E1} / {@code R1-E3} 这样的形式。 */
    private static List<ReferencedEvidence> reference(List<Evidence> evidence, String owner) {
        List<ReferencedEvidence> referenced = new ArrayList<>(evidence.size());
        for (int index = 0; index < evidence.size(); index++) {
            referenced.add(new ReferencedEvidence(
                    new EvidenceReference(owner + "-E" + (index + 1)),
                    evidence.get(index)));
        }
        return List.copyOf(referenced);
    }

    /**
     * 本次发现所依据的用户侧输入，在构造时固定。
     *
     * <p>它同时是后续记录 {@code userProfileId} 与 {@code userProfileRevision} 的来源——
     * 一条 Product Direction 必须能够追溯到确定的 {@code UserProfileId + revision}
     * （INV-D01、INV-D08），而那必须正好是本次推荐所依据的那一版。
     */
    public UserProfileSnapshot userProfileSnapshot() {
        return userProfileSnapshot;
    }

    /** 本次依据的 Repository Profile，顺序即引用编号中 {@code R{i}} 的取值来源。 */
    public List<RepositoryProfile> repositoryProfiles() {
        return repositoryProfiles;
    }

    /**
     * 第 {@code repositoryProfileIndex} 个 Repository Profile 的 Evidence 及其引用，
     * 顺序与该 Profile 中的顺序一致。
     *
     * @param repositoryProfileIndex {@link #repositoryProfiles()} 中的下标
     */
    public List<ReferencedEvidence> evidenceOf(int repositoryProfileIndex) {
        return repositoryEvidence.get(repositoryProfileIndex);
    }

    /** 该引用是否是本次提供给模型的引用之一。 */
    public boolean containsEvidence(EvidenceReference reference) {
        return reference != null && evidenceReferences.contains(reference.value());
    }

    /** 该资产是否是本次提供给模型的资产之一。 */
    public boolean containsAsset(SoftwareAssetId assetId) {
        return assetId != null && assetIds.contains(assetId.value());
    }

    /**
     * 本次提供给模型的全部 Software Asset，按出现顺序。
     *
     * <p>返回的是不可修改的视图：这份集合是「模型可以标识哪些资产」的白名单，
     * 能被外部改动就等于白名单失效。
     */
    public Set<String> assetIds() {
        return assetIds;
    }

    /**
     * 一条被本次调用引用的 Evidence：模型看到的引用，与它实际指向的领域对象。
     *
     * <p>两者必须一起使用才成立——引用只有在本输入内才有意义（{@link EvidenceReference}）。
     */
    public record ReferencedEvidence(EvidenceReference reference, Evidence evidence) {

        public ReferencedEvidence {
            if (reference == null) {
                throw new IllegalArgumentException("ReferencedEvidence 必须指定 reference");
            }
            if (evidence == null) {
                throw new IllegalArgumentException("ReferencedEvidence 必须指定 evidence");
            }
        }
    }

    /**
     * 本次发现所依据的用户侧输入快照：内容、revision 与它们的 Evidence。
     *
     * <p>它是 {@code UserProfile} 在某一时点的内容，不是那个 Aggregate 本身：用户探索的
     * 下一轮会继续修改 Profile，而一次已经在进行的发现必须始终依据它开始时的那一份输入。
     *
     * <p>它不是领域对象，也不持久化：{@code UserProfileId + revision} 才是可追溯的引用，
     * 内容快照本身只在本次发现内有效。
     *
     * @param userProfileId       所依据的 User Profile，不得为 {@code null}
     * @param userProfileRevision 所依据的版本，不得小于 1（INV-D01、INV-D08）
     * @param interests           兴趣与关注领域
     * @param behaviors           行为与使用场景
     * @param painPoints          痛点与不满意之处
     * @param technicalCapabilities 技术能力
     * @param projectGoals        项目目标
     * @param constraints         重要约束
     * @param evidence            该 Profile 当时的 Evidence 及各自的引用
     */
    public record UserProfileSnapshot(
            UserProfileId userProfileId,
            int userProfileRevision,
            List<String> interests,
            List<String> behaviors,
            List<String> painPoints,
            List<String> technicalCapabilities,
            List<String> projectGoals,
            List<String> constraints,
            List<ReferencedEvidence> evidence) {

        public UserProfileSnapshot {
            if (userProfileId == null) {
                throw new IllegalArgumentException("User Profile 快照必须指定 userProfileId");
            }
            if (userProfileRevision < 1) {
                throw new IllegalArgumentException(
                        "User Profile 快照的 revision 必须是确定的版本: " + userProfileRevision);
            }
            interests = copySection(interests, "interests");
            behaviors = copySection(behaviors, "behaviors");
            painPoints = copySection(painPoints, "painPoints");
            technicalCapabilities = copySection(technicalCapabilities, "technicalCapabilities");
            projectGoals = copySection(projectGoals, "projectGoals");
            constraints = copySection(constraints, "constraints");
            if (evidence == null) {
                throw new IllegalArgumentException("User Profile 快照的 evidence 不能为 null");
            }
            evidence = List.copyOf(evidence);
        }

        /** 固定当前这一版内容与它的 Evidence 引用。 */
        private static UserProfileSnapshot capture(UserProfile profile) {
            return new UserProfileSnapshot(
                    profile.id(),
                    profile.revision(),
                    profile.interests(),
                    profile.behaviors(),
                    profile.painPoints(),
                    profile.technicalCapabilities(),
                    profile.projectGoals(),
                    profile.constraints(),
                    reference(profile.evidence(), "U"));
        }

        private static List<String> copySection(List<String> values, String section) {
            if (values == null) {
                throw new IllegalArgumentException(
                        "User Profile 快照的 " + section + " 不能为 null");
            }
            return List.copyOf(values);
        }
    }
}
