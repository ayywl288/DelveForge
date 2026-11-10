package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.application.userdiscovery.conversation.DiscoveryReadiness;
import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;

import java.util.List;
import java.util.Optional;

/**
 * User Profile 的持久化边界。
 *
 * <p>本接口由 Application 拥有、由 Infrastructure 实现（RULE-ARCH-003、RULE-ARCH-004）。
 * Application 只依赖本抽象，不得依赖 SQLite、MyBatis-Plus、Mapper、持久化 Entity
 * 等任何具体技术类型。
 *
 * <p>本 Port 不是通用 Repository：没有 {@code BaseRepository<T>} 一类抽象，
 * 方法集合只覆盖当前 Use Case 的最小需求。后续 Use Case 出现新的持久化需求时，
 * 再按该需求扩展，而不是提前为可能的查询场景预留方法。
 *
 * <p>当前状态与按版本查找的只读内容投影分别提供。
 * 但实现必须为每次保存保留该 revision 的内容快照，使
 * {@code UserProfileId + revision} 具备恢复当时内容的基础（DOMAIN_MODEL.md §10.3）。
 */
public interface UserProfileRepository {

    /**
     * 保存 User Profile 的当前状态。
     *
     * <p>实现需要保留每次提交的 revision 的内容快照（六个内容区与 Evidence）。
     * 两点需要明确：
     *
     * <pre>
     * 只保存实际提交的版本   版本号之间可能有间隔：Application 可能在一次 Use Case 内
     *                       连续修改多个内容区、最后只提交一次 save，
     *                       未提交过的 revision 不产生快照
     * 快照是内容快照         不含 status：状态变化不推进 revision（DOMAIN_MODEL.md §6.1），
     *                       因此快照只能恢复内容，不能恢复当时的 status
     * </pre>
     *
     * <p>为避免破坏已保存的历史，以下写入会被拒绝：
     *
     * <pre>
     * 过期保存          传入 revision 低于已保存的当前 revision
     * 同版本内容冲突    传入 revision 已有快照，但内容不同
     * </pre>
     *
     * <p>内容完全相同的重复保存是幂等的。
     *
     * <p>本方法不判断 Profile 是否发生了领域意义上的变化，
     * revision 由 {@code UserProfile} Aggregate 自己维护。
     *
     * @param profile 待保存的 Profile，不得为 {@code null}
     * @throws IllegalStateException 过期保存，或同一 revision 的内容冲突
     */
    void save(UserProfile profile);

    /** 历史内容不含当时的状态或对话，仅包含指定版本保存的六个内容区。 */
    record Sections(List<String> interests, List<String> behaviors, List<String> painPoints,
            List<String> technicalCapabilities, List<String> projectGoals, List<String> constraints) {
        public Sections {
            interests = List.copyOf(interests);
            behaviors = List.copyOf(behaviors);
            painPoints = List.copyOf(painPoints);
            technicalCapabilities = List.copyOf(technicalCapabilities);
            projectGoals = List.copyOf(projectGoals);
            constraints = List.copyOf(constraints);
        }
    }

    /** 历史快照里存在当前版本已无条目的分区。只供提取校验，不推定当时的修改人。 */
    List<String> sectionsWithSupersededItems(UserProfileId id, int revision);

    Optional<Sections> findSections(UserProfileId id, int revision);

    /**
     * 按标识查找 User Profile。
     *
     * @param id Profile 标识，不得为 {@code null}
     * @return 对应的 Profile；不存在时为空
     */
    Optional<UserProfile> findById(UserProfileId id);

    /** 原子检查原始版本、状态及内容后保存；并发变化必须作为状态冲突拒绝。 */
    void saveIfUnchanged(UserProfile profile, UserProfile expected);

    /** 与 Profile 保存同一事务记录服务端通过的最低充分性；不保存问题或评估历史。 */
    void saveWithSufficiency(UserProfile profile, UserProfile expected, boolean sufficient);

    /** 原子保存完整同版判断；并发已建立的不同判断必须拒绝，不能反转缓存。 */
    default void saveWithReadiness(UserProfile profile, UserProfile expected,
            DiscoveryReadiness readiness) {
        saveWithSufficiency(profile, expected, readiness.sufficient());
    }

    /**
     * 复用通过结果提交同版审阅；同一事务中核对 expected 快照与仍有效的通过记录。
     * 通过记录已撤销必须拒绝旧候选，不得根据缓存布尔值重新创建记录。
     */
    void saveReviewIfSufficient(UserProfile profile, UserProfile expected);

    /** 原子核对当前快照后替换其充分性元数据；不足时删除旧通过标记，不保存评估历史。 */
    void recordSufficiencyAssessment(UserProfile expected, boolean sufficient);

    /** 已通过结果的便捷入口，不改变 Profile 内容、Evidence、revision 或状态。 */
    default void recordSufficientAssessment(UserProfile expected) {
        recordSufficiencyAssessment(expected, true);
    }

    /** 只匹配当前内容快照；没有记录表示未知，不能解释为 AI 判定不足。 */
    boolean hasSufficientAssessment(UserProfileId id, int revision);

    /** 查询本版已保存的完整判断；空表示尚未评估，不把未知当成不足。 */
    default Optional<DiscoveryReadiness> findReadinessAssessment(UserProfileId id, int revision) {
        return Optional.empty();
    }

}
