package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 执行一份可信的读取计划：按计划把真实文件读进来，形成分析材料。
 *
 * <pre>
 * RepositoryReadPlan + WorkspaceRef
 *         ↓  逐条 readFile（同一 analyzedRevision）
 * 执行期尺寸复核（依据**真实内容**的 UTF-8 字节数）
 *         ↓
 * RepositoryReadResult（材料 + 执行期诊断）
 * </pre>
 *
 * <h2>它没有选择权</h2>
 *
 * <p>本类只执行计划：读哪些文件、按什么顺序，全部由 {@link RepositoryReadPlan} 决定。
 * 它不新增文件、不替换文件、不重新规划。路径只能来自
 * {@link RepositoryMapEntry#relativePath()}——也就是列目录时得到的真实路径，
 * 而不是模型输出的任何字符串。
 *
 * <h2>为什么要用真实内容再量一次</h2>
 *
 * <p>规划依据的是 Git blob metadata 里的字节数，而它不一定等于实际读到的内容的字节数。
 * 因此这里独立地按实际内容复核一次同一条通道的 {@code maxFileBytes} 与 {@code maxTotalBytes}：
 *
 * <pre>
 * 实际内容超过单文件上限       剔除，记诊断，继续处理后面的文件
 * 加入它会超出本通道剩余总量   剔除，记诊断，继续处理后面的文件
 * </pre>
 *
 * <p>剔除而不截断：半个文件会让模型基于缺失的部分下结论，而系统并不知道缺了什么。
 *
 * <p>**文件数量上限不在这里重新判定**：它在规划时已经定死，而执行只会让数量变少，
 * 不会变多。因此本类不读 {@code maxFiles}。
 *
 * <p>两条通道各自记账，不会互相借用预算：一条通道剩余的总量不会被另一条用掉。
 *
 * <h2>读取失败不是「跳过」</h2>
 *
 * <p>尺寸问题是**可以被安全剔除**的候选；读取失败不是——它意味着环境层面出了问题
 * （读不到一个在确定 revision 上确实存在的文件）。因此读取失败直接向上抛
 * {@link com.ayywl.delveforge.application.port.workspace.WorkspaceException}，
 * 让整次分析失败，而不是悄悄少读几个文件还当作分析成功。
 *
 * <p>本类不持有修改能力，也不接触模型。
 */
public final class RepositoryReadExecutor {

    private final WorkspaceReadPort workspace;

    private final RepositoryMaterialBudget foundationBudget;

    private final RepositoryMaterialBudget targetedSourceBudget;

    public RepositoryReadExecutor(WorkspaceReadPort workspace,
                                  RepositoryMaterialBudget foundationBudget,
                                  RepositoryMaterialBudget targetedSourceBudget) {
        if (workspace == null) {
            throw new IllegalArgumentException("RepositoryReadExecutor 必须指定 workspace");
        }
        if (foundationBudget == null) {
            throw new IllegalArgumentException(
                    "RepositoryReadExecutor 必须指定 foundationBudget");
        }
        if (targetedSourceBudget == null) {
            throw new IllegalArgumentException(
                    "RepositoryReadExecutor 必须指定 targetedSourceBudget");
        }
        this.workspace = workspace;
        this.foundationBudget = foundationBudget;
        this.targetedSourceBudget = targetedSourceBudget;
    }

    /**
     * 按计划读取文件。
     *
     * @param plan    已经确定的读取计划，不得为 {@code null}
     * @param workspace 目标 Repository，不得为 {@code null}
     * @return 真正可用的材料与执行期诊断
     * @throws IllegalArgumentException 任一参数为 {@code null}
     * @throws com.ayywl.delveforge.application.port.workspace.WorkspaceException
     *                                  某个计划内的文件读取失败
     */
    public RepositoryReadResult execute(RepositoryReadPlan plan, WorkspaceRef workspace) {
        if (plan == null) {
            throw new IllegalArgumentException("RepositoryReadExecutor 必须指定 plan");
        }
        if (workspace == null) {
            throw new IllegalArgumentException("RepositoryReadExecutor 必须指定 workspace");
        }

        LaneResult foundation = readLane(
                workspace, plan.analyzedRevision(), plan.foundationEntries(), foundationBudget);
        LaneResult targeted = readLane(
                workspace, plan.analyzedRevision(), plan.targetedSourceEntries(),
                targetedSourceBudget);

        List<RepositorySourceFile> material =
                new ArrayList<>(foundation.material().size() + targeted.material().size());
        material.addAll(foundation.material());
        material.addAll(targeted.material());

        List<SkippedReadCandidate> skipped =
                new ArrayList<>(foundation.skipped().size() + targeted.skipped().size());
        skipped.addAll(foundation.skipped());
        skipped.addAll(targeted.skipped());

        return new RepositoryReadResult(material, skipped);
    }

    /**
     * 读一条通道，并按它自己的预算复核实际内容。
     *
     * <p>顺序就是计划的顺序——那是规划时定下的优先级。
     */
    private LaneResult readLane(WorkspaceRef workspace,
                                String analyzedRevision,
                                List<RepositoryMapEntry> entries,
                                RepositoryMaterialBudget budget) {

        List<RepositorySourceFile> material = new ArrayList<>(entries.size());
        List<SkippedReadCandidate> skipped = new ArrayList<>();
        long acceptedBytes = 0;

        for (RepositoryMapEntry entry : entries) {
            String content = this.workspace.readFile(
                    workspace, analyzedRevision, entry.relativePath());

            long actualBytes = utf8Length(content);
            if (actualBytes > budget.maxFileBytes()) {
                skipped.add(new SkippedReadCandidate(
                        entry, RepositoryReadSkipReason.SELECTED_BUT_TOO_LARGE));
                continue;
            }
            // long 累计：两条通道的预算都是 int，加上一个 int 长度不会溢出，
            // 因此这里的比较不会因为溢出而放行一个超预算的文件。
            if (acceptedBytes + actualBytes > budget.maxTotalBytes()) {
                skipped.add(new SkippedReadCandidate(
                        entry, RepositoryReadSkipReason.EXCEEDS_REMAINING_TOTAL_BYTES));
                continue;
            }

            material.add(new RepositorySourceFile(entry.relativePath(), content));
            acceptedBytes += actualBytes;
        }
        return new LaneResult(List.copyOf(material), List.copyOf(skipped));
    }

    private static long utf8Length(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 一条通道的读取结果。 */
    private record LaneResult(List<RepositorySourceFile> material,
                              List<SkippedReadCandidate> skipped) {
    }
}
