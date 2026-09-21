package com.ayywl.delveforge.application.port.workspace;

import java.util.regex.Pattern;

/**
 * Workspace 中的一项内容。
 *
 * <p>路径始终是相对于 Workspace 根目录的相对路径，不携带宿主机绝对路径。
 * 这一约束是 Workspace 边界的一部分：业务代码不应接触宿主机文件系统布局。
 *
 * @param relativePath 相对于 Workspace 根目录的路径
 * @param directory    该项是否为目录
 * @param size         内容大小（字节）；目录为 0
 */
public record WorkspaceEntry(String relativePath, boolean directory, long size) {

    /** Windows 盘符形式：{@code C:\...} 或 {@code C:/...}。 */
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    public WorkspaceEntry {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("WorkspaceEntry 的 relativePath 不能为空");
        }
        if (size < 0) {
            throw new IllegalArgumentException("WorkspaceEntry 的 size 不能为负数: " + size);
        }
        if (isAbsolute(relativePath)) {
            throw new IllegalArgumentException(
                    "WorkspaceEntry 的 relativePath 必须是相对路径: " + relativePath);
        }
        if (escapesRoot(relativePath)) {
            throw new IllegalArgumentException(
                    "WorkspaceEntry 的 relativePath 不得越过 Workspace 根目录: " + relativePath);
        }
    }

    private static boolean isAbsolute(String path) {
        return path.startsWith("/") || path.startsWith("\\") || DRIVE_PREFIX.matcher(path).matches();
    }

    private static boolean escapesRoot(String path) {
        for (String segment : path.split("[/\\\\]")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}
