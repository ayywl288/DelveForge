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
 * <p>实现由 Infrastructure 层提供（{@code GitWorkspaceAdapter}）。
 *
 * <h2>读取的内容以哪个版本为准</h2>
 *
 * <p>读取类操作（{@link #listEntries}、{@link #readFile}）都显式接受一个 revision，
 * 并只返回该 revision 对应的已提交内容，而不是工作区磁盘上的当前内容：
 * 未提交修改、untracked 文件与被忽略的文件都不进入读取结果，
 * 也不会被当成新的 revision。
 *
 * <p>revision 必须是已经解析出来的完整 commit id（由 {@link #headRevision} 返回，
 * 或此前记录下来的某个 revision）。<b>可移动的引用名（如 {@code HEAD} 或分支名）
 * 不被接受</b>：它们指向的 commit 会在两次调用之间变化，那样
 * 「{@code RepositoryProfile.analyzedRevision = abc123}」就可能与 Profile 实际
 * 描述的内容不是同一个版本——一次分析会在不知不觉中混合多个版本。
 *
 * <p>把 revision 显式传给每个读取操作，正是为了让一次分析固定在一个版本上：
 * 调用方先取 {@link #headRevision}，之后所有读取都带着同一个 id
 * （DOMAIN_MODEL.md §8.3 的分析输入包含 Repository Revision，
 * ROADMAP.md §8.4：MVP 默认仅基于已提交的 Git Revision 建立 Repository Profile
 * 与 Working Copy）。
 */
public interface WorkspaceReadPort {

    /**
     * 判断该位置当前是否是一个可读取的本地 Git Repository。
     *
     * <p>用于 Repository Analysis 的前置条件检查（DOMAIN_MODEL.md §8.3：
     * 「MVP 中必须为可访问的本地 Git Repository」）。
     *
     * <p>可读取需要同时满足：位置存在且是一个目录、其 Git 元数据可读取、
     * 并且该位置本身就是 Repository 根目录——位于某个 Repository 内部的子目录
     * 不算一个 Repository，否则「分析的是哪一个位置」会与实际读取的范围不一致。
     *
     * <p>本方法只回答「能不能读」：位置不存在、不可访问或不是 Repository 时
     * 返回 {@code false}，不抛异常。它不判断该 Repository 是否已经有 commit——
     * 空仓库仍然是可读取的 Repository，但 {@link #headRevision} 会明确失败。
     *
     * <p>当前实现只把带工作树的 Repository 视为可读取：bare repository 返回
     * {@code false}（这是当前实现的选择，不是领域模型的规定）。
     *
     * @param workspace 目标 Workspace
     * @return 该位置当前是否可作为一个只读 Repository 使用
     * @throws IllegalArgumentException {@code workspace} 缺失或其取值不是一个可用的位置
     * @throws WorkspaceException       环境故障导致无法执行 Git 操作（例如找不到 git 可执行文件）
     */
    boolean isReadableRepository(WorkspaceRef workspace);

    /**
     * 列出指定 revision 下、指定目录中的内容。
     *
     * <p>列出的是 {@code revision} 这个 commit 中已提交的内容，见类文档的说明。
     *
     * @param workspace    目标 Workspace
     * @param revision     已解析的完整 commit id，不得是可移动的引用名
     * @param relativePath 相对于 Workspace 根目录的起始路径，{@code ""} 表示根目录本身
     * @param maxDepth     从起始路径开始的最大层级，{@code 1} 表示只返回直接子项，必须大于 0
     * @return 层级内的内容项，按相对路径升序
     * @throws IllegalArgumentException {@code revision} 不是该 Repository 中可解析的完整 commit id，
     *                                  或 {@code relativePath} 不是安全的相对路径，
     *                                  或 {@code maxDepth} 不大于 0
     * @throws WorkspaceException       该位置不是可读取的 Repository，或列出失败
     */
    List<WorkspaceEntry> listEntries(
            WorkspaceRef workspace, String revision, String relativePath, int maxDepth);

    /**
     * 读取指定 revision 下一个文件的内容。
     *
     * <p>读取的是 {@code revision} 这个 commit 中该文件已提交的内容，见类文档的说明。
     *
     * @param workspace    目标 Workspace
     * @param revision     已解析的完整 commit id，不得是可移动的引用名
     * @param relativePath 相对于 Workspace 根目录的文件路径
     * @return 文件文本内容
     * @throws IllegalArgumentException {@code revision} 不是该 Repository 中可解析的完整 commit id，
     *                                  或 {@code relativePath} 不是安全的相对路径
     * @throws WorkspaceException       该位置不是可读取的 Repository，或该文件在该 revision 中不存在 / 不是文件
     */
    String readFile(WorkspaceRef workspace, String revision, String relativePath);

    /**
     * 读取 Workspace 当前 HEAD 对应的 revision 标识。
     *
     * <p>该值是 Workspace 的当前软件状态，不等同于领域语义中的
     * {@code baselineRevision} / {@code lastVerifiedRevision}。
     *
     * <p>Repository Analysis 用它确定本次分析对应的软件状态（§8.3）。
     *
     * @param workspace 目标 Workspace
     * @return revision 标识
     * @throws WorkspaceException 该位置不是可读取的 Repository，
     *                            或无法确定当前已提交的 revision（例如空仓库还没有任何 commit）
     */
    String headRevision(WorkspaceRef workspace);
}
