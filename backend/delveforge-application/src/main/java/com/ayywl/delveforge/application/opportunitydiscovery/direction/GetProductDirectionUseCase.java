package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;

/**
 * 读取一条已经保存的 Product Direction。
 *
 * <p>读到的就是那条方向保存时的完整事实：discovery basis、recommendation content、
 * candidate assets、Evidence 与它当前的生命周期状态。状态可能已经不是
 * {@code CANDIDATE}（用户可能已经选择或拒绝过），本 Use Case 不因此改变读取语义，
 * 也不过滤状态。
 *
 * <p>读取本身不做编排，因此这里只负责把「找不到」翻译成明确的失败语义，让调用方不必
 * 自行判断 {@code Optional}。
 *
 * <h2>只有按标识读取</h2>
 *
 * <p>不提供按 User Profile / Repository Profile 查列表、分页与历史查询：当前没有这样的
 * 消费者，{@link ProductDirectionRepository} 也只提供按标识读取。出现真实需求时再补，
 * 而不是现在预留。
 *
 * <p>它不接触 AI，也不访问 Workspace。
 */
public class GetProductDirectionUseCase {

    private final ProductDirectionRepository productDirectionRepository;

    public GetProductDirectionUseCase(ProductDirectionRepository productDirectionRepository) {
        if (productDirectionRepository == null) {
            throw new IllegalArgumentException(
                    "GetProductDirectionUseCase 必须指定 productDirectionRepository");
        }
        this.productDirectionRepository = productDirectionRepository;
    }

    /**
     * 读取指定的 Product Direction。
     *
     * @param productDirectionId 方向标识
     * @return 该方向保存时的内容、依据与当前状态
     * @throws ProductDirectionNotFoundException 该方向不存在
     */
    public ProductDirection get(ProductDirectionId productDirectionId) {
        return productDirectionRepository.findById(productDirectionId)
                .orElseThrow(() -> new ProductDirectionNotFoundException(productDirectionId));
    }
}
