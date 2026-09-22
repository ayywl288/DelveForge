package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 从 Workspace 读出一次 Repository 分析的材料。
 *
 * <pre>
 * listEntries(ref, revision, "", maxDepth)
 *         ↓  挑选文件（策略见 RepositoryAnalysisMaterialPolicy）
 * readFile(ref, revision, path)
 *         ↓
 * RepositorySourceFile(path, content)
 * </pre>
 *
 * <h2>路径来自真实读取链路</h2>
 *
 * <p>每个材料的路径都取自 {@link WorkspaceReadPort#listEntries} 返回的条目，
 * 内容来自对**同一个路径**的 {@link WorkspaceReadPort#readFile}。
 * 本类不构造、不拼接、不猜测任何路径——因此后续 Evidence 的 {@code sourceRef}
 * 指向的是真实读过的文件，而不是看起来合理的位置。
 *
 * <h2>Revision 只作为参数传递</h2>
 *
 * <p>本类不解析 HEAD，也不持有 revision：调用方解析一次之后传进来，所有读取都带着它。
 * 这样即使分析期间源 Repository 的 HEAD 移动，本次材料也不会混入其它 revision 的内容。
 */
public final class RepositoryAnalysisMaterialCollector {

    private final WorkspaceReadPort workspace;
    private final RepositoryAnalysisMaterialPolicy policy;

    public RepositoryAnalysisMaterialCollector(
            WorkspaceReadPort workspace, RepositoryAnalysisMaterialPolicy policy) {
        if (workspace == null) {
            throw new IllegalArgumentException("MaterialCollector 必须指定 workspace");
        }
        if (policy == null) {
            throw new IllegalArgumentException("MaterialCollector 必须指定 policy");
        }
        this.workspace = workspace;
        this.policy = policy;
    }

    /**
     * 读取该 revision 下符合策略的材料。
     *
     * <p>大小按字节计（来自列目录结果）。字节数不代表 token 数，这里只用它给出一个确定的边界。
     *
     * @param workspace 目标 Repository
     * @param revision  本次分析固定的 commit id
     * @return 材料，按相对路径升序；策略下没有任何文件可读时为空列表
     */
    public List<RepositorySourceFile> collect(WorkspaceRef workspace, String revision) {
        List<WorkspaceEntry> entries =
                this.workspace.listEntries(workspace, revision, "", policy.maxDepth());

        List<WorkspaceEntry> candidates = new ArrayList<>();
        for (WorkspaceEntry entry : entries) {
            if (entry.directory() || isExcluded(entry.relativePath())) {
                continue;
            }
            candidates.add(entry);
        }
        // Port 不保证顺序；这里显式排序，使同一次分析的结果可复现
        candidates.sort(Comparator.comparing(WorkspaceEntry::relativePath));

        List<RepositorySourceFile> material = readWithinPolicy(workspace, revision, candidates);
        if (material.isEmpty()) {
            // 空材料不是「分析出空结论」，而是根本无法分析：让下游拿着空材料去问模型，
            // 得到的不是分析而是编造。用可判别的语义失败，而不是让提取层抛通用参数异常——
            // 那会被接口层翻译成「请求不合法」，暗示调用方改请求就能成功。
            throw new RepositoryNotAnalyzableException(
                    "该 Repository 在选材策略下没有任何可分析的文件: "
                            + workspace + " @ " + revision);
        }
        return material;
    }

    /**
     * 按策略读取候选文件。
     *
     * <p>取舍发生在读取**之前**：文件大小来自列目录的结果，因此超大文件不会被读进来，
     * 大量超限文件也不会被逐个读完。上限约束的是实际读取量，而不只是进入材料的内容量——
     * 否则一个巨大的文件仍然会先被完整加载，再被判断为「不该读」。
     *
     * <p>读取因此被 maxFiles 与累计大小界住：读的每个文件都是准备收进材料的。
     * 读后核对一次实际内容长度，是为了在大小不可信时仍不把超大内容送进材料；
     * 正常情况下大小由 git 给出，这一步不会触发。
     */
    private List<RepositorySourceFile> readWithinPolicy(
            WorkspaceRef workspace, String revision, List<WorkspaceEntry> candidates) {

        List<RepositorySourceFile> material = new ArrayList<>();
        long collectedBytes = 0;

        for (WorkspaceEntry candidate : candidates) {
            if (material.size() >= policy.maxFiles()) {
                break;
            }
            if (candidate.size() > policy.maxFileBytes()) {
                // 跳过而不是截断：截断过的文件会让模型基于半份内容下结论
                continue;
            }
            if (collectedBytes + candidate.size() > policy.maxTotalBytes()) {
                break;
            }

            String content = this.workspace.readFile(workspace, revision, candidate.relativePath());
            if (content.length() > policy.maxFileBytes()) {
                continue;
            }

            material.add(new RepositorySourceFile(candidate.relativePath(), content));
            collectedBytes += candidate.size();
        }
        return List.copyOf(material);
    }

    /**
     * 该路径是否落在策略排除的目录或文件类型内。
     *
     * <p>目录按路径段匹配（任意一层出现即排除），扩展名按最后一个点之后的部分匹配。
     * 这是给模型筛材料的启发式，不是对文件类型的准确判断——真正的判断只有读进来才知道，
     * 那时已经花掉了读取成本，因此这里用名字做粗筛。
     */
    private boolean isExcluded(String relativePath) {
        String[] segments = relativePath.split("/");
        for (int index = 0; index < segments.length - 1; index++) {
            if (policy.excludedDirectories().contains(segments[index])) {
                return true;
            }
        }

        String fileName = segments[segments.length - 1];
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return policy.excludedExtensions().contains(
                fileName.substring(dot).toLowerCase(Locale.ROOT));
    }
}
