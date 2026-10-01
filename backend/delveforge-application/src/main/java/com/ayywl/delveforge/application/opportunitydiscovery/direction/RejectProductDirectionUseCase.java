package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;

/**
 * 用户明确不选择某个候选 Product Direction。
 *
 * <pre>
 * 按标识加载目标方向
 *         ↓  目标必须处于 CANDIDATE，否则失败且不产生任何写入
 * 保存它进入 REJECTED 之后的状态
 * </pre>
 *
 * <h2>只有 CANDIDATE 可以被拒绝</h2>
 *
 * <p>§6.2 只定义了 {@code CANDIDATE → REJECTED} 这一条边。已经 SELECTED 的方向不能直接
 * 被拒绝——「不想继续用这个方向了」的正确表达是选择另一个候选方向，由
 * {@link SelectProductDirectionUseCase} 把它变成 {@code SUPERSEDED}；SUPERSEDED 是终态，
 * 同样没有到 REJECTED 的边。这些越界组合由 {@link ProductDirection#reject()} 拒绝，
 * 本类不另写一份状态规则。
 *
 * <h2>拒绝不是删除</h2>
 * <p>被拒绝的方向仍然保存下来：它的内容、分析来源与依据都不动，之后仍然读得回来
 * （§10.5、RULE-DOM-007）。它只是不再进入 Evolution Planning。
 *
 * <h2>它不需要 INV-D09 协调</h2>
 *
 * <p>拒绝不会产生新的 SELECTED 方向，因此不涉及「全局最多一个当前方向」这条约束，
 * 也不需要读取别的方向。本 Use Case 只接触它自己那一条。
 *
 * <p>与选择一样，它没有任何自动触发路径：拒绝必须来自用户的明确操作。
 */
public class RejectProductDirectionUseCase {

    private final ProductDirectionRepository productDirectionRepository;

    public RejectProductDirectionUseCase(ProductDirectionRepository productDirectionRepository) {
        if (productDirectionRepository == null) {
            throw new IllegalArgumentException(
                    "RejectProductDirectionUseCase 必须指定 productDirectionRepository");
        }
        this.productDirectionRepository = productDirectionRepository;
    }

    /**
     * 明确拒绝指定的 Product Direction。
     *
     * @param productDirectionId 目标方向标识，不得为 {@code null}
     * @return 拒绝之后的目标方向（{@code REJECTED}，内容与依据未变）
     * @throws IllegalArgumentException               标识为 {@code null}
     * @throws ProductDirectionNotFoundException      该方向不存在
     * @throws com.ayywl.delveforge.domain.direction.ProductDirectionStateException
     *                                                该方向当前状态不是 {@code CANDIDATE}
     */
    public ProductDirection reject(ProductDirectionId productDirectionId) {
        if (productDirectionId == null) {
            throw new IllegalArgumentException(
                    "RejectProductDirectionUseCase 必须指定 productDirectionId");
        }

        ProductDirection target = productDirectionRepository.findById(productDirectionId)
                .orElseThrow(() -> new ProductDirectionNotFoundException(productDirectionId));

        // 状态不合法时在这里失败，此时还没有任何写入。
        target.reject();

        productDirectionRepository.save(target);
        return target;
    }
}
