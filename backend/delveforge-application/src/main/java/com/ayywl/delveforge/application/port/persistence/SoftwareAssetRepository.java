package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import java.util.Optional;

/**
 * Software Asset 的持久化边界。
 *
 * <p>本接口由 Application 拥有、由 Infrastructure 实现（RULE-ARCH-003、RULE-ARCH-004）。
 * Application 只依赖本抽象，不得依赖 SQLite、MyBatis-Plus、Mapper、持久化 Entity
 * 等任何具体技术类型。
 *
 * <p>本 Port 不是通用 Repository：方法集合只覆盖当前 Use Case 的最小需求
 * （登记后按标识读回）。后续 Use Case 出现新的持久化需求时再按需求扩展。
 *
 * <h2>持久化的是元数据，不是代码</h2>
 *
 * <p>本 Port 只负责保存与恢复 Software Asset 的领域元数据
 * （DOMAIN_MODEL.md §10.2：身份、来源、定位信息、权限与授权相关元数据）。
 * 资产的源代码本身由 Git / Filesystem 保存，不属于本 Port：
 *
 * <pre>
 * 不判断 location 是否真实存在
 * 不判断该 location 是否是 Git Repository
 * 不执行 Git / Filesystem / Shell 操作（RULE-ARCH-009）
 * </pre>
 *
 * <p>因此登记一个 Software Asset 不需要任何 Workspace 能力，
 * 只读访问 Repository 属于后续 Repository Analysis 流程。
 */
public interface SoftwareAssetRepository {

    /**
     * 保存 Software Asset 的当前元数据。
     *
     * <p>按标识覆盖：同一 {@code id} 的重复保存更新已有记录，不产生第二行。
     *
     * <p>以下都是当前的实现选择，不是 DOMAIN_MODEL.md 的规定：§10.2 只要求保存资产的
     * 元数据，没有规定覆盖策略、去重规则或历史保留策略，§6 也没有为 Software Asset
     * 定义状态机——但「文档没有定义状态机」并不等于「文档已经决定了覆盖保存」。
     *
     * <pre>
     * 按 id 覆盖      本 Task 采用覆盖保存，因此 save 的语义是「保存当前元数据」
     * 不做去重        领域模型没有定义「同一 location 只能对应一个 Software Asset」，
     *                 因此 location 不参与判断，两个不同 id 指向同一 location 都允许保存
     * 不保留历史      本 Task 不保存 Software Asset 的历史版本，因此没有 revision 可查
     * </pre>
     *
     * <p>如果将来需要保留授权变化的历史，应当改变本 Port 的语义并新增迁移，
     * 而不是让现有实现默默承担两种含义。
     *
     * <p>本方法不校验领域规则：资产是否合法由 {@code SoftwareAsset} Aggregate 决定
     * （RULE-DOM-002），Adapter 只负责把它的当前状态写下去。
     *
     * @param asset 待保存的资产，不得为 {@code null}
     */
    void save(SoftwareAsset asset);

    /**
     * 按标识查找 Software Asset。
     *
     * @param id 资产标识，不得为 {@code null}
     * @return 对应的资产；不存在时为空
     */
    Optional<SoftwareAsset> findById(SoftwareAssetId id);
}
