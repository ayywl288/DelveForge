package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.evolution.execution.EvolutionExecutionException;
import com.ayywl.delveforge.application.port.workspace.AuthorizedStepExecution;
import com.ayywl.delveforge.application.port.workspace.AuthorizedStepRollback;
import com.ayywl.delveforge.application.port.workspace.CandidateMutationResult;
import com.ayywl.delveforge.application.port.workspace.ConfirmedExecutionFailureException;
import com.ayywl.delveforge.application.port.workspace.PreparedWorkspace;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyBasisChangedException;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyInspectionPort;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyProvisioningPort;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyRollbackException;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyTextNotExactException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceMutationPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRollbackPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceStorageUnavailableException;
import com.ayywl.delveforge.domain.evolution.ExecutionFailureResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过 Local Git 分别提供仓库读取与隔离环境准备能力。
 *
 * <p>读取和准备能力通过独立 Port 暴露：所有 Git 调用都发生在 Infrastructure
 * 内部（RULE-ARCH-009），Domain 与 Application 只看到 {@link WorkspaceReadPort}
 * 与相对路径。M4 写能力通过独立 {@code WorkspaceMutationPort} 暴露，Repository Analysis
 * 仍只依赖读取 Port，在类型层面拿不到修改能力（RULE-ARCH-010、ADR-0001）。
 *
 * <h2>读取的是已提交内容，不是工作区</h2>
 *
 * <p>三个读取操作都作用于 {@code HEAD} 指向的那个 commit 的 tree 与 blob，
 * 而不是磁盘上的工作区文件：
 *
 * <pre>
 * headRevision   当前 HEAD 的 commit
 * listEntries    该 commit 的 tree 条目
 * readFile       该 commit 中该路径的 blob
 * </pre>
 *
 * <p>因此未提交修改、untracked 与被忽略的文件都不会出现在读取结果中，
 * 读到的内容与报告的 revision 严格对应。这不是「顺带过滤」：若读取工作区，
 * {@code RepositoryProfile.analyzedRevision} 就会声称分析了一个它其实没有描述的版本。
 *
 * <p>读取方法只使用 Git 的只读 plumbing 命令；provision 只在托管目录创建独立克隆：
 *
 * <pre>
 * rev-parse --is-inside-work-tree / --show-toplevel / --verify HEAD
 * ls-tree -r -t -z --full-tree HEAD
 * cat-file blob HEAD:path
 * </pre>
 *
 * <h2>当前实现前提</h2>
 *
 * <p>以下三点是当前 Adapter 的实现选择，不是领域模型或架构文档规定的要求：
 *
 * <pre>
 * 依赖运行环境能找到 git 可执行文件
 *     当前直接调用 PATH 上的 {@code git}。如果将来出现「用户机器上找不到 git」
 *     的真实场景，再评估改为可配置路径或引入纯 Java 的 Git 实现。
 *
 * WorkspaceRef 的 value 就是宿主机上的绝对路径
 *     领域侧的 Software Asset location 是一个位置引用，Application 负责把它映射为
 *     WorkspaceRef；本 Adapter 把该值当作本地路径来解释。相对路径被拒绝：
 *     它会相对于进程工作目录解析，静默接受会让「分析的是哪个位置」不可预期。
 *
 * 只读取带工作树的 Repository
 *     bare repository 没有工作树，{@link #isReadableRepository} 返回 false。
 * </pre>
 */
@Component
@EnableConfigurationProperties(WorkspaceProperties.class)
public class GitWorkspaceAdapter implements WorkspaceReadPort, WorkingCopyProvisioningPort,
        WorkingCopyInspectionPort, WorkspaceMutationPort, WorkspaceRollbackPort {

    private static final Logger log = LoggerFactory.getLogger(GitWorkspaceAdapter.class);

    private final Path managedRoot;
    private final List<Path> existingRoots;

    /**
     * 无配置构造仅供独立只读使用；环境准备必须显式配置托管位置。
     */
    public GitWorkspaceAdapter() {
        this.managedRoot = null;
        this.existingRoots = List.of();
    }

    @Autowired
    public GitWorkspaceAdapter(WorkspaceProperties properties) {
        this.managedRoot = properties.root();
        this.existingRoots = properties.existingRoots();
    }

    public GitWorkspaceAdapter(Path managedRoot) {
        this.managedRoot = managedRoot;
        this.existingRoots = List.of();
    }

    /** 核对物理 revision、隔离与整树内容；Candidate 通过此检查不等于已获得领域信任。 */
    @Override
    public void requireTrustedState(WorkspaceRef copy, WorkspaceRef source, String expectedRevision) {
        Path target = requireManagedCopy(copy);
        Path original = resolveLocation(source).toAbsolutePath().normalize();
        try {
            original = original.toRealPath();
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot inspect original asset isolation", failure);
        }
        if (target.startsWith(original) || original.startsWith(target)
                || target.getParent().startsWith(original) || original.startsWith(target.getParent())) {
            throw new WorkingCopyBasisChangedException("Working Copy overlaps the original asset");
        }
        if (!expectedRevision.equals(headRevision(copy))) {
            throw new WorkingCopyBasisChangedException("Working Copy HEAD differs from the trusted revision");
        }

        // status 会跳过这些索引项；只读检查标志，不能清除标志或刷新 index 来掩盖问题。
        GitCommandResult flags = execute(target, "--no-optional-locks", "-c", "core.fsmonitor=false",
                "ls-files", "--cached", "-v", "-z");
        if (!flags.succeeded()) {
            throw new WorkspaceException("Cannot inspect Working Copy index flags");
        }
        for (String entry : flags.stdoutText().split("\0")) {
            if (!entry.isEmpty() && (Character.isLowerCase(entry.charAt(0)) || entry.charAt(0) == 'S')) {
                throw new WorkingCopyBasisChangedException("Working Copy index flags can hide tracked changes");
            }
        }

        // 整树读取真实文件并重新计算 Git blob；stat 缓存、HEAD 与 status 都不能替代内容证据。
        requireTrackedContent(copy, target, expectedRevision);

        // tracked 内容已经独立证明；status / 工作树 diff 会调用 clean filter，额外文件只用枚举能力检查。
        if (!contentGit(target, expectedRevision, "ls-files", "--others", "--exclude-standard", "-z").isEmpty()
                || !contentGit(target, expectedRevision, "ls-files", "--others", "--ignored", "--exclude-standard", "-z")
                        .isEmpty()) {
            throw new WorkingCopyBasisChangedException("Working Copy contains changes or untracked artifacts");
        }
    }

    /** 独立于 index 的 stat 缓存核对全部 tracked 内容；固定版本的属性允许合法 Git 换行转换。 */
    private void requireTrackedContent(WorkspaceRef copy, Path target, String revision) {
        List<String> paths = new ArrayList<>();
        List<String> objects = new ArrayList<>();
        List<String> modes = new ArrayList<>();
        List<String> indexEntries = new ArrayList<>();
        String tree = contentGit(target, revision, "ls-tree", "-r", "-z", "--full-tree", revision);
        for (String entry : tree.split("\0")) {
            if (entry.isEmpty()) {
                continue;
            }
            int separator = entry.indexOf('\t');
            String[] metadata = separator < 0 ? new String[0] : entry.substring(0, separator).split(" ");
            if (metadata.length != 3 || !metadata[0].matches("100(644|755)")
                    || !metadata[1].equals("blob") || !metadata[2].matches("[0-9a-f]{40}")) {
                throw new WorkingCopyBasisChangedException("Tracked content cannot be proven for this Git tree entry");
            }
            String path = entry.substring(separator + 1);
            paths.add(path);
            objects.add(metadata[2]);
            modes.add(metadata[0]);
            indexEntries.add(metadata[0] + " " + metadata[2] + " 0\t" + path);
        }
        // 比较 index 的对象 / mode / stage，读取内容时仍不依赖其文件时间或大小缓存。
        List<String> actualIndex = Arrays.stream(contentGit(target, revision, "ls-files", "--stage", "-z")
                .split("\0")).filter(entry -> !entry.isEmpty()).toList();
        if (!actualIndex.equals(indexEntries)) {
            throw new WorkingCopyBasisChangedException("Working Copy index differs from the exact revision tree");
        }
        inspectSafePaths(copy, paths);
        // 替代 status 后保留 Git 启用 filemode 时的实际可执行位检查；Windows 不解释 POSIX mode。
        if (target.getFileSystem().supportedFileAttributeViews().contains("posix")
                && contentGit(target, revision, "config", "--type=bool", "--default=true", "--get", "core.filemode")
                        .trim().equals("true")) {
            try {
                for (int index = 0; index < paths.size(); index++) {
                    boolean executable = Files.getPosixFilePermissions(target.resolve(paths.get(index)),
                            LinkOption.NOFOLLOW_LINKS).contains(PosixFilePermission.OWNER_EXECUTE);
                    if (executable != modes.get(index).equals("100755")) {
                        throw new WorkingCopyBasisChangedException("Tracked executable mode differs from the exact revision");
                    }
                }
            } catch (IOException failure) {
                throw new WorkspaceException("Cannot prove tracked executable mode", failure);
            }
        }
        for (int start = 0; start < paths.size(); start += 32) {
            List<String> batch = paths.subList(start, Math.min(start + 32, paths.size()));
            requireSupportedAttributes(target, revision, batch);
            // 即使属性 / 配置在检查后变化，此命令也没有执行 filter 的能力。
            List<String> hashArguments = new ArrayList<>(List.of("hash-object", "--no-filters", "--"));
            hashArguments.addAll(batch);
            List<String> hashes = contentGit(target, revision, hashArguments.toArray(String[]::new)).lines().toList();
            if (hashes.size() != batch.size()) {
                throw new WorkingCopyBasisChangedException("Cannot read all tracked byte identities");
            }
            List<String> converted = new ArrayList<>();
            for (int index = 0; index < batch.size(); index++) {
                if (!hashes.get(index).equals(objects.get(start + index))) {
                    converted.add(batch.get(index));
                }
            }
            // 元数据按批次读取，仍逐文件证明完整真实字节；不能让 CRLF 文件数线性放大进程启动成本。
            Map<String, String> eolFacts = converted.isEmpty() ? Map.of() : trackedEolFacts(target, revision, converted);
            String autocrlf = converted.isEmpty() ? "false"
                    : contentGit(target, revision, "config", "--default=false", "--get", "core.autocrlf")
                            .trim().toLowerCase(Locale.ROOT);
            for (int index = 0; index < batch.size(); index++) {
                String expected = objects.get(start + index);
                String path = batch.get(index);
                if (!hashes.get(index).equals(expected)
                        && !lineEndingEquivalent(target, path, expected, eolFacts.get(path), autocrlf)) {
                    throw new WorkingCopyBasisChangedException("Working Copy tracked bytes differ from the exact revision");
                }
            }
        }
    }

    private Map<String, String> trackedEolFacts(Path target, String revision, List<String> paths) {
        List<String> arguments = new ArrayList<>(List.of("--literal-pathspecs", "ls-files", "--eol", "-z", "--"));
        arguments.addAll(paths);
        Map<String, String> facts = new HashMap<>();
        for (String record : contentGit(target, revision, arguments.toArray(String[]::new)).split("\0")) {
            int separator = record.indexOf('\t');
            String path = separator < 0 ? "" : record.substring(separator + 1);
            if (!paths.contains(path) || facts.put(path, record.substring(0, separator)) != null) {
                throw new WorkingCopyBasisChangedException("Cannot read exact tracked EOL descriptions");
            }
        }
        if (facts.size() != paths.size()) {
            throw new WorkingCopyBasisChangedException("Cannot read all tracked EOL descriptions");
        }
        return facts;
    }

    /** 仅使用 Git 的只读 EOL 描述和本地字节转换，不调用任何 Git clean / smudge / process。 */
    private boolean lineEndingEquivalent(Path target, String path, String expected, String eol, String autocrlf) {
        String[] facts = eol.trim().split("\\s+", 3);
        if (facts.length != 3 || !facts[0].equals("i/lf")
                || (!facts[1].equals("w/crlf") && !facts[1].equals("w/mixed"))) {
            return false;
        }
        String attributes = facts[2].trim();
        boolean text = List.of("attr/text", "attr/text=auto", "attr/text eol=lf", "attr/text eol=crlf",
                "attr/text=auto eol=lf", "attr/text=auto eol=crlf").contains(attributes);
        if (attributes.equals("attr/")) {
            text = autocrlf.equals("true") || autocrlf.equals("input");
        }
        if (!text) {
            return false;
        }
        try (var input = Files.newInputStream(target.resolve(path))) {
            // 只有需要 EOL 等价证明的文件才读取有界快照；超限不截断、不改写源码。
            byte[] actual = input.readNBytes(1048577);
            if (actual.length > 1048576) {
                throw new WorkspaceException("Tracked EOL proof exceeds its byte budget");
            }
            byte[] normalized = new byte[actual.length];
            int length = 0;
            for (int index = 0; index < actual.length; index++) {
                if (actual[index] == 0) {
                    return false;
                }
                if (actual[index] != '\r' || index + 1 == actual.length || actual[index + 1] != '\n') {
                    normalized[length++] = actual[index];
                }
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + length + "\0").getBytes(StandardCharsets.US_ASCII));
            digest.update(normalized, 0, length);
            return HexFormat.of().formatHex(digest.digest()).equals(expected);
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new WorkspaceException("Cannot prove tracked EOL bytes", failure);
        }
    }

    /** 按存在性拒绝外部转换；同时用于当前内容证明与恢复目标的 checkout 前检查。 */
    private void requireSupportedAttributes(Path target, String revision, List<String> paths) {
        List<String> arguments = new ArrayList<>(List.of("check-attr", "-z", "--all", "--"));
        arguments.addAll(paths);
        String output = contentGit(target, revision, arguments.toArray(String[]::new));
        String[] attributes = output.isEmpty() ? new String[0] : output.split("\0");
        if (attributes.length % 3 != 0) {
            throw new WorkingCopyBasisChangedException("Cannot confirm tracked content conversion attributes");
        }
        for (int offset = 0; offset < attributes.length; offset += 3) {
            if (!paths.contains(attributes[offset])
                    || List.of("filter", "ident", "working-tree-encoding").contains(attributes[offset + 1])) {
                // --all 的属性存在性不依赖歧义值；显式 -filter 也保守拒绝，不能把同名 driver 当作安全哨兵。
                throw new WorkingCopyBasisChangedException("Git conversion cannot prove exact tracked content");
            }
        }
    }

    private String contentGit(Path target, String revision, String... arguments) {
        // 属性来自不可变 revision，不采用可能被 stat 缓存漏报的工作树 .gitattributes；不写对象或刷新 index。
        List<String> exact = new ArrayList<>(List.of("--no-replace-objects", "--attr-source=" + revision,
                "-c", "core.safecrlf=false"));
        exact.addAll(List.of(arguments));
        WorkspaceCommandRunner.Result result = mutationGitResult(target, null, exact.toArray(String[]::new));
        if (!result.succeeded()) {
            throw new WorkspaceException("Cannot read exact tracked content with supported Git conversion");
        }
        return result.output();
    }

    @Override
    public void requireExactSourceText(WorkspaceRef copy, String revision, Map<String, String> expectedOriginals) {
        Path target = requireManagedCopy(copy);
        String commit = requireCommitId(target, revision);
        for (Map.Entry<String, String> original : expectedOriginals.entrySet()) {
            String path = requireSafeRelativePath(original.getKey(), false);
            GitCommandResult blob = execute(target, "cat-file", "blob", commit + ":" + path);
            if (!blob.succeeded()) {
                throw new WorkspaceException("Cannot inspect proposal source blob at expected revision");
            }
            String exact;
            try {
                // 仅 M4 修改资格使用严格解码；M2 的分析读取仍保留既有行为。
                exact = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(blob.stdout()))
                        .toString();
            } catch (CharacterCodingException invalid) {
                throw new WorkingCopyTextNotExactException("Proposal source blob is not lossless UTF-8 text");
            }
            if (!exact.equals(original.getValue())) {
                throw new WorkingCopyTextNotExactException("Proposal source text differs from the expected revision blob");
            }
        }
    }

    @Override
    public void requireSafePaths(WorkspaceRef copy, List<String> paths) {
        inspectSafePaths(copy, paths);
    }

    private void inspectSafePaths(WorkspaceRef copy, List<String> paths) {
        Path target = requireManagedCopy(copy);
        GitCommandResult tree = execute(target, "ls-tree", "-r", "-z", "HEAD");
        if (!tree.succeeded()) {
            throw new WorkspaceException("Cannot inspect Working Copy path modes");
        }
        List<String> submodules = new ArrayList<>();
        for (String entry : tree.stdoutText().split("\u0000")) {
            if (entry.startsWith("160000 commit ")) {
                submodules.add(entry.substring(entry.indexOf('\t') + 1));
            }
        }
        try {
            for (String path : paths) {
                String safe = requireSafeRelativePath(path, false);
                if (submodules.stream().anyMatch(prefix -> safe.equals(prefix) || safe.startsWith(prefix + "/"))) {
                    throw new WorkingCopyBasisChangedException("Proposal cannot modify a submodule tree");
                }
                Path resolved = target.resolve(safe).normalize();
                if (!resolved.startsWith(target) || resolved.equals(target)) {
                    throw new WorkingCopyBasisChangedException("Proposed path escapes the managed copy");
                }
                Path cursor = target;
                for (Path segment : target.relativize(resolved)) {
                    cursor = cursor.resolve(segment);
                    if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                        if (Files.isSymbolicLink(cursor) || !cursor.toRealPath().equals(cursor)) {
                            throw new WorkingCopyBasisChangedException("Proposed path crosses a filesystem link");
                        }
                        if (!cursor.equals(resolved) && !Files.isDirectory(cursor)) {
                            throw new WorkingCopyBasisChangedException("Proposed path has a non-directory parent");
                        }
                        if (Files.isDirectory(cursor) && Files.exists(cursor.resolve(".git"))) {
                            throw new WorkingCopyBasisChangedException("Proposed path enters a nested repository");
                        }
                        if (cursor.equals(resolved) && !Files.isRegularFile(cursor)) {
                            throw new WorkingCopyBasisChangedException("Proposed file is not a regular file");
                        }
                    }
                }
            }
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot inspect proposed paths", failure);
        }
    }

    /** 应用绑定 EXECUTING 检查点的补丁，发布一个本地 Candidate；不更新领域状态或模拟回滚。 */
    @Override
    public CandidateMutationResult applyCandidate(AuthorizedStepExecution execution) {
        WorkspaceRef copy = execution.workingCopy();
        String startingRevision = execution.validated().basis().expectedRevision();
        var proposal = execution.validated().proposal();
        requireTrustedState(copy, execution.originalAsset(), startingRevision);
        requireSafePaths(copy, proposal.declaredAffectedPaths());
        Path target = requireManagedCopy(copy);

        Path patch = null;
        Path expectedIndex = null;
        String expectedTree = null;
        String phase = "PATCH_MATERIALIZATION";
        ConfirmedExecutionFailureException confirmed = null;
        try {
            patch = Files.createTempFile(target.resolve(".git"), "delveforge-patch-", ".diff");
            Files.writeString(patch, proposal.unifiedDiff(), StandardCharsets.UTF_8);

            // 从本次不可变修改起点（首次 A / Repair B）生成 tree，不把 Candidate 起点误当作可信基线。
            expectedIndex = Files.createTempFile(target.resolve(".git"), "delveforge-index-", ".tmp");
            Files.delete(expectedIndex);
            phase = "EXPECTED_TREE";
            mutationGit(target, expectedIndex, "read-tree", startingRevision);
            mutationGit(target, expectedIndex, "apply", "--cached", "--whitespace=nowarn", "--", patch.toString());
            expectedTree = mutationGit(target, expectedIndex, "write-tree").trim();

            // 可变 index / 工作树只是执行载体，不能替代后面的不可变快照校验。
            phase = "APPLY_CHECK";
            mutationGit(target, "apply", "--check", "--index", "--whitespace=nowarn", "--", patch.toString());
            requireTrustedState(copy, execution.originalAsset(), startingRevision);
            phase = "APPLY";
            mutationGit(target, "apply", "--index", "--whitespace=nowarn", "--", patch.toString());

            phase = "APPLIED_TREE_CHECK";
            List<String> indexed = Arrays.stream(mutationGit(target, "diff", "--cached", "--no-ext-diff", "--no-renames",
                    "--name-only", "-z", startingRevision, "--").split("\0")).filter(path -> !path.isEmpty()).toList();
            if (!new HashSet<>(indexed).equals(new HashSet<>(proposal.declaredAffectedPaths()))
                    || !mutationGit(target, "diff", "--no-ext-diff", "--name-only", "-z", "--").isEmpty()
                    || !mutationGit(target, "ls-files", "--others", "--exclude-standard", "-z").isEmpty()
                    || !mutationGit(target, "ls-files", "--others", "--ignored", "--exclude-standard", "-z").isEmpty()
                    || !startingRevision.equals(headRevision(copy))) {
                throw new EvolutionExecutionException("Actual Working Copy change differs from the authorized scope");
            }
            requireSafePaths(copy, indexed);
            String tree = mutationGit(target, "write-tree").trim();
            if (!tree.equals(expectedTree)) {
                throw new EvolutionExecutionException("Candidate tree differs from the complete validated patch");
            }

            // 发布前只检查固定 tree；路径及执行摘要也必须来自这个不可变版本。
            List<String> actual = Arrays.stream(mutationGit(target, "diff", "--no-ext-diff", "--no-renames",
                    "--name-only", "-z", startingRevision, tree, "--").split("\0")).filter(path -> !path.isEmpty()).toList();
            if (!new HashSet<>(actual).equals(new HashSet<>(proposal.declaredAffectedPaths()))) {
                throw new EvolutionExecutionException("Candidate tree exceeds the authorized scope");
            }
            requireSafePaths(copy, actual);
            List<String> changes = mutationGit(target, "diff", "--no-ext-diff", "--no-renames",
                    "--numstat", startingRevision, tree, "--").lines().toList();

            phase = "COMMIT_OBJECT";
            String candidate = mutationGit(target, "-c", "user.name=DelveForge", "-c", "user.email=local@delveforge.invalid",
                    "-c", "commit.gpgSign=false", "commit-tree", tree, "-p", startingRevision,
                    "-m", "DelveForge evolution step " + execution.validated().basis().stepId().value()).trim();

            // update-ref 的旧值核对防止并发 HEAD 漂移；commit-tree 不运行提交 hooks。
            phase = "PUBLISH";
            mutationGit(target, "update-ref", "HEAD", candidate, startingRevision);
            requireTrustedState(copy, execution.originalAsset(), candidate);
            return new CandidateMutationResult(candidate, actual, changes, List.of(), Instant.now());
        } catch (ConfirmedGitRejection failure) {
            confirmed = new ConfirmedExecutionFailureException(captureFailure(execution, target, phase,
                    failure.exitCode, expectedTree));
            throw confirmed;
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot materialize controlled proposal patch", failure);
        } finally {
            WorkspaceException cleanup = null;
            if (expectedIndex != null) {
                try {
                    Files.deleteIfExists(expectedIndex);
                    Files.deleteIfExists(Path.of(expectedIndex + ".lock"));
                } catch (IOException failure) {
                    cleanup = new WorkspaceException("Cannot remove invocation index artifact", failure);
                }
            }
            if (patch != null) {
                try {
                    Files.deleteIfExists(patch);
                } catch (IOException failure) {
                    if (cleanup == null) {
                        cleanup = new WorkspaceException("Cannot remove invocation patch artifact", failure);
                    } else {
                        cleanup.addSuppressed(failure);
                    }
                }
            }
            if (cleanup != null) {
                if (confirmed == null) {
                    // 已形成但未交付的 Candidate 或其他未知结果仍未知，不由清理异常推断失败。
                    throw cleanup;
                }
                // 已确认的主失败不能被 finally 覆盖；保留 Diff 与诊断，恢复仍须独立证明。
                ExecutionFailureResult fact = confirmed.result();
                var retained = new ConfirmedExecutionFailureException(new ExecutionFailureResult(fact.id(),
                        fact.baselineRevision(), fact.startingRevision(), fact.phase(), fact.exitCode(),
                        fact.diagnostic() + ";ARTIFACT_CLEANUP_NOT_CONFIRMED", fact.observedRevision(),
                        fact.expectedTree(), fact.diffComplete(), fact.diff(), fact.failedAt()));
                retained.addSuppressed(confirmed);
                retained.addSuppressed(cleanup);
                throw retained;
            }
        }
    }

    /** User Rollback 要求干净 Candidate；系统回滚可额外恢复已证明的完整未发布 tree，不清理用户文件。 */
    @Override
    public RestoredBaseline restoreBaseline(AuthorizedStepRollback authorized) {
        WorkspaceRef copy = authorized.workingCopy();
        String candidate = authorized.startingRevision();
        String baseline = authorized.baselineRevision();
        String phase = "PRECHECK";
        try {
            Path target = requireManagedCopy(copy);
            phase = "OBJECT_VIEW";
            if (Files.exists(target.resolve(".git/info/grafts"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(target.resolve(".git/refs/replace"), LinkOption.NOFOLLOW_LINKS)
                    || !rollbackGit(target, phase, "for-each-ref", "--format=%(refname)", "refs/replace/").isBlank()) {
                throw new WorkspaceException("Rollback cannot confirm baseline through an alternate Git object view");
            }
            if (authorized.executionFailure() != null) {
                phase = "FAILURE_SNAPSHOT";
                requireFailureSnapshot(authorized, target);
            } else {
                requireTrustedState(copy, authorized.originalAsset(), candidate);
            }
            if (!candidate.matches("[0-9a-f]{40}") || !baseline.matches("[0-9a-f]{40}")) {
                throw new WorkspaceException("Rollback requires exact commit identities");
            }
            phase = "BASELINE_LOOKUP";
            rollbackGit(target, phase, "cat-file", "-e", baseline + "^{commit}");
            phase = "BASELINE_ATTRIBUTES";
            // 初始 baseline 可能具有 Candidate 已删除的属性；checkout 前拒绝目标转换，不能先运行 smudge 再拒绝。
            List<String> baselinePaths = Arrays.stream(contentGit(target, baseline,
                    "ls-tree", "-r", "--name-only", "-z", baseline).split("\0"))
                    .filter(path -> !path.isEmpty()).toList();
            for (int start = 0; start < baselinePaths.size(); start += 32) {
                requireSupportedAttributes(target, baseline,
                        baselinePaths.subList(start, Math.min(start + 32, baselinePaths.size())));
            }
            phase = "PATH_CHECK";
            // 候选与目标两侧路径均检查，防止链接、嵌套仓库和 Windows 路径别名。
            List<String> paths = Arrays.stream(rollbackGit(target, phase, "diff", "--no-ext-diff", "--no-renames",
                    "--name-only", "-z", candidate, baseline, "--").split("\0"))
                    .filter(path -> !path.isEmpty()).toList();
            requireSafePaths(copy, paths);
            if (authorized.executionFailure() != null) {
                phase = "UNPUBLISHED_RESTORE";
                restoreProvenUnpublishedTree(authorized, target);
            } else {
                requireTrustedState(copy, authorized.originalAsset(), candidate);
            }
            phase = "CHECKOUT";
            // 不强制覆盖 ignored / untracked 文件，禁用 hooks，操作只在托管独立克隆中发生。
            rollbackGit(target, phase, "checkout", "--detach", "--no-overwrite-ignore", "--no-recurse-submodules", baseline);
            phase = "POSTCHECK";
            requireTrustedState(copy, authorized.originalAsset(), baseline);
            return new RestoredBaseline(baseline, Instant.now());
        } catch (RuntimeException failure) {
            String observed = null;
            if (!Thread.currentThread().isInterrupted()) {
                try {
                    String actual = headRevision(copy);
                    if (actual.matches("[0-9a-f]{40}")) {
                        observed = actual;
                    }
                } catch (RuntimeException inspectionFailure) {
                    failure.addSuppressed(inspectionFailure);
                }
            }
            Integer exit = failure instanceof WorkingCopyRollbackException detail ? detail.exitCode() : null;
            log.warn("operation=workspace.rollback result=NOT_CONFIRMED phase={} exitCode={} observedRevision={} exceptionType={}",
                    phase, exit, observed, failure.getClass().getSimpleName());
            throw new WorkingCopyRollbackException(phase, observed, exit, failure);
        }
    }

    private static final class ConfirmedGitRejection extends RuntimeException {
        private final int exitCode;

        private ConfirmedGitRejection(int exitCode) {
            super("Git rejected controlled mutation");
            this.exitCode = exitCode;
        }
    }

    /** 只保存真实 identity / index 差异，不保存源码、补丁、命令自由文本或凭据。 */
    private ExecutionFailureResult captureFailure(AuthorizedStepExecution execution, Path target,
            String phase, int exit, String expectedTree) {
        String startingRevision = execution.validated().basis().expectedRevision();
        // Repair 的 expectedRevision 是 B，失败恢复目标仍为第一次冻结的 A。
        // 能力已由完整 EXECUTING 检查点构造，baseline 随能力携带，不从 HEAD 猜测。
        return captureFailure(execution.workingCopy(), target, execution.baselineRevision(), startingRevision,
                phase, exit, expectedTree);
    }

    private ExecutionFailureResult captureFailure(WorkspaceRef copy, Path target, String baseline, String start,
            String phase, int exit, String expectedTree) {
        String observed = null;
        try {
            observed = contentGit(target, start, "rev-parse", "HEAD").trim();
            if (!observed.matches("[0-9a-f]{40}")) {
                throw new WorkspaceException("Failure HEAD identity is unavailable");
            }
            // 不读取额外用户文件；其存在阻止现场完整证明和后续恢复。
            requireNoExtraFiles(target, start);
            Map<String, String> baselineBlobs = new LinkedHashMap<>();
            Map<String, String> baselineEntries = new LinkedHashMap<>();
            for (String row : contentGit(target, baseline, "ls-tree", "-r", "-z", baseline).split("\0")) {
                if (row.isEmpty()) {
                    continue;
                }
                int tab = row.indexOf('\t');
                String[] fields = row.substring(0, tab).split(" ");
                if (!fields[1].equals("blob")) {
                    throw new WorkspaceException("Unsupported failure tree");
                }
                baselineBlobs.put(row.substring(tab + 1), fields[2]);
                baselineEntries.put(row.substring(tab + 1), fields[0] + " " + fields[2] + " 0");
            }
            Map<String, String> indexEntries = new LinkedHashMap<>();
            for (String row : contentGit(target, start, "ls-files", "--stage", "-z").split("\0")) {
                if (row.isEmpty()) {
                    continue;
                }
                int tab = row.indexOf('\t');
                String path = row.substring(tab + 1);
                if (indexEntries.put(path, row.substring(0, tab)) != null) {
                    throw new WorkspaceException("Unmerged failure index");
                }
            }
            TreeSet<String> all = new TreeSet<>(baselineBlobs.keySet());
            all.addAll(indexEntries.keySet());
            inspectSafePaths(copy, List.copyOf(all));
            List<String> present = all.stream().filter(path -> Files.exists(target.resolve(path),
                    LinkOption.NOFOLLOW_LINKS)).toList();
            Map<String, String> physical = new LinkedHashMap<>();
            for (int offset = 0; offset < present.size(); offset += 32) {
                List<String> batch = present.subList(offset, Math.min(offset + 32, present.size()));
                List<String> args = new ArrayList<>(List.of("hash-object", "--no-filters", "--"));
                args.addAll(batch);
                List<String> hashes = contentGit(target, start, args.toArray(String[]::new)).lines().toList();
                if (hashes.size() != batch.size()) {
                    throw new WorkspaceException("Incomplete failure byte proof");
                }
                for (int i = 0; i < batch.size(); i++) {
                    physical.put(batch.get(i), hashes.get(i));
                }
            }
            List<ExecutionFailureResult.DiffEntry> diff = new ArrayList<>();
            for (String path : all) {
                String original = baselineBlobs.get(path);
                String entry = indexEntries.get(path);
                String raw = physical.get(path);
                if (!java.util.Objects.equals(baselineEntries.get(path), entry)
                        || !java.util.Objects.equals(original, raw)) {
                    diff.add(new ExecutionFailureResult.DiffEntry(path, original, entry, raw));
                }
            }
            return new ExecutionFailureResult(UUID.randomUUID().toString(), baseline, start, phase, exit,
                    "CONFIRMED_GIT_REJECTION", observed, expectedTree, true, diff, Instant.now());
        } catch (RuntimeException failure) {
            return new ExecutionFailureResult(UUID.randomUUID().toString(), baseline, start, phase, exit,
                    "CONFIRMED_GIT_REJECTION;EVIDENCE_UNAVAILABLE:" + failure.getClass().getSimpleName(),
                    observed, expectedTree, false, List.of(), Instant.now());
        }
    }

    private void requireNoExtraFiles(Path target, String revision) {
        if (!contentGit(target, revision, "ls-files", "--others", "--exclude-standard", "-z").isEmpty()
                || !contentGit(target, revision, "ls-files", "--others", "--ignored", "--exclude-standard", "-z").isEmpty()) {
            throw new WorkingCopyBasisChangedException("Extra user files prevent exact failure proof");
        }
    }

    private void requireFailureSnapshot(AuthorizedStepRollback authorized, Path target) {
        ExecutionFailureResult saved = authorized.executionFailure();
        ExecutionFailureResult actual = captureFailure(authorized.workingCopy(), target, saved.baselineRevision(),
                saved.startingRevision(), saved.phase(), saved.exitCode(), saved.expectedTree());
        if (!saved.diffComplete() || !actual.diffComplete()
                || !saved.startingRevision().equals(actual.observedRevision())
                || !java.util.Objects.equals(saved.observedRevision(), actual.observedRevision())
                || !saved.diff().equals(actual.diff())) {
            throw new WorkingCopyBasisChangedException("Failure snapshot no longer matches physical state");
        }
    }

    private void restoreProvenUnpublishedTree(AuthorizedStepRollback authorized, Path target) {
        requireFailureSnapshot(authorized, target);
        try {
            requireTrustedState(authorized.workingCopy(), authorized.originalAsset(), authorized.startingRevision());
            return;
        } catch (WorkingCopyBasisChangedException changed) {
            // 干净起点不匹配时，只考虑本次固定的完整补丁 tree；其他部分写入不能被强制覆盖。
            String expected = authorized.executionFailure().expectedTree();
            if (expected == null || !expected.matches("[0-9a-f]{40}")) {
                throw changed;
            }
            Path original = resolveLocation(authorized.originalAsset()).toAbsolutePath().normalize();
            try {
                original = original.toRealPath();
            } catch (IOException failure) {
                throw new WorkspaceException("Cannot confirm automatic rollback isolation", failure);
            }
            if (target.startsWith(original) || original.startsWith(target)
                    || target.getParent().startsWith(original) || original.startsWith(target.getParent())) {
                throw new WorkingCopyBasisChangedException("Automatic rollback overlaps original asset");
            }
            for (String flag : contentGit(target, expected, "ls-files", "-v", "-z").split("\0")) {
                if (!flag.isEmpty() && (Character.isLowerCase(flag.charAt(0)) || flag.charAt(0) == 'S')) {
                    throw new WorkingCopyBasisChangedException("Failure index flags hide content");
                }
            }
            requireTrackedContent(authorized.workingCopy(), target, expected);
            requireNoExtraFiles(target, expected);
            List<String> paths = Arrays.stream(rollbackGit(target, "UNPUBLISHED_PATHS", "diff", "--no-ext-diff",
                    "--no-renames", "--name-only", "-z", authorized.baselineRevision(), expected, "--")
                    .split("\0")).filter(path -> !path.isEmpty()).toList();
            requireSafePaths(authorized.workingCopy(), paths);
            requireFailureSnapshot(authorized, target);
            if (!paths.isEmpty()) {
                List<String> args = new ArrayList<>(List.of("--literal-pathspecs", "restore",
                        "--source=" + authorized.baselineRevision(), "--staged", "--worktree", "--"));
                args.addAll(paths);
                rollbackGit(target, "UNPUBLISHED_RESTORE", args.toArray(String[]::new));
            }
        }
    }

    private String mutationGit(Path directory, String... arguments) {
        return mutationGit(directory, null, arguments);
    }

    private String mutationGit(Path directory, Path index, String... arguments) {
        var result = mutationGitResult(directory, index, arguments);
        if (!result.succeeded()) {
            // 仅接受正常返回、完整读取且具有 Git 拒绝标记的有限负向结果；信号退出等仍 UNKNOWN。
            String output = result.output().stripLeading();
            if ((result.exitCode() == 1 && output.startsWith("error:"))
                    || (result.exitCode() == 128 && output.startsWith("fatal:"))) {
                throw new ConfirmedGitRejection(result.exitCode());
            }
            throw new WorkspaceException("Controlled Git mutation outcome is not established");
        }
        return result.output();
    }

    private String rollbackGit(Path directory, String phase, String... arguments) {
        // 同一 SHA 的 replacement / graft 视图不能作为原始 baseline 的恢复依据。
        List<String> exactArguments = new ArrayList<>(List.of("--no-replace-objects"));
        exactArguments.addAll(List.of(arguments));
        var result = mutationGitResult(directory, null, exactArguments.toArray(String[]::new));
        if (!result.succeeded()) {
            throw new WorkingCopyRollbackException(phase, null, result.exitCode(),
                    new WorkspaceException("Controlled rollback Git operation did not complete"));
        }
        return result.output();
    }

    protected WorkspaceCommandRunner.Result mutationGitResult(Path directory, Path index, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.fsmonitor=false",
                "-c", "core.hooksPath=" + directory.resolve(".git/delveforge-disabled-hooks"), "-C", directory.toString()));
        command.addAll(List.of(arguments));
        return index == null ? WorkspaceCommandRunner.run(directory, command, Duration.ofSeconds(30), 1048576)
                : WorkspaceCommandRunner.runWithIndex(directory, command, index, Duration.ofSeconds(30), 1048576);
    }

    private Path requireManagedCopy(WorkspaceRef copy) {
        try {
            Path target = resolveLocation(copy).normalize();
            Path root = allowedCopyRoot(target);
            Path metadata = target.resolve(".git");
            Path marker = metadata.resolve("delveforge-preparation");
            if (!root.equals(target.getParent()) || !target.toRealPath().equals(target)
                    || !Files.isDirectory(metadata, LinkOption.NOFOLLOW_LINKS)
                    || !metadata.toRealPath().equals(metadata)
                    || !Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(marker) || !marker.toRealPath().equals(marker)
                    || Files.readString(marker).isBlank()
                    || Files.exists(metadata.resolve("objects/info/alternates"))) {
                throw new WorkingCopyBasisChangedException("Workspace is not an isolated managed Working Copy");
            }
            return requireReadableRepository(copy);
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot inspect managed Working Copy", failure);
        }
    }

    @Override
    public PreparedWorkspace provision(WorkspaceRef source, String revision, String directoryName) {
        Path repository = requireReadableRepository(source);
        String commit = requireCommitId(repository, revision);
        requireUnchangedBasis(source, commit);

        Path target = null;
        boolean allocated = false;
        try {
            Path root;
            try {
                root = provisioningRoot(repository);
            } catch (IOException failure) {
                throw new WorkspaceStorageUnavailableException("Cannot access configured workspace root", failure);
            }
            requireDirectoryName(directoryName);
            target = root.resolve(directoryName);
            try {
                Files.createDirectory(target); // 不能覆盖或接管已有目录。
            } catch (IOException failure) {
                throw new WorkspaceStorageUnavailableException("Managed target is unavailable or already exists", failure);
            }
            allocated = true;

            GitCommandResult clone = execute(root, "clone", "--no-local", "--no-checkout",
                    "--template=", "--", repository.toString(), target.toString());
            if (!clone.succeeded()) {
                throw new WorkspaceException("Working Copy clone failed: " + describe(target, clone));
            }

            // 禁用模板与 checkout hooks，整个准备过程不操作源仓库的工作树或 index。
            GitCommandResult checkout = execute(target, "-c", "core.hooksPath="
                    + target.resolve(".git/delveforge-disabled-hooks"), "checkout", "--detach", commit);
            if (!checkout.succeeded()) {
                throw new WorkspaceException("Working Copy checkout failed: " + describe(target, checkout));
            }

            WorkspaceRef location = new WorkspaceRef(target.toString());
            if (!commit.equals(headRevision(location))) {
                throw new WorkspaceException("Working Copy checked-out revision differs from requested basis");
            }
            GitCommandResult status = execute(target, "status", "--porcelain");
            if (!status.succeeded() || !status.stdoutText().isBlank()) {
                // 在补偿删除前保留失败类别；路径与 Git 自由文本不得进入日志。
                log.warn("operation=workspace.provision phase=POST_CHECKOUT_STATUS result={} "
                                + "exitCode={} stdoutBytes={} stderrPresent={}",
                        status.succeeded() ? "STATUS_OUTPUT_NONEMPTY" : "STATUS_COMMAND_FAILED",
                        status.exitCode(), status.stdout().length, !status.stderr().isEmpty());
                throw new WorkspaceException("Working Copy checkout is not clean");
            }
            requireUnchangedBasis(source, commit);

            // 准备凭据用于后续补偿时核对所有权，不能仅凭路径删除托管目录。
            String token = UUID.randomUUID().toString();
            Files.writeString(target.resolve(".git/delveforge-preparation"), token);
            return new PreparedWorkspace(location, commit, token);
        } catch (IOException | RuntimeException failure) {
            RuntimeException primary = failure instanceof RuntimeException runtime ? runtime
                    : new WorkspaceException("Working Copy provisioning filesystem failure", failure);
            if (allocated) {
                try {
                    deleteOwnedDirectory(target);
                } catch (IOException | RuntimeException cleanup) {
                    primary.addSuppressed(cleanup);
                    log.warn("operation=workspace.provision result=CLEANUP_FAILED exceptionType={}", cleanup.getClass().getName());
                }
            }
            throw primary;
        }
    }

    @Override
    public void discard(PreparedWorkspace candidate) {
        try {
            Path target = resolveLocation(candidate.workspace());
            Path root = allowedCopyRoot(target);
            if (!target.getParent().equals(root) || !target.toRealPath().equals(target)) {
                throw new WorkspaceException("Refusing cleanup outside managed direct-child directory");
            }

            Path marker = target.resolve(".git/delveforge-preparation");
            if (!marker.toRealPath().startsWith(target)
                    || !Files.readString(marker).equals(candidate.preparationToken())) {
                throw new WorkspaceException("Refusing cleanup without matching preparation ownership");
            }

            deleteOwnedDirectory(target);
        } catch (IOException | WorkingCopyBasisChangedException failure) {
            throw new WorkspaceException("Working Copy cleanup failed", failure);
        }
    }

    private void requireUnchangedBasis(WorkspaceRef source, String revision) {
        if (!revision.equals(headRevision(source))) {
            throw new WorkingCopyBasisChangedException("Source HEAD changed; re-analyze and re-plan before preparation");
        }
    }

    private Path provisioningRoot(Path repository) throws IOException {
        Path source = repository.toRealPath();
        Path root = canonicalProspectiveRoot();
        if (root.startsWith(source) || source.startsWith(root)) {
            throw new WorkspaceException("Managed workspace root must be separate from source repository");
        }

        try {
            Files.createDirectories(root);
        } catch (IOException failure) {
            throw new WorkspaceStorageUnavailableException("Cannot create configured workspace root", failure);
        }
        if (!root.toRealPath().equals(root)) {
            throw new WorkspaceStorageUnavailableException("Managed workspace root changed");
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(root)) {
            throw new WorkspaceStorageUnavailableException("Configured workspace root is not a writable directory");
        }
        return root;
    }

    private Path canonicalProspectiveRoot() throws IOException {
        return canonicalProspectiveRoot(managedRoot);
    }

    private Path canonicalProspectiveRoot(Path configuredRoot) throws IOException {
        if (configuredRoot == null || !configuredRoot.isAbsolute()) {
            throw new WorkspaceStorageUnavailableException("Provisioning requires an absolute configured workspace root");
        }

        Path root = configuredRoot.normalize();
        Path ancestor = root;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new WorkspaceStorageUnavailableException("Configured workspace storage volume is unavailable");
        }
        Path realAncestor = ancestor.toRealPath();
        if (!realAncestor.equals(ancestor) || !Files.isDirectory(realAncestor, LinkOption.NOFOLLOW_LINKS)) {
            throw new WorkspaceStorageUnavailableException("Configured workspace root traverses a link or a file");
        }
        return realAncestor.resolve(ancestor.relativize(root)).normalize();
    }

    private Path allowedCopyRoot(Path target) throws IOException {
        // 旧副本只能继续留在运维显式配置的根目录，不从浏览器或准备凭据推导存储授权。
        List<Path> allowed = new ArrayList<>(existingRoots);
        if (managedRoot != null) {
            allowed.add(managedRoot);
        }
        for (Path root : allowed) {
            if (root.isAbsolute() && root.normalize().equals(target.getParent())) {
                return canonicalProspectiveRoot(root);
            }
        }
        throw new WorkingCopyBasisChangedException("Working Copy is outside configured managed roots");
    }

    private static void requireDirectoryName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
                || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) {
            throw new WorkspaceException("Working Copy directory must be a safe single name");
        }
    }

    private void deleteOwnedDirectory(Path target) throws IOException {
        Path root = allowedCopyRoot(target);
        if (!target.getParent().equals(root) || !target.toRealPath().equals(target)) {
            throw new WorkspaceException("Refusing cleanup outside managed direct-child directory");
        }
        try (var paths = Files.walk(target)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                // 清理 clone 残留时不能跨越符号链接或 junction。
                if (!Files.isSymbolicLink(entry)) {
                    if (!entry.toRealPath().startsWith(target)) {
                        throw new WorkspaceException("Refusing cleanup across filesystem link");
                    }
                    entry.toFile().setWritable(true);
                }
                Files.delete(entry);
            }
        }
    }

    private static final String GIT_EXECUTABLE = "git";

    /** {@code ls-tree} 输出中 tree（目录）的类型名。 */
    private static final String TREE_TYPE = "tree";

    @Override
    public boolean isReadableRepository(WorkspaceRef workspace) {
        // 位置本身不可用（相对路径、路径语法非法）同样是「不是一个可读仓库」，
        // 而不是「调用方参数写错了」：调用方只是拿了一个已登记资产的 location 来问。
        // 读取操作仍然要求可解析的位置，那里照旧拒绝。
        Path location = usableLocation(workspace);
        return location != null && isReadable(location);
    }

    @Override
    public String headRevision(WorkspaceRef workspace) {
        Path repository = requireReadableRepository(workspace);

        GitCommandResult result = execute(repository, "rev-parse", "--verify", "HEAD");
        if (!result.succeeded()) {
            throw new WorkspaceException(
                    "无法确定 Repository 当前已提交的 revision（例如空仓库还没有任何 commit）: "
                            + describe(repository, result));
        }
        return result.stdoutText().trim();
    }

    @Override
    public List<WorkspaceEntry> listEntries(
            WorkspaceRef workspace, String revision, String relativePath, int maxDepth) {
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth 必须大于 0: " + maxDepth);
        }
        String startPath = requireSafeRelativePath(relativePath, true);
        Path repository = requireReadableRepository(workspace);
        String commitId = requireCommitId(repository, revision);

        // -l 让 git 一并给出每个 blob 的大小：调用方据此在读取之前判断值不值得读，
        // 否则「读进来再决定要不要」会对超大文件做一次完整加载。
        GitCommandResult result =
                execute(repository, "ls-tree", "-r", "-t", "-l", "-z", "--full-tree", commitId);
        if (!result.succeeded()) {
            throw new WorkspaceException(
                    "无法读取 Repository 已提交的目录结构: "
                            + commitId + " " + describe(repository, result));
        }
        return collectEntries(result.stdout(), startPath, maxDepth, repository);
    }

    @Override
    public String readFile(WorkspaceRef workspace, String revision, String relativePath) {
        String path = requireSafeRelativePath(relativePath, false);
        Path repository = requireReadableRepository(workspace);
        String commitId = requireCommitId(repository, revision);

        // cat-file 直接输出对象内容，不经过任何 checkout / 过滤器，
        // 因此拿到的就是该 commit 中保存的那份内容。
        GitCommandResult result = execute(repository, "cat-file", "blob", commitId + ":" + path);
        if (!result.succeeded()) {
            throw new WorkspaceException(
                    "无法读取文件（该路径在该 revision 中不存在，或不是文件）: "
                            + path + " @ " + commitId + " " + describe(repository, result));
        }
        return result.stdoutText();
    }

    /**
     * 校验 revision 是一个已解析的完整 commit id，并把它规范化后返回。
     *
     * <p>用 git 自己做这次判断，而不是在 Java 里做十六进制格式检查：只有 git 才知道
     * 这个 id 在当前 Repository 里是否存在、是否是一个 commit。
     *
     * <p>两个必须区分开的失败：
     *
     * <pre>
     * 解析结果与输入不同   输入是可移动的引用名（HEAD / 分支名）或缩写的 id
     *                      → 拒绝：它们在不同时刻指向不同 commit，
     *                        会让一次分析在不知不觉中混合多个版本
     * 无法解析            该 id 在当前 Repository 中不存在
     * </pre>
     */
    private String requireCommitId(Path repository, String revision) {
        if (revision == null || revision.isBlank()) {
            throw new IllegalArgumentException("读取时必须指定 revision");
        }

        GitCommandResult result =
                execute(repository, "rev-parse", "--verify", "--quiet", revision + "^{commit}");
        String resolved = result.stdoutText().trim();

        if (!result.succeeded() || resolved.isEmpty()) {
            throw new IllegalArgumentException(
                    "revision 必须是当前 Repository 中存在的完整 commit id: " + revision);
        }
        if (!resolved.equalsIgnoreCase(revision)) {
            throw new IllegalArgumentException(
                    "revision 必须是完整的 commit id，不能是可移动的引用名"
                            + "（HEAD 或分支名）或缩写的 id: " + revision);
        }
        return resolved.toLowerCase(Locale.ROOT);
    }

    /**
     * 解析 WorkspaceRef 指向的本地位置。
     *
     * <p>要求绝对路径：相对路径会相对于进程的工作目录解析，那是一个与 Workspace
     * 无关的环境细节，静默接受会让「分析的是哪个位置」变得不可预期。
     */
    private static Path resolveLocation(WorkspaceRef workspace) {
        Path location = usableLocation(workspace);
        if (location == null) {
            throw new IllegalArgumentException(
                    "Workspace 位置必须是一个可用的绝对路径: "
                            + (workspace == null ? "<null>" : workspace.value()));
        }
        return location;
    }

    /**
     * 把 WorkspaceRef 解析为一个可用的本地位置；不可用时返回 {@code null}。
     *
     * <p>它与 {@link #resolveLocation} 是同一件事的两种表达：读取操作需要位置，
     * 因此不可用即拒绝；可读性检查只回答「能不能读」，因此不可用即 {@code false}。
     * 两者共用这里的判定，避免「什么算可用位置」出现两套说法。
     *
     * <p>只捕获 {@link InvalidPathException}（路径语法本身非法，例如 Windows 上的保留
     * 字符），不笼统捕获 {@link IllegalArgumentException}：后者可能来自别处，
     * 那属于实现缺陷，不该被静默当成「位置不可用」。
     */
    private static Path usableLocation(WorkspaceRef workspace) {
        if (workspace == null) {
            return null;
        }
        try {
            Path location = Path.of(workspace.value());
            return location.isAbsolute() ? location : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * 校验调用方传入的相对路径不会越过 Workspace 根目录。
     *
     * <p>规则与 {@link WorkspaceEntry} 对结果路径的要求一致（不得是绝对路径、
     * 不得包含 {@code ..}），区别是这里可以额外允许空路径表示根目录。
     *
     * <p>越界检查必须在这里做，而不是指望 git 报错：读取内容来自 commit tree，
     * 但这不代表可以先把一个越界路径交给外部进程。
     *
     * <p>当前只有本 Adapter 需要校验「输入路径」，因此规则先就近实现；
     * 出现第二个 Workspace 实现时，应把它提升为 Port 层的共享校验，
     * 而不是各自复制一份。
     */
    private static String requireSafeRelativePath(String relativePath, boolean allowRoot) {
        if (relativePath == null) {
            throw new IllegalArgumentException("路径不能为 null");
        }
        if (relativePath.isEmpty()) {
            if (allowRoot) {
                return relativePath;
            }
            throw new IllegalArgumentException("文件路径不能为空");
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")
                || hasDrivePrefix(relativePath)) {
            throw new IllegalArgumentException("路径必须是相对路径: " + relativePath);
        }
        for (String segment : relativePath.split("[/\\\\]")) {
            if ("..".equals(segment)) {
                throw new IllegalArgumentException(
                        "路径不得越过 Workspace 根目录: " + relativePath);
            }
        }
        return relativePath;
    }

    /** Windows 盘符形式：{@code C:\...} 或 {@code C:/...}。 */
    private static boolean hasDrivePrefix(String path) {
        return path.length() > 2
                && Character.isLetter(path.charAt(0))
                && path.charAt(1) == ':'
                && (path.charAt(2) == '\\' || path.charAt(2) == '/');
    }

    /**
     * 该位置当前是否是一个可读取的 Repository 根目录。
     *
     * <p>只回答「能不能读」，因此任何一项不满足都返回 {@code false}；
     * 无法执行 Git 命令属于环境故障，仍由 {@link #execute} 抛出 {@link WorkspaceException}。
     */
    private boolean isReadable(Path location) {
        if (!Files.isDirectory(location)) {
            return false;
        }
        GitCommandResult insideWorkTree =
                execute(location, "rev-parse", "--is-inside-work-tree");
        if (!insideWorkTree.succeeded() || !"true".equals(insideWorkTree.stdoutText().trim())) {
            return false;
        }
        GitCommandResult topLevel = execute(location, "rev-parse", "--show-toplevel");
        return topLevel.succeeded() && isSameDirectory(location, topLevel.stdoutText().trim());
    }

    /**
     * 传入的位置是否就是 Repository 的根目录。
     *
     * <p>比较的是两侧的真实路径：Windows 上盘符大小写、{@code /} 与 {@code \}
     * 的写法差异都不应被当作不同位置。
     */
    private static boolean isSameDirectory(Path location, String topLevel) {
        try {
            return location.toRealPath().equals(Path.of(topLevel).toRealPath());
        } catch (IOException e) {
            return false;
        }
    }

    private Path requireReadableRepository(WorkspaceRef workspace) {
        Path location = resolveLocation(workspace);
        if (!isReadable(location)) {
            throw new WorkspaceException(
                    "该位置不是可读取的本地 Git Repository: " + location);
        }
        return location;
    }

    /**
     * 从 {@code ls-tree -r -t -z} 的输出中取出起始路径下、层级不超过
     * {@code maxDepth} 的条目。
     *
     * <p>{@code -t} 使目录自身也出现在输出中，因此目录无需额外推断；
     * {@code -z} 用 NUL 分隔，路径不被引号或转义包裹，非 ASCII 文件名可以原样解析。
     * 条目按相对路径升序返回，使同一状态下的读取结果稳定可比。
     */
    private static List<WorkspaceEntry> collectEntries(
            byte[] output, String startPath, int maxDepth, Path repository) {

        String prefix = startPath.isEmpty() ? "" : startPath + "/";
        List<WorkspaceEntry> entries = new ArrayList<>();

        for (String record : new String(output, StandardCharsets.UTF_8).split("\0")) {
            if (record.isEmpty()) {
                continue;
            }
            int tab = record.indexOf('\t');
            if (tab < 0) {
                throw new WorkspaceException(
                        "无法解析 git ls-tree 的输出: " + record + " (" + repository + ")");
            }
            String metadata = record.substring(0, tab);
            String path = record.substring(tab + 1);

            if (!path.startsWith(prefix)) {
                continue;
            }
            String[] segments = path.substring(prefix.length()).split("/", -1);
            if (segments.length > maxDepth) {
                continue;
            }
            entries.add(new WorkspaceEntry(path, isTree(metadata), sizeOf(metadata)));
        }

        entries.sort(Comparator.comparing(WorkspaceEntry::relativePath));
        return List.copyOf(entries);
    }

    /** {@code ls-tree} 的元数据形如 {@code <mode> <type> <object> <size>}（带 {@code -l}）。 */
    private static boolean isTree(String metadata) {
        return TREE_TYPE.equals(typeOf(metadata));
    }

    private static String typeOf(String metadata) {
        String[] fields = metadata.split("\\s+");
        return fields.length > 1 ? fields[1] : "";
    }

    /**
     * blob 的大小（字节）。
     *
     * <p>{@code -l} 对 tree 给出 {@code -}（目录没有大小），按 0 处理。
     * 无法解析时也按 0 处理：大小只用于「值不值得读」的取舍，
     * 取值不可信时按「可读」对待，读取失败会由 readFile 自己报错，不会静默丢内容。
     */
    private static long sizeOf(String metadata) {
        String[] fields = metadata.split("\\s+");
        if (fields.length < 4) {
            return 0;
        }
        try {
            return Long.parseLong(fields[3]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 执行一条只读 git 命令。
     *
     * <p>命令用 {@code git -C <repository>} 指定作用目录，不依赖进程工作目录。
     * stderr 在单独的虚拟线程里排空：只读命令的 stderr 一般很短，但一旦超过管道缓冲区，
     * 只读 stdout 的一方会与子进程互相等待。
     */
    private GitCommandResult execute(Path repository, String... arguments) {
        List<String> command = new ArrayList<>(arguments.length + 5);
        command.add(GIT_EXECUTABLE);
        command.add("-C");
        command.add(repository.toString());
        // 与受控写入执行器一致：Windows 长路径必须实际落盘，不能依赖宿主 Git 的默认值。
        command.addAll(List.of("-c", "core.longpaths=true"));
        command.addAll(List.of(arguments));

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            WorkspaceCommandRunner.removeProviderCredentials(builder.environment());
            process = builder.start();
        } catch (IOException e) {
            throw new WorkspaceException(
                    "无法执行 git 命令（运行环境里找不到 git 可执行文件）: " + command, e);
        }

        AtomicReference<byte[]> errorOutput = new AtomicReference<>(new byte[0]);
        Thread errorReader = Thread.ofVirtual().start(() -> {
            try {
                errorOutput.set(process.getErrorStream().readAllBytes());
            } catch (IOException e) {
                // 读不到 stderr 不影响命令结果：它只用于失败时的诊断信息
                errorOutput.set(new byte[0]);
            }
        });

        try {
            byte[] output = process.getInputStream().readAllBytes();
            int exitCode = process.waitFor();
            errorReader.join();
            return new GitCommandResult(
                    exitCode, output, new String(errorOutput.get(), StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            process.destroyForcibly();
            throw new WorkspaceException("执行 git 命令失败: " + command, e);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new WorkspaceException("执行 git 命令被中断: " + command, e);
        }
    }

    /** 失败信息：命令作用的位置 + git 自己给出的原因。 */
    private static String describe(Path repository, GitCommandResult result) {
        return "(" + repository + ", exit " + result.exitCode() + ", " + result.stderr() + ")";
    }

    /**
     * 一次只读 git 命令的结果。
     *
     * <p>stdout 保持原始字节：文件内容可能是非 UTF-8 或二进制，
     * 提前解码会破坏 readFile 的结果。
     */
    private record GitCommandResult(int exitCode, byte[] stdout, String stderr) {

        boolean succeeded() {
            return exitCode == 0;
        }

        String stdoutText() {
            return new String(stdout, StandardCharsets.UTF_8);
        }
    }
}
