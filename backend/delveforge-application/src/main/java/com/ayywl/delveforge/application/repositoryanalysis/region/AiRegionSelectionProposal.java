package com.ayywl.delveforge.application.repositoryanalysis.region;

import java.util.List;

/**
 * AI 提出的区域选择提议：若干有序的 {@code RR-*} 引用。
 *
 * <pre>
 * RepositoryRegionCatalog → Region Scout → AiRegionSelectionProposal
 *                                              ↓ 引用校验
 *                                       RepositoryRegionSelection
 * </pre>
 *
 * <h2>它回答的只是「接下来探索哪几个区域」</h2>
 *
 * <p>提议不包含任何关于仓库的结论。模型看到的是目录前缀、规模与语言/结构提示——看不到代码，
 * 因此它没有资格断言「这个目录实现了什么」。它只能指出「接下来应该往哪几个目录里看」。
 *
 * <h2>不可信输入</h2>
 *
 * <p>本类型是 AI 通信协议的一部分，不是领域对象，也不是可信结果。它里面每个引用的合法性
 * 都由 {@link RepositoryRegionProposalResolver} 依据建立本次调用时的
 * {@link RepositoryRegionCatalog} 校验；校验通过之后才产生 {@link RepositoryRegionSelection}。
 *
 * <p>本类型只校验形状（非空、元素非空）。引用格式、数量上限这些**与模型约定的契约**由
 * {@link RepositoryRegionProposalParser} 负责拒绝——只有那里知道「这份内容来自模型」与
 * 本次配置的上限，因而能给出正确的失败语义。
 *
 * @param regionRefs 有序的区域引用，不得为 {@code null} 或空，元素不得为 {@code null}
 */
public record AiRegionSelectionProposal(List<RepositoryRegionReference> regionRefs) {

    /** 一次区域选择要求的最少区域数：空选择没有意义。 */
    public static final int MIN_SELECTED_REGIONS = 1;

    public AiRegionSelectionProposal {
        if (regionRefs == null || regionRefs.isEmpty()) {
            throw new IllegalArgumentException("区域选择提议必须给出 regionRefs");
        }
        for (RepositoryRegionReference reference : regionRefs) {
            if (reference == null) {
                throw new IllegalArgumentException("区域选择提议的 regionRefs 不能包含 null");
            }
        }
        regionRefs = List.copyOf(regionRefs);
    }
}
