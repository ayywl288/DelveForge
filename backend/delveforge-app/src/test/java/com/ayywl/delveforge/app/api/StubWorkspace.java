package com.ayywl.delveforge.app.api;

import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace 测试替身：按 revision 提供文件内容，并可切换「不是可读仓库」。
 *
 * <p>接口层的测试关心的是 HTTP → Application → Persistence 这条链路，
 * 真实 Git 集成由 Infrastructure 的测试单独覆盖（AGENTS.md §10.3）：
 * 在那里验证「真实 git 命令读到的就是 commit tree」，在这里验证「接口把结果映射对了」。
 */
final class StubWorkspace implements WorkspaceReadPort {

    private final Map<String, String> files = new LinkedHashMap<>();

    private String revision = "abc123def456";

    private boolean readableRepository = true;

    /**
     * 回到默认状态：可读的仓库、没有任何文件。
     *
     * <p>替身是共享的静态实例，因此每个测试开始前都要复位，否则前一个测试留下的
     * 「不可读」标记会悄悄影响后一个测试。
     */
    StubWorkspace reset() {
        files.clear();
        readableRepository = true;
        return this;
    }

    StubWorkspace givenFile(String relativePath, String content) {
        files.put(relativePath, content);
        return this;
    }

    /** 只剩下选材策略会排除的文件：用于「没有可分析材料」的场景。 */
    StubWorkspace givenNotAnalyzableContent() {
        files.clear();
        files.put("logo.png", "not really an image");
        files.put("node_modules/left-pad/index.js", "module.exports = 1");
        return this;
    }

    StubWorkspace givenRevision(String revision) {
        this.revision = revision;
        return this;
    }

    StubWorkspace givenNotRepository() {
        this.readableRepository = false;
        return this;
    }

    @Override
    public boolean isReadableRepository(WorkspaceRef workspace) {
        return readableRepository;
    }

    @Override
    public String headRevision(WorkspaceRef workspace) {
        return revision;
    }

    @Override
    public List<WorkspaceEntry> listEntries(
            WorkspaceRef workspace, String revision, String relativePath, int maxDepth) {
        String prefix = relativePath.isEmpty() ? "" : relativePath + "/";
        Map<String, WorkspaceEntry> entries = new LinkedHashMap<>();

        for (String path : files.keySet()) {
            if (!path.startsWith(prefix)) {
                continue;
            }
            String[] segments = path.substring(prefix.length()).split("/");
            StringBuilder current = new StringBuilder(prefix);
            for (int index = 0; index < segments.length && index < maxDepth; index++) {
                current.append(segments[index]);
                boolean directory = index < segments.length - 1;
                String entryPath = current.toString();
                entries.putIfAbsent(entryPath, new WorkspaceEntry(
                        entryPath,
                        directory,
                        directory ? 0 : utf8Length(files.get(entryPath))));
                current.append('/');
            }
        }
        return List.copyOf(entries.values());
    }

    @Override
    public String readFile(WorkspaceRef workspace, String revision, String relativePath) {
        String content = files.get(relativePath);
        if (content == null) {
            throw new WorkspaceException("文件不存在: " + relativePath);
        }
        return content;
    }

    private static int utf8Length(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }
}
