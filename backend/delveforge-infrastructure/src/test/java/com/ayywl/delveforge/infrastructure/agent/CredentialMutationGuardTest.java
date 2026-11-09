package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentPolicyRejection;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CredentialMutationGuardTest {
    @TempDir Path root;
    private final CredentialMutationGuard guard = new CredentialMutationGuard(new DeterministicRepositorySecretPolicy());

    private CredentialMutationGuard.Changes changes(String before, String after) throws Exception {
        Path old = root.resolve("old"), updated = root.resolve("updated");
        Files.writeString(old, before == null ? "" : before);
        Files.writeString(updated, after == null ? "" : after);
        var process = new ProcessBuilder("git", "diff", "--no-index", "--no-ext-diff", "--no-textconv", "--unified=0",
                "--", old.toString(), updated.toString()).redirectErrorStream(true).start();
        String diff = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        if (exit != 0 && exit != 1) {
            throw new IllegalStateException("Controlled fixture comparison failed");
        }
        return CredentialMutationGuard.ranges(diff);
    }

    @Test
    void safeEditsOnBothSidesOfEmbeddedSecretAreAllowedWithoutChangingItsBytes() throws Exception {
        String before = "ordinary=1;\npassword='synthetic-private-value';\nlast=1;\n";
        String after = before.replace("ordinary=1", "ordinary=2").replace("last=1", "last=2");
        guard.requireSafe("Config.java", before, after, changes(before, after));
    }

    @Test
    void configReplacementDeletionAndSameLineEditsAreAllowed() throws Exception {
        String before = "password='synthetic-private-value'; int ordinary=1;\nother();\n";
        for (String after : new String[]{before.replace("synthetic-private-value", "different-private-value"),
                "other();\n", before.replace("ordinary=1", "ordinary=2")}) {
            var delta = changes(before, after);
            guard.requireSafe("Config.java", before, after, delta);
        }
    }

    @Test
    void onlyAccidentalRedactionWritebackIsRejected() throws Exception {
        String before = "ordinary();\n";
        for (String after : new String[]{"password='synthetic-private-value';\n", "value='[redacted-credential]';\n",
                "String key='ghp_0123456789abcdefghijklmnopqrstuvwx';\n"}) {
            var delta = changes(before, after);
            if (after.contains("[redacted-credential]")) {
                assertEquals(AgentPolicyRejection.Rule.PATCH_CREDENTIALS, assertThrows(AgentPolicyRejection.class,
                        () -> guard.requireSafe("Auth.js", before, after, delta)).rule());
            } else {
                guard.requireSafe("Auth.js", before, after, delta);
            }
        }
    }

    @Test
    void ordinaryJwtHandlersAndGeneratedTestValuesStayEditable() throws Exception {
        String before = "getToken: () => localStorage.getItem('token'),\n";
        String after = before + "const token = this.getToken();\nconst password = randomValue();\n";
        guard.requireSafe("Auth.js", before, after, changes(before, after));
    }

    @Test
    void springReferencesAndNonemptyDefaultsCanBeEdited() throws Exception {
        String before = "spring.application.name=fixture\nspring.datasource.password=\n";
        String after = "spring.application.name=fixture\nspring.datasource.password=${DB_PASSWORD:}\n"
                + "app.jwt.secret=${JWT_SECRET}\n";
        String path = "src/main/resources/application.properties";
        guard.requireSafe(path, before, after, changes(before, after));
        for (String literal : new String[]{"embedded-private-value", "${DB_PASSWORD:embedded-private-value}"}) {
            String unsafe = after.replace("${DB_PASSWORD:}", literal);
            var delta = changes(after, unsafe);
            guard.requireSafe(path, after, unsafe, delta);
        }
        String protectedBefore = "spring.datasource.password=embedded-private-value\n";
        var protectedDelta = changes(protectedBefore, after);
        guard.requireSafe(path, protectedBefore, after, protectedDelta);
    }

    @Test
    void protectedLinesKeepCrLfAndExactEditDiffDoesNotCopyAdjacentSecret() throws Exception {
        String before = "password='synthetic-private-value';\r\nordinary=1;\r\n";
        String after = before.replace("ordinary=1", "ordinary=2");
        guard.requireSafe("Config.java", before, after, changes(before, after));
        String diff = ExactEditPatch.create("Config.java", before, "ordinary=1", "ordinary=2", 131072);
        assertFalse(diff.contains("synthetic-private-value"));
    }

    @Test
    void gitFunctionHeadingsCannotSmuggleUnchangedSourceIntoFrozenDiff() {
        String diff = "@@ -3 +3 @@ password='synthetic-private-value'\n-old\n+new\n";
        assertEquals("@@ -3 +3 @@\n-old\n+new\n", DockerAgentSandbox.removeHunkHeadings(diff));
    }
}
