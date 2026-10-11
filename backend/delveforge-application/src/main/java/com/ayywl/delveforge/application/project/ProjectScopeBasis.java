package com.ayywl.delveforge.application.project;

/** 客户端只声明上下文的读取版本，不能指定或改变稳定 flowId。 */
public record ProjectScopeBasis(String contextId, long expectedVersion) {
    public ProjectScopeBasis {
        ProjectFlowContext.requireId(contextId);
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("Project version is required");
        }
    }
}
