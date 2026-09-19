package com.ayywl.delveforge.domain.user;

import com.ayywl.delveforge.domain.evidence.Evidence;
import java.util.ArrayList;
import java.util.List;

/**
 * User Profile Aggregate Root（DOMAIN_MODEL.md §3.1、§11.3）。
 *
 * <p>User Profile 描述当前与项目发现相关的用户信息。它不是完整个人档案，
 * 也不保存未经整理的聊天记录：只有已经结构化的、能够影响 Product Direction
 * 发现与 Evolution Planning 的内容才属于本 Aggregate。
 *
 * <p>本类当前只承载 Aggregate 自身的领域语义：
 *
 * <pre>
 * 创建 User Profile
 * 在允许的状态下更新结构化的 Profile 内容
 * 内容或判断依据发生实际变化时推进 revision
 * 记录支撑 Profile 判断的 Evidence
 * §6.1 定义的全部状态转换
 * 按已保存的状态重建（reconstitute）User Profile
 * </pre>
 *
 * <p>不包含：用户输入如何被转换为这些结构化内容，以及 Sufficiency Assessment
 * （是否「信息足够」由 Application 结合 AI 建议判断）。这些由 Application 承担。
 *
 * <p>本类不承载任何持久化语义：{@link #reconstitute} 只是重建入口，
 * 历史 revision 如何保存与重新获取（DOMAIN_MODEL.md §10.3）属于 Persistence 设计。
 */
public class UserProfile {

    /** 新建 Profile 的初始 revision（DOMAIN_MODEL.md §10.3 的 revision 序列自 1 开始）。 */
    private static final int INITIAL_REVISION = 1;

    private final UserProfileId id;
    private UserProfileStatus status;
    private int revision;

    private List<String> interests;
    private List<String> behaviors;
    private List<String> painPoints;
    private List<String> technicalCapabilities;
    private List<String> projectGoals;
    private List<String> constraints;
    private List<Evidence> evidence;

    /**
     * 创建一个新的 User Profile。
     *
     * <p>新建的 Profile 尚无任何结构化内容，处于 {@link UserProfileStatus#EXPLORING}，
     * revision 为初始值。内容由后续的更新操作逐步形成。
     *
     * @param id Profile 标识，不得为 {@code null}
     * @throws IllegalArgumentException id 为 {@code null} 或其值不合法
     */
    public static UserProfile create(UserProfileId id) {
        return new UserProfile(id);
    }

    /**
     * 按已保存的状态重建一个 User Profile。
     *
     * <p>本入口用于 Persistence 从存储中恢复已有 Profile，也用于在写入前构造一个隔离的
     * 候选副本（由 Application 在写入前构造）；不用于创建新的 Profile——
     * 新建请使用 {@link #create(UserProfileId)}。它一次性接管完整状态，没有逐字段的
     * 修改入口，因此不构成绕过 Aggregate 规则的任意 mutation API：
     *
     * <pre>
     * 可以重建   任意合法的 status / revision 组合，包括 CONFIRMED
     * 不能重建   status 为 null、revision &lt; 1、内容区含空值、Evidence 含 null
     * </pre>
     *
     * <p>重建不改变 {@code revision}：恢复出的就是保存时的那个版本，
     * 因此重复保存与重新加载都不会制造额外 revision。
     *
     * <p>本方法只校验取值的合法性，不重新判定生命周期规则——被重建的
     * {@code status} 本身就是要恢复的领域状态。
     *
     * @param id                    身份，不得为 {@code null}
     * @param status                保存时的状态，不得为 {@code null}
     * @param revision              保存时的版本，不得小于初始 revision
     * @param interests             兴趣与关注领域
     * @param behaviors             真实存在的行为与使用场景
     * @param painPoints            希望解决的问题或不满意之处
     * @param technicalCapabilities 当前具备的开发与技术能力
     * @param projectGoals          希望通过项目实现的目标
     * @param constraints           影响项目方向选择的重要约束
     * @param evidence              该 revision 对应的判断依据集合
     * @throws IllegalArgumentException 任一参数不满足上述约束
     */
    public static UserProfile reconstitute(
            UserProfileId id,
            UserProfileStatus status,
            int revision,
            List<String> interests,
            List<String> behaviors,
            List<String> painPoints,
            List<String> technicalCapabilities,
            List<String> projectGoals,
            List<String> constraints,
            List<Evidence> evidence) {

        if (id == null) {
            throw new IllegalArgumentException("User Profile 必须指定 id");
        }
        if (status == null) {
            throw new IllegalArgumentException("重建 User Profile 必须指定 status");
        }
        if (revision < INITIAL_REVISION) {
            throw new IllegalArgumentException(
                    "重建 User Profile 的 revision 不能小于 " + INITIAL_REVISION + ": " + revision);
        }

        UserProfile profile = new UserProfile(id);
        profile.status = status;
        profile.revision = revision;
        profile.interests = normalizeSection(interests, "interests");
        profile.behaviors = normalizeSection(behaviors, "behaviors");
        profile.painPoints = normalizeSection(painPoints, "painPoints");
        profile.technicalCapabilities =
                normalizeSection(technicalCapabilities, "technicalCapabilities");
        profile.projectGoals = normalizeSection(projectGoals, "projectGoals");
        profile.constraints = normalizeSection(constraints, "constraints");
        profile.evidence = normalizeEvidence(evidence);
        return profile;
    }

    private UserProfile(UserProfileId id) {
        if (id == null) {
            throw new IllegalArgumentException("User Profile 必须指定 id");
        }
        this.id = id;
        this.status = UserProfileStatus.EXPLORING;
        this.revision = INITIAL_REVISION;
        this.interests = List.of();
        this.behaviors = List.of();
        this.painPoints = List.of();
        this.technicalCapabilities = List.of();
        this.projectGoals = List.of();
        this.constraints = List.of();
        this.evidence = List.of();
    }

    /**
     * 更新用户的兴趣与关注领域。
     *
     * <p>只有在内容确实发生变化时才推进 {@code revision}（DOMAIN_MODEL.md §6.1）。
     *
     * @param interests 新的完整内容，不得为 {@code null}，元素不得为 {@code null} 或空白
     * @throws IllegalArgumentException 参数不满足上述约束
     * @throws UserProfileStateException 当前状态不允许修改 Profile 内容
     */
    public void updateInterests(List<String> interests) {
        requireProfileUpdateAllowed();
        List<String> updated = normalizeSection(interests, "interests");
        if (updated.equals(this.interests)) {
            return;
        }
        this.interests = updated;
        advanceRevision();
    }

    /**
     * 更新用户真实存在的行为与使用场景。
     *
     * @throws IllegalArgumentException 参数为 {@code null}，或元素为 {@code null} 或空白
     * @throws UserProfileStateException 当前状态不允许修改 Profile 内容
     */
    public void updateBehaviors(List<String> behaviors) {
        requireProfileUpdateAllowed();
        List<String> updated = normalizeSection(behaviors, "behaviors");
        if (updated.equals(this.behaviors)) {
            return;
        }
        this.behaviors = updated;
        advanceRevision();
    }

    /**
     * 更新用户希望解决的问题或不满意之处。
     *
     * @throws IllegalArgumentException 参数为 {@code null}，或元素为 {@code null} 或空白
     * @throws UserProfileStateException 当前状态不允许修改 Profile 内容
     */
    public void updatePainPoints(List<String> painPoints) {
        requireProfileUpdateAllowed();
        List<String> updated = normalizeSection(painPoints, "painPoints");
        if (updated.equals(this.painPoints)) {
            return;
        }
        this.painPoints = updated;
        advanceRevision();
    }

    /**
     * 更新用户当前具备的开发与技术能力。
     *
     * @throws IllegalArgumentException 参数为 {@code null}，或元素为 {@code null} 或空白
     * @throws UserProfileStateException 当前状态不允许修改 Profile 内容
     */
    public void updateTechnicalCapabilities(List<String> technicalCapabilities) {
        requireProfileUpdateAllowed();
        List<String> updated = normalizeSection(technicalCapabilities, "technicalCapabilities");
        if (updated.equals(this.technicalCapabilities)) {
            return;
        }
        this.technicalCapabilities = updated;
        advanceRevision();
    }

    /**
     * 更新用户希望通过项目实现的目标。
     *
     * @throws IllegalArgumentException 参数为 {@code null}，或元素为 {@code null} 或空白
     * @throws UserProfileStateException 当前状态不允许修改 Profile 内容
     */
    public void updateProjectGoals(List<String> projectGoals) {
        requireProfileUpdateAllowed();
        List<String> updated = normalizeSection(projectGoals, "projectGoals");
        if (updated.equals(this.projectGoals)) {
            return;
        }
        this.projectGoals = updated;
        advanceRevision();
    }

    /**
     * 更新影响项目方向选择的重要约束。
     *
     * @throws IllegalArgumentException 参数为 {@code null}，或元素为 {@code null} 或空白
     * @throws UserProfileStateException 当前状态不允许修改 Profile 内容
     */
    public void updateConstraints(List<String> constraints) {
        requireProfileUpdateAllowed();
        List<String> updated = normalizeSection(constraints, "constraints");
        if (updated.equals(this.constraints)) {
            return;
        }
        this.constraints = updated;
        advanceRevision();
    }

    /**
     * 记录一条支撑 Profile 判断的 Evidence。
     *
     * <p>按 DOMAIN_MODEL.md §6.1 的 Revision 触发规则，{@code revision} 同时覆盖六个
     * 内容区与 Aggregate 内的 Evidence 集合：Evidence 记录“为什么形成当前判断”，
     * 因此记录一条与集合中已有条目完整值不同的 Evidence 会推进 {@code revision}，
     * 即使六个内容区没有变化。
     *
     * <p>完整值相同的 Evidence 视为重复：集合与 {@code revision} 都不变。
     * 去重按完整 Value Object 比较，不按 {@code sourceRef} 合并、替换或确认已有条目。
     *
     * <p>本操作与其余内容更新适用相同的状态约束（§6.1）。
     *
     * @param evidence 待记录的 Evidence，不得为 {@code null}
     * @throws IllegalArgumentException evidence 为 {@code null}
     * @throws UserProfileStateException 当前状态不允许修改 Profile 内容
     */
    public void recordEvidence(Evidence evidence) {
        requireProfileUpdateAllowed();
        if (evidence == null) {
            throw new IllegalArgumentException("User Profile 的 evidence 不能为 null");
        }
        if (this.evidence.contains(evidence)) {
            return;
        }
        List<Evidence> updated = new ArrayList<>(this.evidence);
        updated.add(evidence);
        this.evidence = List.copyOf(updated);
        advanceRevision();
    }

    /**
     * 把 Profile 从 {@link UserProfileStatus#EXPLORING} 推进到
     * {@link UserProfileStatus#REVIEWING}（DOMAIN_MODEL.md §6.1）。
     *
     * <p>只有 {@code EXPLORING} 是这条转移的合法起点：§6.1 里「信息足够 → REVIEWING」
     * 只从 EXPLORING 出发。{@code REVIEWING} 与 {@code CONFIRMED} 都不是它的起点，
     * 因此这里拒绝，而不是静默忽略。
     *
     * <p>状态变化不推进 {@code revision}：revision 只由六个内容区与 Evidence 的实际变化
     * 推进（§6.1 的 Revision 触发规则），状态本身不在其中。
     *
     * <p>是否「信息足够」不由本方法判断——它只负责这条状态转移在领域上是否被允许。
     *
     * @throws UserProfileStateException 当前状态不是 {@code EXPLORING}
     */
    public void beginReview() {
        if (status != UserProfileStatus.EXPLORING) {
            throw new UserProfileStateException(
                    "User Profile 当前状态不允许进入 REVIEWING: " + status);
        }
        this.status = UserProfileStatus.REVIEWING;
    }

    /**
     * 把 Profile 从 {@link UserProfileStatus#REVIEWING} 退回
     * {@link UserProfileStatus#EXPLORING}：用户在 Review 阶段选择继续探索
     * （DOMAIN_MODEL.md §6.1 的「REVIEWING → EXPLORING：Continue discovery」）。
     *
     * <p>只有 {@code REVIEWING} 是这条转移的合法起点。用户明确确认之后的退回走
     * {@link #reopenDiscovery()}，两者是不同的转移，因此这里不为 {@code CONFIRMED} 开后门。
     *
     * <p>状态变化不推进 {@code revision}（§6.1 的 Revision 触发规则只覆盖六个内容区与
     * Evidence）。退回 EXPLORING 之后，Profile 内容重新允许修改。
     *
     * @throws UserProfileStateException 当前状态不是 {@code REVIEWING}
     */
    public void continueDiscovery() {
        if (status != UserProfileStatus.REVIEWING) {
            throw new UserProfileStateException(
                    "User Profile 当前状态不允许继续探索: " + status);
        }
        this.status = UserProfileStatus.EXPLORING;
    }

    /**
     * 把 Profile 从 {@link UserProfileStatus#REVIEWING} 推进到
     * {@link UserProfileStatus#CONFIRMED}：用户明确确认当前 Profile
     * （DOMAIN_MODEL.md §6.1 的「REVIEWING → CONFIRMED：User confirms profile」）。
     *
     * <p>只有 {@code REVIEWING} 是这条转移的合法起点：确认必须是用户在一次 Review 之后
     * 做出的显式决定，不能从 {@code EXPLORING} 直接跳到已确认。
     *
     * <h2>确认必须绑定用户实际查看的版本</h2>
     *
     * <p>调用方必须给出用户做出决定时所看的 {@code expectedRevision}。确认的意义是
     * 「用户同意这一版内容」；如果期间内容又变化过，当前 revision 已经不是用户看过的
     * 那一版，此时确认不再代表用户的真实决定，因此拒绝而不是默默确认服务端的最新版本。
     *
     * <p>状态变化不推进 {@code revision}，因此确认之后的 Profile 就是
     * 「{@code CONFIRMED} @ {@code expectedRevision}」——这个组合构成后续
     * Product Direction Discovery 的稳定基线：确认之后内容不再允许修改，
     * 该 revision 对应的内容快照也已经被 Persistence 保留（§10.3）。
     *
     * <p>本方法只负责这条状态转移是否被允许；「用户是否真的按下了确认」属于
     * Application 的显式请求，不由领域模型推测。
     *
     * @param expectedRevision 用户确认时所依据的 revision，必须等于当前 {@code revision}
     * @throws UserProfileStateException 当前状态不是 {@code REVIEWING}，
     *                                   或 {@code expectedRevision} 与当前 revision 不一致
     */
    public void confirm(int expectedRevision) {
        if (status != UserProfileStatus.REVIEWING) {
            throw new UserProfileStateException(
                    "User Profile 当前状态不允许确认: " + status);
        }
        if (revision != expectedRevision) {
            throw new UserProfileStateException(
                    "确认所依据的 revision 已过期: 期望 " + expectedRevision
                            + "，当前 revision 为 " + revision);
        }
        this.status = UserProfileStatus.CONFIRMED;
    }

    /**
     * 把 Profile 从 {@link UserProfileStatus#CONFIRMED} 退回
     * {@link UserProfileStatus#EXPLORING}：用户重新开启探索
     * （DOMAIN_MODEL.md §6.1 的「CONFIRMED → EXPLORING：Reopen discovery」）。
     *
     * <p>只有 {@code CONFIRMED} 是这条转移的合法起点。这只是取消「已确认」这一状态，
     * 不删除任何内容，也不改变 {@code revision}——重新探索产生的第一个内容变化才会
     * 推进出一个新版本。
     *
     * @throws UserProfileStateException 当前状态不是 {@code CONFIRMED}
     */
    public void reopenDiscovery() {
        if (status != UserProfileStatus.CONFIRMED) {
            throw new UserProfileStateException(
                    "User Profile 当前状态不允许重新开启探索: " + status);
        }
        this.status = UserProfileStatus.EXPLORING;
    }

    public UserProfileId id() {
        return id;
    }

    public UserProfileStatus status() {
        return status;
    }

    public int revision() {
        return revision;
    }

    public List<String> interests() {
        return interests;
    }

    public List<String> behaviors() {
        return behaviors;
    }

    public List<String> painPoints() {
        return painPoints;
    }

    public List<String> technicalCapabilities() {
        return technicalCapabilities;
    }

    public List<String> projectGoals() {
        return projectGoals;
    }

    public List<String> constraints() {
        return constraints;
    }

    public List<Evidence> evidence() {
        return evidence;
    }

    /**
     * 校验并固化一段结构化内容。
     *
     * <p>返回的列表不可修改，也不与调用方传入的列表共享状态，
     * 使 Profile 的内容只能通过本 Aggregate 的更新操作改变。
     */
    private static List<String> normalizeSection(List<String> values, String sectionName) {
        if (values == null) {
            throw new IllegalArgumentException("User Profile 的 " + sectionName + " 不能为 null");
        }
        List<String> normalized = new ArrayList<>(values.size());
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "User Profile 的 " + sectionName + " 不能包含空值");
            }
            normalized.add(value);
        }
        return List.copyOf(normalized);
    }

    /**
     * 校验并固化一组 Evidence，使重建出的集合不可再由外部修改。
     */
    private static List<Evidence> normalizeEvidence(List<Evidence> evidence) {
        if (evidence == null) {
            throw new IllegalArgumentException("User Profile 的 evidence 不能为 null");
        }
        List<Evidence> normalized = new ArrayList<>(evidence.size());
        for (Evidence item : evidence) {
            if (item == null) {
                throw new IllegalArgumentException("User Profile 的 evidence 不能包含 null");
            }
            normalized.add(item);
        }
        return List.copyOf(normalized);
    }

    /**
     * 校验当前状态是否允许修改 Profile 内容（DOMAIN_MODEL.md §6.1、§8.1）。
     *
     * <p>Profile 内容与 Evidence 同属 Aggregate 的领域状态，适用同一状态约束。
     */
    private void requireProfileUpdateAllowed() {
        if (!status.allowsProfileUpdate()) {
            throw new UserProfileStateException(
                    "User Profile 当前状态不允许修改内容: " + status);
        }
    }

    /**
     * 推进 revision。
     *
     * <p>这是 revision 的唯一变更入口：只有在影响项目发现的重要领域内容
     * （Profile 内容或判断依据 Evidence）发生实际变化时才调用，
     * 避免产生无意义的新版本。
     */
    private void advanceRevision() {
        revision++;
    }
}
