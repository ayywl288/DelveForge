package com.ayywl.delveforge.application.port.persistence;

import com.ayywl.delveforge.domain.direction.ProductDirectionId;

/**
 * 本次选择与「已经存在另一个当前 SELECTED 方向」冲突。
 *
 * <p>INV-D09 规定当前 MVP 全局最多只能存在一个当前 {@code SELECTED} 的 Product Direction
 * （DOMAIN_MODEL.md §7.1）。正常切换由 Application 在一次原子写入里完成：原方向先进入
 * {@code SUPERSEDED}，新方向再进入 {@code SELECTED}。本异常表示那条路径没有成立——
 * 写入时存储里仍然存在另一个当前 {@code SELECTED} 的方向。
 *
 * <p>典型来源是并发：两个选择请求同时读到「当前没有 SELECTED 方向」，各自把
 * {@code CANDIDATE → SELECTED}，其中先提交的那个成功，后一个撞上存储层的唯一约束。
 * 这类竞态不能靠「先查再写」消除，只能由存储层兜底，因此本异常是那条兜底的出口。
 *
 * <p>它是业务冲突而不是技术故障：请求本身可以理解，重试或改为选择另一个方向就能继续。
 * 因此它有自己的类型，而不是让 {@code DataIntegrityViolationException} 一类的持久化异常
 * 穿过 Port 边界——那会把 SQLite、唯一索引与约束名泄漏给 Application 与 Interface 层，
 * 也会让后者无法只把这一类失败映射成对应的协议错误（AGENTS.md §8.7）。
 *
 * <p>注意它与 {@link ProductDirectionContentConflictException} 的区别：后者是「同一标识
 * 的推荐内容被改写」，本异常是「同时存在两个当前方向」。两者的调用方处置也不同。
 */
public class ProductDirectionSelectionConflictException extends RuntimeException {

    public ProductDirectionSelectionConflictException(ProductDirectionId productDirectionId) {
        super("已经存在另一个当前 SELECTED 的 Product Direction，无法同时选择: "
                + productDirectionId.value());
    }

    /**
     * @param cause 存储层报告的原始冲突；只用于本层诊断，不向 Port 之外传播
     */
    public ProductDirectionSelectionConflictException(ProductDirectionId productDirectionId,
                                                      Throwable cause) {
        super("已经存在另一个当前 SELECTED 的 Product Direction，无法同时选择: "
                + productDirectionId.value(), cause);
    }
}
