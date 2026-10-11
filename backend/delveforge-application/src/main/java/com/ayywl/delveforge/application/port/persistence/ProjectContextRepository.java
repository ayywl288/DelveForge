package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.application.project.ProjectFlowContext;
import com.ayywl.delveforge.application.project.ProjectVisibility;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    default ProjectVisibility visibility(String key) {
        return new ProjectVisibility(key, 0, null);
    }

    /** 原子 CAS 与实际 Step / Candidate 检查；仅改变导航可见性。 */
    default ProjectVisibility changeVisibility(String key, long expectedVersion, boolean deleted, Instant now) {
        throw new UnsupportedOperationException("Atomic navigation visibility change is required");
    }
}
