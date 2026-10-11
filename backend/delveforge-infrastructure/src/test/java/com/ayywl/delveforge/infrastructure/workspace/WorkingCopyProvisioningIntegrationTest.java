package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkingCopyProvisioningIntegrationTest {
    private final GitTestRepositories repositories = new GitTestRepositories();
    @AfterEach void cleanup() throws Exception {
        repositories.deleteAll();
    }

    @Test
    void independentCloneUsesAnalyzedCommitAndLeavesOriginalIncludingDirtyFilesUntouched() throws Exception {
        var source = repositories.createCommitted("source", Map.of("report.txt", "committed"));
        var revision = GitTestRepositories.headRevision(source);
        Files.writeString(source.resolve("report.txt"), "dirty");
        Files.writeString(source.resolve("untracked.txt"), "untracked");
        var before = GitTestRepositories.snapshot(source);
        var adapter = new GitWorkspaceAdapter(repositories.directory("managed"));
        PreparedWorkspace prepared = adapter.provision(new WorkspaceRef(source.toString()), revision, "copy");
        var copy = Path.of(prepared.workspace().value());
        assertEquals(revision, prepared.revision());
        assertEquals(revision, GitTestRepositories.headRevision(copy));
        assertEquals("committed", Files.readString(copy.resolve("report.txt")));
        assertFalse(Files.exists(copy.resolve("untracked.txt")));
        assertFalse(Files.exists(copy.resolve(".git/objects/info/alternates")));
        assertEquals("", GitTestRepositories.runGit(copy, "status", "--porcelain"));
        try (var paths = Files.walk(source.resolve(".git/objects"))) {
            for (var object : paths.filter(Files::isRegularFile).toList()) {
                Path counterpart = copy.resolve(".git/objects").resolve(source.resolve(".git/objects").relativize(object));
                if (Files.exists(counterpart)) {
                    assertFalse(Files.isSameFile(object, counterpart));
                }
            }
        }
        Files.writeString(copy.resolve("report.txt"), "copy-only");
        assertEquals(before, GitTestRepositories.snapshot(source));
        adapter.discard(prepared);
        assertFalse(Files.exists(copy));
        assertEquals(before, GitTestRepositories.snapshot(source));
    }

    @Test
    void longWindowsCheckoutPathsAreMaterializedAndRemainClean() throws Exception {
        var root = repositories.directory("managed");
        String name = "copy";
        // 只让目标文件越过 260 字符；源文件仍可由默认 Git 建立，避免夹具自身先失败。
        int filenameLength = Math.max(1, 262 - root.resolve(name).toString().length() - 9);
        String relative = "src/" + "f".repeat(filenameLength) + ".txt";
        var source = repositories.createCommitted("source", Map.of(relative, "committed"));
        var before = GitTestRepositories.snapshot(source);
        assertTrue(root.resolve(name).resolve(relative).toString().length() > 260);

        var adapter = new GitWorkspaceAdapter(root);
        PreparedWorkspace prepared = adapter.provision(new WorkspaceRef(source.toString()),
                GitTestRepositories.headRevision(source), name);

        Path copy = Path.of(prepared.workspace().value());
        assertEquals("committed", Files.readString(copy.resolve(relative)));
        assertEquals("", GitTestRepositories.runGit(copy, "-c", "core.longpaths=true", "status", "--porcelain"));
        assertEquals(before, GitTestRepositories.snapshot(source));
        adapter.discard(prepared);
        assertFalse(Files.exists(copy));
    }

    @Test
    void rejectsStaleRevisionBeforeCreatingAnyCandidate() throws Exception {
        var source = repositories.createCommitted("source", Map.of("report.txt", "one"));
        String old = GitTestRepositories.headRevision(source);
        Files.writeString(source.resolve("report.txt"), "two");
        GitTestRepositories.runGit(source, "add", "report.txt");
        GitTestRepositories.commit(source, "next");
        var before = GitTestRepositories.snapshot(source);
        var root = repositories.directory("managed");
        assertThrows(WorkingCopyBasisChangedException.class, () -> new GitWorkspaceAdapter(root)
                .provision(new WorkspaceRef(source.toString()), old, "copy"));
        assertFalse(Files.exists(root));
        assertEquals(before, GitTestRepositories.snapshot(source));
    }

    @Test
    void refusesUnsafeNamesOverlappingStorageAndExistingTarget() throws Exception {
        var source = repositories.createCommitted("source", Map.of("report.txt", "one"));
        var ref = new WorkspaceRef(source.toString());
        var revision = GitTestRepositories.headRevision(source);
        var root = repositories.directory("managed");
        var adapter = new GitWorkspaceAdapter(root);
        for (String name : new String[]{"../escape", "nested/copy", "CON", "C:\\escape", "."}) {
            assertThrows(WorkspaceException.class, () -> adapter.provision(ref, revision, name));
        }
        assertThrows(WorkspaceException.class,
                () -> new GitWorkspaceAdapter(source.resolve("copies")).provision(ref, revision, "copy"));
        Files.createDirectories(root.resolve("existing"));
        Files.writeString(root.resolve("existing/keep.txt"), "keep");
        assertThrows(WorkspaceException.class, () -> adapter.provision(ref, revision, "existing"));
        assertEquals("keep", Files.readString(root.resolve("existing/keep.txt")));
        assertFalse(Files.exists(source.resolve("copies")));
    }

    @Test
    void refusesCleanupWithForgedOwnershipOrOutsideManagedRoot() throws Exception {
        var source = repositories.createCommitted("source", Map.of("report.txt", "one"));
        var ref = new WorkspaceRef(source.toString());
        var adapter = new GitWorkspaceAdapter(repositories.directory("managed"));
        PreparedWorkspace prepared = adapter.provision(ref, GitTestRepositories.headRevision(source), "copy");
        assertThrows(WorkspaceException.class, () -> adapter.discard(
                new PreparedWorkspace(prepared.workspace(), prepared.revision(), "forged")));
        assertThrows(WorkspaceException.class, () -> adapter.discard(
                new PreparedWorkspace(ref, prepared.revision(), prepared.preparationToken())));
        assertTrue(Files.exists(source.resolve("report.txt")));
        assertTrue(Files.exists(Path.of(prepared.workspace().value())));
        adapter.discard(prepared);
    }

    @Test
    void customRootCreatesOnlyNewCopiesAndExplicitOldRootPreservesExistingCopy() throws Exception {
        Path source = repositories.createCommitted("source", Map.of("report.txt", "one"));
        WorkspaceRef ref = new WorkspaceRef(source.toString());
        String revision = GitTestRepositories.headRevision(source);
        Path oldRoot = repositories.directory("old-managed");
        GitWorkspaceAdapter oldAdapter = new GitWorkspaceAdapter(oldRoot);
        PreparedWorkspace oldCopy = oldAdapter.provision(ref, revision, "old-copy");
        Map<String, String> before = GitTestRepositories.snapshot(Path.of(oldCopy.workspace().value()));
        Path newRoot = repositories.directory("new-storage/workspaces");
        GitWorkspaceAdapter changed = new GitWorkspaceAdapter(new WorkspaceProperties(newRoot, java.util.List.of(oldRoot)));
        PreparedWorkspace newCopy = changed.provision(ref, revision, "new-copy");
        assertEquals(newRoot.resolve("new-copy"), Path.of(newCopy.workspace().value()));
        changed.requireTrustedState(oldCopy.workspace(), ref, revision);
        assertEquals(before, GitTestRepositories.snapshot(Path.of(oldCopy.workspace().value())));
        assertThrows(WorkingCopyBasisChangedException.class,
                () -> new GitWorkspaceAdapter(newRoot).requireTrustedState(oldCopy.workspace(), ref, revision));
        assertTrue(Files.exists(Path.of(oldCopy.workspace().value())));
        changed.discard(newCopy);
        changed.discard(oldCopy);
    }

    @Test
    void unavailableOrRelativeRootNeverFallsBackOrOverwritesAFile() throws Exception {
        Path source = repositories.createCommitted("source", Map.of("report.txt", "one"));
        WorkspaceRef ref = new WorkspaceRef(source.toString());
        String revision = GitTestRepositories.headRevision(source);
        Path file = repositories.directory("not-a-directory");
        Files.writeString(file, "keep");
        Map<String, String> before = GitTestRepositories.snapshot(source);
        for (Path root : new Path[] {file, file.resolve("workspaces"), Path.of("relative-workspaces")}) {
            assertThrows(WorkspaceStorageUnavailableException.class,
                    () -> new GitWorkspaceAdapter(root).provision(ref, revision, "copy"));
        }
        assertEquals("keep", Files.readString(file));
        assertEquals(before, GitTestRepositories.snapshot(source));
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            java.util.List<Path> mounted = java.util.Arrays.stream(java.io.File.listRoots())
                    .map(java.io.File::toPath).toList();
            Path absentDrive = java.util.stream.IntStream.rangeClosed('A', 'Z')
                    .mapToObj(letter -> Path.of((char) letter + ":\\"))
                    .filter(path -> !mounted.contains(path)).findFirst().orElseThrow();
            assertThrows(WorkspaceStorageUnavailableException.class,
                    () -> new GitWorkspaceAdapter(absentDrive.resolve("workspaces"))
                            .provision(ref, revision, "copy"));
            assertEquals(before, GitTestRepositories.snapshot(source));
        }
    }

    @Test
    void rejectsConfiguredRootThroughSymlinkOrWindowsJunction() throws Exception {
        Path source = repositories.createCommitted("source", Map.of("report.txt", "one"));
        Path destination = repositories.directory("other-directory");
        Files.createDirectory(destination);
        Path link = repositories.directory("linked-root");
        if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), destination.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.waitFor(), output);
        } else {
            Files.createSymbolicLink(link, destination);
        }
        try {
            assertThrows(WorkspaceStorageUnavailableException.class, () -> new GitWorkspaceAdapter(link.resolve("copies"))
                    .provision(new WorkspaceRef(source.toString()), GitTestRepositories.headRevision(source), "copy"));
            assertFalse(Files.exists(destination.resolve("copies")));
        } finally {
            // 仅删除本测试建立的链接自身，不能递归进入其目标。
            Files.delete(link);
        }
    }

    @Test
    void realCloneFailureRemovesOnlyItsAllocatedCandidateAndLeavesSourceUntouched() throws Exception {
        var source = repositories.createCommitted("source", Map.of("report.txt", "one"));
        var revision = GitTestRepositories.headRevision(source);
        String blob = GitTestRepositories.runGit(source, "rev-parse", "HEAD:report.txt").trim();
        Path object = source.resolve(".git/objects").resolve(blob.substring(0, 2)).resolve(blob.substring(2));
        object.toFile().setWritable(true);
        Files.writeString(object, "corrupt object"); // 保持 commit / HEAD 可解析，让失败发生在真实 clone 传输阶段。
        var before = GitTestRepositories.snapshot(source);
        Path root = repositories.directory("managed");
        Files.createDirectories(root.resolve("existing"));
        Files.writeString(root.resolve("existing/keep.txt"), "keep");
        assertThrows(WorkspaceException.class, () -> new GitWorkspaceAdapter(root)
                .provision(new WorkspaceRef(source.toString()), revision, "candidate"));
        assertFalse(Files.exists(root.resolve("candidate")));
        assertEquals("keep", Files.readString(root.resolve("existing/keep.txt")));
        assertEquals(before, GitTestRepositories.snapshot(source));
    }
}
