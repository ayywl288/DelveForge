package com.ayywl.delveforge.application.port.workspace;

/**
 * Workspace 能力调用失败。
 *
 * <p>用于在边界处明确表达“本地软件操作失败”，避免 Git / 文件系统 / 构建工具等
 * 专有异常直接泄漏到业务代码中。
 */
public class WorkspaceException extends RuntimeException {

    public WorkspaceException(String message) {
        super(message);
    }

    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
