package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WorkingCopyInspectionIntegrationTest {
    @TempDir Path base;
    private Path source;
    private Path copy;
    private String revision;
    private GitWorkspaceAdapter adapter;

    @BeforeEach void createCopy() throws Exception {
        source = base.resolve("source");
        Files.createDirectories(source);
        git(source, "init", "-q");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/A.java"), "old\n");
        Files.writeString(source.resolve(".gitignore"), "ignored/\n");
        git(source, "add", ".");
        git(source, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "initial");
        revision = git(source, "rev-parse", "HEAD").trim();
        adapter = new GitWorkspaceAdapter(base.resolve("managed"));
        copy = Path.of(adapter.provision(new WorkspaceRef(source.toString()), revision, "copy").workspace().value());
    }

    private void inspect() {
        adapter.requireTrustedState(new WorkspaceRef(copy.toString()), new WorkspaceRef(source.toString()), revision);
    }

    @Test void checksCleanManagedCopyWithoutChangingSourceOrCopyFilesIncludingIndex() throws Exception {
        Map<String, String> sourceBefore = snapshot(source);
        Map<String, String> copyBefore = snapshot(copy);
        inspect();
        adapter.requireSafePaths(new WorkspaceRef(copy.toString()), List.of("src/A.java", "src/test/New.java"));
        assertEquals(sourceBefore, snapshot(source));
        assertEquals(copyBefore, snapshot(copy));
    }

    @Test void refusesOriginalAssetAndUnmanagedRepository() {
        assertThrows(WorkingCopyBasisChangedException.class,
                () -> adapter.requireTrustedState(new WorkspaceRef(source.toString()),
                        new WorkspaceRef(source.toString()), revision));
    }

    @Test void refusesChangedHeadDirtyTrackedAndUntrackedFiles() throws Exception {
        Files.writeString(copy.resolve("src/A.java"), "dirty\n");
        assertThrows(WorkingCopyBasisChangedException.class, this::inspect);
        Files.writeString(copy.resolve("src/A.java"), "old\n");
        Files.writeString(copy.resolve("unknown.java"), "untracked\n");
        assertThrows(WorkingCopyBasisChangedException.class, this::inspect);
        Files.delete(copy.resolve("unknown.java"));
        git(copy, "checkout", "--orphan", "other");
        git(copy, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "other");
        assertThrows(WorkingCopyBasisChangedException.class, this::inspect);
    }

    @Test void refusesIgnoredArtifactsMissingOwnershipAndSharedObjectStore() throws Exception {
        Path ignored = copy.resolve("ignored");
        Files.createDirectories(ignored);
        Files.writeString(ignored.resolve("output"), "artifact");
        assertThrows(WorkingCopyBasisChangedException.class, this::inspect);
        Files.delete(ignored.resolve("output"));
        Files.delete(ignored);
        Path marker = copy.resolve(".git/delveforge-preparation");
        String ownership = Files.readString(marker);
        Files.delete(marker);
        assertThrows(WorkingCopyBasisChangedException.class, this::inspect);
        Files.writeString(marker, ownership);
        Files.writeString(copy.resolve(".git/objects/info/alternates"), source.resolve(".git/objects").toString());
        assertThrows(WorkingCopyBasisChangedException.class, this::inspect);
    }

    @Test void proposedPathsCannotEscapeOrUseAFileAsParent() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.requireSafePaths(new WorkspaceRef(copy.toString()), List.of("../outside")));
        assertThrows(WorkingCopyBasisChangedException.class,
                () -> adapter.requireSafePaths(new WorkspaceRef(copy.toString()), List.of("src/A.java/child")));
    }

    @Test void proposedPathsCannotEnterNestedRepositoriesOrUnpopulatedSubmodules() throws Exception {
        Path nested = copy.resolve("nested");
        Files.createDirectories(nested);
        git(nested, "init", "-q");
        assertThrows(WorkingCopyBasisChangedException.class,
                () -> adapter.requireSafePaths(new WorkspaceRef(copy.toString()), List.of("nested/New.java")));

        git(copy, "update-index", "--add", "--cacheinfo", "160000," + revision + ",module");
        git(copy, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "gitlink");
        assertFalse(Files.exists(copy.resolve("module")));
        assertThrows(WorkingCopyBasisChangedException.class,
                () -> adapter.requireSafePaths(new WorkspaceRef(copy.toString()), List.of("module/New.java")));
    }

    @Test void exactSourceTextAllowsUtf8CrLfAndReplacementCharactersButRejectsDifferentContent() throws Exception {
        String text = "\u4f60\u597d\ufffd\r\n";
        Files.writeString(copy.resolve("src/Text.java"), text);
        git(copy, "-c", "core.autocrlf=false", "add", "--", "src/Text.java");
        git(copy, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "UTF-8 CRLF blob");
        String current = git(copy, "rev-parse", "HEAD").trim();
        WorkspaceRef workspace = new WorkspaceRef(copy.toString());
        assertEquals(text, adapter.readFile(workspace, current, "src/Text.java"));
        Map<String, String> beforeSource = snapshot(source);
        Map<String, String> beforeCopy = snapshot(copy);
        adapter.requireExactSourceText(workspace, current, Map.of("src/Text.java", text, "src/A.java", "old\n"));
        assertThrows(WorkingCopyTextNotExactException.class,
                () -> adapter.requireExactSourceText(workspace, current, Map.of("src/Text.java", "different\r\n")));
        assertEquals(beforeSource, snapshot(source));
        assertEquals(beforeCopy, snapshot(copy));
    }

    @Test void exactSourceTextRejectsNonUtf8BlobWithoutChangingAnalysisReadBehavior() throws Exception {
        Files.write(copy.resolve("src/Legacy.java"), "caf\u00e9\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        git(copy, "-c", "core.autocrlf=false", "add", "--", "src/Legacy.java");
        git(copy, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "non-UTF-8 blob");
        String current = git(copy, "rev-parse", "HEAD").trim();
        WorkspaceRef workspace = new WorkspaceRef(copy.toString());
        String decoded = adapter.readFile(workspace, current, "src/Legacy.java");
        assertEquals("caf\ufffd\n", decoded);
        Map<String, String> beforeSource = snapshot(source);
        Map<String, String> beforeCopy = snapshot(copy);
        assertThrows(WorkingCopyTextNotExactException.class,
                () -> adapter.requireExactSourceText(workspace, current, Map.of("src/Legacy.java", decoded)));
        assertEquals(beforeSource, snapshot(source));
        assertEquals(beforeCopy, snapshot(copy));
    }

    private static Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> result = new TreeMap<>();
        try (var stream = Files.walk(root)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(file).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
            }
        }
        return result;
    }

    private static String git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", root.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }
}
