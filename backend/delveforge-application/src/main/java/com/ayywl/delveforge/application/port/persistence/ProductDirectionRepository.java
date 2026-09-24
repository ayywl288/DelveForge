package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import java.util.Optional;

/**
 * Product Direction 的持久化边界。
 *
 * <p>本接口由 Application 拥有、由 Infrastructure 实现（RULE-ARCH-003、RULE-ARCH-004）。
 * Application 只依赖本抽象，不得依赖 SQLite、MyBatis-Plus、Mapper、持久化 Entity
 * 等任何具体技术类型。
 *
 * <p>本 Port 不是通用 Repository：方法集合只覆盖当前的最小需求（保存一个方向、
 * 按标识读回）。按 userProfile 或 repositoryProfile 查列表、查当前 SELECTED、
 * 批量保存一次发现的 3–5 个方向等查询，在出现真实消费者时再补，而不是提前预留。
 *
 * <h2>Product Direction 是可更新的 Entity</h2>
 *
 * <p>与 {@link RepositoryProfileRepository} 不同：Repository Profile 是一次分析结果的
 * 快照，因此只允许写入一次；Product Direction 拥有生命周期（§6.2），同一个标识之后会
 * 因为合法领域行为从 CANDIDATE 变为 SELECTED、REJECTED 或 SUPERSEDED，
 * 因此本 Port 的 {@code save} 按标识覆盖，不是「只写一次」。
 *
 * <p>但覆盖的范围是有限的：
 *
 * <pre>
 * 可以更新    status —— 生命周期状态变化是这条方向自己的领域行为
 * 不得改写    最初生成该方向时的 discovery basis、recommendation content、
 *            candidate assets 与 Evidence（§10.5、RULE-DOM-007）
 * </pre>
 *
 * <p>后者不是靠调用方自觉：同一个标识再次保存时，若内容包括分析来源与已保存的
 * 版本不一致，保存会被拒绝（{@link ProductDirectionContentConflictException}），
 * 而不是静默改写历史，也不是静默忽略这次传入的内容。
 *
 * <p>本 Port 不判断方向内容是否正确或完整：方向是否合法由
 * {@code ProductDirection} Aggregate 决定（RULE-DOM-002），本 Port 只负责把它的
 * 当前状态写下去、按存储重建回来。
 */
public interface ProductDirectionRepository {

    /**
     * 保存一个 Product Direction 的当前状态。
     *
     * <p>该标识尚未保存时写入整条方向；已经保存过时更新其生命周期状态。
     * 状态更新不得改写最初生成该方向时的 discovery basis、recommendation content、
     * candidate assets 与 Evidence（§10.5）。
     *
     * <p>整个写入必须是一个整体：身份行与内容行不能出现只写入一部分的中间状态。
     *
     * <p>REJECTED / SUPERSEDED 的方向同样会被保存下来，不因为状态而被删除：
     * 历史方向仍需保留（§10.5、RULE-DOM-007）。本 Port 不提供删除操作。
     *
     * @param productDirection 待保存的方向，不得为 {@code null}
     * @throws ProductDirectionContentConflictException 该标识已经保存过，
     *         且本次传入的内容与已保存的 discovery basis / recommendation content /
     *         candidate assets / Evidence 不一致
     */
    void save(ProductDirection productDirection);

    /**
     * 按标识查找 Product Direction。
     *
     * <p>恢复出来的方向包含它保存时的完整 discovery basis、recommendation content、
     * candidate assets、Evidence 与 lifecycle status。
     *
     * @param id 方向标识，不得为 {@code null}
     * @return 对应的方向；不存在时为空
     */
    Optional<ProductDirection> findById(ProductDirectionId id);
}
