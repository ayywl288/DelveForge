package com.ayywl.delveforge.domain.user;

/**
 * User Profile 当前状态不允许该操作。
 *
 * <p>表示请求本身是可以理解的，只是对象当前处于不能执行该操作的状态：
 *
 * <pre>
 * CONFIRMED 状态下修改 Profile 内容
 * 非 EXPLORING 状态下进入 REVIEWING
 * 非 REVIEWING 状态下确认 Profile
 * 确认所依据的 revision 与当前 revision 不一致（用户看的版本已经过期）
 * </pre>
 *
 * <p>它既不是输入格式错误（{@link IllegalArgumentException}），也不是技术故障，
 * 而是「当前状态与所请求的操作相冲突」。Interface 层据此映射为 409，
 * 使这类可预期的冲突不会被当成 500 服务端错误。
 *
 * <p>刻意不继承或复用 {@code IllegalStateException}：那会把 JDK 的通用异常类型
 * 变成领域语义的载体，使 Interface 层无法只映射领域状态冲突而放过其它
 * {@code IllegalStateException}。
 */
public class UserProfileStateException extends RuntimeException {

    public UserProfileStateException(String message) {
        super(message);
    }
}
