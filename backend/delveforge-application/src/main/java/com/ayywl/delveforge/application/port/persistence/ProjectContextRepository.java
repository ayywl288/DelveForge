package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.application.project.ProjectFlowContext;

import java.util.Optional;
import java.util.List;

/** 仅保存导航关联；CAS 拒绝过期更新，不写入任何业务 Aggregate。 */
public interface ProjectContextRepository {
    Optional<ProjectFlowContext> findById(String id);

    void create(ProjectFlowContext context, Integer expectedConfirmedRevision);

    void update(ProjectFlowContext candidate, long expectedVersion);

    /** 已有 Plan 最多被一个显式上下文认领；并发恢复返回同一个身份。 */
    ProjectFlowContext adoptPlan(ProjectFlowContext context);

    default List<ProjectFlowContext> findByFlow(String flowId) {
        return List.of();
    }

    default void createSuccessor(ProjectFlowContext predecessor, ProjectFlowContext candidate) {
        throw new UnsupportedOperationException("Atomic planning round creation is required");
    }
}
