package com.ayywl.delveforge.application.port.workspace;

import java.util.List;

/**
 * Workspace Gateway 的只读能力。
 *
 * <p>只读流程（如 Repository Analysis）只能依赖本接口，不得获得任何代码修改能力
 * （RULE-ARCH-010）。该约束通过 Port 拆分在类型层面保证：只读流程的依赖中
 * 不存在 {@link WorkspaceMutationPort}。
 *
 * <p>所有路径参数都是相对于 Workspace 根目录的相对路径，且不得越过根目录。
 *
 * <p>实现由 Infrastructure 层提供（例如后续的 {@code GitWorkspaceAdapter}）。
 */
public interface WorkspaceReadPort {

    /**
     * 列出指定目录下的内容。
     *
     * @param workspace    目标 Workspace
     * @param relativePath 相对于 Workspace 根目录的起始路径，{@code ""} 表示根目录本身
     * @param maxDepth     从起始路径开始的最大层级，{@code 1} 表示只返回直接子项，必须大于 0
     * @return 层级内的内容项，顺序不保证
     */
    List<WorkspaceEntry> listEntries(WorkspaceRef workspace, String relativePath, int maxDepth);

    /**
     * 读取一个文件的内容。
     *
     * @param workspace    目标 Workspace
     * @param relativePath 相对于 Workspace 根目录的文件路径
     * @return 文件文本内容
     */
    String readFile(WorkspaceRef workspace, String relativePath);

    /**
     * 读取 Workspace 当前 HEAD 对应的 revision 标识。
     *
     * <p>该值是 Workspace 的当前软件状态，不等同于领域语义中的
     * {@code baselineRevision} / {@code lastVerifiedRevision}。
     *
     * @param workspace 目标 Workspace
     * @return revision 标识
     */
    String headRevision(WorkspaceRef workspace);
}
