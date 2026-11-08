package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentCheckEvidence;
import com.ayywl.delveforge.application.agent.AgentPolicyRejection;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentTools;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
import com.ayywl.delveforge.domain.agent.Candidate;
import com.ayywl.delveforge.infrastructure.workspace.GitRepositoryCompatibility;
import com.ayywl.delveforge.infrastructure.workspace.RepositoryContentPolicy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Docker 只运输副本；正式 WorkingCopy 没有任何容器挂载或写入命令。 */
public final class DockerAgentSandbox implements AgentSandboxPort {
    private static final Duration CONTROL_TIMEOUT = Duration.ofSeconds(45);
    private final AgentSandboxProperties properties;
    private final RepositorySecretPolicy secrets;
    private final AgentProcess commands;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    private static final class Session {
        final Task task;
        final AgentProtocol.Limits limits;
        final Path directory;
        final Map<String, Entry> baseline = new LinkedHashMap<>();
        final List<JsonNode> checks = new ArrayList<>();
        JsonNode agentReport;
        final Set<String> retainedPaths = new HashSet<>();
        final Map<String, String> baselineHashes = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> resourceCapabilities = new LinkedHashMap<>();
        String executionBaselineTree;
        String image;
        int jdk;

        Session(Task task, AgentProtocol.Limits limits, Path directory) {
            this.task = task;
            this.limits = limits;
            this.directory = directory;
        }
    }

    private record Entry(String mode, String objectId) {
    }

    public DockerAgentSandbox(AgentSandboxProperties properties, RepositorySecretPolicy secrets) {
        this(properties, secrets, new AgentProcess());
    }

    DockerAgentSandbox(AgentSandboxProperties properties, RepositorySecretPolicy secrets, AgentProcess commands) {
        this.properties = properties;
        this.secrets = secrets;
        this.commands = commands;
    }

    private String name(String runId, String suffix) {
        requireId(runId);
        return "df-" + runId + "-" + suffix;
    }

    private static void requireId(String runId) {
        if (runId == null || !runId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new AgentException("INVALID_REQUEST", "Invalid Run identity");
        }
    }

    private Path directory(String runId) {
        requireId(runId);
        Path root = properties.root().toAbsolutePath().normalize();
        try {
            if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                root = root.toRealPath();
            }
        } catch (IOException failure) {
            throw new AgentException("RESOURCE_OWNERSHIP", "Cannot resolve the managed Agent root", failure);
        }
        Path directory = root.resolve(runId).normalize();
        if (!directory.getParent().equals(root) || Files.isSymbolicLink(root) || Files.isSymbolicLink(directory)) {
            throw new AgentPolicyRejection(AgentPolicyRejection.Rule.STORAGE_LOCATION, AgentPolicyRejection.Stage.STORAGE);
        }
        try {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && !directory.toRealPath().equals(directory)) {
                throw new AgentException("RESOURCE_OWNERSHIP", "Run storage resolves through an unexpected alias");
            }
        } catch (IOException failure) {
            throw new AgentException("RESOURCE_OWNERSHIP", "Cannot resolve owned Run storage", failure);
        }
        return directory;
    }

    private AgentProcess.Result docker(List<String> arguments, byte[] input, Duration timeout, int maxBytes,
            BooleanSupplier cancelled) {
        List<String> command = new ArrayList<>();
        command.add(properties.docker());
        command.addAll(arguments);
        AgentProcess.Result result = commands.run(command, null, input, timeout, maxBytes, cancelled, Map.of());
        if (result.exitCode() != 0) {
            String diagnostic = result.diagnostic().toLowerCase(Locale.ROOT);
            String code = diagnostic.contains("permission denied") ? "DOCKER_PERMISSION_DENIED"
                    : diagnostic.contains("cannot connect") || diagnostic.contains("is the docker daemon")
                            ? "DOCKER_UNAVAILABLE"
                    : diagnostic.contains("pull access denied") || diagnostic.contains("no such image")
                            || diagnostic.contains("manifest unknown") ? "SANDBOX_IMAGE_UNAVAILABLE"
                    : diagnostic.contains("out of memory") || diagnostic.contains("no space left")
                            || diagnostic.contains("resource temporarily unavailable") ? "SANDBOX_RESOURCE_EXHAUSTED"
                    : "DOCKER_FAILURE";
            throw new AgentException(code, "Controlled Docker " + arguments.getFirst()
                    + " failed; " + sanitize(result.diagnostic()).substring(0, Math.min(600, sanitize(result.diagnostic()).length())));
        }
        return result;
    }

    private byte[] git(Session session, Map<String, String> environment, byte[] input, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.autocrlf=false", "-c",
                "core.hooksPath=", "-c", "core.attributesFile=", "-c", "diff.external=", "-c",
                "core.quotePath=true", "--git-dir=" + session.directory.resolve("repository.git")));
        command.addAll(List.of(arguments));
        AgentProcess.Result result = commands.run(command, session.directory, input, CONTROL_TIMEOUT,
                Math.max(session.limits.maxCandidateBytes() * 4, 150994944), () -> false, environment);
        if (result.exitCode() != 0) {
            String diagnostic = sanitize(result.diagnostic());
            throw new AgentException("GIT_FINALIZATION", "Controlled Git " + arguments[0] + " failed: "
                    + diagnostic.substring(0, Math.min(600, diagnostic.length())));
        }
        return result.bytes();
    }

    private String gitText(Session session, Map<String, String> environment, byte[] input, String... arguments) {
        return new String(git(session, environment, input, arguments), StandardCharsets.UTF_8).strip();
    }

    private byte[] sourceGit(Session session, byte[] input, String... arguments) {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.fsmonitor=false",
                "-c", "core.hooksPath=", "-C", session.task.location()));
        command.addAll(List.of(arguments));
        AgentProcess.Result result = commands.run(command, null, input, CONTROL_TIMEOUT, 150994944,
                () -> false, Map.of());
        if (result.exitCode() != 0) {
            throw new AgentException("BASELINE_SNAPSHOT", "Cannot read trusted baseline Git objects");
        }
        return result.bytes();
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException failure) {
            throw new AgentException("UNSUPPORTED_ENCODING", "Only valid UTF-8 changed source is supported");
        }
    }

    private void requireBaselineBudget(Session session) {
        var inspection = new GitRepositoryCompatibility(secrets)
                .inspect(Path.of(session.task.location()), session.task.baseline());
        var blocker = inspection.report().issues().stream().filter(issue -> issue.blocking()).findFirst();
        if (blocker.isPresent()) {
            throw new AgentException(blocker.get().code(), blocker.get().action());
        }
        if (inspection.report().preparationRequired()) {
            throw new AgentException("GENERATED_OUTPUT_AMBIGUITY", "请通过正常准备流程创建已记录来源的演化基线。");
        }
        if (inspection.report().files() > session.limits.maxFiles()) {
            throw new AgentException("SOURCE_LIMIT", "基线文件数超过本次 Run 的资源预算。");
        }
        for (var resource : inspection.resources().entrySet()) {
            var entry = inspection.entries().get(resource.getKey());
            byte[] bytes = sourceGit(session, null, "cat-file", "blob", entry.objectId());
            session.resourceCapabilities.put(resource.getKey(), Map.of("kind", resource.getValue().kind().name(),
                    "mode", entry.mode(), "sha256", sourceDigest(bytes), "size", entry.size(),
                    "settings", resource.getValue().settings(), "blocksChecks", !resource.getValue().executionAvailable()
                            && RepositoryContentPolicy.buildInput(resource.getKey())));
        }
    }

    private static String sourceDigest(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new AgentException("SOURCE_DIGEST", "SHA-256 is unavailable", failure);
        }
    }

    /** E 仅为执行视图；可信 R 和所有保留 blob 始终留在主机 Git，不给项目进程。 */
    private String executionTree(Session session) {
        Map<String, String> index = Map.of("GIT_INDEX_FILE", session.directory.resolve("execution.index").toString());
        git(session, index, null, "read-tree", session.task.baseline());
        StringBuilder removals = new StringBuilder();
        session.resourceCapabilities.forEach((path, resource) -> {
            if (resource.get("kind").equals("PRESERVED_ONLY")) {
                removals.append("0 ").append("0".repeat(40)).append('\t').append(path).append('\0');
            }
        });
        if (!removals.isEmpty()) {
            git(session, index, removals.toString().getBytes(StandardCharsets.UTF_8), "update-index", "-z", "--index-info");
        }
        session.executionBaselineTree = gitText(session, index, null, "write-tree");
        return session.executionBaselineTree;
    }

    @Override
    public void prepare(Task task, AgentProtocol.Limits limits, BooleanSupplier cancelled) {
        Session session = new Session(task, limits, directory(task.runId()));
        if (sessions.putIfAbsent(task.runId(), session) != null) {
            throw new AgentException("RUN_CONFLICT", "Sandbox already prepared");
        }
        try {
            if (Files.exists(session.directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new AgentException("RESOURCE_OWNERSHIP", "Fresh Run refuses pre-existing storage");
            }
            Files.createDirectories(session.directory);
            String engine = docker(List.of("version", "--format", "{{.Server.Os}} {{.Server.APIVersion}}"),
                    null, CONTROL_TIMEOUT, 8192, cancelled).text().strip();
            String[] engineInfo = engine.split(" ");
            if (engineInfo.length != 2 || !engineInfo[0].equals("linux")
                    || !engineInfo[1].matches("1\\.[0-9]+") || Integer.parseInt(engineInfo[1].substring(2)) < 48) {
                throw new AgentException("DOCKER_ENVIRONMENT", "Linux Engine API 1.48+ is required for isolated dependency networking");
            }
            git(session, Map.of(), null, "init", "--bare", "--template=");
            if (task.baseline() == null || !task.baseline().matches("[0-9a-f]{40}")) {
                throw new AgentException("BASELINE_MISMATCH", "A full trusted Git commit identity is required");
            }
            requireBaselineBudget(session);
            // 直接读取 commit/tree/blob；不启动源仓库的 upload-pack 或任何服务器 hook。
            byte[] commit = sourceGit(session, null, "cat-file", "commit", task.baseline());
            byte[] objects = sourceGit(session, null, "rev-list", "--objects", "--no-object-names", task.baseline() + "^{tree}");
            byte[] pack = sourceGit(session, objects, "pack-objects", "--stdout");
            git(session, Map.of(), pack, "index-pack", "--stdin");
            String baseline = gitText(session, Map.of(), commit, "hash-object", "-w", "-t", "commit", "--stdin");
            if (!task.baseline().equals(baseline)) {
                throw new AgentException("BASELINE_MISMATCH", "Sandbox baseline differs from trusted revision");
            }
            Map<String, byte[]> poms = inspectBaseline(session);
            List<String> mavenRoots = new MavenJdkSelector().roots(poms);
            List<String> selectedMaven = task.checkDirectories().isEmpty()
                    ? mavenRoots.size() == 1 ? mavenRoots : List.of()
                    : mavenRoots.stream().filter(task.checkDirectories()::contains).toList();
            int jdk = task.targetJdk() == null ? properties.defaultJdk() : task.targetJdk();
            for (String directory : selectedMaven) {
                try {
                    int requiredJdk = new MavenJdkSelector().select(poms, task.targetJdk(), properties, directory);
                    jdk = selectedMaven.size() == 1 ? requiredJdk : Math.max(jdk, requiredJdk);
                } catch (AgentException unresolvedToolchain) {
                    // 静态模型不确定时保留所选环境；原生命令的实际诊断交回 Agent。
                }
            }
            if (!properties.images().containsKey(jdk)) {
                throw new AgentException("JDK_UNSUPPORTED", "No controlled image for the selected runtime");
            }
            session.jdk = jdk;
            session.image = docker(List.of("image", "inspect", "--format", "{{.Id}}", properties.images().get(jdk)),
                    null, CONTROL_TIMEOUT, 8192, cancelled).text().strip();
            if (!session.image.matches("sha256:[0-9a-f]{64}")) {
                throw new AgentException("SANDBOX_IMAGE_UNAVAILABLE", "Cannot pin the controlled sandbox image identity");
            }
            Files.write(session.directory.resolve("source.tar"), git(session, Map.of(), null,
                    "archive", "--format=tar", executionTree(session)));
            ensureVolume(name(task.runId(), "source"), task.runId());
            ensureVolume(name(task.runId(), "m2"), task.runId());
            ensureVolume(properties.cacheVolume(), "shared-cache");
            List<String> seed = baseRun(task.runId(), "seed", session.image, "none", false);
            seed.addAll(List.of("--mount", "type=volume,source=" + properties.cacheVolume() + ",target=/cache,readonly",
                    session.image, "python3", "-c", "import shutil; shutil.copytree('/cache','/m2',"
                            + "dirs_exist_ok=True,ignore=shutil.ignore_patterns('.df-pending-*'))"));
            docker(seed, null, CONTROL_TIMEOUT, 8192, cancelled);
            removeContainer(name(task.runId(), "seed"));
            List<String> create = baseRun(task.runId(), "agent", session.image, "none", true);
            create.addAll(List.of(session.image, "sleep", "infinity"));
            docker(create, null, CONTROL_TIMEOUT, 8192, cancelled);
            // 在传入项目源码、初始化和首次模型调用前验证实际固定镜像中的工具。
            verifyProtocol(session, cancelled);
            docker(List.of("exec", "-i", name(task.runId(), "agent"), "python3", "-c",
                    "import sys; open('/tmp/source.tar','wb').write(sys.stdin.buffer.read(150994945))"),
                    Files.readAllBytes(session.directory.resolve("source.tar")), CONTROL_TIMEOUT, 8192, cancelled);
            // 首次捕获也必须保留 Git 已跟踪路径，不能由 .gitignore 重新决定基线成员。
            session.retainedPaths.addAll(session.baseline.keySet());
            JsonNode initial = invoke(session, "initialize", json.createObjectNode(), cancelled);
            requireToolSuccess(initial);
            Set<String> actual = new HashSet<>();
            initial.path("result").path("files").forEach(file -> {
                String path = file.path("path").asText();
                actual.add(path);
                session.retainedPaths.add(path);
                session.baselineHashes.put(path, file.path("sha256").asText() + ":" + file.path("mode").asText());
            });
            if (!actual.equals(session.baseline.keySet())) {
                throw new AgentException("GIT_ATTRIBUTES_UNSUPPORTED", "Archive omitted or transformed baseline paths");
            }
            for (var resource : session.resourceCapabilities.entrySet()) {
                if (resource.getValue().get("kind").equals("PUBLIC_RESOURCE")) {
                    Path publicFile = session.directory.resolve("public").resolve(resource.getKey());
                    Files.createDirectories(publicFile.getParent());
                    Files.write(publicFile, git(session, Map.of(), null, "cat-file", "blob",
                            session.baseline.get(resource.getKey()).objectId()));
                }
            }
            Files.delete(session.directory.resolve("source.tar"));
        } catch (IOException failure) {
            throw new AgentException("ENVIRONMENT_FAILURE", "Cannot prepare owned sandbox material", failure);
        }
    }

    private Map<String, byte[]> inspectBaseline(Session session) {
        String tree = decode(git(session, Map.of(), null, "ls-tree", "-r", "-z", session.task.baseline()));
        Map<String, byte[]> poms = new HashMap<>();
        Set<String> folded = new HashSet<>();
        for (String line : tree.split("\u0000")) {
            if (line.isEmpty()) {
                continue;
            }
            int tab = line.indexOf('\t');
            String[] metadata = line.substring(0, tab).split(" ");
            String path = line.substring(tab + 1);
            if (!GitRepositoryCompatibility.safePath(path)) {
                throw new AgentException("GIT_PATH_UNSUPPORTED", "Unsupported baseline path");
            }
            if (!Set.of("100644", "100755").contains(metadata[0]) || !metadata[1].equals("blob")
                    || !folded.add(path.toLowerCase(Locale.ROOT))) {
                throw new AgentException("GIT_TYPE_UNSUPPORTED", "Baseline contains unsupported modes, paths or tracked build output");
            }
            session.baseline.put(path, new Entry(metadata[0], metadata[2]));
            if (path.equals("pom.xml") || path.endsWith("/pom.xml")) {
                poms.put(path, git(session, Map.of(), null, "cat-file", "blob", metadata[2]));
            }
            if (path.equals(".gitattributes") || path.endsWith("/.gitattributes")) {
                String attributes = decode(git(session, Map.of(), null, "cat-file", "blob", metadata[2]));
                if (attributes.matches("(?s).*(filter=|export-ignore|export-subst|working-tree-encoding|\\bident\\b).*")) {
                    throw new AgentException("GIT_ATTRIBUTES_UNSUPPORTED", "Custom filters/export transformations require explicit support");
                }
            }
        }

        return poms;
    }

    private void requirePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\") || path.length() > 512
                || path.contains(":") || path.contains("\n") || path.contains("\r") || path.contains("\t")
                || path.contains("\"") || secrets.excludes(path)) {
            throw new AgentPolicyRejection(secrets.excludes(path == null ? "" : path)
                    ? AgentPolicyRejection.Rule.PROTECTED_CREDENTIAL_PATH : AgentPolicyRejection.Rule.PATH_FORMAT,
                    AgentPolicyRejection.Stage.PATH_VALIDATION);
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git")
                    || part.equalsIgnoreCase(".hg") || part.equalsIgnoreCase(".svn") || part.endsWith(" ") || part.endsWith(".")
                    || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) {
                throw new AgentPolicyRejection(AgentPolicyRejection.Rule.FORBIDDEN_PATH,
                        AgentPolicyRejection.Stage.PATH_VALIDATION);
            }
            if (Set.of("node_modules", ".df-check", ".df-frontend-dependencies",
                    ".df-frontend-store", ".df-frontend-npm-cache").contains(part) || part.endsWith(".tsbuildinfo")) {
                throw new AgentException("GENERATED_OUTPUT_AMBIGUITY", "Runtime dependencies are not candidate source");
            }
        }
    }

    String readPathFeedback(String path) {
        try {
            requirePath(path);
            return null;
        } catch (AgentPolicyRejection rejection) {
            if ((rejection.rule() != AgentPolicyRejection.Rule.PATH_FORMAT
                    && rejection.rule() != AgentPolicyRejection.Rule.FORBIDDEN_PATH) || !ordinaryReadPath(path)) {
                throw rejection;
            }
            return AgentTools.readError(AgentTools.ReadError.RELATIVE_PATH_REQUIRED);
        }
    }

    private boolean ordinaryReadPath(String path) {
        if (path == null || path.isBlank() || path.length() > 512 || path.startsWith("/")
                || path.startsWith("\\") || path.contains(":")) {
            return false;
        }
        // 这里只检查拼写是否属于普通相对路径，绝不以替换后的路径执行读取。
        boolean hasName = false;
        for (String part : path.split("[/\\\\]", -1)) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..") || Set.of(".git", ".hg", ".svn", ".ssh", ".aws", ".gnupg", ".m2",
                    ".npmrc", ".netrc", ".pypirc").contains(part.toLowerCase(Locale.ROOT))
                    || part.toLowerCase(Locale.ROOT).startsWith(".env") || secrets.excludes(part)) {
                return false;
            }
            try {
                requirePath(part);
            } catch (AgentException rejectedPart) {
                return false;
            }
            hasName = true;
        }
        return hasName;
    }

    private List<String> baseRun(String runId, String suffix, String image, String network, boolean detached) {
        Session session = sessions.get(runId);
        AgentProtocol.Limits limits = session.limits;
        String sourceVolume = "source";
        List<String> result = new ArrayList<>(List.of("run", "--name", name(runId, suffix), "--label",
                "delveforge.owner=" + properties.owner(), "--label", "delveforge.run=" + runId,
                "--network", network, "--user", "11000:11000", "--cap-drop", "ALL", "--security-opt",
                "no-new-privileges", "--read-only", "--pids-limit", Integer.toString(properties.pids()),
                "--ulimit", "core=0:0",
                "--memory", properties.memory(), "--cpus", properties.cpus(), "--tmpfs",
                "/tmp:rw,exec,nosuid,size=256m", "--tmpfs", "/home/agent:rw,nosuid,size=32m,uid=11000,gid=11000",
                "--mount", "type=volume,source=" + name(runId, sourceVolume) + ",target=/workspace",
                "--mount", "type=volume,source=" + name(runId, "m2") + ",target=/m2",
                "--env", "DF_FILE_BYTES=" + limits.maxFileBytes(), "--env", "DF_MAX_FILES=" + limits.maxFiles(),
                "--env", "DF_PATCH_BYTES=" + limits.maxPatchBytes(), "--env",
                "DF_CAPTURE_BYTES=" + Math.max(limits.maxCandidateBytes() * 8, 16777216), "--env",
                "DF_BASELINE_FILE_BYTES=" + GitRepositoryCompatibility.MAX_BASELINE_FILE_BYTES, "--env",
                "DF_BASELINE_TOTAL_BYTES=" + GitRepositoryCompatibility.MAX_SOURCE_BYTES));
        for (String proxy : List.of("HTTP_PROXY", "HTTPS_PROXY", "FTP_PROXY", "ALL_PROXY", "NO_PROXY",
                "http_proxy", "https_proxy", "ftp_proxy", "all_proxy", "no_proxy")) {
            result.addAll(List.of("--env", proxy + "="));
        }
        if (detached) {
            result.add("-d");
        }
        return result;
    }

    private void ensureVolume(String name, String runId) {
        if (inspect("volume", name) == null) {
            docker(List.of("volume", "create", "--label", "delveforge.owner=" + properties.owner(),
                    "--label", "delveforge.run=" + runId, name), null, CONTROL_TIMEOUT, 8192, () -> false);
        }
        requireOwner("volume", name);
    }

    private JsonNode inspect(String kind, String name) {
        List<String> args = kind.equals("container") ? List.of("inspect", name) : List.of(kind, "inspect", name);
        List<String> command = new ArrayList<>(List.of(properties.docker()));
        command.addAll(args);
        AgentProcess.Result result = commands.run(command, null, null, CONTROL_TIMEOUT, 131072, () -> false, Map.of());
        if (result.exitCode() != 0) {
            // Engine 错误不能当作资源不存在。第二次只读探测确认可达。
            docker(List.of("version", "--format", "{{.Server.Version}}"), null, CONTROL_TIMEOUT, 8192, () -> false);
            String diagnostic = result.diagnostic().toLowerCase(Locale.ROOT);
            if (!diagnostic.contains("no such") && !diagnostic.contains("not found")) {
                throw new AgentException("DOCKER_FAILURE", "Cannot establish owned resource existence: "
                        + sanitize(result.diagnostic()).substring(0, Math.min(500, result.diagnostic().length())));
            }
            return null;
        }
        return parse(result.bytes()).get(0);
    }

    private void requireOwner(String kind, String name) {
        JsonNode resource = inspect(kind, name);
        JsonNode labels = kind.equals("container") ? resource.path("Config").path("Labels") : resource.path("Labels");
        String expectedRun = kind.equals("volume") && name.equals(properties.cacheVolume())
                ? "shared-cache" : name.substring(3, 39);
        if (!properties.owner().equals(labels.path("delveforge.owner").asText())
                || !expectedRun.equals(labels.path("delveforge.run").asText())) {
            throw new AgentException("RESOURCE_OWNERSHIP", "Refusing to operate on an unowned Docker resource");
        }
    }

    private JsonNode parse(byte[] bytes) {
        try {
            return json.readTree(bytes);
        } catch (IOException failure) {
            throw new AgentException("TOOL_PROTOCOL", "Invalid controlled tool response", failure);
        }
    }

    private JsonNode invoke(Session session, String name, JsonNode arguments, BooleanSupplier cancelled) {
        try {
            byte[] input = json.writeValueAsBytes(Map.of("name", name, "arguments", arguments,
                    "baselinePaths", session.retainedPaths, "resourceCapabilities", session.resourceCapabilities));
            int maxBytes = Set.of("snapshot", "inspect_patch", "search_code").contains(name) ? Math.max(session.limits.maxCandidateBytes() * 16, 33554432)
                    : Math.max(session.limits.maxFileBytes() * 8, 2097152);
            return parse(docker(List.of("exec", "-i",
                    name(session.task.runId(), "agent"), "python3", "/opt/delveforge/sandbox.py"), input,
                    CONTROL_TIMEOUT,
                    maxBytes, cancelled).bytes());
        } catch (IOException failure) {
            throw new AgentException("TOOL_PROTOCOL", "Cannot encode controlled tool request", failure);
        }
    }

    private void verifyProtocol(Session session, BooleanSupplier cancelled) {
        JsonNode response;
        try {
            response = invoke(session, "protocol", json.createObjectNode(), cancelled);
        } catch (AgentException failure) {
            if (failure.code().equals("TOOL_PROTOCOL")) {
                throw incompatibleProtocol();
            }
            throw failure;
        }
        requireCompatibleProtocol(response);
    }

    static void requireCompatibleProtocol(JsonNode response) {
        if (response == null || !response.path("ok").isBoolean() || !response.path("ok").asBoolean()
                || !response.path("tool").asText().equals("protocol")
                || !response.path("result").path("protocol").asText().equals("delveforge-sandbox")
                || !response.path("result").path("version").isIntegralNumber()
                || !response.path("result").path("version").canConvertToInt()
                || response.path("result").path("version").asInt() != 9) {
            throw incompatibleProtocol();
        }
    }

    private static AgentException incompatibleProtocol() {
        return new AgentException("SANDBOX_PROTOCOL_INCOMPATIBLE",
                "Sandbox tool protocol v9 is required. Build the current agent Dockerfile with a new image tag, "
                        + "configure delveforge.agent.sandbox.images, then explicitly restart the application. "
                        + "See docs/LOCAL_SETUP.md (sandbox protocol troubleshooting).");
    }

    private static void requireToolSuccess(JsonNode response) {
        if (!response.path("ok").asBoolean()) {
            rejectPolicy(response, AgentPolicyRejection.Stage.TOOL_EXECUTION);
            throw new AgentException(response.path("classification").asText("TOOL_ERROR"),
                    "Controlled sandbox operation rejected: " + response.path("reason").asText());
        }
    }

    static void rejectPolicy(JsonNode response, AgentPolicyRejection.Stage stage) {
        if (!response.path("classification").asText().equals("POLICY_REJECTED")) {
            return;
        }
        AgentPolicyRejection.Rule rule;
        try {
            rule = AgentPolicyRejection.Rule.valueOf(response.path("policyRule").asText());
        } catch (IllegalArgumentException unknown) {
            rule = AgentPolicyRejection.Rule.UNCLASSIFIED_POLICY;
        }
        // 阶段由受控调用点决定；只有工具的应用后捕获阶段允许额外细分。
        if (stage == AgentPolicyRejection.Stage.PATCH_APPLICATION
                && response.path("policyStage").asText().equals("SOURCE_CAPTURE")) {
            stage = AgentPolicyRejection.Stage.SOURCE_CAPTURE;
        }
        if (rule == AgentPolicyRejection.Rule.CHECK_MUTATED_SOURCE && stage == AgentPolicyRejection.Stage.TOOL_EXECUTION
                && response.path("sourceChanges").isObject()) {
            AgentPolicyRejection.SourceChanges changes;
            try {
                changes = new ObjectMapper().treeToValue(response.path("sourceChanges"),
                        AgentPolicyRejection.SourceChanges.class);
            } catch (IOException | IllegalArgumentException invalid) {
                // 诊断缺失或畸形不能覆盖原安全拒绝；活动记录会明确标为 UNAVAILABLE。
                throw new AgentPolicyRejection(rule, stage);
            }
            throw AgentPolicyRejection.sourceChanged(changes);
        }
        throw new AgentPolicyRejection(rule, stage);
    }

    /** 只转换只读 inspect_patch 的已知固定语法失败，不持久化异常文本或推断未知写入结果。 */
    static String patchSyntaxReason(JsonNode inspection) {
        if (inspection.path("ok").asBoolean() || !inspection.path("classification").asText().equals("TOOL_ERROR")) {
            return null;
        }
        return switch (inspection.path("reason").asText()) {
            case "ValueError: Invalid unified diff hunk" -> "HUNK_BODY_INVALID";
            case "ValueError: Invalid unified diff hunk counts" -> "HUNK_COUNTS_INVALID";
            case "ValueError: Invalid unified diff hunk header" -> "HUNK_HEADER_INVALID";
            case "ValueError: Invalid or oversized unified diff" -> "DIFF_STRUCTURE_INVALID";
            default -> null;
        };
    }

    /** 只在只读预演入口使用；未知应用结果不能进入此修正路径，异常正文不返回或持久化。 */
    JsonNode patchInspectionFailure(String tool, JsonNode inspection) {
        if (inspection.path("ok").asBoolean() || !inspection.path("classification").asText().equals("TOOL_ERROR")) {
            return null;
        }
        String syntax = patchSyntaxReason(inspection);
        return json.createObjectNode().put("ok", false).put("tool", tool)
                .set("result", json.createObjectNode().put("classification", "PATCH_CONFLICT")
                        .put("mutationNotStarted", true)
                        .put("conflictReason", syntax == null ? "CHECK_FAILED" : "INVALID_DIFF")
                        .put("patchDiagnostic", syntax == null ? "INSPECTION_UNAVAILABLE" : syntax)
                        .put("instruction", "补丁只读预演未通过，未尝试写入。重新读取源码并优先使用 edit_file；持续失败时停止并说明。"));
    }

    /** 检查结论仍来自完整补丁；定位仅针对模型自己提交的输入，不返回任何被识别的值。 */
    AgentPolicyRejection contentRejection(String field, String proposed) {
        return contentRejection(field, proposed, "proposal.txt");
    }

    AgentPolicyRejection contentRejection(String field, String proposed, String path) {
        if (path == null || path.isBlank()) {
            return AgentPolicyRejection.patchContentRejectedBeforeApply();
        }
        SortedSet<Integer> lines = new TreeSet<>(secrets.mutationProtectedLines(path, proposed));
        String[] inputLines = proposed.split(String.valueOf((char) 10), -1);
        for (int index = 0; index < inputLines.length; index++) {
            if (inputLines[index].contains(DeterministicRepositorySecretPolicy.REDACTION_MARKER)) {
                lines.add(index + 1);
            }
        }
        if (lines.isEmpty()) {
            return AgentPolicyRejection.patchContentRejectedBeforeApply();
        }
        return AgentPolicyRejection.patchContentRejectedBeforeApply(
                new AgentPolicyRejection.RejectedInput(field, lines.stream().limit(8).toList()));
    }

    void requirePatchContent(JsonNode inspection, JsonNode arguments) {
        rejectPolicy(inspection, AgentPolicyRejection.Stage.PATCH_INSPECTION);
        requireToolSuccess(inspection);
        JsonNode files = inspection.path("result").path("files");
        if (!files.isObject() || files.isEmpty() || files.size() > 64) {
            throw new AgentException("SANDBOX_PROTOCOL_INCOMPATIBLE", "Controlled patch preview is required");
        }
        ObjectNode beforeImages = json.createObjectNode();
        ObjectNode afterImages = json.createObjectNode();
        var entries = files.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            String path = entry.getKey();
            requirePath(path);
            JsonNode file = entry.getValue();
            String before = capturedText(file.get("before"));
            String after = capturedText(file.get("after"));
            String preimage = file.path("preimage").asText();
            String postimage = file.path("postimage").asText();
            requireWitness(before, preimage);
            requireWitness(after, postimage);
            List<CredentialMutationGuard.Range> oldLines = new ArrayList<>();
            List<CredentialMutationGuard.Range> newLines = new ArrayList<>();
            if (!file.path("changes").isArray()) {
                throw new AgentException("SNAPSHOT_MISMATCH", "Controlled change ranges are required");
            }
            for (JsonNode range : file.path("changes")) {
                if (!range.isArray() || range.size() != 4) {
                    throw new AgentException("SNAPSHOT_MISMATCH", "Invalid controlled change range");
                }
                for (JsonNode value : range) {
                    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                        throw new AgentException("SNAPSHOT_MISMATCH", "Invalid controlled change range");
                    }
                }
                oldLines.add(new CredentialMutationGuard.Range(range.get(0).asInt(), range.get(1).asInt()));
                newLines.add(new CredentialMutationGuard.Range(range.get(2).asInt(), range.get(3).asInt()));
            }
            var guard = new CredentialMutationGuard(secrets);
            guard.requireSafe(path, before, after, new CredentialMutationGuard.Changes(oldLines, newLines));
            if (!preimage.equals("absent") && !postimage.equals("absent")
                    && !preimage.substring(65).equals(postimage.substring(65))
                    && !secrets.mutationProtectedLines(path, before).isEmpty()) {
                throw AgentPolicyRejection.sourceEditRejectedBeforeApply();
            }
            beforeImages.put(path, preimage);
            afterImages.put(path, postimage);
        }
        ((ObjectNode) arguments).set("preimages", beforeImages);
        ((ObjectNode) arguments).set("postimages", afterImages);
    }

    private String capturedText(JsonNode value) {
        if (value == null || !(value.isNull() || value.isTextual())) {
            throw new AgentException("SNAPSHOT_MISMATCH", "Controlled source text is required");
        }
        return value.isNull() ? null : value.asText();
    }

    private void requireWitness(String content, String identity) {
        if (content == null ? !identity.equals("absent")
                : !identity.matches("[0-9a-f]{64}:100(?:644|755)")
                    || !identity.startsWith(Candidate.digest(content) + ":") || content.indexOf(0) >= 0) {
            throw new AgentException("SNAPSHOT_MISMATCH", "Captured source identity differs from its content");
        }
    }

    public String sanitize(String text) {
        return secrets.sanitizeOutput(text);
    }

    @Override
    public String tool(String runId, AgentProtocol.ToolCall call, BooleanSupplier cancelled) {
        Session session = requireSession(runId);
        AgentTools.validate(call, session.limits);
        JsonNode arguments = parse(call.arguments().getBytes(StandardCharsets.UTF_8));
        if (!arguments.isObject()) {
            return "{\"ok\":false,\"classification\":\"INVALID_ARGUMENTS\"}";
        }
        if (call.name().equals("read_log")) {
            return readLog(runId, arguments.path("invocation_id").asText(), arguments.path("stream").asText("stdout"),
                    arguments.path("offset").asInt(), Math.min(arguments.path("limit").asInt(4000), session.limits.maxOutputBytes() / 8));
        }
        if (call.name().equals("exec_command")) {
            return executeCommand(session, call, arguments, cancelled);
        }
        if (arguments.has("path") && !arguments.path("path").asText().equals(".")) {
            String path = arguments.path("path").asText();
            Map<String, Object> resource = session.resourceCapabilities.get(path);
            if (resource != null) {
                if (!AgentTools.readOnly(call.name()) || !resource.get("kind").equals("PUBLIC_RESOURCE")) {
                    throw new AgentPolicyRejection(AgentPolicyRejection.Rule.RESOURCE_CAPABILITY,
                            AgentPolicyRejection.Stage.PATH_VALIDATION);
                }
            } else if (AgentTools.readOnly(call.name())) {
                String feedback = readPathFeedback(path);
                if (feedback != null) {
                    return feedback;
                }
            } else {
                requirePath(path);
            }
        }
        if (call.name().equals("edit_file")) {
            try {
                String patch = exactEditPatch(session, arguments, cancelled);
                arguments = json.createObjectNode().put("patch", patch);
            } catch (ExactEditPatch.Conflict conflict) {
                return json.createObjectNode().put("ok", false).put("tool", "edit_file")
                        .set("result", json.createObjectNode().put("classification", "PATCH_CONFLICT")
                                .put("mutationNotStarted", true).put("conflictReason", conflict.reason())
                                .put("instruction", "原文不存在、不唯一或文件已存在；编辑未应用，重新读取后使用唯一完整原文片段。"))
                        .toString();
            }
        }
        if (AgentTools.mutation(call.name())) {
            JsonNode inspection = invoke(session, "inspect_patch", arguments, cancelled);
            JsonNode failedInspection = patchInspectionFailure(call.name(), inspection);
            if (failedInspection != null) {
                return failedInspection.toString();
            }
            // 只读预演的冲突使用失败外壳；必须明确未写入，不能送入实际应用路径。
            if (inspection.path("ok").isBoolean() && !inspection.path("ok").asBoolean()
                    && inspection.path("result").path("classification").asText().equals("PATCH_CONFLICT")
                    && inspection.path("result").path("mutationNotStarted").isBoolean()
                    && inspection.path("result").path("mutationNotStarted").asBoolean()) {
                ((ObjectNode) inspection).put("tool", call.name());
                return inspection.toString();
            }
            try {
                requirePatchContent(inspection, arguments);
            } catch (AgentPolicyRejection rejection) {
                if (rejection.rule() == AgentPolicyRejection.Rule.PATCH_CREDENTIALS
                        && rejection.sourceEditRejectedBeforeMutation()) {
                    JsonNode original = parse(call.arguments().getBytes(StandardCharsets.UTF_8));
                    String field = call.name().equals("edit_file") ? "new_text" : "patch";
                    String path = original.path("path").asText();
                    if (field.equals("patch") && inspection.path("result").path("files").size() == 1) {
                        path = inspection.path("result").path("files").fieldNames().next();
                    }
                    throw contentRejection(field, original.path(field).asText(), path);
                }
                throw rejection;
            }
        }
        if (call.name().equals("edit_file")) {
            session.retainedPaths.add(parse(call.arguments().getBytes(StandardCharsets.UTF_8)).path("path").asText());
        }
        JsonNode response = invoke(session, call.name().equals("edit_file") ? "apply_patch" : call.name(), arguments, cancelled);
        if (call.name().equals("edit_file") && response.path("ok").asBoolean()) {
            retainSnapshot(session, currentSnapshot(session, cancelled));
        }
        rejectPolicy(response, AgentTools.mutation(call.name()) ? AgentPolicyRejection.Stage.PATCH_APPLICATION
                : AgentPolicyRejection.Stage.TOOL_EXECUTION);
        requireCertainPatchOutcome(response);
        JsonNode visible = sourceResponse(call.name(), response, session.resourceCapabilities.keySet());
        String safe = visible.toString();
        if (safe.getBytes(StandardCharsets.UTF_8).length > session.limits.maxOutputBytes()) {
            return "{\"ok\":false,\"classification\":\"OUTPUT_LIMIT\",\"reason\":\"Request a smaller range\"}";
        }
        return safe;
    }

    private JsonNode currentSnapshot(Session session, BooleanSupplier cancelled) {
        JsonNode response = invoke(session, "snapshot", json.createObjectNode(), cancelled);
        requireToolSuccess(response);
        return response.path("result");
    }

    private void retainSnapshot(Session session, JsonNode snapshot) {
        snapshot.path("files").forEach(file -> session.retainedPaths.add(file.path("path").asText()));
    }

    /** 仅目录不存在可纠正；安全拒绝与基础设施失败保留各自的终止语义。 */
    static String commandDirectoryFeedback(JsonNode response, AgentProtocol.Limits limits) {
        if (response == null || !response.isObject() || !response.path("ok").isBoolean()) {
            throw new AgentException("TOOL_PROTOCOL", "Invalid directory validation response");
        }
        rejectPolicy(response, AgentPolicyRejection.Stage.PATH_VALIDATION);
        if (!response.path("ok").asBoolean() && !response.has("classification")
                && response.path("result").path("classification").asText().equals("INVALID_TOOL_ARGUMENT")
                && response.path("result").path("reason").asText().equals("DIRECTORY_NOT_FOUND")
                && response.path("result").path("mutationNotStarted").asBoolean()) {
            return AgentTools.commandError(AgentTools.CommandError.DIRECTORY_NOT_FOUND, limits);
        }
        requireToolSuccess(response);
        if (!response.path("result").path("directory").isTextual()) {
            throw new AgentException("TOOL_PROTOCOL", "Directory validation result is missing");
        }
        return null;
    }

    /** 每次原生命令独占一个容器；收据来自 Docker 状态与外部快照，不接受项目写入的验证声明。 */
    private String executeCommand(Session session, AgentProtocol.ToolCall call, JsonNode arguments,
            BooleanSupplier cancelled) {
        String directory = arguments.path("directory").asText(".");
        JsonNode location = invoke(session, "validate_directory", json.createObjectNode().put("path", directory), cancelled);
        String directoryFeedback = commandDirectoryFeedback(location, session.limits);
        if (directoryFeedback != null) {
            return directoryFeedback;
        }
        String command = arguments.path("command").asText();
        JsonNode before = currentSnapshot(session, cancelled);
        retainSnapshot(session, before);
        JsonNode reportsBefore = invoke(session, "test_reports", json.createObjectNode(), cancelled).path("result");
        String id = session.task.runId();
        String container = name(id, "command");
        String network = dependencyNetwork(session, cancelled);
        List<String> launch = baseRun(id, "command", session.image, network, true);
        launch.addAll(List.of("--log-driver", "json-file", "--log-opt", "max-size=4m", "--log-opt", "max-file=2",
                "--workdir", directory.equals(".") ? "/workspace" : "/workspace/" + directory,
                "--env", "npm_config_registry=http://df-artifacts:8080/npm/",
                "--env", "npm_config_cache=/m2/npm-cache", "--env", "PNPM_HOME=/m2/pnpm",
                "--env", "npm_config_store_dir=/m2/pnpm-store",
                "--env", "pnpm_config_store_dir=/m2/pnpm-store",
                "--env", "pnpm_config_registry=http://df-artifacts:8080/npm/",
                "--env", "pnpm_config_manage_package_manager_versions=false",
                "--env", "npm_config_manage_package_manager_versions=false",
                "--env", "MAVEN_ARGS=-s /opt/delveforge/offline-settings.xml",
                "--env", "MAVEN_USER_HOME=/m2",
                "--env", "MAVEN_OPTS=-Dmaven.repo.local=/m2",
                "--env", "HTTP_PROXY=http://df-artifacts:8080", "--env", "HTTPS_PROXY=http://df-artifacts:8080",
                "--env", "http_proxy=http://df-artifacts:8080", "--env", "https_proxy=http://df-artifacts:8080",
                "--env", "NO_PROXY=df-artifacts,localhost,127.0.0.1", "--env", "no_proxy=df-artifacts,localhost,127.0.0.1"));
        for (var resource : session.resourceCapabilities.entrySet()) {
            if (resource.getValue().get("kind").equals("PUBLIC_RESOURCE")) {
                launch.addAll(List.of("--mount", "type=bind,source="
                        + session.directory.resolve("public").resolve(resource.getKey()).toAbsolutePath()
                        + ",target=/workspace/" + resource.getKey() + ",readonly"));
            }
        }
        launch.addAll(List.of(session.image, "python3", "/opt/delveforge/command.py",
                Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8))));
        boolean timedOut = false;
        boolean wasCancelled = false;
        long started = System.nanoTime();
        JsonNode state;
        AgentProcess.Result output;
        try {
            docker(launch, null, CONTROL_TIMEOUT, 8192, cancelled);
            try {
                docker(List.of("wait", container), null,
                        Duration.ofSeconds(arguments.path("timeout_seconds").asInt(session.limits.checkSeconds())),
                        8192, cancelled);
            } catch (AgentException failure) {
                timedOut = failure.code().equals("TIME_LIMIT");
                wasCancelled = failure.code().equals("CANCELLED");
                if (!timedOut && !wasCancelled) {
                    throw failure;
                }
                Thread.interrupted();
            }
            stopContainer(container);
            state = inspect("container", container).path("State");
            output = commands.run(List.of(properties.docker(), "logs", container), null, null, CONTROL_TIMEOUT,
                    CommandLogs.STREAM_BYTES, () -> false, Map.of(), true);
            if (output.exitCode() != 0) {
                throw new AgentException("COMMAND_OUTPUT_UNAVAILABLE", "Cannot retrieve command output");
            }
        } finally {
            // 删除容器前必须确认所有写入者退出；失败保持占用，不能继续捕获或运行下一条命令。
            removeContainer(container);
        }
        int exit = state.path("ExitCode").asInt(-1);
        String stdout = CommandLogs.readable(output.text(), secrets);
        String stderr = CommandLogs.readable(output.diagnostic(), secrets);
        String invocation = UUID.randomUUID().toString();
        ObjectNode result = json.createObjectNode().put("checkType", "COMMAND").put("runId", id)
                .put("invocationId", invocation).put("command", sanitize(command)).put("directory", directory)
                .put("exitCode", exit).put("timedOut", timedOut).put("cancelled", wasCancelled)
                .put("stdout", stdout).put("stderr", stderr).put("logDiscarded", output.truncated())
                .put("durationMs", java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                .put("classification", wasCancelled ? "COMMAND_CANCELLED" : timedOut ? "COMMAND_TIMED_OUT"
                        : exit == 0 ? "COMMAND_SUCCEEDED" : "COMMAND_FAILED")
                .put("testDetails", "UNAVAILABLE");
        // 源码捕获失败也保留命令终态及日志；不把缺少快照的记录提升为验证证据。
        boolean retained = CommandLogs.save(session.directory, result);
        JsonNode after = currentSnapshot(session, () -> false);
        retainSnapshot(session, after);
        result.put("snapshot", before.path("snapshot").asText()).put("afterSnapshot", after.path("snapshot").asText())
                .put("sourceChanged", !before.path("snapshot").equals(after.path("snapshot")));
        result.set("sourceView", after.path("sourceView"));
        {
            JsonNode reports = invoke(session, "test_reports", json.createObjectNode()
                    .set("before", reportsBefore.path("fingerprints")), () -> false).path("result");
            result.set("testReport", reports);
            if (reports.path("availability").asText().equals("AVAILABLE")) {
                result.put("testDetails", "AVAILABLE");
                for (String count : List.of("executedTests", "failures", "skipped")) {
                    result.set(count, reports.path(count));
                }
            }
        }
        // 完整收据超出保留预算时，先前已保存的输出仍可读取，不能隐藏它的引用。
        retained = CommandLogs.save(session.directory, (ObjectNode) sanitizeNode(result)) || retained;
        result.put("logDiscarded", output.truncated() || !retained);
        if (retained) {
            result.put("logRef", invocation);
        }
        int previewLimit = Math.min(3000, Math.max(64, session.limits.maxOutputBytes() / 16));
        result.put("stdout", CommandLogs.preview(stdout, previewLimit))
                .put("stderr", CommandLogs.preview(stderr, previewLimit))
                .put("outputTruncated", output.truncated() || stdout.length() > previewLimit || stderr.length() > previewLimit);
        session.checks.add(result.deepCopy());
        result.put("commandTruncated", result.path("command").asText().length() > 2000);
        result.put("command", CommandLogs.preview(result.path("command").asText(), 2000));
        if (result.path("testReport") instanceof ObjectNode report) {
            report.remove("cases");
        }
        ObjectNode response = json.createObjectNode().put("ok", !timedOut && !wasCancelled && exit == 0)
                .put("tool", "exec_command");
        response.set("result", sanitizeNode(result));
        return response.toString();
    }

    static void requireCertainPatchOutcome(JsonNode response) {
        if (response.path("classification").asText().equals("PATCH_OUTCOME_UNCERTAIN")) {
            throw new AgentException("PATCH_OUTCOME_UNCERTAIN", "Patch application outcome could not be established");
        }
    }

    /** 完整源码先净化再切片；内部原文材料字段不进入模型工具结果。 */
    JsonNode sourceResponse(String tool, JsonNode response) {
        return sourceResponse(tool, response, Set.of());
    }

    private JsonNode sourceResponse(String tool, JsonNode response, Set<String> publicPaths) {
        if (!response.path("ok").asBoolean() || !Set.of("read_file", "search_code").contains(tool)) {
            JsonNode visible = sanitizeNode(response);
            if (visible.path("result") instanceof ObjectNode result) {
                // 完整清单留在受信冻结证据；模型使用已有摘要，不挤占工具输出预算。
                result.remove("testManifest");
            }
            return visible;
        }
        JsonNode result = response.path("result");
        ObjectNode visible = json.createObjectNode().put("ok", true).put("tool", tool);
        ObjectNode data = json.createObjectNode();
        if (tool.equals("read_file")) {
            String path = result.path("path").asText();
            if (!publicPaths.contains(path)) {
                requirePath(path);
            }
            if (!result.path("_source").isTextual()) {
                throw new AgentException("SANDBOX_PROTOCOL_INCOMPATIBLE", "Complete source view is required");
            }
            List<String> lines = sourceLines(secrets.sanitizeSource(path, result.path("_source").asText()));
            int first = result.path("startLine").asInt();
            int count = result.path("count").asInt();
            if (first < 1 || count < 1 || count > 300) {
                throw new AgentException("SANDBOX_PROTOCOL_INCOMPATIBLE", "Invalid source slice");
            }
            int start = Math.min(first - 1, lines.size());
            data.put("publicResource", publicPaths.contains(path));
            data.put("path", sanitize(path)).put("startLine", first).put("totalLines", result.path("totalLines").asInt())
                    .put("content", String.join("", lines.subList(start, Math.min(start + count, lines.size()))));
        } else {
            var matches = data.putArray("matches");
            data.put("truncated", result.path("truncated").asBoolean());
            Map<String, List<String>> sources = new HashMap<>();
            for (JsonNode match : result.path("matches")) {
                String path = match.path("path").asText();
                if (!publicPaths.contains(path)) {
                    requirePath(path);
                }
                JsonNode raw = result.path("_sources").path(path);
                if (!raw.isTextual()) {
                    throw new AgentException("SANDBOX_PROTOCOL_INCOMPATIBLE", "Complete search source is required");
                }
                List<String> lines = sources.computeIfAbsent(path,
                        key -> sourceLines(secrets.sanitizeSource(key, raw.asText())));
                int line = match.path("line").asInt();
                if (line < 1 || line > lines.size()) {
                    throw new AgentException("SANDBOX_PROTOCOL_INCOMPATIBLE", "Invalid search source line");
                }
                String excerpt = lines.get(line - 1);
                while (!excerpt.isEmpty() && (excerpt.charAt(excerpt.length() - 1) == 10
                        || excerpt.charAt(excerpt.length() - 1) == 13)) {
                    excerpt = excerpt.substring(0, excerpt.length() - 1);
                }
                matches.addObject().put("publicResource", publicPaths.contains(path)).put("path", sanitize(path)).put("line", line)
                        .put("excerpt", excerpt.substring(0, Math.min(excerpt.length(), 400)));
            }
        }
        visible.set("result", data);
        return visible;
    }

    private List<String> sourceLines(String content) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = content.indexOf(10, start);
            end = end < 0 ? content.length() : end + 1;
            lines.add(content.substring(start, end));
            start = end;
        }
        return lines;
    }

    private JsonNode sanitizeNode(JsonNode node) {
        if (node.isTextual()) {
            return json.getNodeFactory().textNode(sanitize(node.asText()));
        }
        if (node.isArray()) {
            var result = json.createArrayNode();
            node.forEach(value -> result.add(sanitizeNode(value)));
            return result;
        }
        if (node.isObject()) {
            ObjectNode result = json.createObjectNode();
            boolean alteredContent = false;
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                JsonNode safe = sanitizeNode(field.getValue());
                result.set(sanitize(field.getKey()), safe);
                if (field.getKey().equals("content") && !safe.equals(field.getValue())) {
                    alteredContent = true;
                }
            }
            if (alteredContent) {
                result.put("sanitized", true);
                result.put("editingAllowed", false);
            }
            return result;
        }
        return node;
    }

    /** 只读当前快照形成标准 diff；后续仍复用原有 inspect_patch 与 apply_patch，不直接写文件。 */
    private String exactEditPatch(Session session, JsonNode arguments, BooleanSupplier cancelled) {
        String path = arguments.path("path").asText();
        requirePath(path);
        ObjectNode options = json.createObjectNode().put("content", true);
        options.set("baseline", json.valueToTree(session.baselineHashes));
        JsonNode captured = invoke(session, "snapshot", options, cancelled);
        requireToolSuccess(captured);
        String current = null;
        for (JsonNode file : captured.path("result").path("files")) {
            if (!file.path("path").asText().equals(path)) {
                continue;
            }
            byte[] bytes;
            if (file.has("content")) {
                bytes = Base64.getDecoder().decode(file.path("content").asText());
            } else {
                Entry original = session.baseline.get(path);
                if (original == null || !(file.path("sha256").asText() + ":" + file.path("mode").asText())
                        .equals(session.baselineHashes.get(path))) {
                    throw new AgentException("SNAPSHOT_MISMATCH", "Exact edit source differs from captured baseline");
                }
                bytes = git(session, Map.of(), null, "cat-file", "blob", original.objectId());
            }
            current = decode(bytes);
            if (current.indexOf(0) >= 0) {
                throw new AgentException("SOURCE_TEXT", "Binary source cannot be edited");
            }
            break;
        }
        return ExactEditPatch.create(path, current, arguments.path("old_text").asText(),
                arguments.path("new_text").asText(), session.limits.maxPatchBytes());
    }

    private String dependencyNetwork(Session session, BooleanSupplier cancelled) {
        String id = session.task.runId();
        String network = name(id, "network");
        if (inspect("network", network) == null) {
            docker(List.of("network", "create", "--internal", "--opt",
                    "com.docker.network.bridge.gateway_mode_ipv4=isolated", "--label", "delveforge.owner=" + properties.owner(),
                    "--label", "delveforge.run=" + id, network), null, CONTROL_TIMEOUT, 8192, cancelled);
        }
        requireOwner("network", network);
        JsonNode networkState = inspect("network", network);
        if (!networkState.path("Internal").asBoolean()
                || !networkState.path("Options").path("com.docker.network.bridge.gateway_mode_ipv4")
                        .asText().equals("isolated")) {
            throw new AgentException("DOCKER_ENVIRONMENT", "Dependency network isolation could not be established");
        }
        String proxy = name(id, "proxy");
        if (inspect("container", proxy) == null) {
            List<String> launch = new ArrayList<>(List.of("run", "-d", "--name", proxy, "--label",
                    "delveforge.owner=" + properties.owner(), "--label", "delveforge.run=" + id,
                    "--network", "name=bridge,gw-priority=1", "--user", "11000:11000", "--cap-drop", "ALL", "--security-opt",
                    "no-new-privileges", "--read-only", "--memory", "512m", "--cpus", "1", "--pids-limit", "64",
                    "--tmpfs", "/tmp:rw,nosuid,size=64m", "--mount", "type=volume,source=" + properties.cacheVolume()
                    + ",target=/cache", "--env", "DF_ARTIFACT_MODE=command",
                    session.image, "python3", "/opt/delveforge/proxy.py"));
            docker(launch, null, CONTROL_TIMEOUT, 8192, cancelled);
            docker(List.of("network", "connect", "--alias", "df-artifacts", network, proxy), null,
                    CONTROL_TIMEOUT, 8192, cancelled);
        } else {
            requireOwner("container", proxy);
            docker(List.of("start", proxy), null, CONTROL_TIMEOUT, 8192, cancelled);
        }
        return network;
    }

    private Session requireSession(String id) {
        Session session = sessions.get(id);
        if (session == null) {
            throw new AgentException("RUN_CONFLICT", "No live sandbox session; start a fresh Run");
        }
        return session;
    }

    @Override
    public Preflight preflight(String runId, BooleanSupplier cancelled, Consumer<PreflightProgress> progress) {
        Session session = requireSession(runId);
        requireToolSuccess(invoke(session, "probe_environment", json.createObjectNode(), cancelled));
        dependencyNetwork(session, cancelled);
        JsonNode source = currentSnapshot(session, cancelled);
        retainSnapshot(session, source);
        return new Preflight("READY", json.createObjectNode().put("execution", "native-shell")
                .put("snapshot", source.path("snapshot").asText())
                .put("readiness", "Sandbox available; repository prerequisites have not been established")
                .put("instruction", "读取仓库指引、README、清单、锁文件和 CI，按当前 Step 确定并运行必要命令。")
                .toString());
    }

    @Override
    public String checkCapabilities(String runId, BooleanSupplier cancelled) {
        Session session = requireSession(runId);
        return json.createObjectNode().put("execution", "exec_command").put("jdk", session.jdk)
                .put("cwdPersists", false).put("environmentPersists", false).put("processesPersist", false)
                .put("filesPersist", true).put("projectDependenciesPersist", true).put("tmpPersists", false)
                .put("cache", "/m2; npm=/m2/npm-cache; pnpm=/m2/pnpm-store")
                .put("logs", "read_log by invocationId; sanitized host retention, outside candidate source")
                .put("network", "Controlled public Maven/npm artifact hosts only; no private registries or host services")
                .put("readiness", "Discover actual installed tools and project prerequisites with native commands")
                .set("sourceView", currentSnapshot(session, cancelled).path("sourceView")).toString();
    }

    @Override
    public Identity identity(String runId) {
        Session session = requireSession(runId);
        String name = name(runId, "agent");
        requireOwner("container", name);
        JsonNode resource = inspect("container", name);
        JsonNode host = resource.path("HostConfig");
        return new Identity(resource.path("Id").asText(), resource.path("Image").asText(), session.jdk,
                host.path("Memory").asLong(), host.path("NanoCpus").asLong(), host.path("PidsLimit").asLong(),
                host.path("NetworkMode").asText(), host.path("ReadonlyRootfs").asBoolean());
    }

    @Override
    public String readLog(String runId, String invocationId, String stream, int offset, int limit) {
        return CommandLogs.read(directory(runId), invocationId, stream, offset, limit);
    }

    @Override
    public String completionFeedback(String runId, String report, BooleanSupplier cancelled) {
        Session session = requireSession(runId);
        JsonNode proposed = parse(report.getBytes(StandardCharsets.UTF_8));
        if (report.length() > 12000 || !proposed.isObject()) {
            return "{\"classification\":\"EVIDENCE_INVALID\",\"reason\":\"Report exceeds bounds or is not an object\"}";
        }
        session.agentReport = sanitizeNode(proposed);
        return completionFeedback(runId, cancelled);
    }

    @Override
    public String completionFeedback(String runId, BooleanSupplier cancelled) {
        Session session = requireSession(runId);
        JsonNode response = invoke(session, "snapshot", json.createObjectNode(), cancelled);
        requireToolSuccess(response);
        try {
            evidence(session, response.path("result"));
            return null;
        } catch (AgentException failure) {
            if (!Set.of("UNRESOLVED_CHECK_FAILURE", "EVIDENCE_INCOMPARABLE", "EVIDENCE_INVALID").contains(failure.code())) {
                throw failure;
            }
            return json.createObjectNode().put("classification", failure.code())
                    .put("reason", sanitize(failure.getMessage()))
                    .put("instruction", "报告须以 executions 引用本 Run 最终源码上的成功 invocationId，并以 validationComplete 如实说明必要验证是否完成。"
                            + "失败历史保留；解释后续处理和限制，不伪造、改写或引用过期结果为通过。")
                    .toString();
        }
    }

    @Override
    public Candidate finish(String runId, BooleanSupplier cancelled) {
        Session session = requireSession(runId);
        return freezeTree(session, stoppedSnapshot(session, cancelled), true);
    }

    @Override
    public String retainPartial(String runId) {
        Session session = requireSession(runId);
        try {
            freezeTree(session, stoppedSnapshot(session, () -> false), false);
            return session.directory.resolve("partial-metadata.json").toString();
        } catch (AgentException failure) {
            if (failure.code().equals("NO_CHANGES")) {
                return null;
            }
            throw failure;
        }
    }

    private JsonNode stoppedSnapshot(Session session, BooleanSupplier cancelled) {
        String runId = session.task.runId();
        stop(runId);
        // 最终捕获使用全新只读源挂载容器，Agent 与项目进程均已停止。
        List<String> capture = baseRun(runId, "capture", session.image, "none", true);
        for (int i = 0; i < capture.size(); i++) {
            if (capture.get(i).endsWith("target=/workspace")) {
                capture.set(i, capture.get(i) + ",readonly");
            }
        }
        capture.addAll(List.of(session.image, "sleep", "infinity"));
        docker(capture, null, CONTROL_TIMEOUT, 8192, cancelled);
        JsonNode snapshot;
        try {
            byte[] request = json.valueToTree(Map.of("name", "snapshot", "arguments",
                    Map.of("content", true, "baseline", session.baselineHashes),
                    "resourceCapabilities", session.resourceCapabilities, "baselinePaths", session.retainedPaths)).toString().getBytes(StandardCharsets.UTF_8);
            snapshot = parse(docker(List.of("exec", "-i", name(runId, "capture"), "python3",
                    "/opt/delveforge/sandbox.py"), request, CONTROL_TIMEOUT,
                    Math.max(session.limits.maxCandidateBytes() * 16, 33554432), cancelled).bytes());
            requireToolSuccess(snapshot);
        } finally {
            removeContainer(name(runId, "capture"));
        }
        return snapshot.path("result");
    }

    private Candidate freezeTree(Session session, JsonNode snapshot, boolean verified) {
        Map<String, String> index = Map.of("GIT_INDEX_FILE", session.directory.resolve("final.index").toString());
        git(session, index, null, "read-tree", session.task.baseline());
        Set<String> actual = new HashSet<>();
        List<String> changed = new ArrayList<>();
        for (JsonNode file : snapshot.path("files")) {
            String path = file.path("path").asText();
            if (session.resourceCapabilities.containsKey(path)) {
                Map<String, Object> resource = session.resourceCapabilities.get(path);
                if (file.has("content") || !file.path("mode").asText().equals(resource.get("mode"))
                        || !file.path("sha256").asText().equals(resource.get("sha256"))
                        || file.path("preservedOnly").asBoolean() != resource.get("kind").equals("PRESERVED_ONLY")) {
                    throw new AgentException("RESOURCE_INTEGRITY", "Protected baseline resource changed");
                }
            } else {
                requirePath(path);
            }
            if (!actual.add(path)) {
                throw new AgentException("GIT_PATH_CONFLICT", "Duplicate final source path");
            }
            String mode = file.path("mode").asText();
            if (!Set.of("100644", "100755").contains(mode)) {
                throw new AgentException("GIT_TYPE_UNSUPPORTED", "Unsupported final file mode");
            }
            if (!file.has("content")) {
                if (!session.baseline.containsKey(path)
                        || !(file.path("sha256").asText() + ":" + mode).equals(session.baselineHashes.get(path))) {
                    throw new AgentException("SNAPSHOT_MISMATCH", "不可变资源的最终快照与可信基线不同。");
                }
                continue;
            }
            byte[] bytes = Base64.getDecoder().decode(file.path("content").asText());
            String objectId = gitText(session, Map.of(), bytes, "hash-object", "-w", "--no-filters", "--stdin");
            Entry previous = session.baseline.get(path);
            if (previous == null || !previous.objectId().equals(objectId) || !previous.mode().equals(mode)) {
                if (previous != null) {
                    candidateText(git(session, Map.of(), null, "cat-file", "blob", previous.objectId()));
                }
                String content = candidateText(bytes);
                if (previous != null && !previous.mode().equals(mode)
                        && !secrets.mutationProtectedLines(path, candidateText(git(session, Map.of(), null,
                                "cat-file", "blob", previous.objectId()))).isEmpty()) {
                    throw new AgentException("CANDIDATE_POLICY", "Credential-bearing source mode must remain unchanged");
                }
                if (path.equals(".gitattributes") || path.endsWith("/.gitattributes")) {
                    if (content.matches("(?s).*(filter=|export-ignore|export-subst|working-tree-encoding|\\bident\\b).*")) {
                        throw new AgentException("GIT_ATTRIBUTES_UNSUPPORTED", "Unsupported Git attribute change");
                    }
                }
                changed.add(path);
                git(session, index, null, "update-index", "--add", "--cacheinfo", mode + "," + objectId + "," + path);
                requireFrozenMutation(session, index, path, previous == null ? null
                        : git(session, Map.of(), null, "cat-file", "blob", previous.objectId()), bytes);
            }
        }
        for (String deleted : session.baseline.keySet()) {
            if (!actual.contains(deleted)) {
                if (session.resourceCapabilities.containsKey(deleted)) {
                    throw new AgentException("RESOURCE_INTEGRITY", "Protected baseline resource was removed");
                }
                candidateText(git(session, Map.of(), null, "cat-file", "blob", session.baseline.get(deleted).objectId()));
                changed.add(deleted);
                byte[] removal = ("0 " + "0".repeat(40) + "\t" + deleted + "\0").getBytes(StandardCharsets.UTF_8);
                git(session, index, removal, "update-index", "-z", "--index-info");
                requireFrozenMutation(session, index, deleted,
                        git(session, Map.of(), null, "cat-file", "blob", session.baseline.get(deleted).objectId()), null);
            }
        }
        if (changed.isEmpty()) {
            throw new AgentException("NO_CHANGES", "Agent produced no eligible source changes");
        }
        changed.sort(Comparator.naturalOrder());
        String expected = gitText(session, index, null, "write-tree");
        byte[] diff = git(session, Map.of(), null, "diff-tree", "--no-commit-id", "-r", "-p", "--full-index",
                "--binary", "--unified=0", "--no-renames", "--no-ext-diff", "--no-textconv", session.task.baseline(), expected, "--");
        diff = removeHunkHeadings(decode(diff)).getBytes(StandardCharsets.UTF_8);
        if (diff.length > session.limits.maxCandidateBytes()) {
            throw new AgentException("CANDIDATE_LIMIT", "Final Candidate exceeds configured diff budget");
        }
        Map<String, String> replay = Map.of("GIT_INDEX_FILE", session.directory.resolve("replay.index").toString());
        git(session, replay, null, "read-tree", session.task.baseline());
        git(session, replay, diff, "apply", "--cached", "--unidiff-zero", "--whitespace=nowarn", "-");
        String replayed = gitText(session, replay, null, "write-tree");
        if (!expected.equals(replayed)) {
            throw new AgentException("REPLAY_MISMATCH", "Candidate diff does not reconstruct the final source tree");
        }
        String patch = decode(diff);
        if (!verified) {
            try {
                Files.writeString(session.directory.resolve("partial.diff"), patch, StandardCharsets.UTF_8);
                Files.writeString(session.directory.resolve("partial-metadata.json"), json.createObjectNode()
                        .put("runId", session.task.runId()).put("status", "UNVERIFIED_NON_DELIVERABLE")
                        .put("baseline", session.task.baseline()).put("sha256", Candidate.digest(patch))
                        .put("snapshot", snapshot.path("snapshot").asText())
                        .<ObjectNode>set("sourceView", snapshot.path("sourceView"))
                        .set("changedPaths", json.valueToTree(changed)).toString(), StandardCharsets.UTF_8);
            } catch (IOException failure) {
                throw new AgentException("FINALIZATION_STORAGE", "Cannot retain unverified partial work", failure);
            }
            return null;
        }
        String evidence = evidence(session, snapshot);
        Candidate candidate = new Candidate(UUID.randomUUID().toString(), session.task.runId(), session.task.baseline(),
                expected, patch, Candidate.digest(patch), changed, evidence, Instant.now());
        try {
            // SQLite 失败时保留唯一可恢复的 diff，不删除后再声称完成。
            Files.writeString(session.directory.resolve("candidate.diff"), patch, StandardCharsets.UTF_8);
            Files.writeString(session.directory.resolve("candidate-metadata.json"), json.writeValueAsString(Map.of(
                    "id", candidate.id(), "runId", candidate.runId(), "baseline", candidate.baseline(),
                    "expectedTree", expected, "sha256", candidate.sha256(), "changedPaths", changed,
                    "testEvidence", evidence)), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new AgentException("FINALIZATION_STORAGE", "Cannot retain recoverable Candidate material", failure);
        }
        return candidate;
    }

    /** 新旧两侧都验证，防止最终删除或类型转换绕过文本工具的修改边界。 */
    private String candidateText(byte[] bytes) {
        String content = decode(bytes);
        if (content.indexOf(0) >= 0) {
            throw new AgentException("CANDIDATE_POLICY", "Changed source is binary or contains recognized credentials");
        }
        return content;
    }

    /** 最终捕获不信任工具阶段的结论，独立按实际基线和冻结文件重算变更。 */
    private void requireFrozenMutation(Session session, Map<String, String> index, String path,
            byte[] before, byte[] after) {
        String delta = decode(git(session, index, null, "diff", "--cached", "--unified=0",
                "--no-ext-diff", "--no-textconv", session.task.baseline(), "--", path));
        try {
            new CredentialMutationGuard(secrets).requireSafe(path, before == null ? null : candidateText(before),
                    after == null ? null : candidateText(after), CredentialMutationGuard.ranges(delta));
        } catch (AgentPolicyRejection rejected) {
            throw new AgentException("CANDIDATE_POLICY", "Frozen source contains a prohibited credential mutation");
        }
    }

    /** Git 的函数标题也可能来自邻接源码；不让它携带未修改的秘密进入持久化差异。 */
    static String removeHunkHeadings(String diff) {
        return diff.replaceAll("(?m)^(@@ -[0-9]+(?:,[0-9]+)? [+][0-9]+(?:,[0-9]+)? @@).*$", "$1");
    }

    private String evidence(Session session, JsonNode snapshot) {
        ObjectNode evidence = json.createObjectNode().put("evidenceVersion", 7).put("runId", session.task.runId())
                .put("finalSnapshot", snapshot.path("snapshot").asText()).put("status", "NOT_TESTED")
                .put("verificationLevel", "COMMAND_VERIFIED")
                .put("testDetails", "UNAVAILABLE").put("executionBaselineTree", session.executionBaselineTree);
        evidence.set("sourceView", snapshot.path("sourceView"));
        evidence.set("agentReport", session.agentReport == null ? json.createObjectNode() : session.agentReport);
        Set<String> referenced = new HashSet<>();
        evidence.path("agentReport").path("executions").forEach(reference -> referenced.add(reference.asText()));
        var checks = evidence.putArray("checks");
        for (JsonNode record : session.checks) {
            ObjectNode check = record.deepCopy();
            check.put("stale", !snapshot.path("snapshot").asText().equals(check.path("snapshot").asText())
                    || !check.path("snapshot").asText().equals(check.path("afterSnapshot").asText()));
            checks.add(check);
            if (referenced.contains(check.path("invocationId").asText()) && !check.path("stale").asBoolean()
                    && check.path("testDetails").asText().equals("AVAILABLE")) {
                evidence.put("testDetails", "AVAILABLE");
                if (check.path("executedTests").asInt() > 0) {
                    evidence.put("status", "TESTED");
                }
            }
        }
        AgentCheckEvidence.requireAcceptable(evidence);
        return sanitizeNode(evidence).toString();
    }

    private void stopContainer(String name) {
        JsonNode resource = inspect("container", name);
        if (resource == null) {
            return;
        }
        requireOwner("container", name);
        if (resource.path("State").path("Running").asBoolean()) {
            docker(List.of("stop", "--time", "2", name), null, CONTROL_TIMEOUT, 8192, () -> false);
        }
        if (inspect("container", name).path("State").path("Running").asBoolean()) {
            throw new AgentException("CLEANUP_FAILURE", "Owned container is still running: " + name);
        }
    }

    private void removeContainer(String name) {
        if (inspect("container", name) != null) {
            stopContainer(name);
            requireOwner("container", name);
            docker(List.of("rm", name), null, CONTROL_TIMEOUT, 8192, () -> false);
        }
    }

    private void removeVolume(String name) {
        if (inspect("volume", name) != null) {
            requireOwner("volume", name);
            docker(List.of("volume", "rm", name), null, CONTROL_TIMEOUT, 8192, () -> false);
        }
    }

    @Override
    public void stop(String id) {
        // 旧后缀仅用于清理升级前未退出的同归属 Run，不再创建这些执行容器。
        for (String suffix : List.of("command", "agent", "capture", "seed", "proxy",
                "frontend-check", "frontend-prepare", "frontend-proxy", "check", "prepare")) {
            stopContainer(name(id, suffix));
        }
    }

    @Override
    public void cleanup(String id) {
        stop(id);
        for (String suffix : List.of("command", "agent", "capture", "seed", "proxy",
                "frontend-check", "frontend-prepare", "frontend-proxy", "check", "prepare")) {
            removeContainer(name(id, suffix));
        }
        String network = name(id, "network");
        if (inspect("network", network) != null) {
            requireOwner("network", network);
            docker(List.of("network", "rm", network), null, CONTROL_TIMEOUT, 8192, () -> false);
        }
        for (String suffix : List.of("source", "m2", "prepare-source", "check-source",
                "frontend-prepare-source", "frontend-check-source")) {
            removeVolume(name(id, suffix));
        }
        Path directory = directory(id);
        try {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                        if (!path.toAbsolutePath().normalize().startsWith(directory) || Files.isSymbolicLink(path)) {
                            throw new AgentException("RESOURCE_OWNERSHIP", "Unsafe cleanup path; material retained");
                        }
                        if (!path.toRealPath().startsWith(directory.toRealPath())) {
                            throw new AgentException("RESOURCE_OWNERSHIP", "Cleanup target resolves outside owned Run storage");
                        }
                        if (System.getProperty("os.name").startsWith("Windows")) {
                            Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS);
                        }
                        if (path.startsWith(directory.resolve("command-logs"))
                                || path.equals(directory) && Files.exists(directory.resolve("command-logs"))
                                || path.equals(directory.resolve("partial.diff"))
                                || path.equals(directory.resolve("partial-metadata.json"))
                                || path.equals(directory) && Files.exists(directory.resolve("partial-metadata.json"))) {
                            continue;
                        }
                        Files.delete(path);
                    }
                }
            }
        } catch (IOException failure) {
            throw new AgentException("CLEANUP_FAILURE", "Owned Agent material remains: " + id, failure);
        }
        sessions.remove(id);
    }
}
