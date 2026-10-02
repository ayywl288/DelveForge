package com.ayywl.delveforge.application.repositoryanalysis.region;

/**
 * Region 分层导航的守卫上限（ADR-0005 的**导航预算**）。
 *
 * <p>与材料预算（{@code RepositoryMaterialBudget} 的 maxFiles / maxFileBytes / maxTotalBytes）
 * 是两回事：材料预算约束最终读多少源码；导航预算约束探索本身能走多远
 * （因此也约束 AI 调用的增长）。两者不互相占用。
 *
 * <h2>这些是技术/配置守卫，不是领域规则</h2>
 *
 * <p>改变它们不需要改领域模型，但会改变一次分析能看到多少（AGENTS.md §8.9）。
 * 具体数值来自配置，不在代码里兜底。
 *
 * <h2>当前只包含本 Task 需要的两个守卫</h2>
 *
 * <p>ADR-0005 还包含轮数、总调用数与终态分支数三个守卫，它们属于后续的编排步骤
 * ——只有那里才知道「一轮」与「一次调用」是什么。本类型不提前发明它们。
 *
 * @param maxCatalogBytes      Region Catalog 载荷的 UTF-8 字节上限，必须大于 0
 * @param maxSelectedRegions   一次 Region Scout 最多可选多少个区域，必须大于 0
 */
public record RegionNavigationLimits(int maxCatalogBytes, int maxSelectedRegions) {

    public RegionNavigationLimits {
        if (maxCatalogBytes <= 0) {
            throw new IllegalArgumentException(
                    "Region Catalog 的字节上限必须大于 0: " + maxCatalogBytes);
        }
        if (maxSelectedRegions < AiRegionSelectionProposal.MIN_SELECTED_REGIONS) {
            throw new IllegalArgumentException(
                    "一次区域选择的上限不能小于 " + AiRegionSelectionProposal.MIN_SELECTED_REGIONS
                            + ": " + maxSelectedRegions);
        }
    }

    /**
     * 目录载荷超出上限时失败关闭。
     *
     * <p>与 {@code RepositoryUnderstanding.requireCatalogWithinLimit} 同一个口径：
     * 不截断、不采样、不降级。本方法不自己测量——载荷字节由调用方用
     * {@link RepositoryRegionScoutExtraction#catalogPayloadBytes} 取得，
     * 判定与失败语义留在这条边界上，便于编排层决定何时测量。
     *
     * @param payloadBytes     本次 Region Catalog 的序列化字节数，不得为负数
     * @param analyzedRevision 本次导航固定的 revision，用于在失败信息里定位
     * @throws IllegalArgumentException                 payloadBytes 为负数
     * @throws RepositoryRegionCatalogTooLargeException 载荷超过上限
     */
    public void requireCatalogWithinLimit(int payloadBytes, String analyzedRevision) {
        if (payloadBytes < 0) {
            throw new IllegalArgumentException("Region Catalog 的载荷字节数不能为负数: "
                    + payloadBytes);
        }
        if (payloadBytes > maxCatalogBytes) {
            throw new RepositoryRegionCatalogTooLargeException(
                    "REGION_CATALOG_TOO_LARGE: Region 目录载荷 " + payloadBytes
                            + " 字节，超过上限 " + maxCatalogBytes
                            + " 字节；本版本不截断、不采样: " + analyzedRevision);
        }
    }
}
