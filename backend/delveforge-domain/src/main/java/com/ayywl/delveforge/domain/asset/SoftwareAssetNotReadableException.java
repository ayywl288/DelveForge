package com.ayywl.delveforge.domain.asset;

/**
 * Software Asset 当前不允许读取，因此不能进入 Repository Analysis。
 *
 * <p>表示资产自身声明的读取权限不允许本次访问（DOMAIN_MODEL.md §3.2 的
 * {@code readPermission}、§12.6 的 Analysis 判定、INV-A01），既不是调用方输入格式错误
 * （{@link IllegalArgumentException}），也不是技术故障（例如路径不存在、进程无权限访问
 * 该目录）。Interface 层据此可以把领域层面的读取限制与其它两类失败区分开。
 *
 * <p>刻意不继承或复用 {@code IllegalStateException}：那会把 JDK 的通用异常类型变成
 * 领域语义的载体，使 Interface 层无法只映射这一类领域限制而放过其它同类异常
 * （与 {@code UserProfileStateException} 的处理方式一致）。
 */
public class SoftwareAssetNotReadableException extends RuntimeException {

    public SoftwareAssetNotReadableException(String message) {
        super(message);
    }
}
