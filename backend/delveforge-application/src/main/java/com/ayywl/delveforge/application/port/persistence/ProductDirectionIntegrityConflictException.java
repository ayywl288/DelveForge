package com.ayywl.delveforge.application.port.persistence;

/**
 * 存储中的状态违反了领域不变量，以至于本次操作无法继续。
 *
 * <p>当前只有一种情形：{@link ProductDirectionRepository#findCurrentSelected()} 读到
 * <b>多于一条</b>当前 {@code SELECTED} 的方向，而 INV-D09 要求全局最多一条
 * （DOMAIN_MODEL.md §7.1）。正常写入路径由存储层的唯一约束保证不会出现这种状态；
 * 出现它说明数据与领域模型已经不一致——更早的数据、被绕过的写入，或迁移中的中间态。
 *
 * <p>此时查询失败而不是挑一条返回：静默挑一条会让调用方以为系统里只有一个当前方向，
 * 而它即将取代的那个可能才是有依据的那一个。保留多行检测而不是改成「取第一条」，
 * 是因为那会把一个数据完整性问题变成一次看起来正常的业务操作。
 *
 * <p>它是 Application 层拥有的类型，而不是 {@code IllegalStateException}：后者是通用异常，
 * Interface 层无法只把这一类与真正的服务端故障区分开（AGENTS.md §8.7）。这里需要的是一条
 * 稳定、可分类的语义——「存储违反了领域不变量」——由它承载，而不是靠猜异常来源。
 *
 * <p>它与 {@link ProductDirectionSelectionConflictException} 的区别：那一条是**正常数据**
 * 上的并发冲突（已经有另一个当前方向，改选一个即可），这一条是**数据本身**需要修复。
 */
public class ProductDirectionIntegrityConflictException extends RuntimeException {

    public ProductDirectionIntegrityConflictException(String detail) {
        super("Product Direction 的存储状态违反领域不变量: " + detail);
    }
}
