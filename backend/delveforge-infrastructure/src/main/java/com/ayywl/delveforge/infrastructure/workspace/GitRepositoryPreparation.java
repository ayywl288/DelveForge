package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.domain.evolution.PreparationProvenance;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** 只在刚创建的私有 clone 中以独立 index 构造 P；原 A、源码与原仓库不变。 */
final class GitRepositoryPreparation {
    private GitRepositoryPreparation() {
    }

    static PreparationProvenance prepare(Path target, GitRepositoryCompatibility.Inspection inspection) {
        var report = inspection.report();
        if (!report.preparationRequired()) {
            return PreparationProvenance.unchanged(report.revision());
        }
        if (!report.supported()) {
            throw new WorkspaceException("Repository compatibility must be resolved before generated-output preparation");
        }
        Path index = target.resolve(".git/delveforge-index-preparation-" + UUID.randomUUID());
        Map<String, String> environment = Map.of("GIT_INDEX_FILE", index.toString(),
                "GIT_AUTHOR_NAME", "DelveForge Preparation", "GIT_AUTHOR_EMAIL", "preparation@delveforge.invalid",
                "GIT_COMMITTER_NAME", "DelveForge Preparation", "GIT_COMMITTER_EMAIL", "preparation@delveforge.invalid",
                "GIT_AUTHOR_DATE", "2000-01-01T00:00:00Z", "GIT_COMMITTER_DATE", "2000-01-01T00:00:00Z");
        RuntimeException primary = null;
        try {
            CompatibilityGit.run(target, null, environment, "read-tree", report.revision());
            StringBuilder deletions = new StringBuilder();
            TreeSet<String> roots = new TreeSet<>();
            for (String path : report.generatedPaths()) {
                deletions.append("0 ").append("0".repeat(40)).append('\t').append(path).append('\0');
                roots.add(GitRepositoryCompatibility.generatedRoot(path, inspection.poms().keySet()) + "target");
            }
            CompatibilityGit.run(target, deletions.toString().getBytes(StandardCharsets.UTF_8), environment,
                    "update-index", "-z", "--index-info");
            List<String> metadata = new ArrayList<>();
            List<String> ignore = new ArrayList<>();
            for (String root : roots) {
                String ignored = CompatibilityGit.text(target, null, Map.of(), "check-ignore", "--no-index", "--",
                        root + "/.delveforge-generated-probe");
                if (ignored.isEmpty()) {
                    ignore.add("/" + root + "/");
                }
            }
            if (!ignore.isEmpty()) {
                if (report.immutablePaths().contains(".gitignore")) {
                    throw new WorkspaceException("Protected ignore metadata requires an explicit user decision");
                }
                var entry = inspection.entries().get(".gitignore");
                byte[] original = entry == null ? new byte[0]
                        : CompatibilityGit.run(target, null, Map.of(), "cat-file", "blob", entry.objectId());
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(original)).toString();
                String ending = text.contains("\r\n") ? "\r\n" : "\n";
                String value = text + (text.isEmpty() || text.endsWith("\n") ? "" : ending)
                        + String.join(ending, ignore) + ending;
                String object = CompatibilityGit.text(target, value.getBytes(StandardCharsets.UTF_8), environment,
                        "hash-object", "-w", "--no-filters", "--stdin");
                CompatibilityGit.run(target, null, environment, "update-index", "--add", "--cacheinfo",
                        (entry == null ? "100644" : entry.mode()) + "," + object + ",.gitignore");
                metadata.add(".gitignore");
            }
            String tree = CompatibilityGit.text(target, null, environment, "write-tree");
            String prepared = CompatibilityGit.text(target, null, environment, "commit-tree", tree,
                    "-p", report.revision(), "-m", "DelveForge prepared baseline (" + GitRepositoryCompatibility.POLICY + ")");
            String parent = CompatibilityGit.text(target, null, Map.of(), "rev-list", "--parents", "-n", "1", prepared);
            if (!parent.equals(prepared + " " + report.revision())) {
                throw new WorkspaceException("Prepared commit does not retain its exact original parent");
            }
            CompatibilityGit.run(target, null, Map.of(), "update-ref", "HEAD", prepared, report.revision());
            CompatibilityGit.run(target, null, Map.of(), "read-tree", "--reset", "-u", prepared);
            return new PreparationProvenance(report.revision(), prepared, GitRepositoryCompatibility.POLICY,
                    report.generatedPaths(), metadata);
        } catch (IOException failure) {
            primary = new WorkspaceException("Cannot safely normalize managed ignore metadata", failure);
            throw primary;
        } catch (RuntimeException failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                Files.deleteIfExists(index);
            } catch (IOException failure) {
                var cleanup = new WorkspaceException("Cannot remove this preparation's private Git index", failure);
                if (primary != null) {
                    primary.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }
}
