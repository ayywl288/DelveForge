package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.domain.asset.SoftwareAssetId;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;
import com.ayywl.delveforge.domain.evolution.WorkingCopyId;
import com.ayywl.delveforge.domain.evolution.WorkingCopyStatus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.commit;
import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.headRevision;
import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.runGit;
import static com.ayywl.delveforge.infrastructure.workspace.GitTestRepositories.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GitWorkingCopyTreeDiffIntegrationTest {
    private final GitTestRepositories repositories = new GitTestRepositories();

    @AfterEach
    void cleanUp() throws Exception {
        repositories.deleteAll();
    }

    private Path repository() throws Exception {
        Path path = repositories.createCommitted("managed", Map.of(
                "modify.java", "old\n", "delete.java", "delete\n", "reverted.java", "original\n"));
        Files.writeString(path.resolve(".git/delveforge-preparation"), "owned-fixture");
        return path;
    }

    private WorkingCopy copy(Path path) {
        return WorkingCopy.reconstitute(new WorkingCopyId("copy"), new SoftwareAssetId("asset"),
                "baseline", path.toString(), "later", "later", WorkingCopyStatus.EVOLVING);
    }

    private GitWorkingCopyTreeDiffAdapter adapter(Path path) {
        return new GitWorkingCopyTreeDiffAdapter(new WorkingCopyStorageInspector(
                new WorkspaceProperties(path.getParent())));
    }

    private String save(Path path) throws Exception {
        runGit(path, "add", "-A");
        commit(path, "fixture stage");
        return headRevision(path);
    }

    @Test
    void comparesFixedTreesAcrossRepairsAndIgnoresLaterHeadAndDirtyWorkingTree() throws Exception {
        Path path = repository();
        String baseline = headRevision(path);
        Files.writeString(path.resolve("modify.java"), "new\n");
        Files.delete(path.resolve("delete.java"));
        Files.writeString(path.resolve("added.java"), "added\n");
        Files.writeString(path.resolve("reverted.java"), "temporary\n");
        save(path);
        Files.writeString(path.resolve("reverted.java"), "original\n");
        save(path);
        Files.writeString(path.resolve("modify.java"), "repaired\n");
        String verified = save(path);
        Files.writeString(path.resolve("later.java"), "next step\n");
        save(path);
        Files.writeString(path.resolve("dirty.java"), "uncommitted\n");
        Map<String, String> before = snapshot(path);

        var result = adapter(path).compare(copy(path), baseline, verified);

        assertNull(result.reason());
        assertEquals(java.util.List.of("added.java:ADDED", "delete.java:DELETED", "modify.java:MODIFIED"),
                result.files().stream().map(file -> file.path() + ":" + file.changeType()).toList());
        assertEquals(before, snapshot(path));
    }

    @Test
    void reportsAvailableZeroNetChangeAfterReversion() throws Exception {
        Path path = repository();
        String baseline = headRevision(path);
        Files.writeString(path.resolve("modify.java"), "temporary\n");
        save(path);
        Files.writeString(path.resolve("modify.java"), "old\n");
        String verified = save(path);
        var result = adapter(path).compare(copy(path), baseline, verified);
        assertNull(result.reason());
        assertEquals(result.baselineTree(), result.verifiedTree());
        assertEquals(java.util.List.of(), result.files());
    }

    @Test
    void missingObjectIsUnavailableAndDoesNotFetchOrReturnEmptyFiles() throws Exception {
        Path path = repository();
        var before = snapshot(path);
        var result = adapter(path).compare(copy(path), headRevision(path), "1".repeat(40));
        assertEquals("HISTORICAL_OBJECT_MISSING", result.reason());
        assertNull(result.files());
        assertEquals(before, snapshot(path));
    }

    @Test
    void rejectsRevisionExpressionsAndMissingOwnership() throws Exception {
        Path path = repository();
        assertEquals("REVISION_INVALID", adapter(path).compare(copy(path), "HEAD", headRevision(path)).reason());
        Files.delete(path.resolve(".git/delveforge-preparation"));
        assertEquals("WORKSPACE_METADATA_UNSAFE",
                adapter(path).compare(copy(path), headRevision(path), headRevision(path)).reason());
    }

    @Test
    void rejectsAlternatesAndUnconfiguredRoots() throws Exception {
        Path path = repository();
        String revision = headRevision(path);
        Files.writeString(path.resolve(".git/objects/info/alternates"), repositories.directory("other").toString());
        assertEquals("WORKSPACE_METADATA_UNSAFE", adapter(path).compare(copy(path), revision, revision).reason());
        var restricted = new GitWorkingCopyTreeDiffAdapter(new WorkingCopyStorageInspector(
                new WorkspaceProperties(repositories.directory("elsewhere"))));
        assertEquals("WORKSPACE_ROOT_NOT_ALLOWED", restricted.compare(copy(path), revision, revision).reason());
    }

    @Test
    void rejectsUnsupportedTreeEntriesWithoutReturningPartialFiles() throws Exception {
        Path path = repository();
        String baseline = headRevision(path);
        String candidate = treeCommit(path, baseline, "160000 commit " + baseline + "\tsubmodule\0");
        var result = adapter(path).compare(copy(path), baseline, candidate);
        assertEquals("TREE_UNREADABLE_OR_UNSUPPORTED", result.reason());
        assertNull(result.files());
    }

    @Test
    void outputBudgetFailureDoesNotReturnATruncatedDifference() throws Exception {
        Path path = repository();
        String baseline = headRevision(path);
        String blob = runGit(path, "rev-parse", baseline + ":modify.java").trim();
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < 8000; i++) {
            entries.append("100644 blob ").append(blob).append('\t')
                    .append("long-name-".repeat(13)).append(i).append(".java\0");
        }
        String candidate = treeCommit(path, baseline, entries.toString());
        var before = snapshot(path);
        var result = adapter(path).compare(copy(path), baseline, candidate);
        assertEquals("READ_FAILED_OR_LIMIT_EXCEEDED", result.reason());
        assertNull(result.files());
        assertEquals(before, snapshot(path));
    }

    private String treeCommit(Path path, String baseline, String entries) throws Exception {
        Process process = new ProcessBuilder("git", "-C", path.toString(), "mktree", "-z")
                .redirectErrorStream(true).start();
        process.getOutputStream().write(entries.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        String tree = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertEquals(0, process.waitFor(), tree);
        return runGit(path, "-c", "user.name=DelveForge Test", "-c", "user.email=test@delveforge.local",
                "commit-tree", tree, "-p", baseline, "-m", "synthetic tree").trim();
    }

    @Test
    void fixedObjectReadsIgnoreConfiguredExternalDiffAndHooks() throws Exception {
        Path path = repository();
        String revision = headRevision(path);
        runGit(path, "config", "diff.external", "command-that-must-not-run");
        runGit(path, "config", "core.fsmonitor", "command-that-must-not-run");
        var before = snapshot(path);
        assertNull(adapter(path).compare(copy(path), revision, revision).reason());
        assertEquals(before, snapshot(path));
    }
}
