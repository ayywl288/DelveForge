package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.direction.ProductDirection;
import com.ayywl.delveforge.domain.direction.ProductDirectionStatus;

/**
 * 一次生命周期转换，连同它所依据的那个状态。
 *
 * <pre>
 * expectedFrom   读取这个方向时，它是这个状态
 * direction      在此之后，它被推进到了它的下一个状态
 * </pre>
 *
 * <p>「依据的状态」是这条记录存在的原因。只写「它现在应该是什么状态」不足以保护一次转换：
 * 从读取到写入之间，另一个请求可能已经把这条方向推进到了别处，而写入者手上拿的还是旧副本。
 * 无条件地按旧副本更新状态，会让一个**已经提交的用户决定**被静默覆盖——
 * 例如一方刚刚选中某个方向，另一方用读取时拿到的候选副本把它改成 REJECTED。
 *
 * <p>因此调用方必须如实声明它依据的是哪个状态，由存储层在**同一条语句**里核对：
 *
 * <pre>
 * UPDATE … SET status = 新状态 WHERE id = ? AND status = expectedFrom
 * </pre>
 *
 * <p>受影响行数为 0 就说明依据已经不成立，整次写入（含同批次的其它转换）失败，
 * 抛出 {@link ProductDirectionStatusConflictException}，而不是把旧判断盖上去。
 *
 * <h2>为什么一个状态就够，不需要 revision</h2>
 *
 * <p>REJECTED 与 SUPERSEDED 都是终态（DOMAIN_MODEL.md §6.2），生命周期没有回路，
 * 因此不存在「状态变走又变回来、让旧依据重新成立」的情形。一个起始状态足以判定这次转换
 * 是否仍然成立，不需要给 Product Direction 引入版本号。
 *
 * @param direction   已经完成领域转换的方向，不得为 {@code null}
 * @param expectedFrom 本次转换所依据的起始状态，不得为 {@code null}；
 *                     必须是 {@code direction} 在**领域转换发生之前**的状态
 */
public record ProductDirectionTransition(ProductDirection direction,
                                         ProductDirectionStatus expectedFrom) {

    public ProductDirectionTransition {
        if (direction == null) {
            throw new IllegalArgumentException("Product Direction 转换必须指定 direction");
        }
        if (expectedFrom == null) {
            throw new IllegalArgumentException(
                    "Product Direction 转换必须指定 expectedFrom");
        }
    }
}
