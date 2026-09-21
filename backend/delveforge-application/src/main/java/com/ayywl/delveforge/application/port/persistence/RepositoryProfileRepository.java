package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfile;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import java.util.Optional;

/**
 * Repository Profile 的持久化边界。
 *
 * <p>本接口由 Application 拥有、由 Infrastructure 实现（RULE-ARCH-003、RULE-ARCH-004）。
 * Application 只依赖本抽象，不得依赖 SQLite、MyBatis-Plus、Mapper、持久化 Entity
 * 等任何具体技术类型。
 *
 * <p>本 Port 不是通用 Repository：方法集合只覆盖当前 Use Case 的最小需求
 * （保存一次分析结果、按标识读回）。按 assetId 查列表、查最近一条等查询在出现真实
 * 消费者时再补，而不是提前预留。
 *
 * <h2>Snapshot 不是可更新的记录</h2>
 *
 * <p>Repository Profile 是一次分析结果的快照，因此本 Port 只提供「写入一次」与
 * 「按标识读回」，没有 update：
 *
 * <pre>
 * 同一个 Software Asset @ 新的 revision
 *     → 新的 Repository Profile（新的标识）
 *     而不是修改已有 Profile
 * </pre>
 *
 * <p>同一标识的重复保存会被拒绝，而不是静默覆盖（DOMAIN_MODEL.md §10.4、INV-D04）：
 * 已有快照可能仍被历史 Product Direction 或 Evolution Plan 引用。
 * 这一点与本项目 {@code SoftwareAssetRepository} 的按标识覆盖不同——Software Asset
 * 保存的是可能变化的资产元数据，Repository Profile 保存的是不可改写的分析快照，
 * 两者的写入语义本就不同，不应互相复制。
 *
 * <p>本 Port 不判断分析内容是否正确或完整：Profile 是否合法由
 * {@code RepositoryProfile} Aggregate 决定（RULE-DOM-002）。
 */
public interface RepositoryProfileRepository {

    /**
     * 保存一次分析结果的快照。
     *
     * <p>按标识写入一次：该标识尚未保存时写入；已经保存过则拒绝。
     *
     * <p>整个写入必须是一个整体：Profile 的身份与内容不能出现只写入一部分的中间状态。
     *
     * @param profile 待保存的快照，不得为 {@code null}
     * @throws RepositoryProfileAlreadyExistsException 该标识已经保存过
     */
    void save(RepositoryProfile profile);

    /**
     * 按标识查找 Repository Profile。
     *
     * @param id Profile 标识，不得为 {@code null}
     * @return 对应的快照；不存在时为空
     */
    Optional<RepositoryProfile> findById(RepositoryProfileId id);
}
