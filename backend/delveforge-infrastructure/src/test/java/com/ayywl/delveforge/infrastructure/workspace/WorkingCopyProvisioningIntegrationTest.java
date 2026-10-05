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
