package com.ayywl.delveforge.application.port.workspace;

/**
 * 指向 Workspace 中一个受管代码位置的不透明句柄。
 *
 * <p>该类型属于 Application / Workspace 边界，<b>不是 Domain 概念</b>。
 * 它只回答“这次操作作用在哪个受管位置上”，不表达 Software Asset 或 Working Copy
 * 的领域身份。当 Domain 后续建立 SoftwareAsset / WorkingCopy 标识后，
 * 由 Application 层负责把它们映射为 {@code WorkspaceRef}，
 * 而不是在 Application 层另建一套领域标识（RULE-DOM-006）。
 *
 * <p>句柄的取值与解析方式由 Infrastructure 的 Workspace Adapter 决定。
 *
 * @param value 非空且非空白的位置标识
 */
public record WorkspaceRef(String value) {

    public WorkspaceRef {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("WorkspaceRef 的 value 不能为空");
        }
    }
}
