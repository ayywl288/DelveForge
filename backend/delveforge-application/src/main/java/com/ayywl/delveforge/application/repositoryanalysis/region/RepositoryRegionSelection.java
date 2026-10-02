package com.ayywl.delveforge.application.repositoryanalysis.region;

import java.util.List;

/**
 * 一次 Region Scout 的可信结果：已校验、已解析回去的区域，按模型给出的顺序排列。
 *
 * <pre>
 * AiRegionSelectionProposal（不可信，只有 RR-* 编号）
 *         ↓  引用校验 + 换回真实区域
 * RepositoryRegionSelection（可信，真实区域 + 分支优先级）
 * </pre>
 *
 * <h2>顺序有意义</h2>
 *
 * <p>列表顺序就是模型表达的**分支优先级**：越靠前的 Region 越应该先被探索。
 * 本类型因此不排序、不去重、不合并——那些都属于后续编排步骤。
 *
 * <h2>它是导航提示，不是仓库事实</h2>
 *
 * <p>「值得继续探索的区域」是一个查看意图，不是「这个目录实现了什么」。
 * 后者只有在文件内容真的被读进来并经过既有分析之后才成立（ADR-0005）。
 */
public final class RepositoryRegionSelection {

    private final String analyzedRevision;
    private final List<RepositoryRegion> regions;

    private RepositoryRegionSelection(String analyzedRevision, List<RepositoryRegion> regions) {
        this.analyzedRevision = analyzedRevision;
        this.regions = regions;
    }

    /**
     * 建立一次已校验的区域选择。
     *
     * @param analyzedRevision 本次导航固定的 commit id，不得为空白
     * @param regions          有序的被选区域，不得为 {@code null} 或空，元素不得为 {@code null}，
     *                         且不得包含重复的目录前缀
     * @throws IllegalArgumentException 参数不满足上述约束
     */
    public static RepositoryRegionSelection of(String analyzedRevision,
                                               List<RepositoryRegion> regions) {
        if (analyzedRevision == null || analyzedRevision.isBlank()) {
            throw new IllegalArgumentException(
                    "RepositoryRegionSelection 必须指定 analyzedRevision");
        }
        if (regions == null || regions.isEmpty()) {
            throw new IllegalArgumentException(
                    "RepositoryRegionSelection 的 regions 不能为空");
        }
        List<RepositoryRegion> copy = List.copyOf(regions);
        for (int i = 0; i < copy.size(); i++) {
            RepositoryRegion region = copy.get(i);
            if (region == null) {
                throw new IllegalArgumentException(
                        "RepositoryRegionSelection 的 regions 不能包含 null");
            }
            for (int j = i + 1; j < copy.size(); j++) {
                if (copy.get(j) != null
                        && region.pathPrefix().equals(copy.get(j).pathPrefix())) {
                    throw new IllegalArgumentException(
                            "RepositoryRegionSelection 的 regions 不能重复目录前缀: "
                                    + region.pathPrefix());
                }
            }
        }
        return new RepositoryRegionSelection(analyzedRevision, copy);
    }

    /** 本次导航固定的 commit id。 */
    public String analyzedRevision() {
        return analyzedRevision;
    }

    /** 被选中的区域，顺序即分支优先级。 */
    public List<RepositoryRegion> regions() {
        return regions;
    }

    public int size() {
        return regions.size();
    }
}
