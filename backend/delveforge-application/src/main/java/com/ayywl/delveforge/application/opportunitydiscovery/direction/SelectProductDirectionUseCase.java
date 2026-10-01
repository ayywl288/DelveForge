package com.ayywl.delveforge.application.opportunitydiscovery.direction;

import com.ayywl.delveforge.application.port.persistence.ProductDirectionRepository;
import com.ayywl.delveforge.application.port.persistence.ProductDirectionTransition;
import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;
import java.util.List;
import java.util.Optional;

/**
 * 用户明确选择一个候选 Product Direction 作为后续 Evolution Planning 的目标方向。
 *
 * <pre>
 * 按标识加载目标方向
 *         ↓  目标必须处于 CANDIDATE，否则失败且不产生任何写入
 * 加载当前 SELECTED 的方向（如果有）
 *         ↓  存在时让它进入 SUPERSEDED
 * 一次原子写入：原方向先离开 SELECTED，目标方向再进入 SELECTED
 * </pre>
 *
 * <h2>这是 INV-D07 要求的那次明确用户行为</h2>
 *
 * <p>选择只能由调用方发起：本 Use Case 没有任何自动触发路径，发现流程、AI 输出与启动装配
 * 都不会调用它（DOMAIN_MODEL.md §6.2、AGENTS.md §3.3）。方向生成之后仍然全部是
 * {@code CANDIDATE}，本类不改变这一点。
 *
 * <h2>它只做编排，不做领域判断</h2>
 *
 * <p>「什么状态能变成什么状态」由 {@link ProductDirection#select()} 与
 * {@link ProductDirection#supersede()} 决定（RULE-DOM-002），本类不重复那些规则，
 * 也不直接改状态。它负责的只有顺序与原子性：
 *
 * <pre>
 * 目标状态不合法时在改动任何东西之前失败
 * 原方向的取代与目标方向的选择是一次写入，不是两次
 * </pre>
 *
 * <p>顺序上有一处与直觉不同：目标的 {@code select()} 在读取「当前 SELECTED 是谁」之前
 * 调用。因为 {@code select()} 本身就是「目标是否处于 CANDIDATE」这条前置判断，
 * 把它放在最前面意味着不合法的目标在**任何**读取与改动之前就被拒绝。
 *
 * <h2>INV-D09 的协调发生在这一层</h2>
 *
 * <p>「全局最多一个当前 SELECTED 方向，切换时原方向必须同批进入 SUPERSEDED」是一条跨
 * Aggregate 的约束，单个 Product Direction 看不到别的方向，因此只能在这里协调
 * （DOMAIN_MODEL.md §7.1）。本类不为此发明任何流程身份：当前 MVP 的约束是**全局**的，
 * 查询的就是那一个当前方向（{@link ProductDirectionRepository#findCurrentSelected()}）。
 *
 * <p>「先查再写」挡不住并发——两个请求可能同时读到「当前没有 SELECTED 方向」。
 * 因此存储层还有一道唯一约束作为最终守卫，撞上它时
 * {@link com.ayywl.delveforge.application.port.persistence.ProductDirectionSelectionConflictException}
 * 从 Port 边界抛出，本类原样向上传递：那不是本层能裁决的事情，也不该被伪装成别的失败。
 *
 * <h2>失败不留下任何持久化副作用</h2>
 *
 * <p>唯一一次写入在最后，且是原子的。目标不存在、目标状态不合法、读取当前方向时发现存储
 * 不一致——都在写入之前失败，原方向不会被提前改成 SUPERSEDED。
 */
public class SelectProductDirectionUseCase {

    private final ProductDirectionRepository productDirectionRepository;

    public SelectProductDirectionUseCase(ProductDirectionRepository productDirectionRepository) {
        if (productDirectionRepository == null) {
            throw new IllegalArgumentException(
                    "SelectProductDirectionUseCase 必须指定 productDirectionRepository");
        }
        this.productDirectionRepository = productDirectionRepository;
    }

    /**
     * 明确选择指定的 Product Direction。
     *
     * @param productDirectionId 目标方向标识，不得为 {@code null}
     * @return 选择之后的目标方向（{@code SELECTED}，内容与依据未变）
     * @throws IllegalArgumentException                标识为 {@code null}
     * @throws ProductDirectionNotFoundException       该方向不存在
     * @throws com.ayywl.delveforge.domain.direction.ProductDirectionStateException
     *                                                 该方向当前状态不是 {@code CANDIDATE}
     * @throws com.ayywl.delveforge.application.port.persistence.ProductDirectionSelectionConflictException
     *                                                 写入时存储里已存在另一个当前 SELECTED 方向
     * @throws com.ayywl.delveforge.application.port.persistence.ProductDirectionStatusConflictException
     *                                                 本次依据的状态在写入时已经变化，或写入
     *                                                 没有拿到存储的写锁（并发选择）
     */
    public ProductDirection select(ProductDirectionId productDirectionId) {
        if (productDirectionId == null) {
            throw new IllegalArgumentException(
                    "SelectProductDirectionUseCase 必须指定 productDirectionId");
        }

        ProductDirection target = productDirectionRepository.findById(productDirectionId)
                .orElseThrow(() -> new ProductDirectionNotFoundException(productDirectionId));

        // 先记下转换所依据的状态，再推进它。这条 select() 同时是「目标必须处于 CANDIDATE」
        // 的前置判断：不合法时在这里失败，此时还没有读取当前方向，更没有改动任何东西。
        ProductDirectionStatus targetBasis = target.status();
        target.select();

        Optional<ProductDirection> currentSelected =
                productDirectionRepository.findCurrentSelected();

        if (currentSelected.isEmpty()) {
            productDirectionRepository.saveTransitions(
                    List.of(new ProductDirectionTransition(target, targetBasis)));
            return target;
        }

        ProductDirection previous = currentSelected.get();
        ProductDirectionStatus previousBasis = previous.status();
        previous.supersede();

        // 顺序就是语义：原方向必须先离开 SELECTED，目标方向才能进入——存储层只允许
        // 存在一个当前 SELECTED 方向，反过来写会在中间态撞上那条约束。
        //
        // 两条都带上各自依据的起始状态：存储层据此核对这份依据是否仍然成立。若期间已经
        // 有人抢先选择了别的方向、或把目标推到了别处，整批失败，而不是把基于旧状态的
        // 判断盖到已经推进过的行上。
        productDirectionRepository.saveTransitions(List.of(
                new ProductDirectionTransition(previous, previousBasis),
                new ProductDirectionTransition(target, targetBasis)));
        return target;
    }
}
