package com.ayywl.delveforge.application.port.workspace;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用 Workspace 假实现。
 *
 * <p>它同时实现只读与修改两个 Port，说明一个 Infrastructure Adapter 可以同时提供两种能力，
 * 而调用方仍然只依赖自己需要的那一个 Port。
 *
 * <p>本替身只代表「当前 HEAD 这一个版本」的内容：读取必须带上 {@code givenHeadRevision}
 * 给出的那个 revision，其他值一律拒绝。这样替身不会比真实 Adapter 宽松——
 * 真实 Adapter 同样只接受已解析的完整 commit id，不接受可移动的引用名。
 */
final class InMemoryWorkspace implements WorkspaceReadPort, WorkspaceMutationPort {

    private final Map<String, String> files = new LinkedHashMap<>();

    private String headRevision = "rev-0";

    private boolean readableRepository = true;

    void givenFile(String relativePath, String content) {
        files.put(relativePath, content);
    }

    void givenHeadRevision(String revision) {
        this.headRevision = revision;
    }

    void givenNotRepository() {
        this.readableRepository = false;
    }

    @Override
    public boolean isReadableRepository(WorkspaceRef workspace) {
        return readableRepository;
    }

    @Override
    public List<WorkspaceEntry> listEntries(
            WorkspaceRef workspace, String revision, String relativePath, int maxDepth) {
        requireKnownRevision(revision);
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
                entries.putIfAbsent(entryPath, new WorkspaceEntry(entryPath, directory, directory ? 0 : utf8Length(files.get(entryPath))));
                current.append('/');
            }
        }
        return List.copyOf(entries.values());
    }

    @Override
    public String readFile(
            WorkspaceRef workspace, String revision, String relativePath) {
        requireKnownRevision(revision);
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

    /**
     * 只承认当前 HEAD 那个已解析的 revision。
     *
     * <p>与实际 Adapter 一致：不是已解析的完整 commit id 的一律拒绝，因此
     * {@code HEAD} 或分支名在这里同样通不过。
     *
     * <p>替身的已知局限：它只保留一个版本，因此「读取更早的 revision」无法表达
     * （真实 Adapter 支持，只要该 commit 还在 Repository 中）。
     */
    private void requireKnownRevision(String revision) {
        if (!headRevision.equals(revision)) {
            throw new IllegalArgumentException("revision 必须是已解析的完整 commit id: " + revision);
        }
    }

    @Override
    public void writeFile(WorkspaceRef workspace, String relativePath, String content) {
        files.put(relativePath, content);
    }

    /** 内容的字节数，与真实 Adapter 由 git 给出的 blob 大小口径一致。 */
    private static int utf8Length(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }
}
