package com.ayywl.delveforge.application.repositoryanalysis.map;

import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从一个已解析 revision 的完整已提交树建立 {@link RepositoryMap}。
 *
 * <pre>
 * listEntries(ref, revision, "", 整棵树)
 *         ↓  只取文件（丢掉目录项）
 * 按相对路径升序
 *         ↓  按位置分配 RF-1 … RF-n
 * 逐项分类（RepositoryPathClassifier）
 *         ↓
 * RepositoryMap
 * </pre>
 *
 * <h2>全程不读文件内容</h2>
 *
 * <p>本类只调用 {@link WorkspaceReadPort#listEntries}，**从不调用
 * {@link WorkspaceReadPort#readFile}**。这不是优化，而是这个阶段成立的前提：
 * 一旦为了分类去读内容，「让整棵树可见」就会退化成另一次有界采样，Map 也就不再完整。
 *
 * <h2>revision 由调用方给出，本类不解析 HEAD</h2>
 *
 * <p>与 {@code AnalyzeRepositoryUseCase}、{@code RepositoryAnalysisMaterialCollector}
 * 保持同一条规则（M1 复盘 §5.1）：HEAD 只解析一次，之后所有读取都带同一个 commit id。
 * 本类既不解析也不缓存 revision——它把它原样传给 Workspace，并记进 Map。
 *
 * <h2>它在整条链路里的位置</h2>
 *
 * <p>由 {@code RepositoryUnderstanding} 调用，是 Repository Analysis 的第一步（ADR-0004）。
 * M1 的确定性选材策略（{@code RepositoryAnalysisMaterialCollector} /
 * {@code RepositoryAnalysisMaterialPolicy}）已经不在生产链路上。
 *
 * <h2>空树</h2>
 *
 * <p>该 revision 上没有任何已提交文件时返回空 Map，而不是失败：空是一份诚实的描述。
 * 「没有可分析材料时不该去问模型」这条判断属于读取阶段，由
 * {@code RepositoryUnderstanding} 在调用任何 Scout 之前作出。
 */
public final class RepositoryMapBuilder {

    /**
     * 列目录时不设层级限制。
     *
     * <p>{@code listEntries} 的契约要求层级大于 0，这里取 int 上界表示「整棵树」。
     * 与材料收集器不同，这里的不设限不是为了多读几个文件，而是因为 Map 的定义就是完整的树。
     */
    private static final int ENTIRE_TREE = Integer.MAX_VALUE;

    private final WorkspaceReadPort workspace;

    public RepositoryMapBuilder(WorkspaceReadPort workspace) {
        if (workspace == null) {
            throw new IllegalArgumentException("RepositoryMapBuilder 必须指定 workspace");
        }
        this.workspace = workspace;
    }

    /**
     * 建立该 revision 上的 Repository Map。
     *
     * @param workspace 目标 Repository，不得为 {@code null}
     * @param revision  本次分析固定的 commit id，不得为 {@code null}；
     *                  必须是已解析的完整 commit id，不得是 {@code HEAD} 或分支名
     * @return 该 revision 上完整已提交树的描述符集合
     * @throws IllegalArgumentException 参数为 {@code null}，或 revision 不可用
     * @throws com.ayywl.delveforge.application.port.workspace.WorkspaceException
     *                                  该位置不是可读取的 Repository，或列出失败
     */
    public RepositoryMap build(WorkspaceRef workspace, String revision) {
        if (workspace == null) {
            throw new IllegalArgumentException("RepositoryMapBuilder 必须指定 workspace");
        }
        if (revision == null) {
            throw new IllegalArgumentException("RepositoryMapBuilder 必须指定 revision");
        }

        List<WorkspaceEntry> tree = this.workspace.listEntries(workspace, revision, "", ENTIRE_TREE);

        List<WorkspaceEntry> files = new ArrayList<>(tree.size());
        for (WorkspaceEntry entry : tree) {
            if (!entry.directory()) {
                files.add(entry);
            }
        }
        // 显式排序，不依赖 Adapter 的输出顺序：引用编号按位置分配，因此顺序必须是
        // Map 自己确定下来的东西。真实 Adapter 已经按路径升序返回，这里再排一次是为了
        // 让「同一个 revision 得到同一份 Map」这条性质由本类的代码保证，而不是由
        // 某个实现恰好遵守契约来保证。
        files.sort(Comparator.comparing(WorkspaceEntry::relativePath));

        List<RepositoryMapEntry> descriptors = new ArrayList<>(files.size());
        for (int index = 0; index < files.size(); index++) {
            WorkspaceEntry file = files.get(index);
            RepositoryPathClassifier.Classification classification =
                    RepositoryPathClassifier.classify(file.relativePath());
            descriptors.add(new RepositoryMapEntry(
                    RepositoryFileReference.of(index + 1),
                    file.relativePath(),
                    file.size(),
                    classification.language(),
                    classification.materialKind(),
                    classification.roleHints()));
        }

        return RepositoryMap.of(revision, descriptors);
    }
}
