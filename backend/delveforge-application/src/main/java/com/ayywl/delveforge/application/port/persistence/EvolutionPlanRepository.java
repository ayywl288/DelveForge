package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.application.project.ProjectScopeBasis;
import com.ayywl.delveforge.domain.asset.SoftwareAsset;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.evolution.EvolutionPlan;
import com.ayywl.delveforge.domain.evolution.EvolutionPlanId;

import java.util.List;
import java.util.Optional;

/**
 * 规划结果作为一个 Aggregate 原子插入一次，不能覆盖历史。
 * 提交时必须再次确认 Direction 仍为 SELECTED；失败不得留下部分内容、Step 或 Evidence。
 */
public interface EvolutionPlanRepository {
    void save(EvolutionPlan plan);

    /** 与本次输入相同的资产授权仍有效时原子提交；不能退化为无条件 save。 */
    default void saveAtAssetAuthorization(EvolutionPlan plan, SoftwareAsset expectedAsset) {
        throw new UnsupportedOperationException("Atomic authorization check is required");
    }

    default void saveInProject(EvolutionPlan plan, SoftwareAsset expectedAsset,
            ProjectScopeBasis project) {
        if (project != null) {
            throw new UnsupportedOperationException("Atomic project planning is required");
        }
        saveAtAssetAuthorization(plan, expectedAsset);
    }

    Optional<EvolutionPlan> findById(EvolutionPlanId id);
    List<EvolutionPlan> findByProductDirectionId(ProductDirectionId id);

    /** 具有持久化归属的实现必须在外部操作之前核对 Plan / Direction 绑定。 */
    default void requireFlowBinding(EvolutionPlan plan) {
        // 无独立持久化归属的内存实现使用单一作用域。
    }
}
