package com.ayywl.delveforge.infrastructure.workbench;

import com.ayywl.delveforge.application.workbench.WorkbenchRejection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectWorkcopiesTest {
    @TempDir Path temp;

    private String git(Path directory, String... arguments) throws Exception {
        var cmd =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-C",
                                directory.toString(),
                                "-c",
                                "user.name=Fixture",
                                "-c",
                                "user.email=fixture@example.invalid",
                                "-c",
                                "core.autocrlf=false"));
        cmd.addAll(List.of(arguments));
        var process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output.strip();
    }

    @Test
    void independentBranchContainsCurrentBytesAndWorksAfterSourceMove() throws Exception {
        Path source = Files.createDirectory(temp.resolve("source"));
        git(source, "init", "-b", "personal/next");
        Files.writeString(source.resolve("file"), "baseline\n");
        Files.writeString(source.resolve("removed"), "deleted");
        Files.writeString(source.resolve(".gitignore"), ".local-conf\nnode_modules/\n");
        git(source, "add", ".");
        git(source, "commit", "-m", "baseline");
        String head = git(source, "rev-parse", "HEAD");
        Files.writeString(source.resolve("file"), "用户的\r\n改动\r\n");
        Files.delete(source.resolve("removed"));
        Files.writeString(source.resolve("new"), "untracked");
        Files.writeString(source.resolve(".local-conf"), "keep ignored project configuration");
        Files.createDirectories(source.resolve("node_modules/example"));
        Files.writeString(source.resolve("node_modules/example/generated"), "windows build");
        String before = git(source, "status", "--porcelain");
        var importer = new ProjectWorkcopies(temp.resolve("control/projects"));
        Path copy = Path.of(importer.prepare(UUID.randomUUID().toString(), source.toString()));
        assertEquals(before, git(source, "status", "--porcelain"));
        assertArrayEquals(
                Files.readAllBytes(source.resolve("file")),
                Files.readAllBytes(copy.resolve("file")));
        assertEquals(head, git(copy, "rev-parse", "HEAD"));
        assertEquals("personal/next", git(copy, "branch", "--show-current"));
        assertEquals("", git(copy, "diff", "--cached", "--name-only"));
        assertFalse(Files.exists(copy.resolve("removed")));
        assertFalse(Files.exists(copy.resolve("node_modules")));
        assertEquals("untracked", Files.readString(copy.resolve("new")));
        assertTrue(Files.exists(copy.resolve(".local-conf")));
        Files.move(source, temp.resolve("source-offline"));
        git(copy, "fsck", "--full");
        git(copy, "add", "-A");
        git(copy, "commit", "-m", "independent");
    }

    @Test
    void detachedAndUnbornBaselinesPreserveTheirActualHead() throws Exception {
        var importer = new ProjectWorkcopies(temp.resolve("control/projects"));
        Path source = Files.createDirectory(temp.resolve("source"));
        git(source, "init", "-b", "fresh");
        Files.writeString(source.resolve("new"), "new");
        Path unborn = Path.of(importer.prepare(UUID.randomUUID().toString(), source.toString()));
        assertEquals("refs/heads/fresh", git(unborn, "symbolic-ref", "HEAD"));
        assertTrue(git(unborn, "status", "--porcelain").contains("?? new"));
        git(source, "add", ".");
        git(source, "commit", "-m", "initial");
        git(source, "checkout", "--detach");
        Path detached = Path.of(importer.prepare(UUID.randomUUID().toString(), source.toString()));
        assertEquals("", git(detached, "branch", "--show-current"));
        assertEquals(git(source, "rev-parse", "HEAD"), git(detached, "rev-parse", "HEAD"));
    }

    @Test
    void newAndPlainDirectoryProjectsOwnTheirGitRoot() throws Exception {
        git(temp, "init", "-b", "host");
        var importer = new ProjectWorkcopies(temp.resolve("control/projects"));
        Path source = Files.createDirectories(temp.resolve("plain"));
        Files.writeString(source.resolve("note"), "hello");
        for (String origin : new String[] {null, source.toString()}) {
            Path copy = Path.of(importer.prepare(UUID.randomUUID().toString(), origin));
            assertTrue(Files.isSameFile(copy, Path.of(git(copy, "rev-parse", "--show-toplevel"))));
            assertEquals("main", git(copy, "branch", "--show-current"));
        }
        assertFalse(Files.exists(source.resolve(".git")));
    }

    @Test
    void nestedRepositoryAndOverlappingSourceAreNeverPublished() throws Exception {
        Path parent = temp.resolve("control/projects");
        var importer = new ProjectWorkcopies(parent);
        Path source = Files.createDirectories(temp.resolve("source/nested"));
        git(source, "init", "-b", "nested");
        String id = UUID.randomUUID().toString();
        assertThrows(
                WorkbenchRejection.class,
                () -> importer.prepare(id, source.getParent().toString()));
        assertThrows(WorkbenchRejection.class, () -> importer.prepare(id, parent.toString()));
        assertFalse(Files.exists(parent.resolve(id)));
    }
}
