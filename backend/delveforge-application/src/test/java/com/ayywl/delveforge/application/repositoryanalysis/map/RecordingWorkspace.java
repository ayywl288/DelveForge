package com.ayywl.delveforge.application.repositoryanalysis.map;

import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import java.util.ArrayList;
import java.util.List;

/**
 * 记录调用的 Workspace 替身。
 *
 * <p>与材料收集器的替身不同，本替身的关键作用不是「提供内容」，而是**证明内容没有被读**：
 * {@link #readFile} 与 {@link #headRevision} 一律抛错并留下记录。Repository Map 的构建
 * 只允许列目录，任何一次内容读取或 HEAD 解析都会让测试立刻失败。
 *
 * <p>条目按插入顺序保存，但 {@link #listEntries} 不做排序——排序是 Map 构建自己的责任，
 * 替身如果先排好，就会掩盖构建端漏掉排序的问题。
 */
final class RecordingWorkspace implements WorkspaceReadPort {

    private final List<WorkspaceEntry> entries = new ArrayList<>();

    private final List<String> listedRevisions = new ArrayList<>();

    private final List<String> listedPaths = new ArrayList<>();

    private final List<Integer> listedDepths = new ArrayList<>();

    private final List<String> readFileRequests = new ArrayList<>();

    private int headRevisionRequests;

    private boolean readableRepository = true;

    RecordingWorkspace givenFile(String relativePath, long size) {
        entries.add(new WorkspaceEntry(relativePath, false, size));
        return this;
    }

    RecordingWorkspace givenDirectory(String relativePath) {
        entries.add(new WorkspaceEntry(relativePath, true, 0));
        return this;
    }

    RecordingWorkspace givenNotRepository() {
        this.readableRepository = false;
        return this;
    }

    @Override
    public boolean isReadableRepository(WorkspaceRef workspace) {
        return readableRepository;
    }

    @Override
    public List<WorkspaceEntry> listEntries(
            WorkspaceRef workspace, String revision, String relativePath, int maxDepth) {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth 必须大于 0: " + maxDepth);
        }
        listedRevisions.add(revision);
        listedPaths.add(relativePath);
        listedDepths.add(maxDepth);

        String prefix = relativePath.isEmpty() ? "" : relativePath + "/";
        List<WorkspaceEntry> matched = new ArrayList<>();
        for (WorkspaceEntry entry : entries) {
            if (!entry.relativePath().startsWith(prefix)) {
                continue;
            }
            if (depthOf(entry.relativePath()) > maxDepth) {
                continue;
            }
            matched.add(entry);
        }
        return List.copyOf(matched);
    }

    @Override
    public String readFile(WorkspaceRef workspace, String revision, String relativePath) {
        readFileRequests.add(relativePath);
        throw new AssertionError(
                "Repository Map 的构建不得读取文件内容，但它读取了: " + relativePath);
    }

    @Override
    public String headRevision(WorkspaceRef workspace) {
        headRevisionRequests++;
        throw new AssertionError("Repository Map 的构建不得解析 HEAD");
    }

    /** 内容读取被请求过的路径；正常情况下为空。 */
    List<String> readFileRequests() {
        return List.copyOf(readFileRequests);
    }

    int headRevisionRequests() {
        return headRevisionRequests;
    }

    /** 列目录用过的 revision，按调用顺序。 */
    List<String> listedRevisions() {
        return List.copyOf(listedRevisions);
    }

    List<String> listedPaths() {
        return List.copyOf(listedPaths);
    }

    List<Integer> listedDepths() {
        return List.copyOf(listedDepths);
    }

    private static int depthOf(String relativePath) {
        return relativePath.split("/").length;
    }
}
