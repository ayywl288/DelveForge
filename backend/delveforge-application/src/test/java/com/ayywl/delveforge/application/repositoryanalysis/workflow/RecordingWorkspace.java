package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceMutationPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace 测试替身：按 revision 保存内容，并记录被调用时使用的 revision 与路径。
 *
 * <p>同时实现只读与修改两个 Port（一个真实 Adapter 也可以两者都提供），
 * 因此可以用来验证只读流程确实没有碰修改能力。
 *
 * <p>它按 revision 分别保存文件，并可以在「列目录之后」移动 HEAD，用来模拟
 * 「分析期间源 Repository 又产生了新提交」这一情形：若调用方在读取时重新解析 HEAD，
 * 拿到的就会是另一个 revision 的内容，测试据此可以发现混用 revision 的问题。
 */
final class RecordingWorkspace implements WorkspaceReadPort, WorkspaceMutationPort {

    private final Map<String, Map<String, String>> filesByRevision = new LinkedHashMap<>();

    private final List<String> listedRevisions = new ArrayList<>();

    private final List<String> readRevisions = new ArrayList<>();

    private final List<String> readPaths = new ArrayList<>();

    private String headRevision;

    private boolean readableRepository = true;

    private RuntimeException readFailure;

    private String moveHeadAfterNextListingTo;

    private int headRevisionCalls;

    private int mutationCalls;

    /** 只登记某个 revision 的内容，不改变当前 HEAD。 */
    void givenRevision(String revision, Map<String, String> files) {
        filesByRevision.put(revision, Map.copyOf(files));
    }

    /** 指定当前 HEAD 指向哪个 revision。 */
    void givenHeadRevision(String revision) {
        this.headRevision = revision;
    }

    void givenUnreadableRepository() {
        this.readableRepository = false;
    }

    void failReadsWith(RuntimeException exception) {
        this.readFailure = exception;
    }

    /** 下一次 listEntries 之后模拟 HEAD 前移：后续读取应当仍然使用原来的 revision。 */
    void moveHeadAfterNextListing(String revision) {
        this.moveHeadAfterNextListingTo = revision;
    }

    List<String> listedRevisions() {
        return List.copyOf(listedRevisions);
    }

    List<String> readRevisions() {
        return List.copyOf(readRevisions);
    }

    List<String> readPaths() {
        return List.copyOf(readPaths);
    }

    int headRevisionCalls() {
        return headRevisionCalls;
    }

    int mutationCalls() {
        return mutationCalls;
    }

    @Override
    public boolean isReadableRepository(
            WorkspaceRef workspace) {
        return readableRepository;
    }

    @Override
    public String headRevision(WorkspaceRef workspace) {
        headRevisionCalls++;
        return headRevision;
    }

    @Override
    public List<WorkspaceEntry> listEntries(
            WorkspaceRef workspace, String revision, String relativePath, int maxDepth) {
        requireFileFailure();
        listedRevisions.add(revision);

        String prefix = relativePath.isEmpty() ? "" : relativePath + "/";
        Map<String, WorkspaceEntry> entries = new LinkedHashMap<>();
        for (String path : filesOf(revision).keySet()) {
            if (!path.startsWith(prefix)) {
                continue;
            }
            String[] segments = path.substring(prefix.length()).split("/");
            StringBuilder current = new StringBuilder(prefix);
            for (int index = 0; index < segments.length && index < maxDepth; index++) {
                current.append(segments[index]);
                boolean directory = index < segments.length - 1;
                entries.putIfAbsent(current.toString(),
                        new WorkspaceEntry(current.toString(), directory, directory ? 0 : utf8Length(filesOf(revision).get(current.toString()))));
                current.append('/');
            }
        }

        if (moveHeadAfterNextListingTo != null) {
            this.headRevision = moveHeadAfterNextListingTo;
            this.moveHeadAfterNextListingTo = null;
        }
        return List.copyOf(entries.values());
    }

    @Override
    public String readFile(WorkspaceRef workspace, String revision, String relativePath) {
        requireFileFailure();
        readRevisions.add(revision);
        readPaths.add(relativePath);

        String content = filesOf(revision).get(relativePath);
        if (content == null) {
            throw new WorkspaceException(
                    "文件不存在: " + relativePath + " @ " + revision);
        }
        return content;
    }

    @Override
    public void writeFile(WorkspaceRef workspace, String relativePath, String content) {
        mutationCalls++;
    }

    private Map<String, String> filesOf(String revision) {
        Map<String, String> files = filesByRevision.get(revision);
        if (files == null) {
            throw new WorkspaceException("未知的 revision: " + revision);
        }
        return files;
    }

    private void requireFileFailure() {
        if (readFailure != null) {
            throw readFailure;
        }
    }

    /** 内容的字节数，与真实 Adapter 由 git 给出的 blob 大小口径一致。 */
    private static int utf8Length(String content) {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }
}
