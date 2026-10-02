package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 定向源码的候选流：某个 {@code analyzedRevision} 上一串**有序**的源码候选。
 *
 * <pre>
 * 分层 Scout（Region 导航 + 分支本地 File Scout）
 *         ↓  保序轮转合并
 * RepositoryTargetedSourceCandidates（有序、去重）
 *         ↓  交给既有的 RepositoryReadPlanner
 * </pre>
 *
 * <h2>为什么不是 {@code RepositoryInspectionPlan}</h2>
 *
 * <p>{@code RepositoryInspectionPlan} 描述的是**模型给出的查看意图**：每个区域带一个 label，
 * 表达「这一组文件打算用来看什么」，label 本身就是模型对仓库的编排提示。
 * 分层 Scout 合并出的候选流没有这样的标签——它的顺序来自「区域优先级 × 分支内优先级」的
 * 保序轮转，是 Application 自己算出来的，不是模型对文件分组的表达。
 *
 * <p>把候选流塞进一个带 label 的查看区域，等于替模型宣称了一个它从未表达过的分组。
 * 因此这里用另一个类型，把「谁产生的」这件事留在类型上。
 *
 * <h2>顺序就是**考虑顺序**</h2>
 *
 * <p>规划器逐条按这个顺序考虑候选：放得下就选，放不下或重复就跳过并继续往下。
 * 顺序不是排序建议，而是取舍依据——这正是 ADR-0005 要求「保序轮转的顺序必须成为
 * 定向源码的考虑顺序」的那一条。
 *
 * <h2>它不包含文件内容，也不触发读取</h2>
 *
 * <p>每一项都是列目录得到的描述符。本类型不持有 Workspace 能力，因此没有读取入口。
 *
 * <h2>不变量</h2>
 *
 * <p>候选不得为 {@code null}、不得重复，且同一个**相对路径**也不得出现两次——
 * 与 {@link RepositoryReadPlan} 同一条口径：ADR-0004 把「已解析 revision + 提交树相对路径」
 * 定义为稳定技术身份，两个不同的编号指向同一个路径时，物理上仍然是同一个文件。
 */
public final class RepositoryTargetedSourceCandidates {

    private final String analyzedRevision;

    private final List<RepositoryMapEntry> orderedCandidates;

    private RepositoryTargetedSourceCandidates(String analyzedRevision,
                                               List<RepositoryMapEntry> orderedCandidates) {
        this.analyzedRevision = analyzedRevision;
        this.orderedCandidates = orderedCandidates;
    }

    /**
     * 建立一条候选流。
     *
     * @param analyzedRevision  本次分析固定的 commit id，不得为空白
     * @param orderedCandidates 有序的源码候选，不得为 {@code null} 或空，
     *                          元素不得为 {@code null}，编号与相对路径都不得重复
     * @throws IllegalArgumentException 参数不满足上述约束
     */
    public static RepositoryTargetedSourceCandidates of(
            String analyzedRevision, List<RepositoryMapEntry> orderedCandidates) {
        if (analyzedRevision == null || analyzedRevision.isBlank()) {
            throw new IllegalArgumentException(
                    "RepositoryTargetedSourceCandidates 必须指定 analyzedRevision");
        }
        if (orderedCandidates == null || orderedCandidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "RepositoryTargetedSourceCandidates 的 orderedCandidates 不能为空");
        }

        Set<RepositoryFileReference> seenReferences = new LinkedHashSet<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        List<RepositoryMapEntry> copy = new ArrayList<>(orderedCandidates.size());
        for (RepositoryMapEntry candidate : orderedCandidates) {
            if (candidate == null) {
                throw new IllegalArgumentException(
                        "RepositoryTargetedSourceCandidates 的 orderedCandidates 不能包含 null");
            }
            if (!seenReferences.add(candidate.reference())) {
                throw new IllegalArgumentException(
                        "定向源码候选不得重复: " + candidate.relativePath());
            }
            if (!seenPaths.add(candidate.relativePath())) {
                throw new IllegalArgumentException(
                        "同一个路径不得作为两个候选出现（不同编号也不行）: "
                                + candidate.relativePath());
            }
            copy.add(candidate);
        }
        return new RepositoryTargetedSourceCandidates(
                analyzedRevision, List.copyOf(copy));
    }

    /** 本次分析固定的 commit id。 */
    public String analyzedRevision() {
        return analyzedRevision;
    }

    /** 有序的源码候选，顺序即规划器的考虑顺序。 */
    public List<RepositoryMapEntry> orderedCandidates() {
        return orderedCandidates;
    }

    public int size() {
        return orderedCandidates.size();
    }
}
