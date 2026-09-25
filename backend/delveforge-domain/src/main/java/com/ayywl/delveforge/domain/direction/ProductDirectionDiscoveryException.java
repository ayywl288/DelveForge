package com.ayywl.delveforge.domain.direction;

/**
 * 一次 Product Direction Discovery 的结果不满足领域要求，因此不能被接受为 Product
 * Direction。
 *
 * <p>它表示的是<b>领域接受失败</b>，不是别的几类失败：
 *
 * <pre>
 * 不是 AiGatewayException        模型调用或输出解析失败（那发生在更早的边界）
 * 不是 IllegalArgumentException  调用方参数形状错误（null、缺少必要输入）
 * 不是 Persistence 故障          本异常与存储无关
 * </pre>
 *
 * <p>典型情形：
 *
 * <pre>
 * 用于发现的 User Profile 尚未 CONFIRMED
 * 某条依据的 EvidenceOrigin 与输入对不上（指向别的 Profile、别的 revision，
 *     或该 Evidence 并不属于它所声称的那个集合）
 * 三类关键判断中有一类没有任何依据（INV-D06）
 * 某条方向没有实际依据任何 Repository Profile
 * 候选 Software Asset 无法由该方向实际依据的 Repository Profile 支撑
 * 提案数量不在当前要求的范围内
 * </pre>
 *
 * <p>这些都是可预期的领域拒绝，因此有自己的类型而不是借用 JDK 通用异常：Interface 层
 * 需要能够只把这一类冲突映射成对应的协议错误（AGENTS.md §8.7）。与
 * {@code ProductDirectionStateException} 的分工是——那个描述「当前状态不允许这个操作」，
 * 这个描述「这次发现的结果本身不合领域要求」。
 */
public class ProductDirectionDiscoveryException extends RuntimeException {

    public ProductDirectionDiscoveryException(String message) {
        super(message);
    }
}
