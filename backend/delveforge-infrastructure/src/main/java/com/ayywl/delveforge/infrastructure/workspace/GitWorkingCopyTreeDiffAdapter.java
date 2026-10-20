package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.WorkingCopyTreeDiffPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.domain.evolution.WorkingCopy;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** 仅枚举固定 tree 的 mode / blob 身份；不运行 diff 驱动、过滤器或任何工作树命令。 */
@Component
public final class GitWorkingCopyTreeDiffAdapter implements WorkingCopyTreeDiffPort {
    private static final int MAX_BYTES = 1048576;
    private static final int MAX_ENTRIES = 10000;
    private final WorkingCopyStorageInspector storage;

    public GitWorkingCopyTreeDiffAdapter(WorkingCopyStorageInspector storage) {
        this.storage = storage;
    }

    @Override
    public Difference compare(WorkingCopy copy, String baseline, String verified) {
        String issue = storage.inspect(copy);
        if (issue != null) {
            return Difference.unavailable(issue);
        }
        if (!objectId(baseline) || !objectId(verified)) {
            return Difference.unavailable("REVISION_INVALID");
        }
        Path target = Path.of(copy.location());
        try {
            if (!safeMetadata(target.resolve(".git"))) {
                return Difference.unavailable("WORKSPACE_METADATA_UNSAFE");
            }
            String before = tree(target, baseline);
            String after = tree(target, verified);
            if (before == null || after == null) {
                return Difference.unavailable("HISTORICAL_OBJECT_MISSING");
            }
            Map<String, String> oldFiles = entries(target, before);
            Map<String, String> newFiles = entries(target, after);
            if (oldFiles == null || newFiles == null) {
                return Difference.unavailable("TREE_UNREADABLE_OR_UNSUPPORTED");
            }
            TreeSet<String> paths = new TreeSet<>(oldFiles.keySet());
            paths.addAll(newFiles.keySet());
            List<FileChange> changes = new ArrayList<>();
            for (String path : paths) {
                String oldValue = oldFiles.get(path);
                String newValue = newFiles.get(path);
                if (!java.util.Objects.equals(oldValue, newValue)) {
                    changes.add(new FileChange(path, oldValue == null ? "ADDED"
                            : newValue == null ? "DELETED" : "MODIFIED"));
                }
            }
            return new Difference(null, before, after, List.copyOf(changes));
        } catch (IOException | UncheckedIOException | SecurityException failure) {
            return Difference.unavailable("WORKSPACE_UNREADABLE");
        } catch (WorkspaceException failure) {
            // 执行器的期限和输出预算失败不能伪装成完整空列表；不回显宿主路径或 stderr。
            return Difference.unavailable("READ_FAILED_OR_LIMIT_EXCEEDED");
        }
    }

    private boolean safeMetadata(Path metadata) throws IOException {
        if (!Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS) || !metadata.toRealPath().equals(metadata)
                || !Files.isDirectory(metadata.resolve("objects"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(metadata.resolve("commondir"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(metadata.resolve("objects/info/alternates"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(metadata.resolve("objects/info/http-alternates"), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        // 历史副本必须仍有托管凭据；不接管普通仓库，也不跟随 metadata 中的链接。
        Path marker = metadata.resolve("delveforge-preparation");
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 4096) {
            return false;
        }
        try (var paths = Files.walk(metadata)) {
            var iterator = paths.iterator();
            int count = 0;
            while (iterator.hasNext()) {
                Path path = iterator.next();
                if (++count > MAX_ENTRIES || Files.isSymbolicLink(path) || !path.toRealPath().equals(path)) {
                    return false;
                }
            }
        }
        return !Files.readString(marker).isBlank();
    }

    private String tree(Path target, String revision) {
        var commit = read(target, "rev-parse", "--verify", "--quiet", "--end-of-options", revision + "^{commit}");
        if (!commit.succeeded() && commit.exitCode() != 1) {
            throw new WorkspaceException("Cannot resolve historical commit with controlled Git");
        }
        if (!commit.succeeded() || !revision.equals(commit.output().trim())) {
            return null;
        }
        var tree = read(target, "rev-parse", "--verify", "--quiet", "--end-of-options", revision + "^{tree}");
        if (!tree.succeeded() && tree.exitCode() != 1) {
            throw new WorkspaceException("Cannot resolve historical tree with controlled Git");
        }
        String value = tree.output().trim();
        return tree.succeeded() && objectId(value) ? value : null;
    }

    private Map<String, String> entries(Path target, String tree) {
        var result = read(target, "ls-tree", "-r", "-z", "--full-tree", tree);
        if (!result.succeeded()) {
            return null;
        }
        Map<String, String> entries = new TreeMap<>();
        if (result.output().isEmpty()) {
            return entries;
        }
        if (!result.output().endsWith("\0")) {
            return null;
        }
        for (String entry : result.output().split("\0")) {
            int tab = entry.indexOf('\t');
            if (tab < 0) {
                return null;
            }
            String identity = entry.substring(0, tab);
            String path = entry.substring(tab + 1);
            if (!identity.matches("100(?:644|755) blob (?:[0-9a-f]{40}|[0-9a-f]{64})")
                    || path.isBlank() || path.indexOf('\ufffd') >= 0 || path.indexOf('\\') >= 0
                    || path.startsWith("/") || path.codePoints().anyMatch(Character::isISOControl)
                    || List.of(path.split("/")).stream().anyMatch(part -> part.equals("..") || part.equals(".git"))
                    || entries.put(path, identity) != null || entries.size() > MAX_ENTRIES) {
                return null;
            }
        }
        return entries;
    }

    private WorkspaceCommandRunner.Result read(Path target, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "--no-pager", "--no-optional-locks",
                "--no-replace-objects", "--no-lazy-fetch", "-c", "core.fsmonitor=false", "-c",
                "core.hooksPath=" + target.resolve(".git/delveforge-disabled-hooks"), "-c", "core.attributesFile=",
                "--git-dir=" + target.resolve(".git"), "--work-tree=" + target));
        command.addAll(List.of(arguments));
        return WorkspaceCommandRunner.run(target, command, Duration.ofSeconds(3), MAX_BYTES);
    }

    private static boolean objectId(String value) {
        return value != null && value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})");
    }
}
