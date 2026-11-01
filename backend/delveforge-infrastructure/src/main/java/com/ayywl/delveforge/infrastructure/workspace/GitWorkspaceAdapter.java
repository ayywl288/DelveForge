package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.Delivery;
import com.ayywl.delveforge.application.port.workspace.CandidateDeliveryPort;
import com.ayywl.delveforge.application.port.workspace.PreparedWorkspace;
import com.ayywl.delveforge.application.port.workspace.RepositoryCompatibility;
import com.ayywl.delveforge.application.port.workspace.RepositoryCompatibilityPort;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyBasisChangedException;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyInspectionPort;
import com.ayywl.delveforge.application.port.workspace.WorkingCopyProvisioningPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.port.workspace.WorkspaceStorageUnavailableException;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.domain.agent.Candidate;
import com.ayywl.delveforge.domain.evolution.PreparationProvenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 通过 Local Git 分别提供仓库读取与隔离环境准备能力。
 *
 * <p>读取和准备能力通过独立 Port 暴露：所有 Git 调用都发生在 Infrastructure
 * 内部（RULE-ARCH-009），Domain 与 Application 只看到 {@link WorkspaceReadPort}
 * 与相对路径。M4 写能力通过独立 {@code CandidateDeliveryPort} 暴露，Repository Analysis
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
        WorkingCopyInspectionPort, CandidateDeliveryPort,
        RepositoryCompatibilityPort {

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
        requireDeliveryTree(copy, source, expectedRevision, expectedRevision);
    }

    private void requireDeliveryTree(WorkspaceRef copy, WorkspaceRef source, String expectedHead, String expectedRevision) {
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
        if (!expectedHead.equals(headRevision(copy))) {
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

        requireRepositoryAttributeSources(target);

        // 整树读取真实文件并重新计算 Git blob；stat 缓存、HEAD 与 status 都不能替代内容证据。
        requireTrackedContent(copy, target, expectedRevision);

        // tracked 内容已经独立证明；status / 工作树 diff 会调用 clean filter，额外文件只用枚举能力检查。
        if (!contentGit(target, expectedRevision, "ls-files", "--others", "--exclude-standard", "-z").isEmpty()
                || !contentGit(target, expectedRevision, "ls-files", "--others", "--ignored", "--exclude-standard", "-z")
                        .isEmpty()) {
            throw new WorkingCopyBasisChangedException("Working Copy contains changes or untracked artifacts");
        }
    }

    @Override
    public Occupation occupy(Delivery delivery) {
        Path target = requireManagedCopy(new WorkspaceRef(delivery.location()));
        try {
            Path lockPath = target.resolve(".git/delveforge-delivery.lock");
            if (Files.isSymbolicLink(lockPath)) {
                throw new AgentException("DELIVERY_CONFLICT", "Delivery lock identity is unsafe");
            }
            FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (IOException | OverlappingFileLockException failure) {
                channel.close();
                throw new AgentException("DELIVERY_CONFLICT", "WorkingCopy has an active Delivery writer", failure);
            }
            if (lock == null) {
                channel.close();
                throw new AgentException("DELIVERY_CONFLICT", "WorkingCopy has an active Delivery writer");
            }
            return () -> {
                try {
                    lock.release();
                    channel.close();
                } catch (IOException failure) {
                    throw new WorkspaceException("Cannot release Delivery process occupation", failure);
                }
            };
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot acquire Delivery process occupation", failure);
        }
    }

    private Path deliveryTarget(Delivery delivery) {
        Path target = requireManagedCopy(new WorkspaceRef(delivery.location()));
        try {
            for (String path : List.of("index.lock", "HEAD.lock", "MERGE_HEAD", "REBASE_HEAD", "CHERRY_PICK_HEAD",
                    "REVERT_HEAD", "rebase-merge", "rebase-apply", "sequencer", "info/grafts", "refs/replace",
                    "info/sparse-checkout")) {
                if (Files.exists(target.resolve(".git").resolve(path), LinkOption.NOFOLLOW_LINKS)) {
                    throw new WorkingCopyBasisChangedException("Unresolved Git operation prevents Delivery: " + path);
                }
            }
            // provision 会继承浅克隆边界；它不是未完成操作，不妨碍以 R 为父创建并证明 C。
            // 仍拒绝非普通 shallow 文件；发布时的完整父列表/树/index/内容检查不变。
            Path shallow = target.resolve(".git/shallow");
            if (Files.exists(shallow, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isRegularFile(shallow, LinkOption.NOFOLLOW_LINKS)
                    || !shallow.toRealPath().equals(shallow))) {
                throw new WorkingCopyBasisChangedException("Shallow boundary file identity cannot be proven");
            }
            String head = Files.readString(target.resolve(".git/HEAD")).strip();
            if (!head.matches("[0-9a-f]{40}")) {
                throw new WorkingCopyBasisChangedException("Delivery requires its managed detached HEAD");
            }
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot establish Delivery Git safety", failure);
        }
        return target;
    }

    @Override
    public Position inspect(Delivery delivery) {
        try {
            Path target = deliveryTarget(delivery);
            if (Files.exists(target.resolve(".git/delveforge-delivery-incomplete"), LinkOption.NOFOLLOW_LINKS)) {
                return Position.UNKNOWN;
            }
            WorkspaceRef copy = new WorkspaceRef(delivery.location());
            WorkspaceRef original = new WorkspaceRef(delivery.originalLocation());
            String head = headRevision(copy);
            if (delivery.revision() != null && head.equals(delivery.revision())) {
                String commit = deliveryGit(target, null, "rev-list", "--parents", "-n", "1", head).strip();
                if (!commit.equals(head + " " + delivery.baseline())
                        || !deliveryGit(target, null, "rev-parse", head + "^{tree}").strip()
                                .equals(delivery.expectedTree())) {
                    return Position.UNKNOWN;
                }
                requireDeliveryTree(copy, original, head, delivery.expectedTree());
                return Position.PUBLISHED;
            }
            if (!head.equals(delivery.baseline())) {
                return Position.UNKNOWN;
            }
            String indexTree = deliveryGit(target, null, "write-tree").strip();
            if (indexTree.equals(delivery.expectedTree())) {
                requireDeliveryTree(copy, original, head, delivery.expectedTree());
                return Position.APPLIED;
            }
            requireDeliveryTree(copy, original, head, delivery.baseline());
            return Position.BASELINE;
        } catch (WorkspaceException | WorkingCopyBasisChangedException failure) {
            log.warn("operation=delivery.inspect candidate={} result=UNKNOWN reason={}",
                    delivery.candidateId(), failure.getMessage());
            return Position.UNKNOWN;
        }
    }

    @Override
    public void replay(Delivery delivery, Candidate candidate) {
        Path target = deliveryTarget(delivery);
        Path index = null;
        Path patch = null;
        try {
            index = Files.createTempFile(target.resolve(".git"), "delveforge-index-", ".delivery");
            Files.delete(index);
            patch = Files.createTempFile(target.resolve(".git"), "delveforge-delivery-", ".diff");
            Files.writeString(patch, candidate.diff(), StandardCharsets.UTF_8);
            deliveryGit(target, index, "read-tree", delivery.baseline());
            deliveryGit(target, index, "apply", "--cached", "--unidiff-zero", "--whitespace=nowarn", "--", patch.toString());
            if (!deliveryGit(target, index, "write-tree").strip().equals(candidate.expectedTree())) {
                throw new AgentException("TREE_MISMATCH", "Frozen diff does not replay to expected Candidate tree");
            }
            List<String> paths = Arrays.stream(deliveryGit(target, index, "diff-tree", "--no-commit-id", "--no-renames",
                    "--name-only", "-r", "-z", delivery.baseline(), candidate.expectedTree()).split("\0"))
                    .filter(path -> !path.isEmpty()).toList();
            if (!new HashSet<>(paths).equals(new HashSet<>(candidate.changedPaths()))) {
                throw new AgentException("TREE_MISMATCH", "Frozen changed paths differ from replayed tree");
            }
            Set<String> foldedPaths = new HashSet<>();
            for (String entry : deliveryGit(target, index, "ls-tree", "-r", "-z", candidate.expectedTree()).split("\0")) {
                if (entry.isEmpty()) {
                    continue;
                }
                int separator = entry.indexOf('\t');
                String[] metadata = separator < 0 ? new String[0] : entry.substring(0, separator).split(" ");
                String path = separator < 0 ? "" : entry.substring(separator + 1);
                if (metadata.length != 3 || !Set.of("100644", "100755").contains(metadata[0])
                        || !metadata[1].equals("blob") || !foldedPaths.add(path.toLowerCase(Locale.ROOT))) {
                    throw new AgentException("TREE_MISMATCH", "Candidate contains unsupported modes or case collisions");
                }
                requireDeliveryPath(path);
            }
            requireSafePaths(new WorkspaceRef(delivery.location()), paths);
            requireSafeTreeAttributes(target, candidate.expectedTree());
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot replay frozen Candidate", failure);
        } finally {
            try {
                if (index != null) {
                    Files.deleteIfExists(index);
                }
                if (patch != null) {
                    Files.deleteIfExists(patch);
                }
            } catch (IOException failure) {
                throw new WorkspaceException("Cannot remove owned Delivery replay material", failure);
            }
        }
    }

    @Override
    public void apply(Delivery delivery, Candidate candidate) {
        Path target = deliveryTarget(delivery);
        requireTrustedState(new WorkspaceRef(delivery.location()), new WorkspaceRef(delivery.originalLocation()),
                delivery.baseline());
        requireSafeTreeAttributes(target, delivery.expectedTree());
        Path material = null;
        Path index = null;
        Path indexLock = target.resolve(".git/index.lock");
        Path incomplete = target.resolve(".git/delveforge-delivery-incomplete");
        boolean locked = false;
        try {
            material = Files.createTempDirectory(target.resolve(".git"), "delveforge-render-");
            index = target.resolve(".git/delveforge-index-" + UUID.randomUUID() + ".delivery");
            Path rendered = Files.createDirectory(material.resolve("files"));
            deliveryGit(target, index, "read-tree", delivery.expectedTree());
            Map<String, String[]> baseline = deliveryEntries(target, delivery.baseline());
            Map<String, String[]> next = deliveryEntries(target, delivery.expectedTree());
            List<String> oldPaths = candidate.changedPaths().stream().filter(baseline::containsKey).toList();
            Map<String, String> eol = oldPaths.isEmpty() ? Map.of() : trackedEolFacts(target, delivery.baseline(), oldPaths);
            String autocrlf = contentGit(target, delivery.baseline(), "config", "--default=false", "--get", "core.autocrlf")
                    .strip().toLowerCase(Locale.ROOT);
            List<DeliveryFiles.Change> changes = new ArrayList<>();
            for (String path : candidate.changedPaths()) {
                byte[] before = baseline.containsKey(path) ? boundedDeliveryBytes(target.resolve(path)) : null;
                if (before != null && !blobIdentity(before).equals(baseline.get(path)[2])
                        && !lineEndingEquivalent(before, baseline.get(path)[2], eol.get(path), autocrlf)) {
                    throw new WorkingCopyBasisChangedException("Delivery snapshot differs from proven baseline bytes");
                }
                byte[] after = null;
                if (next.containsKey(path)) {
                    // Git 只渲染自有临时目录，保留版本属性 / autocrlf 的转换；不写正式源码。
                    deliveryGit(target, index, "--attr-source=" + delivery.expectedTree(), "-c", "core.safecrlf=false",
                            "checkout-index", "--prefix=" + rendered.toString().replace('\\', '/') + "/", "--", path);
                    after = boundedDeliveryBytes(rendered.resolve(path));
                }
                changes.add(new DeliveryFiles.Change(path, before, after, next.containsKey(path) ? next.get(path)[0] : "100644"));
            }
            byte[] originalIndex = boundedDeliveryBytes(target.resolve(".git/index"));
            // 正式 index 在原生边界内只核对身份，不原地覆写，保留 Git 的原子 index 发布。
            changes.add(new DeliveryFiles.Change(".git/index", originalIndex, originalIndex, "100644"));
            Path patch = material.resolve("candidate.diff");
            Files.writeString(patch, candidate.diff(), StandardCharsets.UTF_8);
            requireTrustedState(new WorkspaceRef(delivery.location()), new WorkspaceRef(delivery.originalLocation()),
                    delivery.baseline());
            Files.writeString(indexLock, candidate.id(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            locked = true;
            String capture = "delveforge-captured-" + UUID.randomUUID();
            Files.writeString(incomplete, candidate.id() + "\n" + capture + "\n", StandardOpenOption.CREATE_NEW);
            beforeDeliveryMutation(target);
            try {
                DeliveryFiles.apply(target, changes, capture);
            } catch (DeliveryFiles.NotStarted failure) {
                // 原生边界证明尚未动过正式文件/index，外部冲突仍由 inspect 决定是否需要恢复。
                Files.delete(incomplete);
                throw new AgentException("MUTATION_NOT_STARTED", "Official file/index mutation never started", failure);
            }
            Files.delete(indexLock);
            locked = false;
            // 只有源码写入成功后才更新 index；--cached 不接触正式工作树，并由 Git 自己持有 index.lock。
            beforeDeliveryIndexPublication(target);
            deliveryGit(target, null, "--attr-source=" + delivery.expectedTree(), "-c", "core.safecrlf=false",
                    "apply", "--cached", "--unidiff-zero", "--whitespace=nowarn", "--", patch.toString());
            requireDeliveryTree(new WorkspaceRef(delivery.location()), new WorkspaceRef(delivery.originalLocation()),
                    delivery.baseline(), delivery.expectedTree());
            // 只有实际 B/index 全部证明后才移除此标记；任何失败/崩溃均不允许按 B 自动接受。
            Files.delete(incomplete);
        } catch (IOException failure) {
            throw new WorkspaceException("Non-destructive Candidate mutation did not complete; scene retained", failure);
        } finally {
            try {
                if (locked) {
                    Files.delete(indexLock);
                }
                if (index != null) {
                    Files.deleteIfExists(index);
                }
                if (material != null) {
                    // 仅删除本次渲染材料；Linux 捕获目录与 incomplete 标记不属于清理范围。
                    try (var files = Files.walk(material)) {
                        for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                            Files.delete(path);
                        }
                    }
                }
            } catch (IOException failure) {
                throw new WorkspaceException("Cannot release owned Delivery render material", failure);
            }
        }
    }

    private Map<String, String[]> deliveryEntries(Path target, String revision) {
        Map<String, String[]> entries = new LinkedHashMap<>();
        for (String entry : contentGit(target, revision, "ls-tree", "-r", "-z", revision).split("\0")) {
            if (!entry.isEmpty()) {
                int separator = entry.indexOf('\t');
                entries.put(entry.substring(separator + 1), entry.substring(0, separator).split(" "));
            }
        }
        return entries;
    }

    private static byte[] boundedDeliveryBytes(Path path) throws IOException {
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(1048577);
            if (bytes.length > 1048576) {
                throw new IOException("Delivery file/index exceeds its byte budget");
            }
            return bytes;
        }
    }

    private static String blobIdentity(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new WorkspaceException("Cannot prove Delivery blob bytes", failure);
        }
    }

    /** 确定性故障注入边界；生产实现不改变文件，真实写入紧随其后。 */
    protected void beforeDeliveryMutation(Path target) { }

    /** 源码已写入、Git 原子发布 index 前的故障注入边界。 */
    protected void beforeDeliveryIndexPublication(Path target) { }

    @Override
    public String createCommit(Delivery delivery) {
        Path target = deliveryTarget(delivery);
        if (inspect(delivery) != Position.APPLIED) {
            throw new AgentException("TREE_MISMATCH", "Cannot commit an unproven official tree");
        }
        return deliveryGit(target, null, "-c", "user.name=DelveForge", "-c", "user.email=local@delveforge.invalid",
                "-c", "commit.gpgSign=false", "commit-tree", delivery.expectedTree(), "-p", delivery.baseline(),
                "-m", "DelveForge: implement step " + delivery.stepId(), "-m", "Candidate: " + delivery.candidateId()).strip();
    }

    @Override
    public void publish(Delivery delivery) {
        Path target = deliveryTarget(delivery);
        if (inspect(delivery) != Position.APPLIED) {
            throw new AgentException("TREE_MISMATCH", "Publication requires the exact Candidate worktree");
        }
        deliveryGit(target, null, "update-ref", "--no-deref", "HEAD", delivery.revision(), delivery.baseline());
    }

    private String deliveryGit(Path target, Path index, String... arguments) {
        List<String> exact = new ArrayList<>(List.of("--no-replace-objects", "-c", "core.hooksPath=/dev/null"));
        exact.addAll(List.of(arguments));
        WorkspaceCommandRunner.Result result = mutationGitResult(target, index, exact.toArray(String[]::new));
        if (!result.succeeded()) {
            throw new WorkspaceException("Controlled Delivery Git operation did not complete (exit " + result.exitCode() + ")");
        }
        return result.output();
    }

    private static void requireDeliveryPath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.length() > 512
                || path.contains(":") || path.contains("\n") || path.contains("\r") || path.contains("\t")
                || path.contains("\"")
                || new DeterministicRepositorySecretPolicy().excludes(path)) {
            throw new AgentException("TREE_MISMATCH", "Candidate contains an unsupported or protected source path");
        }
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")
                    || part.equalsIgnoreCase(".hg") || part.equalsIgnoreCase(".svn") || part.endsWith(" ") || part.endsWith(".")
                    || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) {
                throw new AgentException("TREE_MISMATCH", "Candidate source path escapes the controlled Git tree");
            }
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
        requireCanonicalAttributePaths(target, paths);
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
        try {
            return lineEndingEquivalent(boundedDeliveryBytes(target.resolve(path)), expected, eol, autocrlf);
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot prove tracked EOL bytes", failure);
        }
    }

    private boolean lineEndingEquivalent(byte[] actual, String expected, String eol, String autocrlf) {
        if (eol == null) {
            return false;
        }
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
        try {
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
        } catch (NoSuchAlgorithmException failure) {
            throw new WorkspaceException("Cannot prove tracked EOL bytes", failure);
        }
    }

    /** 外部属性不能随 tracked 文件恢复而改变；仅接受受版本证明的仓库属性来源。 */
    private void requireRepositoryAttributeSources(Path target) {
        // 此配置查询不带覆盖值，也不解释路径或哨兵；包括 include / 各级配置的有效值。
        WorkspaceCommandRunner.Result configured = WorkspaceCommandRunner.run(target,
                List.of("git", "-C", target.toString(), "config", "--null", "--get", "core.attributesFile"),
                Duration.ofSeconds(30), 1048576);
        if ((configured.exitCode() != 0 && configured.exitCode() != 1)
                || (configured.succeeded() && !configured.output().equals("\0"))) {
            throw new WorkingCopyBasisChangedException("External Git attribute source cannot be proven stable");
        }
        Path info = target.resolve(".git/info");
        try {
            if ((Files.exists(info, LinkOption.NOFOLLOW_LINKS)
                    && (!Files.isDirectory(info, LinkOption.NOFOLLOW_LINKS) || !info.toRealPath().equals(info)))
                    || Files.exists(info.resolve("attributes"), LinkOption.NOFOLLOW_LINKS)) {
                // info/attributes 的高优先级规则及链接不属于任何固定 revision；不能被 checkout 的属性证明覆盖。
                throw new WorkingCopyBasisChangedException("Unversioned Git attributes prevent controlled mutation");
            }
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot confirm Git attribute source isolation", failure);
        }
    }

    /** Git 对象路径区分大小写，宿主文件系统未必如此；无法统一解释的属性文件名不进入写路径。 */
    private void requireCanonicalAttributePaths(Path target, List<String> paths) {
        TreeSet<Path> directories = new TreeSet<>();
        directories.add(target);
        for (String path : paths) {
            String safe = requireSafeRelativePath(path, false);
            for (String segment : safe.split("/")) {
                if (segment.equalsIgnoreCase(".gitattributes") && !segment.equals(".gitattributes")) {
                    throw new WorkingCopyBasisChangedException("Git attribute path has an ambiguous case variant");
                }
            }
            Path parent = target.resolve(safe).getParent();
            while (parent != null && parent.startsWith(target)) {
                directories.add(parent);
                parent = parent.getParent();
            }
        }
        try {
            for (Path directory : directories) {
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                        || !directory.toRealPath().equals(directory)) {
                    throw new WorkingCopyBasisChangedException("Cannot prove Git attribute directory identity");
                }
                // 枚举真实目录项，不能用 Windows Path.equals 或 Git index 推断磁盘上的大小写。
                try (var entries = Files.newDirectoryStream(directory)) {
                    for (Path entry : entries) {
                        String name = entry.getFileName().toString();
                        if (name.equalsIgnoreCase(".gitattributes")
                                && (!name.equals(".gitattributes")
                                || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                                || !entry.toRealPath().equals(entry))) {
                            throw new WorkingCopyBasisChangedException("Physical Git attribute source is ambiguous");
                        }
                    }
                }
            }
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot prove Git attribute path identity", failure);
        }
    }

    /** 只解析 Git 的首个 pattern 字段及属性 token；不是 glob 匹配器，也不扩展支持的转换。 */
    private void requireSafeAttributeRule(String line) {
        int cursor = 0;
        while (cursor < line.length() && isAttributeBlank(line.charAt(cursor))) {
            cursor++;
        }
        if (cursor == line.length() || line.charAt(cursor) == '#') {
            return;
        }
        String pattern;
        if (line.charAt(cursor) == '"') {
            StringBuilder decoded = new StringBuilder();
            boolean closed = false;
            cursor++;
            while (cursor < line.length()) {
                char value = line.charAt(cursor++);
                if (value == '"') {
                    closed = true;
                    break;
                }
                if (value == '\\') {
                    if (cursor == line.length()) {
                        throw new WorkingCopyBasisChangedException("Cannot decode quoted Git attribute pattern");
                    }
                    char escaped = line.charAt(cursor++);
                    if (escaped >= '0' && escaped <= '3' && cursor + 1 < line.length()
                            && line.charAt(cursor) >= '0' && line.charAt(cursor) <= '7'
                            && line.charAt(cursor + 1) >= '0' && line.charAt(cursor + 1) <= '7') {
                        value = (char) ((escaped - '0') * 64 + (line.charAt(cursor) - '0') * 8
                                + line.charAt(cursor + 1) - '0');
                        cursor += 2;
                    } else {
                        value = switch (escaped) {
                            case '"', '\\' -> escaped;
                            case 'a' -> '\u0007';
                            case 'b' -> '\b';
                            case 'f' -> '\f';
                            case 'n' -> '\n';
                            case 'r' -> '\r';
                            case 't' -> '\t';
                            case 'v' -> '\u000b';
                            default -> throw new WorkingCopyBasisChangedException(
                                    "Cannot decode quoted Git attribute pattern");
                        };
                    }
                }
                decoded.append(value);
            }
            if (!closed) {
                throw new WorkingCopyBasisChangedException("Cannot decode quoted Git attribute pattern");
            }
            pattern = decoded.toString();
        } else {
            int start = cursor;
            while (cursor < line.length() && !isAttributeBlank(line.charAt(cursor))) {
                cursor++;
            }
            pattern = line.substring(start, cursor);
        }
        // Git 只把首个解码后的 pattern 的非空 [attr] 前缀视为宏；中间字符组与单独 [attr] 是 glob。
        if ((pattern.startsWith("[attr]") && pattern.length() > 6)
                || Arrays.stream(line.substring(cursor).split("[ \\t\\r\\n]+"))
                        .anyMatch(this::isExternalConversionAttribute)) {
            // 恢复会分批更新属性文件；休眠转换规则和真实宏也必须在危险写入前拒绝。
            throw new WorkingCopyBasisChangedException("Git attribute definitions cannot prove safe restoration");
        }
    }

    /** 属性文本只接受可证明的 UTF-8 / LF / CRLF 子集；不把 BOM 当作可兼容的语法前缀。 */
    private void requireSafeAttributeText(String definitions) {
        for (int index = 0; index < definitions.length(); index++) {
            char value = definitions.charAt(index);
            if (value == '\ufeff' || value == '\ufffd' || value == 127
                    || (value < 32 && value != '\t' && value != '\n' && value != '\r')
                    || (value == '\r' && (index + 1 == definitions.length()
                            || definitions.charAt(index + 1) != '\n'))) {
                // 有界命令以 UTF-8 解码；替换字符、NUL/控制字节或裸 CR 不足以统一证明 Git 的字节解释。
                throw new WorkingCopyBasisChangedException("Git attribute text has an unproven encoding or delimiter");
            }
        }
    }

    private boolean isExternalConversionAttribute(String token) {
        int separator = token.indexOf('=');
        String name = separator < 0 ? token : token.substring(0, separator);
        if (name.startsWith("-") || name.startsWith("!")) {
            name = name.substring(1);
        }
        // 只按 ASCII 属性名判定能力；任意值里的 Unicode 行分隔符不能绕过正则的 dot 边界。
        return List.of("filter", "ident", "working-tree-encoding").contains(name);
    }

    private boolean isAttributeBlank(char value) {
        return value == ' ' || value == '\t' || value == '\r' || value == '\n';
    }

    /** 按存在性拒绝外部转换；同时用于当前内容证明与恢复目标的 checkout 前检查。 */
    private void requireSupportedAttributes(Path target, String revision, List<String> paths) {
        for (String path : paths) {
            if (path.equals(".gitattributes") || path.endsWith("/.gitattributes")) {
                String definitions = contentGit(target, revision, "cat-file", "blob", revision + ":" + path);
                requireSafeAttributeText(definitions);
                for (String line : definitions.lines().toList()) {
                    requireSafeAttributeRule(line);
                }
            }
        }
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

    /** 仅检查不可变 tree 的属性能力，供写入前的预期结果和原始恢复目标共用。 */
    private void requireSafeTreeAttributes(Path target, String revision) {
        List<String> paths = Arrays.stream(contentGit(target, revision,
                "ls-tree", "-r", "--name-only", "-z", revision).split("\0"))
                .filter(path -> !path.isEmpty()).toList();
        requireCanonicalAttributePaths(target, paths);
        for (int start = 0; start < paths.size(); start += 32) {
            requireSupportedAttributes(target, revision, paths.subList(start, Math.min(start + 32, paths.size())));
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

    protected WorkspaceCommandRunner.Result mutationGitResult(Path directory, Path index, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.fsmonitor=false",
                "-c", "core.hooksPath=" + directory.resolve(".git/delveforge-disabled-hooks"),
                // 每条命令均固定全局属性为空；即使 tracked include 配置被恢复也不能重新启用外部规则。
                "-c", "core.attributesFile=", "-C", directory.toString()));
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
    public RepositoryCompatibility assess(WorkspaceRef source,
            String revision) {
        Path repository = requireReadableRepository(source);
        return compatibility().inspect(repository, revision).report();
    }

    @Override
    public void requirePreparationProvenance(WorkspaceRef copy,
            PreparationProvenance provenance) {
        if (provenance.originalRevision().equals(provenance.preparedRevision())) {
            return;
        }
        Path target = requireManagedCopy(copy);
        String parent = CompatibilityGit.text(target, null, Map.of(), "rev-list", "--parents", "-n", "1",
                provenance.preparedRevision());
        if (!parent.equals(provenance.preparedRevision() + " " + provenance.originalRevision())) {
            throw new WorkingCopyBasisChangedException("Prepared baseline parent differs from recorded provenance");
        }
        var original = compatibility().inspect(target, provenance.originalRevision());
        if (!original.report().supported() || !original.report().generatedPaths().equals(provenance.removedPaths())) {
            throw new WorkingCopyBasisChangedException("Prepared deletion policy differs from recorded provenance");
        }
        byte[] diff = CompatibilityGit.run(target, null, Map.of(), "diff-tree", "--no-commit-id", "-r",
                "--name-status", "--no-renames", "-z", provenance.originalRevision(), provenance.preparedRevision());
        String[] changes = new String(diff, StandardCharsets.UTF_8).split("\0");
        Set<String> removed = new HashSet<>();
        Set<String> metadata = new HashSet<>();
        for (int i = 0; i < changes.length; i += 2) {
            if (i + 1 >= changes.length) {
                throw new WorkingCopyBasisChangedException("Incomplete preparation tree evidence");
            }
            if (changes[i].equals("D")) {
                removed.add(changes[i + 1]);
            } else if (Set.of("A", "M").contains(changes[i]) && changes[i + 1].equals(".gitignore")) {
                metadata.add(changes[i + 1]);
            } else {
                throw new WorkingCopyBasisChangedException("Preparation contains an unauthorized source change");
            }
        }
        if (!removed.equals(new HashSet<>(provenance.removedPaths()))
                || !metadata.equals(new HashSet<>(provenance.metadataPaths()))) {
            throw new WorkingCopyBasisChangedException("Preparation tree differs from persisted changed paths");
        }
        if (!metadata.isEmpty()) {
            var entry = original.entries().get(".gitignore");
            byte[] before = entry == null ? new byte[0]
                    : CompatibilityGit.run(target, null, Map.of(), "cat-file", "blob", entry.objectId());
            byte[] after = CompatibilityGit.run(target, null, Map.of(), "cat-file", "blob",
                    provenance.preparedRevision() + ":.gitignore");
            if (after.length < before.length || !Arrays.equals(before, Arrays.copyOf(after, before.length))) {
                throw new WorkingCopyBasisChangedException("Preparation replaced original ignore metadata");
            }
            Set<String> allowed = new HashSet<>();
            for (String path : removed) {
                allowed.add(GitRepositoryPreparation.directoryIgnoreRule(
                        GitRepositoryCompatibility.generatedRoot(path, original.poms().keySet()) + "target"));
            }
            String appended = new String(after, before.length, after.length - before.length, StandardCharsets.UTF_8);
            if (appended.lines().filter(line -> !line.isEmpty()).anyMatch(line -> !allowed.contains(line))) {
                throw new WorkingCopyBasisChangedException("Preparation added unrelated ignore metadata");
            }
        }
        try {
            Path marker = target.resolve(".git/delveforge-preparation.json");
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 8388608) {
                throw new WorkingCopyBasisChangedException("Preparation receipt is missing or unsafe");
            }
            var stored = new ObjectMapper().readValue(Files.readAllBytes(marker),
                    PreparationProvenance.class);
            if (!provenance.equals(stored)) {
                throw new WorkingCopyBasisChangedException("Git and SQLite preparation receipts differ");
            }
        } catch (IOException failure) {
            throw new WorkspaceException("Cannot prove preparation receipt", failure);
        }
    }

    private GitRepositoryCompatibility compatibility() {
        return new GitRepositoryCompatibility(
                new DeterministicRepositorySecretPolicy());
    }

    @Override
    public PreparedWorkspace provision(WorkspaceRef source, String revision, String directoryName) {
        Path repository = requireReadableRepository(source);
        String commit = requireCommitId(repository, revision);
        requireUnchangedBasis(source, commit);
        GitRepositoryCompatibility.Inspection compatibility = compatibility().inspect(repository, commit);
        var blocker = compatibility.report().issues().stream()
                .filter(issue -> issue.blocking() && !issue.code().equals("MAVEN_REQUIRED")).findFirst();
        if (blocker.isPresent()) {
            throw new AgentException(blocker.get().code(), blocker.get().action());
        }

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

            // 只复制当前分支所需历史；预算与计时由同一受控 Git 执行器保证，不运行项目 Hook。
            CompatibilityGit.run(root, null, Map.of(), "clone", "--no-local", "--no-checkout",
                    "--single-branch", "--template=", "--", repository.toString(), target.toString());
            CompatibilityGit.run(target, null, Map.of(), "checkout", "--detach", commit);

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
            var provenance = GitRepositoryPreparation.prepare(target, compatibility);
            String token = UUID.randomUUID().toString();
            Files.writeString(target.resolve(".git/delveforge-preparation"), token);
            Files.writeString(target.resolve(".git/delveforge-preparation.json"),
                    new ObjectMapper().writeValueAsString(provenance));
            requireTrustedState(location, source, provenance.preparedRevision());
            requirePreparationProvenance(location, provenance);
            requireUnchangedBasis(source, commit);
            return new PreparedWorkspace(location, provenance.preparedRevision(), token, provenance);
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
