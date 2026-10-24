package com.ayywl.delveforge.application.port.workspace;

/** 只读且不执行项目代码的 pinned Git 兼容性检查，不授予演化权限。 */
public interface RepositoryCompatibilityPort {
    RepositoryCompatibility assess(WorkspaceRef repository, String revision);
}
