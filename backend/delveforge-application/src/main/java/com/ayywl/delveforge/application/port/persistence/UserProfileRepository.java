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
 * <p>当前范围只包含 User Profile 的当前状态。历史 revision 的保存与重新获取
 * （DOMAIN_MODEL.md §10.3）不在本 Port 当前表达范围内。
 */
public interface UserProfileRepository {

    /**
     * 保存 User Profile 的当前状态；已存在同一 {@link UserProfileId} 时覆盖。
     *
     * <p>本方法不判断 Profile 是否发生了领域意义上的变化，
     * revision 由 {@code UserProfile} Aggregate 自己维护。
     *
     * @param profile 待保存的 Profile，不得为 {@code null}
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
