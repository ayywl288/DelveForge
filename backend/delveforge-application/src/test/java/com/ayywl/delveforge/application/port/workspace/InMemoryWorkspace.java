package com.ayywl.delveforge.application.port.workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用 Workspace 假实现。
 *
 * <p>它同时实现只读与修改两个 Port，说明一个 Infrastructure Adapter 可以同时提供两种能力，
 * 而调用方仍然只依赖自己需要的那一个 Port。
 */
final class InMemoryWorkspace implements WorkspaceReadPort, WorkspaceMutationPort {

    private final Map<String, String> files = new LinkedHashMap<>();

    private String headRevision = "rev-0";

    void givenFile(String relativePath, String content) {
        files.put(relativePath, content);
    }

    void givenHeadRevision(String revision) {
        this.headRevision = revision;
    }

    @Override
    public List<WorkspaceEntry> listEntries(WorkspaceRef workspace, String relativePath, int maxDepth) {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth 必须大于 0");
        }
        String prefix = relativePath.isEmpty() ? "" : relativePath + "/";

        Map<String, WorkspaceEntry> entries = new LinkedHashMap<>();
        for (String path : files.keySet()) {
            if (!path.startsWith(prefix)) {
                continue;
            }
            String[] segments = path.substring(prefix.length()).split("/");
            StringBuilder current = new StringBuilder(prefix);
            // 只输出层级内的项；比 maxDepth 更深的文件仍然贡献其在层级内的祖先目录
            for (int i = 0; i < segments.length && i < maxDepth; i++) {
                current.append(segments[i]);
                boolean directory = i < segments.length - 1;
                String entryPath = current.toString();
                entries.putIfAbsent(entryPath, new WorkspaceEntry(entryPath, directory));
                current.append('/');
            }
        }
        return List.copyOf(entries.values());
    }

    @Override
    public String readFile(WorkspaceRef workspace, String relativePath) {
        String content = files.get(relativePath);
        if (content == null) {
            throw new WorkspaceException("文件不存在: " + relativePath);
        }
        return content;
    }

    @Override
    public String headRevision(WorkspaceRef workspace) {
        return headRevision;
    }

    @Override
    public void writeFile(WorkspaceRef workspace, String relativePath, String content) {
        files.put(relativePath, content);
    }
}
