package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.application.project.ProjectScopeBasis;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.repositoryprofile.RepositoryProfileId;
import com.ayywl.delveforge.domain.user.UserProfileId;

import java.util.List;
import java.util.Optional;

/**
 * Direction 的身份、内容与不可变流程归属。批次保存和生命周期转换必须整体提交。
 * save/saveAll 仅允许新建或内容、状态一致的重复保存；状态变化只能通过带依据的 CAS 转换。
 * 无项目参数的 API 使用 standalone 作用域，不认领归属未知的历史记录。
 */
public interface ProductDirectionRepository {
    void save(ProductDirection productDirection);

    void saveAll(List<ProductDirection> productDirections);

    /** 按列表顺序 CAS，只修改状态；任一冲突整批回滚，不改内容或归属。 */
    void saveTransitions(List<ProductDirectionTransition> transitions);

    Optional<ProductDirection> findById(ProductDirectionId id);

    /** 兼容单流程 API 的 standalone 查询；正常选择必须使用 findCurrentSelectedFor。 */
    Optional<ProductDirection> findCurrentSelected();

    /** 只按目标方向已保存的归属查询，不按画像、路径或浏览器列表推断。 */
    default Optional<ProductDirection> findCurrentSelectedFor(ProductDirectionId targetId) {
        return findCurrentSelected();
    }

    default List<ProductDirection> findByFlow(String flowId) {
        return List.of();
    }

    default void lockDirectionScope(ProductDirectionId id,
            ProjectScopeBasis project) {
        requireDirectionScope(id, project);
    }

    default String flowId(ProductDirectionId id) {
        return "standalone";
    }

    default void requireDirectionScope(ProductDirectionId id,
            ProjectScopeBasis project) {
        if (project != null) {
            throw new UnsupportedOperationException("Persisted project ownership is required");
        }
    }

    default void requireDiscoveryScope(ProjectScopeBasis project,
            UserProfileId userId,
            List<RepositoryProfileId> repositoryIds) {
        if (project != null) {
            throw new UnsupportedOperationException("Persisted project ownership is required");
        }
    }

    /** 同版上下文、画像与候选归属在同一事务中提交。 */
    default void saveAllInProject(List<ProductDirection> values,
            ProjectScopeBasis project) {
        if (project != null) {
            throw new UnsupportedOperationException("Atomic project discovery is required");
        }
        saveAll(values);
    }

    default void requirePlanningScope(ProductDirectionId id,
            ProjectScopeBasis project,
            RepositoryProfileId repositoryId) {
        requireDirectionScope(id, project);
    }
}
