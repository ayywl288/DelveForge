package com.ayywl.delveforge.domain.direction;

/**
 * Product Direction 当前状态不允许该操作。
 *
 * <p>表示请求本身是可以理解的，只是该方向当前处于不能执行该操作的状态：
 *
 * <pre>
 * 非 CANDIDATE 状态下选择该方向
 * 非 CANDIDATE 状态下拒绝该方向
 * 非 SELECTED 状态下让该方向被取代
 * </pre>
 *
 * <p>它既不是输入格式错误（{@link IllegalArgumentException}），也不是技术故障，
 * 而是「当前状态与所请求的操作相冲突」。Interface 层据此映射为 409，
 * 使这类可预期的冲突不会被当成 500 服务端错误。
 *
 * <p>刻意不继承或复用 {@code IllegalStateException}：那会把 JDK 的通用异常类型
 * 变成领域语义的载体，使 Interface 层无法只映射领域状态冲突而放过其它
 * {@code IllegalStateException}。与 {@code UserProfileStateException} 同理，
 * 两者各自表达自己 Aggregate 的状态冲突。
 */
public class ProductDirectionStateException extends RuntimeException {

    public ProductDirectionStateException(String message) {
        super(message);
    }
}
