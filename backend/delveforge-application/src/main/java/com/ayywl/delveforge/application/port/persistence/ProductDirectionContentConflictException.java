package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionId;

/**
 * 该 Product Direction 已经保存过，但本次传入的内容与已保存的版本不一致。
 *
 * <p>Product Direction 拥有生命周期，因此同一个标识允许再次保存——但允许变化的只有
 * 生命周期状态。最初生成该方向时的 discovery basis、recommendation content、
 * candidate assets 与 Evidence 是这条方向推荐语义的原始依据，
 * 不得因为后续状态变化或重复保存而被改写（DOMAIN_MODEL.md §10.5、RULE-DOM-007）。
 *
 * <p>出现本异常意味着调用方拿着同一个 {@link ProductDirectionId} 提交了另一条内容不同的
 * {@link ProductDirection}。保存被拒绝而不是覆盖：
 *
 * <pre>
 * 覆盖    → 历史推荐依据被静默改写，之后无法回答这个方向当初凭什么被推荐
 * 静默忽略 → 调用方以为自己的改动已经生效，实际被丢弃
 * </pre>
 *
 * <p>只改状态不触发本异常：状态不在比较范围内。
 *
 * <p>这是业务冲突而不是技术故障：请求本身可以理解，只是与已经保存的领域状态冲突。
 * 因此它有自己的类型，而不是借用 JDK 的通用异常——后者会让 Interface 层无法只把
 * 这一类冲突映射成对应的协议错误（AGENTS.md §8.7）。
 */
public class ProductDirectionContentConflictException extends RuntimeException {

    public ProductDirectionContentConflictException(ProductDirectionId productDirectionId) {
        super("Product Direction 已存在，且本次内容与已保存的推荐依据不一致: "
                + productDirectionId.value());
    }
}
