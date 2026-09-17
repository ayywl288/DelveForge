package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.user.UserProfile;
import com.ayywl.delveforge.domain.user.UserProfileId;
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
 * <p>本 Port 的方法集合只表达当前状态：没有按历史 revision 查询的入口。
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

    /**
     * 按标识查找 User Profile。
     *
     * @param id Profile 标识，不得为 {@code null}
     * @return 对应的 Profile；不存在时为空
     */
    Optional<UserProfile> findById(UserProfileId id);
}
