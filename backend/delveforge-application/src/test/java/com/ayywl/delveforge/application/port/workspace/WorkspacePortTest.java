package com.ayywl.delveforge.application.port.workspace;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 Workspace Gateway 边界可以在不依赖 Git / 文件系统 / Infrastructure 的情况下被实现与使用，
 * 并验证只读能力与修改能力在类型层面是隔离的。
 */
class WorkspacePortTest {

    private static final WorkspaceRef WORKSPACE = new WorkspaceRef("workspace-1");

    private InMemoryWorkspace workspace() {
        InMemoryWorkspace workspace = new InMemoryWorkspace();
        workspace.givenFile("README.md", "# demo");
        workspace.givenFile("src/main/App.java", "class App {}");
        workspace.givenFile("src/test/AppTest.java", "class AppTest {}");
        workspace.givenHeadRevision("abc123");
        return workspace;
    }

    @Test
    void readOnlyCapabilityIsUsableOnItsOwn() {
        WorkspaceReadPort read = workspace();

        assertEquals("abc123", read.headRevision(WORKSPACE));
        assertEquals("# demo", read.readFile(WORKSPACE, "README.md"));
    }

    @Test
    void listsEntriesWithinRequestedDepth() {
        WorkspaceReadPort read = workspace();

        List<String> rootDepth1 = read.listEntries(WORKSPACE, "", 1).stream()
                .map(WorkspaceEntry::relativePath).toList();
        assertEquals(List.of("README.md", "src"), rootDepth1);

        List<String> rootDepth2 = read.listEntries(WORKSPACE, "", 2).stream()
                .map(WorkspaceEntry::relativePath).toList();
        assertEquals(List.of("README.md", "src", "src/main", "src/test"), rootDepth2);

        List<String> srcDepth1 = read.listEntries(WORKSPACE, "src", 1).stream()
                .map(WorkspaceEntry::relativePath).toList();
        assertEquals(List.of("src/main", "src/test"), srcDepth1);
    }

    @Test
    void listEntriesMarksDirectories() {
        WorkspaceReadPort read = workspace();

        List<WorkspaceEntry> entries = read.listEntries(WORKSPACE, "", 1);

        WorkspaceEntry src = entries.stream()
                .filter(entry -> entry.relativePath().equals("src")).findFirst().orElseThrow();
        WorkspaceEntry readme = entries.stream()
                .filter(entry -> entry.relativePath().equals("README.md")).findFirst().orElseThrow();

        assertTrue(src.directory());
        assertFalse(readme.directory());
    }

    @Test
    void mutationIsVisibleThroughReadCapability() {
        InMemoryWorkspace workspace = workspace();
        WorkspaceReadPort read = workspace;
        WorkspaceMutationPort mutation = workspace;

        mutation.writeFile(WORKSPACE, "src/main/App.java", "class App { /* evolved */ }");

        assertEquals("class App { /* evolved */ }", read.readFile(WORKSPACE, "src/main/App.java"));
    }

    /**
     * RULE-ARCH-010 / RULE-ARCH-011 的类型层面保障：只读流程注入的 Port 中不存在修改能力。
     * 该断言用于防止后续有人把两个能力合并回同一个接口。
     */
    @Test
    void readAndMutationCapabilitiesAreSeparateTypes() {
        assertFalse(WorkspaceReadPort.class.isAssignableFrom(WorkspaceMutationPort.class));
        assertFalse(WorkspaceMutationPort.class.isAssignableFrom(WorkspaceReadPort.class));
    }

    @Test
    void readFailureIsExpressedAsWorkspaceException() {
        WorkspaceReadPort read = workspace();

        assertThrows(WorkspaceException.class, () -> read.readFile(WORKSPACE, "missing.txt"));
    }

    @Test
    void rejectsBlankWorkspaceRef() {
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceRef(null));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceRef("  "));
    }

    /**
     * 业务代码只应接触相对于 Workspace 根目录的路径，不得接触宿主机绝对路径，
     * 也不得通过 {@code ..} 越出 Workspace 根目录。
     */
    @Test
    void rejectsAbsoluteOrEscapingPaths() {
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceEntry("/etc/passwd", false));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceEntry("\\windows\\system32", false));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceEntry("C:\\repo\\a.txt", false));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceEntry("src/../../outside.txt", false));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceEntry("", false));
        assertThrows(IllegalArgumentException.class, () -> new WorkspaceEntry(null, false));
    }

    @Test
    void acceptsOrdinaryRelativePaths() {
        assertEquals("src/main/App.java",
                new WorkspaceEntry("src/main/App.java", false).relativePath());
    }
}
