package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentPolicyRejection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExactEditPatchTest {
    @TempDir Path root;

    private int apply(String patch, boolean check) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.longpaths=true",
                "-c", "core.autocrlf=false", "apply", "--no-index", "--whitespace=nowarn"));
        if (check) {
            command.add("--check");
        }
        command.add("-");
        var process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        try (var input = process.getOutputStream()) {
            input.write(patch.getBytes(StandardCharsets.UTF_8));
        }
        process.getInputStream().readAllBytes();
        return process.waitFor();
    }

    @Test
    void exactReplacementPreservesUtf8CrLfAndMissingFinalNewlineWithRealGit() throws Exception {
        String source = "before\r\n中文,\"quoted\"\r\nafter";
        Files.writeString(root.resolve("source.txt"), source);
        String patch = ExactEditPatch.create("source.txt", source, "中文,\"quoted\"", "changed\nvalue", 131072);
        assertEquals(0, apply(patch, true));
        assertEquals(source, Files.readString(root.resolve("source.txt")));
        assertEquals(0, apply(patch, false));
        assertEquals("before\r\nchanged\nvalue\r\nafter", Files.readString(root.resolve("source.txt")));
    }

    @Test
    void creationUsesGitNoOverwriteAndRemainsNonMutatingIfPathAppears() throws Exception {
        String patch = ExactEditPatch.create("created.txt", null, "", "new\ncontent", 131072);
        assertEquals(0, apply(patch, true));
        Files.writeString(root.resolve("created.txt"), "user-owned");
        assertEquals(1, apply(patch, true));
        assertEquals("user-owned", Files.readString(root.resolve("created.txt")));
        assertEquals("CONTEXT_MISMATCH", assertThrows(ExactEditPatch.Conflict.class,
                () -> ExactEditPatch.create("created.txt", "user-owned", "", "replacement", 131072)).reason());
    }

    @Test
    void missingAmbiguousOrAlreadyAppliedEditsHaveExplicitPreMutationConflicts() {
        assertEquals("CONTEXT_MISMATCH", assertThrows(ExactEditPatch.Conflict.class,
                () -> ExactEditPatch.create("a", "aa", "a", "b", 131072)).reason());
        assertEquals("CONTEXT_MISMATCH", assertThrows(ExactEditPatch.Conflict.class,
                () -> ExactEditPatch.create("a", "current", "previous", "new", 131072)).reason());
        assertEquals("PATH_STATE_MISMATCH", assertThrows(ExactEditPatch.Conflict.class,
                () -> ExactEditPatch.create("a", null, "previous", "new", 131072)).reason());
        assertEquals("CHANGE_ALREADY_PRESENT", assertThrows(ExactEditPatch.Conflict.class,
                () -> ExactEditPatch.create("a", "current", "current", "current", 131072)).reason());
    }

    @Test
    void generatedPatchStillRejectsStaleSourceAndRetainsTheOriginalSizeBudget() throws Exception {
        String patch = ExactEditPatch.create("a.txt", "current\n", "current", "new", 131072);
        Files.writeString(root.resolve("a.txt"), "concurrent-user-change\n");
        assertEquals(1, apply(patch, true));
        assertEquals("concurrent-user-change\n", Files.readString(root.resolve("a.txt")));
        assertEquals(AgentPolicyRejection.Rule.PATCH_SIZE, assertThrows(AgentPolicyRejection.class,
                () -> ExactEditPatch.create("a", null, "", "some content", 2)).rule());
    }

    @Test
    void emptyReplacementAndPatchHeaderLookingDataAreLiteralContent() throws Exception {
        String source = "--- a/private\n+++ b/private\nretain\n";
        Files.writeString(root.resolve("a.txt"), source);
        String patch = ExactEditPatch.create("a.txt", source, "--- a/private\n+++ b/private\n", "", 131072);
        assertEquals(0, apply(patch, false));
        assertEquals("retain\n", Files.readString(root.resolve("a.txt")));
    }
}
