package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * {@link WorkspaceReadPort} 的本地 Git 实现。
 *
 * <p>本 Adapter 是 Workspace 边界的只读实现：所有 Git 调用都发生在 Infrastructure
 * 内部（RULE-ARCH-009），Domain 与 Application 只看到 {@link WorkspaceReadPort}
 * 与相对路径。它不实现 {@code WorkspaceMutationPort}，因此 Repository Analysis
 * 等只读流程在类型层面拿不到任何修改能力（RULE-ARCH-010、ADR-0001）。
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
 * <p>只使用 git 的只读 plumbing 命令，不执行任何写操作：
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
public class GitWorkspaceAdapter implements WorkspaceReadPort {

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
        List<String> command = new ArrayList<>(arguments.length + 3);
        command.add(GIT_EXECUTABLE);
        command.add("-C");
        command.add(repository.toString());
        command.addAll(List.of(arguments));

        Process process;
        try {
            process = new ProcessBuilder(command).start();
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
