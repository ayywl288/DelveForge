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
 * </pre>
 *
 * <p>不包含：用户输入如何被转换为这些结构化内容、Sufficiency Assessment、
 * Review / Confirm 流程，以及 §6.1 中决定状态如何变化的部分。这些由后续实现引入。
 *
 * <p>本类不承载任何持久化语义。历史 revision 如何保存与重新获取
 * （DOMAIN_MODEL.md §10.3）属于 Persistence 设计，不由本类型表达。
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
     * @throws IllegalStateException    当前状态不允许修改 Profile 内容
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
     * @throws IllegalStateException    当前状态不允许修改 Profile 内容
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
     * @throws IllegalStateException    当前状态不允许修改 Profile 内容
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
     * @throws IllegalStateException    当前状态不允许修改 Profile 内容
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
     * @throws IllegalStateException    当前状态不允许修改 Profile 内容
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
     * @throws IllegalStateException    当前状态不允许修改 Profile 内容
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
     * <p>Evidence 是 Profile 的重要领域内容（DOMAIN_MODEL.md §3.1），并处于
     * User Profile Aggregate 的一致性边界内（§11.3）：它决定某个 revision
     * 对应的判断依据是什么（§10.3 要求确定 revision 能够追溯当时的 Profile 状态）。
     * 因此本操作与其他内容更新遵循相同规则：受状态约束，并在判断依据确实发生
     * 变化时推进 {@code revision}。
     *
     * <p>已经记录过的相同 Evidence 不再重复记录，也不会因此推进 {@code revision}。
     *
     * @param evidence 待记录的 Evidence，不得为 {@code null}
     * @throws IllegalArgumentException evidence 为 {@code null}
     * @throws IllegalStateException    当前状态不允许修改 Profile 内容
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
     * 校验当前状态是否允许修改 Profile 内容（DOMAIN_MODEL.md §6.1、§8.1）。
     *
     * <p>Profile 内容与 Evidence 同属 Aggregate 的领域状态，适用同一状态约束。
     */
    private void requireProfileUpdateAllowed() {
        if (!status.allowsProfileUpdate()) {
            throw new IllegalStateException(
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
