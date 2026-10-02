package com.ayywl.delveforge.application.repositoryanalysis.secret;

/**
 * 凭据边界无法安全完成这次处理。
 *
 * <p>契约上，{@link RepositorySecretPolicy} 的两个方法要么给出可用的答案，要么以此失败。
 * 调用方对它的处理只有一种：**失败关闭**——不读、不发给模型、不保存任何东西。
 * 不存在「边界没跑完就按原文继续」这条路，那正是这个边界要防的事。
 *
 * <h2>当前实现不会以它失败，但契约必须允许</h2>
 *
 * <p>{@link DeterministicRepositorySecretPolicy} 的规则是对字符串的纯函数，
 * 今天不存在让它无法完成输入的情形。这个类型仍然定义在这里，因为：
 *
 * <pre>
 * 1. 两个执行点的调用方必须按「可能失败」来写——只有类型存在，失败关闭才写得出来；
 * 2. 将来若引入需要放弃的规则（例如某种无法在有限时间内判定完的形态），
 *    它需要一个既成的失败出口，而不是临时把某个 IllegalStateException 当契约用；
 * 3. 测试需要一个能被注入的失败，才能验证调用方真的失败关闭了。
 * </pre>
 *
 * <p>因此它是**契约的一部分**，不是为测试造的类型——只是今天没有生产路径触发它。
 */
public class RepositorySecretBoundaryException extends RuntimeException {

    public RepositorySecretBoundaryException(String detail) {
        super(detail);
    }

    public RepositorySecretBoundaryException(String detail, Throwable cause) {
        super(detail, cause);
    }
}
