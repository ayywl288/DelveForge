package com.ayywl.delveforge.domain.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 冻结的沙箱结果；expectedTree 是 Git tree，不是正式 WorkingCopy commit 或已接受的演化结果。 */
public record Candidate(String id, String runId, String baseline, String expectedTree, String diff,
        String sha256, List<String> changedPaths, String testEvidence, Instant createdAt) {

    public Candidate {
        Objects.requireNonNull(id);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(createdAt);
        if (id.isBlank() || runId.isBlank() || baseline == null || !baseline.matches("[0-9a-f]{40}")
                || expectedTree == null || !expectedTree.matches("[0-9a-f]{40}")
                || diff == null || diff.isBlank() || !digest(diff).equals(sha256)
                || changedPaths == null || changedPaths.isEmpty() || testEvidence == null) {
            throw new IllegalArgumentException("Candidate requires complete replay-validated material and integrity");
        }
        changedPaths = List.copyOf(changedPaths);
    }

    public static String digest(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 unavailable", impossible);
        }
    }
}
