package com.ayywl.delveforge.application.port.workspace;

/**
 * Workspace Gateway 的代码修改能力。
 *
 * <p>该能力单独成 Port，使只读流程无法通过类型系统获得修改权限（RULE-ARCH-010）。
 *
 * <p>在 MVP 中，Evolution Execution 是唯一允许请求该能力的业务流程（RULE-ARCH-011）。
 * 即使在该流程内：
 *
 * <pre>
 * write capability
 *     ≠
 * unrestricted filesystem access
 * </pre>
 *
 * <p>调用方必须传入当前已授权的 Evolution Step 所绑定 Working Copy 对应的
 * {@link WorkspaceRef}，不得对原始 Software Asset 调用本接口（RULE-DOM-005）。
 * 写能力不等于无限制文件系统访问：具体作用范围由 Adapter 依据该句柄限定。
 *
 * <p>实现由 Infrastructure 层提供。
 */
public interface WorkspaceMutationPort {

    /**
     * 把内容写入 Workspace 中的一个文件，已存在时覆盖。
     *
     * @param workspace    目标 Workspace，必须是当前 Evolution Step 绑定的 Working Copy
     * @param relativePath 相对于 Workspace 根目录的文件路径
     * @param content      文件内容
     */
    void writeFile(WorkspaceRef workspace, String relativePath, String content);
}
