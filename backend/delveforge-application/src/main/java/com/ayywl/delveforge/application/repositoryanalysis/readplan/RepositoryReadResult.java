package com.ayywl.delveforge.application.repositoryanalysis.readplan;

import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import java.util.List;

/**
 * 执行一份读取计划之后真正拿到的材料。
 *
 * <pre>
 * material    真正读进来、并通过执行期尺寸复核的文件（相对路径 + 内容）
 * skipped     在执行期被剔除的候选与原因（诊断，不参与分析）
 * </pre>
 *
 * <h2>它比计划少，不会比计划多</h2>
 *
 * <p>执行只会**剔除**：计划里已经通过的那些约束在执行期被重新核对一次（因为计划依据的是
 * blob metadata，而真实内容可能更大），不满足的候选在这里被剔除。执行阶段不会新增文件、
 * 不会替换成别的文件、也不会回头重新规划——文件数量的上限已经在规划时定死。
 *
 * <p>因此 {@code material} 是 {@code RepositoryReadPlan} 的一个子集，
 * {@code skipped} 是执行期新产生的诊断。
 *
 * <p>本类型不持久化，也不进入 Domain。
 *
 * @param material 可用的分析材料；不得为 {@code null}，元素不得为 {@code null}
 * @param skipped  执行期被剔除的候选与原因；不得为 {@code null}，元素不得为 {@code null}
 */
public record RepositoryReadResult(List<RepositorySourceFile> material,
                                   List<SkippedReadCandidate> skipped) {

    public RepositoryReadResult {
        if (material == null) {
            throw new IllegalArgumentException("RepositoryReadResult 的 material 不能为 null");
        }
        for (RepositorySourceFile file : material) {
            if (file == null) {
                throw new IllegalArgumentException(
                        "RepositoryReadResult 的 material 不能包含 null");
            }
        }
        if (skipped == null) {
            throw new IllegalArgumentException("RepositoryReadResult 的 skipped 不能为 null");
        }
        for (SkippedReadCandidate candidate : skipped) {
            if (candidate == null) {
                throw new IllegalArgumentException(
                        "RepositoryReadResult 的 skipped 不能包含 null");
            }
        }
        material = List.copyOf(material);
        skipped = List.copyOf(skipped);
    }

    public boolean isEmpty() {
        return material.isEmpty();
    }

    public int size() {
        return material.size();
    }

    /** 执行期因为超过单文件上限而被剔除的候选数。 */
    public long tooLargeCount() {
        return countOf(RepositoryReadSkipReason.SELECTED_BUT_TOO_LARGE);
    }

    /** 执行期因为放不进该通道剩余总量而被剔除的候选数。 */
    public long totalBudgetExceededCount() {
        return countOf(RepositoryReadSkipReason.EXCEEDS_REMAINING_TOTAL_BYTES);
    }

    private long countOf(RepositoryReadSkipReason reason) {
        return skipped.stream().filter(candidate -> candidate.reason() == reason).count();
    }
}
