package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.direction.ProductDirectionId;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;

/**
 * 本次写入所依据的那个状态，在写入时已经不是存储里的状态。
 *
 * <p>一次生命周期转换是在「读取到的状态」之上推进的。从读取到写入之间，另一个请求可能已经
 * 把这条方向推进到了别处——此时按旧副本写入，会把一个**已经提交的用户决定**静默覆盖。
 * 因此存储层在执行写入的同一条语句里核对起始状态，核对不通过就整次失败（见
 * {@link ProductDirectionTransition}）。
 *
 * <h2>它同时表示「这条写入没有拿到存储的写锁」</h2>
 *
 * <p>SQLite 在存在并发写入者时会直接拒绝拿不到锁的那一方（{@code SQLITE_BUSY} /
 * {@code SQLITE_LOCKED}），而不是排队等待。这与上面的情形有同样的含义与同样的处置：
 * 本次依据的状态无法确认仍然成立，调用方应当重新读取之后再决定。
 * 把它也归入本异常，是为了不让一个数据库锁超时以「未分类的数据访问异常」变成 500——
 * 那既没有告诉调用方发生了什么，也把 SQLite 的细节漏了出去。
 *
 * <h2>它不是内容冲突</h2>
 *
 * <p>与 {@link ProductDirectionContentConflictException} 的区别：那一条说的是「同标识的
 * 推荐内容被改写」，这一条说的是「状态已经不是你看到的那个」。两者的调用方处置也不同：
 * 前者是提错了内容，后者是重新读取之后再试。
 *
 * <p>也不与 {@link ProductDirectionSelectionConflictException} 混同：那一条是
 * INV-D09 的「已经存在另一个当前方向」，与本次方向自身的历史无关。
 */
public class ProductDirectionStatusConflictException extends RuntimeException {

    /** 存储中的状态已经不是本次所依据的那一个。 */
    public ProductDirectionStatusConflictException(ProductDirectionId productDirectionId,
                                                   ProductDirectionStatus expectedFrom) {
        super("Product Direction 的状态已经变化，不再是本次所依据的 "
                + expectedFrom + ": " + productDirectionId.value());
    }

    /**
     * 试图在不声明所依据状态的情况下改写状态。
     *
     * <p>生命周期变化必须经由
     * {@link ProductDirectionRepository#saveTransitions} 表达，因为只有它能声明
     * 「这次推进依据的是哪个状态」。
     */
    public ProductDirectionStatusConflictException(ProductDirectionId productDirectionId) {
        super("Product Direction 的状态变化必须声明所依据的起始状态: "
                + productDirectionId.value());
    }

    /** 写入没有拿到存储的写锁（并发写入者正在提交）。 */
    public ProductDirectionStatusConflictException(ProductDirectionId productDirectionId,
                                                   Throwable cause) {
        super("Product Direction 的写入与另一个并发写入冲突: " + productDirectionId.value(),
                cause);
    }
}
