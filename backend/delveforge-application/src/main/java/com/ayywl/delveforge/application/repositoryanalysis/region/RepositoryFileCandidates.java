package com.ayywl.delveforge.application.repositoryanalysis.region;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import java.util.ArrayList;
import java.util.List;

/**
 * Scout 阶段的最终产物：**有序的可信文件候选**，供后续步骤交给材料读取预算。
 *
 * <pre>
 * 终态文件组 1..n
 *         ↓  每组一次分支本地 File Scout（已解析、已校验）
 * 各组组内的优先级顺序
 *         ↓  保序轮转合并
 * RepositoryFileCandidates（有序候选文件）
 * </pre>
 *
 * <h2>顺序的含义分三层，不要混为一谈</h2>
 *
 * <pre>
 * 被 Region Scout 选中的兄弟分支之间   模型的取舍顺序（ADR-0005 的分支优先级）
 * 一条分支内                          File Scout 表达的优先级
 * 直属文件组与子分支之间              结构约定 —— 直属文件从未被任何一次 Region Scout 排序过
 * </pre>
 *
 * <p>合并把前两层保序地交织起来；第三层只是决定「哪些分支先进入轮转」，不是模型给出的
 * 优先关系。因此本类型只承诺「这是一个确定、可复现的顺序」，**不宣称整个列表是一份
 * 由模型给出的优先级**。
 *
 * <h2>文件身份是相对路径，不是引用编号</h2>
 *
 * <p>{@code orderedFiles} 里的每一项都是本次分析那张 {@code RepositoryMap} 上的**原描述符**，
 * 是该 Map 描述符的子集。编号是调用内的短名（ADR-0004）：分支本地 File Scout 用的是各自
 * 重新分配的 {@code RF-1…RF-n}，跨分支互不可解析；这里交回的是原描述符，
 * 因此「是哪些文件」在合并之后依然可核对。消费方要构造自己那一次调用的引用时重新编号即可。
 *
 * <h2>要么完整，要么没有</h2>
 *
 * <p>任何一条分支的 File Scout 失败、或调用总数用尽，都不会产生本对象：
 * 不存在「跑了一部分分支的合并结果」。
 */
public final class RepositoryFileCandidates {

    private final String analyzedRevision;
    private final List<RepositoryMapEntry> orderedFiles;
    private final int regionScoutCalls;
    private final int fileScoutCalls;

    private RepositoryFileCandidates(String analyzedRevision,
                                     List<RepositoryMapEntry> orderedFiles,
                                     int regionScoutCalls,
                                     int fileScoutCalls) {
        this.analyzedRevision = analyzedRevision;
        this.orderedFiles = orderedFiles;
        this.regionScoutCalls = regionScoutCalls;
        this.fileScoutCalls = fileScoutCalls;
    }

    /**
     * 建立一次 Scout 阶段的结果。
     *
     * @param analyzedRevision 本次分析固定的 commit id，不得为空白
     * @param orderedFiles     有序候选文件，不得为 {@code null} 或空，元素不得为 {@code null}，
     *                         且不得重复
     * @param regionScoutCalls 本次分析用掉的 Region Scout 调用数，不得为负数
     * @param fileScoutCalls   本次分析用掉的 File Scout 调用数，不得为负数
     * @throws IllegalArgumentException 参数不满足上述约束
     */
    public static RepositoryFileCandidates of(String analyzedRevision,
                                              List<RepositoryMapEntry> orderedFiles,
                                              int regionScoutCalls,
                                              int fileScoutCalls) {
        if (analyzedRevision == null || analyzedRevision.isBlank()) {
            throw new IllegalArgumentException(
                    "RepositoryFileCandidates 必须指定 analyzedRevision");
        }
        if (orderedFiles == null || orderedFiles.isEmpty()) {
            throw new IllegalArgumentException(
                    "RepositoryFileCandidates 的 orderedFiles 不能为空");
        }
        if (regionScoutCalls < 0) {
            throw new IllegalArgumentException(
                    "RepositoryFileCandidates 的 regionScoutCalls 不能为负数: " + regionScoutCalls);
        }
        if (fileScoutCalls < 0) {
            throw new IllegalArgumentException(
                    "RepositoryFileCandidates 的 fileScoutCalls 不能为负数: " + fileScoutCalls);
        }

        List<RepositoryMapEntry> copy = new ArrayList<>(orderedFiles.size());
        for (RepositoryMapEntry entry : orderedFiles) {
            if (entry == null) {
                throw new IllegalArgumentException(
                        "RepositoryFileCandidates 的 orderedFiles 不能包含 null");
            }
            if (copy.contains(entry)) {
                throw new IllegalArgumentException(
                        "RepositoryFileCandidates 的 orderedFiles 不能包含重复文件: "
                                + entry.relativePath());
            }
            copy.add(entry);
        }
        return new RepositoryFileCandidates(
                analyzedRevision, List.copyOf(copy), regionScoutCalls, fileScoutCalls);
    }

    /** 本次分析固定的 commit id。 */
    public String analyzedRevision() {
        return analyzedRevision;
    }

    /** 有序候选文件。顺序确定、可复现；具体含义见类说明。 */
    public List<RepositoryMapEntry> orderedFiles() {
        return orderedFiles;
    }

    public int size() {
        return orderedFiles.size();
    }

    /** 本次分析用掉的 Region Scout 调用数。 */
    public int regionScoutCalls() {
        return regionScoutCalls;
    }

    /** 本次分析用掉的 File Scout 调用数，等于终态分支数。 */
    public int fileScoutCalls() {
        return fileScoutCalls;
    }

    /** 两条通道合并的 Scout 调用总数。 */
    public int totalScoutCalls() {
        return regionScoutCalls + fileScoutCalls;
    }
}
