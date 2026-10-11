package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.LocalRepositoryIdentity;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/** 只读取路径元数据；已存在的符号链接 / junction 按真实目标比较。 */
@Component
public final class LocalRepositoryIdentityAdapter implements LocalRepositoryIdentity {
    @Override
    public String key(String location, boolean existingRecord) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("Absolute repository path is required");
        }
        String value = location.trim().replace('\\', '/');
        boolean windowsPath = value.matches("^[A-Za-z]:/.*") || value.startsWith("//");
        boolean windowsHost = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
        if (value.startsWith("//?/") || value.startsWith("//./") || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Device paths are not supported");
        }
        if (!windowsPath && !(value.startsWith("/") && !windowsHost) && !existingRecord) {
            throw new IllegalArgumentException("Absolute repository path is required");
        }
        if (value.startsWith("//") && !existingRecord) {
            throw new IllegalArgumentException("Registration supports local paths, not UNC shares");
        }
        // 非本机格式只做词法比较；实际分析仍由原 Workspace Git 检查决定。
        if (!value.startsWith("//") && (windowsPath == windowsHost
                || existingRecord && !windowsPath && !value.startsWith("/"))) {
            Path path = Path.of(location.trim()).toAbsolutePath().normalize();
            try {
                path = resolveExistingAncestor(path);
            } catch (IOException failure) {
                throw new WorkspaceException("Repository path identity could not be resolved", failure);
            }
            value = path.toString().replace('\\', '/');
            windowsPath = windowsHost;
        }
        String prefix = windowsPath ? (value.startsWith("//") ? "//" : value.substring(0, 3)) : "/";
        String tail = value.substring(prefix.length());
        Deque<String> parts = new ArrayDeque<>();
        int rootParts = prefix.equals("//") ? 2 : 0;
        for (String part : tail.split("/+")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (parts.size() > rootParts) {
                    parts.removeLast();
                }
            } else {
                parts.addLast(part);
            }
        }
        if (prefix.equals("//") && parts.size() < 2) {
            throw new IllegalArgumentException("UNC server and share are required");
        }
        String key = prefix + String.join("/", parts);
        return windowsPath ? key.toLowerCase(Locale.ROOT) : key;
    }

    private static Path resolveExistingAncestor(Path requested) throws IOException {
        Path ancestor = requested;
        while (ancestor != null) {
            try {
                // 不跟随时可以识别悬空符号链接；其真实目标不明确时拒绝，而非制造第二个身份。
                Files.readAttributes(ancestor, BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                Path real = ancestor.toRealPath();
                return real.resolve(ancestor.relativize(requested)).normalize();
            } catch (NoSuchFileException missing) {
                if (Files.isSymbolicLink(ancestor)) {
                    throw missing;
                }
                ancestor = ancestor.getParent();
            }
        }
        throw new IOException("Repository volume could not be resolved");
    }
}
