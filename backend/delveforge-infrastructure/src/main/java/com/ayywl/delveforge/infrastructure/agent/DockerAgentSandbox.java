package com.ayywl.delveforge.infrastructure.agent;

import com.ayywl.delveforge.application.agent.AgentException;
import com.ayywl.delveforge.application.agent.AgentCheckEvidence;
import com.ayywl.delveforge.application.agent.BaselineCheckComparison;
import com.ayywl.delveforge.application.agent.AgentPolicyRejection;
import com.ayywl.delveforge.application.agent.AgentProtocol;
import com.ayywl.delveforge.application.agent.AgentTools;
import com.ayywl.delveforge.application.agent.AgentSandboxPort;
import com.ayywl.delveforge.application.repositoryanalysis.secret.DeterministicRepositorySecretPolicy;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;
import com.ayywl.delveforge.domain.agent.Candidate;
import com.ayywl.delveforge.infrastructure.workspace.GitRepositoryCompatibility;
import com.ayywl.delveforge.infrastructure.workspace.MavenRepositoryLayout;
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
        final List<JsonNode> baselineFailures = new ArrayList<>();
        final List<JsonNode> baselineChecks = new ArrayList<>();
        final Map<String, String> baselineHashes = new LinkedHashMap<>();
        final Map<String, Map<String, Object>> resourceCapabilities = new LinkedHashMap<>();
        String executionBaselineTree;
        String mavenDirectory;
        List<String> mavenModules = List.of();
        List<String> mavenRoots = List.of();
        String image;
        int jdk;
        Consumer<PreflightProgress> progress = progress -> { };
        String lastPreparation;
        String mavenDependencyKey;
        // 只有联网准备写入，检查容器只读挂载；依赖元数据变化即销毁并重建。
        String frontendDependencyKey;
        String frontendCapabilities;
        JsonNode frontendResolution;

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
        session.mavenDirectory = inspection.report().mavenDirectory() == null ? "." : inspection.report().mavenDirectory();
        session.mavenModules = inspection.report().modules();
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
            session.mavenRoots = new MavenJdkSelector().roots(poms);
            List<String> selectedMaven = task.checkDirectories().isEmpty()
                    ? session.mavenRoots.size() == 1 ? session.mavenRoots : List.of()
                    : session.mavenRoots.stream().filter(task.checkDirectories()::contains).toList();
            int jdk = task.targetJdk() == null ? properties.defaultJdk() : task.targetJdk();
            for (String directory : selectedMaven) {
                int requiredJdk = new MavenJdkSelector().select(poms, task.targetJdk(), properties, directory);
                jdk = selectedMaven.size() == 1 ? requiredJdk : Math.max(jdk, requiredJdk);
                session.mavenDirectory = directory;
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
            JsonNode initial = invoke(session, "initialize", json.createObjectNode(), cancelled);
            requireToolSuccess(initial);
            Set<String> actual = new HashSet<>();
            initial.path("result").path("files").forEach(file -> {
                String path = file.path("path").asText();
                actual.add(path);
                session.baselineHashes.put(path, file.path("sha256").asText() + ":" + file.path("mode").asText());
            });
            if (!actual.equals(session.baseline.keySet())) {
                throw new AgentException("GIT_ATTRIBUTES_UNSUPPORTED", "Archive omitted or transformed baseline paths");
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
        requireFrontendSourcePaths(session.baseline.keySet());
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
                throw new AgentException("GENERATED_OUTPUT_AMBIGUITY", "Frontend generated material is not source");
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
        boolean isolatedCopy = Set.of("prepare", "check", "frontend-prepare", "frontend-check").contains(suffix);
        String sourceVolume = isolatedCopy ? suffix + "-source" : "source";
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
                "DF_BASELINE_TOTAL_BYTES=" + GitRepositoryCompatibility.MAX_SOURCE_BYTES, "--env",
                "DF_MAVEN_DIRECTORY=" + session.mavenDirectory, "--env",
                modulesEnvironment(session), "--env",
                "DF_CHECK_SECONDS=" + limits.checkSeconds()));
        if (isolatedCopy) {
            result.addAll(List.of("--mount", "type=volume,source=" + name(runId, "source")
                    + ",target=/source,readonly"));
        }
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

    private String modulesEnvironment(Session session) {
        return "DF_MAVEN_MODULES_B64=" + Base64.getEncoder().encodeToString(
                json.valueToTree(session.mavenModules).toString().getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode invoke(Session session, String name, JsonNode arguments, BooleanSupplier cancelled) {
        try {
            byte[] input = json.writeValueAsBytes(Map.of("name", name, "arguments", arguments,
                    "baselinePaths", session.baseline.keySet(), "resourceCapabilities", session.resourceCapabilities));
            int maxBytes = Set.of("snapshot", "inspect_patch", "search_code").contains(name) ? Math.max(session.limits.maxCandidateBytes() * 16, 33554432)
                    : Math.max(session.limits.maxFileBytes() * 8, 2097152);
            if (name.equals("run_frontend_checks")) {
                JsonNode response = invokeFrontendChecks(session, arguments, maxBytes, cancelled);
                attestEnvironment(session, response);
                return response;
            }
            if (Set.of("run_checks", "probe_maven_dependencies").contains(name)) {
                return invokeChecks(session, input, maxBytes, cancelled);
            }
            return parse(docker(List.of("exec", "-i", "--env", modulesEnvironment(session),
                    name(session.task.runId(), "agent"), "python3", "/opt/delveforge/sandbox.py"), input,
                    Duration.ofSeconds(name.equals("run_checks") ? session.limits.checkSeconds() + 15L : 45L),
                    maxBytes, cancelled).bytes());
        } catch (IOException failure) {
            throw new AgentException("TOOL_PROTOCOL", "Cannot encode controlled tool request", failure);
        }
    }

    /** 正式检查只写一次性副本；返回结果必须绑定复制前的完整源码身份。 */
    private JsonNode invokeChecks(Session session, byte[] input, int maxBytes, BooleanSupplier cancelled) {
        String id = session.task.runId();
        JsonNode before = invoke(session, "snapshot", json.createObjectNode(), cancelled);
        requireToolSuccess(before);
        JsonNode response;
        try {
            ensureVolume(name(id, "check-source"), id);
            List<String> launch = baseRun(id, "check", session.image, "none", false);
            launch.addAll(List.of("-i", "--env", "DF_CHECK_COPY=true", session.image, "python3", "-c",
                    "import os,shutil;shutil.copytree('/source','/workspace',dirs_exist_ok=True);"
                            + "os.execvp('python3',['python3','/opt/delveforge/sandbox.py'])"));
            response = parse(docker(launch, input, Duration.ofSeconds(session.limits.checkSeconds() + 15L),
                    maxBytes, cancelled).bytes());
        } finally {
            removeContainer(name(id, "check"));
            removeVolume(name(id, "check-source"));
        }
        JsonNode after = invoke(session, "snapshot", json.createObjectNode(), cancelled);
        requireToolSuccess(after);
        String expected = before.path("result").path("snapshot").asText();
        if (!expected.equals(after.path("result").path("snapshot").asText())
                || (response.path("ok").asBoolean()
                    && !expected.equals(response.path("result").path("snapshot").asText()))) {
            throw new AgentException("SNAPSHOT_INTEGRITY", "Isolated check does not attest the authoritative source");
        }
        return response;
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

    /** 前端依赖与脚本分两个容器；联网阶段不执行项目代码，离线阶段只消费其只读依赖卷。 */
    private JsonNode invokeFrontendChecks(Session session, JsonNode arguments, int maxBytes,
            BooleanSupplier cancelled) throws IOException {
        String id = session.task.runId();
        JsonNode before = invoke(session, "snapshot", json.createObjectNode(), cancelled);
        requireToolSuccess(before);
        String expected = before.path("result").path("snapshot").asText();
        ObjectNode selectedArguments = ((ObjectNode) arguments).deepCopy();
        if (!selectedArguments.has("directory")) {
            JsonNode declared = invoke(session, "describe_checks", json.createObjectNode(), cancelled);
            requireToolSuccess(declared);
            JsonNode capabilities = declared.path("result");
            if (capabilities.path("frontendProjectCount").asInt() != 1) {
                ObjectNode unavailable = json.createObjectNode().put("checkType", "FRONTEND")
                        .put("directory", ".").put("goal", arguments.path("goal").asText())
                        .put("snapshot", expected).put("classification", "CHECK_NOT_AVAILABLE")
                        .put("executionStatus", "NOT_STARTED")
                        .put("tests", 0).put("executedTests", 0).put("skipped", 0).put("failures", 0)
                        .put("errors", 0).put("reports", 0)
                        .put("output", "Select one declared frontend project; do not invent Maven metadata");
                unavailable.set("availableChecks", capabilities);
                return json.createObjectNode().put("ok", true).put("tool", "run_frontend_checks")
                        .set("result", unavailable);
            }
            selectedArguments.put("directory", capabilities.path("frontendProjects").get(0)
                    .path("directory").asText());
        }
        String selectedDirectory = selectedArguments.path("directory").asText();
        if (!selectedDirectory.equals(".")) {
            requirePath(selectedDirectory);
        }
        List<String> dependencyInputs = new ArrayList<>(List.of(selectedDirectory));
        for (JsonNode file : before.path("result").path("files")) {
            String path = file.path("path").asText();
            String filename = path.substring(path.lastIndexOf('/') + 1);
            if (Set.of("package.json", "pnpm-lock.yaml", "package-lock.json", "npm-shrinkwrap.json",
                    "pnpm-workspace.yaml", ".npmrc", ".pnpmfile.cjs", "pnpmfile.cjs", ".yarnrc", ".yarnrc.yml")
                    .contains(filename)) {
                dependencyInputs.add(file.toString());
            }
        }
        String dependencyKey = Candidate.digest(String.join("\n", dependencyInputs));
        stopContainer(name(id, "agent"));
        JsonNode response;
        try {
            ensureVolume(name(id, "frontend-check-source"), id);
            response = null;
            if (!dependencyKey.equals(session.frontendDependencyKey)) {
                session.frontendDependencyKey = null;
                removeVolume(name(id, "frontend-prepare-source"));
                ensureVolume(name(id, "frontend-prepare-source"), id);
                response = preflightOperation(session, "DEPENDENCY_PREPARATION", selectedArguments, () -> {
                    String network = dependencyNetwork(session, cancelled, true);
                    try {
                        return frontendContainer(session, "frontend-prepare", network,
                                "prepare_frontend_dependencies", selectedArguments, maxBytes, cancelled);
                    } catch (IOException failure) {
                        throw new AgentException("TOOL_PROTOCOL", "Cannot encode dependency request", failure);
                    }
                });
                removeContainer(name(id, "frontend-prepare"));
                stopContainer(name(id, "frontend-proxy"));
                if (response.path("result").path("classification").asText().equals("DEPENDENCIES_PREPARED")) {
                    session.frontendDependencyKey = dependencyKey;
                    session.frontendResolution = response.path("result").deepCopy();
                }
            }
            if (dependencyKey.equals(session.frontendDependencyKey)) {
                response = frontendContainer(session, "frontend-check", "none", "run_frontend_checks",
                        selectedArguments, maxBytes, cancelled);
            }
        } finally {
            // 先销毁所有写入者，再回读权威源码；取消与超时也必须走相同清理路径。
            removeContainer(name(id, "frontend-check"));
            removeContainer(name(id, "frontend-prepare"));
            stopContainer(name(id, "frontend-proxy"));
            removeVolume(name(id, "frontend-check-source"));
            if (session.frontendDependencyKey == null) {
                removeVolume(name(id, "frontend-prepare-source"));
            }
            docker(List.of("start", name(id, "agent")), null, CONTROL_TIMEOUT, 8192, () -> false);
        }
        JsonNode after = invoke(session, "snapshot", json.createObjectNode(), cancelled);
        requireToolSuccess(after);
        if (!expected.equals(after.path("result").path("snapshot").asText())
                || response.path("ok").asBoolean()
                    && !expected.equals(response.path("result").path("snapshot").asText())) {
            throw new AgentException("SNAPSHOT_INTEGRITY", "Frontend check does not attest authoritative source");
        }
        JsonNode capabilities = invoke(session, "describe_checks", json.createObjectNode(), cancelled);
        requireToolSuccess(capabilities);
        String configuration = capabilities.path("result").path("configurationSnapshot").asText();
        if (response.path("result") instanceof ObjectNode check) {
            check.put("configurationChanged", !configuration.equals(session.frontendCapabilities));
            check.set("availableChecks", capabilities.path("result"));
            if (session.frontendResolution != null && dependencyKey.equals(session.frontendDependencyKey)) {
                for (String field : List.of("reproducibility", "resolutionSha256", "packageManagerVersion")) {
                    check.set(field, session.frontendResolution.path(field));
                }
            }
        }
        session.frontendCapabilities = configuration;
        return response;
    }

    private JsonNode frontendContainer(Session session, String suffix, String network, String tool,
            JsonNode arguments, int maxBytes, BooleanSupplier cancelled) throws IOException {
        String id = session.task.runId();
        List<String> launch = baseRun(id, suffix, session.image, network, false);
        if (suffix.equals("frontend-check")) {
            launch.addAll(List.of("--mount", "type=volume,source=" + name(id, "frontend-prepare-source")
                    + ",target=/dependencies,readonly"));
        }
        for (int index = 0; index < launch.size(); index++) {
            if (launch.get(index).endsWith("target=/m2")) {
                // 前端检查不需要 Maven 缓存，也不向项目脚本暴露这项独立能力。
                launch.remove(index);
                launch.remove(index - 1);
                break;
            }
        }
        launch.addAll(List.of("-i", "--env", "DF_FRONTEND_COPY=true", session.image, "python3", "-c",
                "import os,shutil;shutil.copytree('/source','/workspace',dirs_exist_ok=True);"
                        + "os.execvp('python3',['python3','/opt/delveforge/sandbox.py'])"));
        byte[] request = json.writeValueAsBytes(Map.of("name", tool, "arguments", arguments,
                "baselinePaths", session.baseline.keySet(), "resourceCapabilities", session.resourceCapabilities));
        return parse(docker(launch, request, Duration.ofSeconds(session.limits.checkSeconds() * 3L + 30L),
                maxBytes, cancelled).bytes());
    }

    static void requireCompatibleProtocol(JsonNode response) {
        if (response == null || !response.path("ok").isBoolean() || !response.path("ok").asBoolean()
                || !response.path("tool").asText().equals("protocol")
                || !response.path("result").path("protocol").asText().equals("delveforge-sandbox")
                || !response.path("result").path("version").isIntegralNumber()
                || !response.path("result").path("version").canConvertToInt()
                || response.path("result").path("version").asInt() != 8) {
            throw incompatibleProtocol();
        }
    }

    private static AgentException incompatibleProtocol() {
        return new AgentException("SANDBOX_PROTOCOL_INCOMPATIBLE",
                "Sandbox tool protocol v8 is required. Build the current agent Dockerfile with a new image tag, "
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
        JsonNode arguments = parse(call.arguments().getBytes(StandardCharsets.UTF_8));
        if (call.name().equals("run_checks") && arguments.isObject() && !arguments.has("directory")) {
            ((ObjectNode) arguments).put("directory", session.mavenDirectory);
        }
        if (!arguments.isObject()) {
            return "{\"ok\":false,\"classification\":\"INVALID_ARGUMENTS\"}";
        }
        if (arguments.has("path") && !arguments.path("path").asText().equals(".")) {
            if (session.resourceCapabilities.containsKey(arguments.path("path").asText())) {
                throw new AgentPolicyRejection(AgentPolicyRejection.Rule.RESOURCE_CAPABILITY,
                        AgentPolicyRejection.Stage.PATH_VALIDATION);
            }
            try {
                if (AgentTools.readOnly(call.name())) {
                    String feedback = readPathFeedback(arguments.path("path").asText());
                    if (feedback != null) {
                        return feedback;
                    }
                } else {
                    requirePath(arguments.path("path").asText());
                }
            } catch (AgentException failure) {
                if (!failure.code().equals("GENERATED_OUTPUT_AMBIGUITY")
                        || !Set.of("list_files", "read_file", "search_code").contains(call.name())) {
                    throw failure;
                }
                // 只读询问未进入文件系统；返回环境事实，不把依赖目录视为可读或可写源码。
                JsonNode declared = invoke(session, "describe_checks", json.createObjectNode(), cancelled);
                requireToolSuccess(declared);
                ObjectNode feedback = json.createObjectNode().put("ok", false).put("tool", call.name())
                        .put("classification", "CHECK_MATERIAL_UNAVAILABLE")
                        .put("instruction", "依赖和检查产物只在一次性检查环境中可用，不属于 Agent 源码视图。"
                                + "不要浏览 node_modules；通过 run_frontend_checks 运行项目脚本并读取实际检查反馈。"
                                + "锁文件不一致应核对 package.json 与锁文件的依赖声明；Node 内置测试无需新增 Vitest 依赖。");
                feedback.set("availableChecks", declared.path("result"));
                if (feedback.toString().getBytes(StandardCharsets.UTF_8).length > session.limits.maxOutputBytes()) {
                    feedback.remove("availableChecks");
                    feedback.put("capabilitiesTruncated", true);
                }
                return sanitize(feedback.toString());
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
        JsonNode response = call.name().equals("run_checks") ? mavenChecks(session, arguments, cancelled)
                : invoke(session, call.name().equals("edit_file") ? "apply_patch" : call.name(), arguments, cancelled);
        if (call.name().equals("run_checks")) {
            if (response.path("ok").asBoolean()) {
                session.checks.add(response.path("result").deepCopy());
            } else if (!response.path("classification").asText().equals("INVALID_ARGUMENTS")) {
                // 仅明确未启动 Maven 的参数错误不形成检查失败；执行/报告未知仍保持阻断。
                ObjectNode missing = json.createObjectNode().put("classification", "CHECK_RESULT_UNAVAILABLE")
                        .put("snapshot", "").put("goal", arguments.path("goal").asText("test"));
                String directory = Path.of(arguments.path("directory").asText("."))
                        .normalize().toString().replace('\\', '/');
                missing.put("directory", directory.isEmpty() ? "." : directory);
                missing.putNull("module");
                missing.putNull("selectedTest");
                session.checks.add(missing);
            }
        }
        if (call.name().equals("run_frontend_checks") && response.path("ok").asBoolean()) {
            ((ObjectNode) response).put("tool", call.name());
            session.checks.add(response.path("result").deepCopy());
        }
        rejectPolicy(response, AgentTools.mutation(call.name()) ? AgentPolicyRejection.Stage.PATCH_APPLICATION
                : AgentPolicyRejection.Stage.TOOL_EXECUTION);
        requireCertainPatchOutcome(response);
        JsonNode visible = sourceResponse(call.name(), response);
        if (Set.of("run_checks", "run_frontend_checks").contains(call.name()) && visible.path("result") instanceof ObjectNode check
                && visible.toString().getBytes(StandardCharsets.UTF_8).length > session.limits.maxOutputBytes()) {
            String output = check.path("output").asText();
            int keep = Math.max(0, session.limits.maxOutputBytes() / 8);
            check.put("output", output.substring(Math.max(0, output.length() - keep)));
            check.put("outputTruncated", true);
        }
        String safe = visible.toString();
        if (safe.getBytes(StandardCharsets.UTF_8).length > session.limits.maxOutputBytes()) {
            return "{\"ok\":false,\"classification\":\"OUTPUT_LIMIT\",\"reason\":\"Request a smaller range\"}";
        }
        return safe;
    }

    static void requireCertainPatchOutcome(JsonNode response) {
        if (response.path("classification").asText().equals("PATCH_OUTCOME_UNCERTAIN")) {
            throw new AgentException("PATCH_OUTCOME_UNCERTAIN", "Patch application outcome could not be established");
        }
    }

    /** 完整源码先净化再切片；内部原文材料字段不进入模型工具结果。 */
    JsonNode sourceResponse(String tool, JsonNode response) {
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
            requirePath(path);
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
            data.put("path", sanitize(path)).put("startLine", first).put("totalLines", result.path("totalLines").asInt())
                    .put("content", String.join("", lines.subList(start, Math.min(start + count, lines.size()))));
        } else {
            var matches = data.putArray("matches");
            data.put("truncated", result.path("truncated").asBoolean());
            Map<String, List<String>> sources = new HashMap<>();
            for (JsonNode match : result.path("matches")) {
                String path = match.path("path").asText();
                requirePath(path);
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
                matches.addObject().put("path", sanitize(path)).put("line", line)
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

    private void restartAgent(String id, BooleanSupplier cancelled) {
        stopContainer(name(id, "agent"));
        docker(List.of("start", name(id, "agent")), null, CONTROL_TIMEOUT, 8192, cancelled);
    }

    private String dependencyNetwork(Session session, BooleanSupplier cancelled) {
        return dependencyNetwork(session, cancelled, false);
    }

    private JsonNode mavenChecks(Session session, JsonNode arguments, BooleanSupplier cancelled) {
        JsonNode source = invoke(session, "snapshot", json.createObjectNode(), cancelled);
        requireToolSuccess(source);
        List<String> inputs = new ArrayList<>();
        inputs.add(arguments.path("directory").asText("."));
        for (JsonNode file : source.path("result").path("files")) {
            String path = file.path("path").asText();
            if (path.equals("pom.xml") || path.endsWith("/pom.xml")
                    || path.startsWith(".mvn/") || path.contains("/.mvn/")) {
                inputs.add(file.toString());
            }
        }
        String key = Candidate.digest(String.join("\n", inputs));
        boolean changed = !key.equals(session.mavenDependencyKey);
        if (changed) {
            session.mavenDependencyKey = null;
        }
        JsonNode response = invoke(session, "run_checks", arguments, cancelled);
        Set<String> fetched = new HashSet<>();
        for (int attempt = 0; attempt < Math.min(8, Math.max(1, session.limits.dependencyRetries()) * 4)
                && response.path("result").path("classification").asText().equals("DEPENDENCY_MISSING"); attempt++) {
            Set<String> missing = missingMavenArtifacts(response.path("result").path("output").asText());
            if (missing.isEmpty()) {
                JsonNode offline = invoke(session, "probe_maven_dependencies", arguments, cancelled);
                missing.addAll(missingMavenArtifacts(offline.path("result").path("output").asText()));
            }
            missing.removeAll(fetched);
            if (missing.isEmpty() || fetched.size() + missing.size() > 32) {
                break;
            }
            fetched.addAll(missing);
            ObjectNode request = json.createObjectNode();
            request.set("artifacts", json.valueToTree(missing.stream().sorted().toList()));
            preflightOperation(session, "DEPENDENCY_PREPARATION", arguments, () -> {
                prepareDependencies(session, request, cancelled);
                return json.createObjectNode().set("result", json.createObjectNode()
                        .put("classification", "PREPARATION_FINISHED"));
            });
            response = invoke(session, "run_checks", arguments, cancelled);
        }
        if (!response.path("result").path("classification").asText().equals("DEPENDENCY_MISSING")) {
            session.mavenDependencyKey = key;
        } else if (response.path("result") instanceof ObjectNode check) {
            check.put("readinessReason", "受控离线 Maven 依赖仍不完整；请核对缺失工件与依赖准备预算，尚未执行测试");
            check.set("missingArtifacts", json.valueToTree(missingMavenArtifacts(check.path("output").asText())
                    .stream().sorted().limit(32).toList()));
            check.put("fetchedArtifactCount", fetched.size());
            check.put("preparationDiagnostic", session.lastPreparation == null
                    ? "No bounded missing artifact identities could be established" : session.lastPreparation);
        }
        if (response.path("result") instanceof ObjectNode check) {
            check.put("configurationSnapshot", key);
            check.put("configurationChanged", changed);
            attestEnvironment(session, response);
            // 保留实际已解析入口的生成目录角色，不改变基线资源能力。
            JsonNode directory = check.path("directory");
            if (directory.isTextual() && !session.mavenModules.contains(directory.asText())) {
                List<String> modules = new ArrayList<>(session.mavenModules);
                modules.add(directory.asText());
                session.mavenModules = List.copyOf(modules);
            }
        }
        return response;
    }

    private void attestEnvironment(Session session, JsonNode response) {
        if (response.path("ok").asBoolean() && response.path("result") instanceof ObjectNode result) {
            ObjectNode environment = result.putObject("environment");
            environment.put("image", session.image).put("configuration", result.path("configurationSnapshot").asText())
                    .put("jdk", session.jdk).put("checkSeconds", session.limits.checkSeconds());
            environment.put("runtimeDependencies", result.path("checkType").asText("MAVEN").equals("MAVEN")
                    ? result.path("runtimeDependencies").asText() : result.path("resolutionSha256").asText());
            for (String field : List.of("runner", "resolutionSha256", "reproducibility", "packageManagerVersion")) {
                environment.put(field, result.path(field).asText());
            }
        }
    }

    static Set<String> missingMavenArtifacts(String output) {
        Set<String> result = new HashSet<>();
        var coordinates = java.util.regex.Pattern.compile(
                "(?<![A-Za-z0-9_.-])([A-Za-z0-9_][A-Za-z0-9_.-]*):([A-Za-z0-9_][A-Za-z0-9_.-]*):"
                        + "(jar|pom|war|zip|test-jar):(?:([A-Za-z0-9_.-]+):)?([A-Za-z0-9_][A-Za-z0-9_.+\\-]*)")
                .matcher(output);
        while (coordinates.find() && result.size() < 33) {
            String before = output.substring(Math.max(0, coordinates.start() - 100), coordinates.start());
            String after = output.substring(coordinates.end(), Math.min(output.length(), coordinates.end() + 80));
            // 错误上下文的 “for project g:a:jar:v” 是本项目身份，不能当成待下载工件。
            if (after.matches("(?s)^\\s*\\(absent\\).*|^\\s+has not been downloaded.*")
                    || before.matches("(?s).*(?:artifact |The POM for |Missing POM for )$")
                    || before.matches("(?s).*Missing:\\h*\\R(?:\\[ERROR]\\h*)?-+\\h*\\R(?:\\[ERROR]\\h*)?\\d+\\)\\h*$")) {
                result.add(coordinates.group(1) + ":" + coordinates.group(2) + ":" + coordinates.group(5)
                        + ":" + coordinates.group(3) + (coordinates.group(4) == null ? "" : ":" + coordinates.group(4)));
            }
        }
        var plugins = java.util.regex.Pattern.compile(
                "Plugin ([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.+\\-]+) (?:or|could)")
                .matcher(output);
        while (plugins.find() && result.size() < 33) {
            result.add(plugins.group(1) + ":" + plugins.group(2) + ":" + plugins.group(3) + ":jar");
        }
        return result;
    }

    private String dependencyNetwork(Session session, BooleanSupplier cancelled, boolean frontend) {
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
        String proxy = name(id, frontend ? "frontend-proxy" : "proxy");
        if (inspect("container", proxy) == null) {
            List<String> launch = new ArrayList<>(List.of("run", "-d", "--name", proxy, "--label",
                    "delveforge.owner=" + properties.owner(), "--label", "delveforge.run=" + id,
                    "--network", "name=bridge,gw-priority=1", "--user", "11000:11000", "--cap-drop", "ALL", "--security-opt",
                    "no-new-privileges", "--read-only", "--memory", "256m", "--cpus", "1", "--pids-limit", "64",
                    "--tmpfs", "/tmp:rw,nosuid,size=64m", "--mount", "type=volume,source=" + properties.cacheVolume()
                    + ",target=/cache", "--env", "DF_ARTIFACT_MODE=" + (frontend ? "frontend" : "maven"),
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

    private void prepareDependencies(Session session, JsonNode args, BooleanSupplier cancelled) {
        String id = session.task.runId();
        JsonNode captured = invoke(session, "snapshot", json.createObjectNode(), cancelled);
        requireToolSuccess(captured);
        String before = captured.path("result").path("snapshot").asText();
        stopContainer(name(id, "agent"));
        try {
            ensureVolume(name(id, "prepare-source"), id);
            String network = dependencyNetwork(session, cancelled);
            // 联网进程只在固定空 POM 上获取坐标，不加载项目 POM、配置、插件或生命周期。
            List<String> launch = baseRun(id, "prepare", session.image, network, false);
            launch.addAll(List.of("-i", session.image, "python3", "/opt/delveforge/sandbox.py"));
            // 准备诊断不构成检查证据；只有随后离线探针能证明实际项目阶段。
            List<String> command = new ArrayList<>(List.of(properties.docker()));
            command.addAll(launch);
            byte[] request = json.writeValueAsBytes(Map.of("name", "fetch_maven_dependencies", "arguments", args));
            var prepared = commands.run(command, null, request, Duration.ofSeconds(session.limits.checkSeconds() * 3L),
                    2097152, cancelled, Map.of());
            String diagnostic = sanitize(prepared.text() + prepared.diagnostic());
            diagnostic += sanitize(docker(List.of("logs", "--tail", "20", name(id, "proxy")), null,
                    CONTROL_TIMEOUT, 8192, () -> false).text());
            session.lastPreparation = diagnostic.substring(Math.max(0, diagnostic.length() - 10000));
        } catch (IOException failure) {
            throw new AgentException("TOOL_PROTOCOL", "Cannot encode artifact preparation", failure);
        } catch (AgentException failure) {
            if (!failure.code().equals("TIME_LIMIT")) {
                throw failure;
            }
            // 先停止项目进程，再读取有界诊断；原 TIME_LIMIT 及失败状态不变，清理仍由 finally 执行。
            stopContainer(name(id, "prepare"));
            session.lastPreparation = mavenPreparationTimeoutDiagnostic(session);
            throw new AgentException("TIME_LIMIT", session.lastPreparation, failure);
        } finally {
            removeContainer(name(id, "prepare"));
            removeVolume(name(id, "prepare-source"));
            stopContainer(name(id, "proxy"));
            docker(List.of("start", name(id, "agent")), null, CONTROL_TIMEOUT, 8192, () -> false);
        }
        JsonNode after = invoke(session, "snapshot", json.createObjectNode(), cancelled);
        requireToolSuccess(after);
        if (!before.equals(after.path("result").path("snapshot").asText())) {
            throw new AgentException("DEPENDENCY_PREPARATION_MUTATED_SOURCE", "Dependency preparation changed source files");
        }
    }

    private String mavenPreparationTimeoutDiagnostic(Session session) {
        String id = session.task.runId();
        ObjectNode diagnostic = json.createObjectNode().put("phase", "MAVEN_DEPENDENCY_PREPARATION")
                .put("outcome", "TIME_LIMIT").put("limitSeconds", session.limits.checkSeconds() * 3L);
        try {
            String log = sanitize(docker(List.of("logs", "--tail", "80", name(id, "prepare")),
                    null, CONTROL_TIMEOUT, 65536, () -> false).text());
            String lastPhase = log.lines().filter(line -> line.startsWith("[INFO] ---"))
                    .reduce((previous, current) -> current).orElse("NOT_OBSERVED");
            diagnostic.put("lastMavenPhase", lastPhase.substring(0, Math.min(lastPhase.length(), 512)));
        } catch (AgentException unavailable) {
            diagnostic.put("mavenLogUnavailable", unavailable.code());
        }
        try {
            byte[] bytes = docker(List.of("exec", name(id, "proxy"), "python3", "-c",
                    "import pathlib,sys; p=pathlib.Path('/tmp/delveforge-maven-progress.json'); "
                            + "sys.stdout.buffer.write(p.read_bytes()[:32769] if p.is_file() else b'{}')"),
                    null, CONTROL_TIMEOUT, 32768, () -> false).bytes();
            diagnostic.set("proxyProgress", sanitizeNode(parse(bytes)));
        } catch (AgentException unavailable) {
            diagnostic.put("proxyProgressUnavailable", unavailable.code());
        }
        try {
            JsonNode state = parse(docker(List.of("inspect", "--format", "{{json .State}}", name(id, "prepare")),
                    null, CONTROL_TIMEOUT, 8192, () -> false).bytes());
            diagnostic.put("oomKilled", state.path("OOMKilled").asBoolean());
            diagnostic.put("stoppedExitCode", state.path("ExitCode").asInt());
        } catch (AgentException unavailable) {
            diagnostic.put("containerStateUnavailable", unavailable.code());
        }
        return diagnostic.toString();
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
        session.progress = progress;
        try {
            return preflight(session, cancelled);
        } finally {
            session.progress = ignored -> { };
        }
    }

    private Preflight preflight(Session session, BooleanSupplier cancelled) {
        JsonNode declared = invoke(session, "describe_checks", json.createObjectNode(), cancelled);
        requireToolSuccess(declared);
        JsonNode capabilities = declared.path("result");
        List<String> frontend = new ArrayList<>();
        capabilities.path("frontendProjects").forEach(project -> frontend.add(project.path("directory").asText()));
        Set<String> available = new TreeSet<>(session.mavenRoots);
        available.addAll(frontend);
        List<String> selected = session.task.checkDirectories();
        ObjectNode diagnosis = json.createObjectNode();
        diagnosis.put("baseline", session.task.baseline());
        diagnosis.set("availableDirectories", json.valueToTree(available));
        if (selected.isEmpty()) {
            if (session.mavenRoots.size() > 1 || frontend.size() > 1) {
                diagnosis.put("reason", "Choose checkDirectories from the exact entries; no model was requested");
                return preflightResult("SELECTION_REQUIRED", diagnosis);
            }
            selected = List.copyOf(available);
        }
        if (selected.isEmpty() || !available.containsAll(selected)) {
            diagnosis.put("reason", "No supported check entry was selected; use availableDirectories");
            return preflightResult(selected.isEmpty() ? "ENVIRONMENT_BLOCKED" : "SELECTION_REQUIRED", diagnosis);
        }
        diagnosis.set("availableDirectories", json.valueToTree(selected));
        var probes = diagnosis.putArray("probes");
        boolean baselineFailure = false;
        try {
            for (String directory : selected) {
                if (session.mavenRoots.contains(directory)) {
                    ObjectNode args = json.createObjectNode().put("directory", directory).put("goal", "test");
                    diagnosis.put("directory", directory).put("goal", "test").put("checkType", "MAVEN");
                    JsonNode response = preflightOperation(session, "MAVEN_CHECK", args,
                            () -> mavenChecks(session, args, cancelled));
                    rejectPolicy(response, AgentPolicyRejection.Stage.TOOL_EXECUTION);
                    requireToolSuccess(response);
                    JsonNode result = response.path("result");
                    session.baselineChecks.add(result.deepCopy());
                    String readiness = EnvironmentReadiness.classify(result);
                    probes.add(preflightProbe(result, readiness));
                    if (readiness.equals("ENVIRONMENT_BLOCKED")) {
                        return preflightResult(readiness, diagnosis);
                    }
                    if (readiness.equals("READY_BASELINE_FAILURE")) {
                        baselineFailure = true;
                        session.baselineFailures.add(result.deepCopy());
                    }
                }
                for (JsonNode project : capabilities.path("frontendProjects")) {
                    if (!directory.equals(project.path("directory").asText())) {
                        continue;
                    }
                    int executed = 0;
                    for (String goal : List.of("typecheck", "build", "test")) {
                        if (project.path("goals").path(goal).asText().equals("ABSENT")) {
                            probes.addObject().put("checkType", "FRONTEND").put("directory", directory)
                                    .put("goal", goal).put("readiness", "NOT_DECLARED");
                            continue;
                        }
                        ObjectNode args = json.createObjectNode().put("directory", directory).put("goal", goal);
                        diagnosis.put("directory", directory).put("goal", goal).put("checkType", "FRONTEND");
                        JsonNode response = preflightOperation(session, "FRONTEND_CHECK", args,
                                () -> invoke(session, "run_frontend_checks", args, cancelled));
                        rejectPolicy(response, AgentPolicyRejection.Stage.TOOL_EXECUTION);
                        requireToolSuccess(response);
                        JsonNode result = response.path("result");
                        session.baselineChecks.add(result.deepCopy());
                        String readiness = EnvironmentReadiness.classify(result);
                        probes.add(preflightProbe(result, readiness));
                        if (readiness.equals("ENVIRONMENT_BLOCKED")) {
                            return preflightResult(readiness, diagnosis);
                        }
                        executed++;
                        if (readiness.equals("READY_BASELINE_FAILURE")) {
                            baselineFailure = true;
                            session.baselineFailures.add(result.deepCopy());
                        }
                    }
                    if (executed == 0) {
                        diagnosis.put("reason", "Selected frontend has no declared supported check");
                        return preflightResult("ENVIRONMENT_BLOCKED", diagnosis);
                    }
                }
            }
        } catch (AgentException failure) {
            if (cancelled.getAsBoolean() || failure.code().equals("CANCELLED")) {
                throw failure;
            }
            diagnosis.put("reason", failure.code()).put("diagnostic", sanitize(failure.getMessage()));
            if (failure instanceof AgentPolicyRejection rejection && rejection.sourceChanges() != null) {
                diagnosis.set("sourceChanges", json.valueToTree(rejection.sourceChanges()));
            }
            return preflightResult(failure instanceof AgentPolicyRejection rejection
                    && rejection.rule() != AgentPolicyRejection.Rule.COMMAND_BUDGET
                    ? "SECURITY_BLOCKED" : "ENVIRONMENT_BLOCKED", diagnosis);
        }
        if (hasExecutedBaselineTests(session) && !comparableBaseline(session)) {
            diagnosis.put("reason", "EVIDENCE_INCOMPARABLE")
                    .put("diagnostic", "Baseline test inventories or execution identities are incomplete; no model was requested");
            return preflightResult("ENVIRONMENT_BLOCKED", diagnosis);
        }
        return preflightResult(baselineFailure ? "READY_BASELINE_FAILURE" : "READY", diagnosis);
    }

    private JsonNode preflightOperation(Session session, String stage, JsonNode arguments, Supplier<JsonNode> operation) {
        String directory = sanitize(arguments.path("directory").asText("."));
        directory = directory.substring(0, Math.min(directory.length(), 200));
        String goal = arguments.path("goal").asText();
        long started = System.nanoTime();
        session.progress.accept(new PreflightProgress(stage, "STARTED", directory, goal, "", 0));
        try {
            JsonNode response = operation.get();
            String classification = response.path("result").path("classification").asText("UNKNOWN");
            session.progress.accept(new PreflightProgress(stage, "COMPLETED", directory, goal, classification,
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            return response;
        } catch (AgentException failure) {
            session.progress.accept(new PreflightProgress(stage, "FAILED", directory, goal, failure.code(),
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            throw failure;
        }
    }

    private boolean hasExecutedBaselineTests(Session session) {
        return session.baselineChecks.stream().anyMatch(check -> Set.of("test", "verify").contains(check.path("goal").asText())
                && check.path("executedTests").asInt() > 0);
    }

    private boolean comparableBaseline(Session session) {
        return !session.baselineChecks.isEmpty() && session.baselineChecks.stream().allMatch(check ->
                Set.of("TESTS_PASSED", "TESTS_FAILED", "BUILD_PASSED_NOT_TESTED", "TYPECHECK_FAILED", "FRONTEND_BUILD_FAILED")
                        .contains(check.path("classification").asText())
                        && (!Set.of("test", "verify").contains(check.path("goal").asText())
                            || check.path("testManifest").path("complete").asBoolean()
                                && check.path("environment").path("runtimeDependencies").asText().matches("[0-9a-f]{64}")));
    }

    private Preflight preflightResult(String outcome, ObjectNode diagnosis) {
        // 保留全部工程/阶段身份，必要时压缩较早输出，符合既有单事件 24K 的预算。
        if (diagnosis.toString().length() > 22000) {
            JsonNode probes = diagnosis.path("probes");
            for (int i = 0; i < probes.size() - 1; i++) {
                ((ObjectNode) probes.get(i)).remove("output");
            }
            diagnosis.put("outputTruncated", true);
        }
        return new Preflight(outcome, sanitize(diagnosis.toString()));
    }

    private JsonNode preflightProbe(JsonNode check, String readiness) {
        ObjectNode probe = json.createObjectNode().put("readiness", readiness);
        for (String field : List.of("checkType", "directory", "goal", "classification", "observedPhase", "runner",
                "unprovenCapabilities", "executedTests", "failures", "errors", "reports", "snapshot",
                "reproducibility", "resolutionSha256", "packageManagerVersion", "missingArtifacts", "fetchedArtifactCount")) {
            if (check.has(field)) {
                JsonNode value = check.path(field);
                if (value.isTextual()) {
                    probe.put(field, value.asText().substring(0, Math.min(value.asText().length(), 200)));
                } else {
                    probe.set(field, value);
                }
            }
        }
        if (!readiness.equals("READY")) {
            String output = check.path("output").asText();
            probe.put("output", output.substring(Math.max(0, output.length() - 600)));
            if (readiness.equals("ENVIRONMENT_BLOCKED")) {
                probe.put("readinessReason", check.path("readinessReason").asText(EnvironmentReadiness.reason(check)));
                String preparation = check.path("preparationDiagnostic").asText();
                if (!preparation.isEmpty()) {
                    probe.put("preparationDiagnostic", preparation.substring(Math.max(0, preparation.length() - 500)));
                }
            }
        }
        return probe;
    }

    @Override
    public String checkCapabilities(String runId, BooleanSupplier cancelled) {
        Session session = requireSession(runId);
        JsonNode response = invoke(session, "describe_checks", json.createObjectNode(), cancelled);
        session.frontendCapabilities = response.path("result").path("configurationSnapshot").asText();
        requireToolSuccess(response);
        return sanitizeNode(response.path("result")).toString();
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
    public String completionFeedback(String runId, BooleanSupplier cancelled) {
        Session session = requireSession(runId);
        JsonNode response = invoke(session, "snapshot", json.createObjectNode(), cancelled);
        requireToolSuccess(response);
        try {
            evidence(session, response.path("result"));
            return null;
        } catch (AgentException failure) {
            if (!Set.of("UNRESOLVED_CHECK_FAILURE", "EVIDENCE_INCOMPARABLE").contains(failure.code())) {
                throw failure;
            }
            return json.createObjectNode().put("classification", failure.code())
                    .put("reason", sanitize(failure.getMessage()))
                    .put("instruction", "运行当前完整检查，解决新增/功能相关失败。不要用选测、跳过测试或编译清除全量失败。"
                            + "历史失败只有完整同环境逐例证据可保留；不可比较时明确失败，不扩大授权。")
                    .toString();
        }
    }

    @Override
    public Candidate finish(String runId, BooleanSupplier cancelled) {
        Session session = requireSession(runId);
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
                    "resourceCapabilities", session.resourceCapabilities)).toString().getBytes(StandardCharsets.UTF_8);
            snapshot = parse(docker(List.of("exec", "-i", name(runId, "capture"), "python3",
                    "/opt/delveforge/sandbox.py"), request, CONTROL_TIMEOUT,
                    Math.max(session.limits.maxCandidateBytes() * 16, 33554432), cancelled).bytes());
            requireToolSuccess(snapshot);
        } finally {
            removeContainer(name(runId, "capture"));
        }
        return freezeTree(session, snapshot.path("result"));
    }

    private Candidate freezeTree(Session session, JsonNode snapshot) {
        Set<String> sourcePaths = new HashSet<>();
        snapshot.path("files").forEach(file -> sourcePaths.add(file.path("path").asText()));
        requireFrontendSourcePaths(sourcePaths);
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
                if (path.matches("(?i).*(\\.(log|class|jar|war|zip|tmp|bak|pyc|lastupdated)|dependency-reduced-pom\\.xml)$")
                        || List.of(path.split("/")).stream().anyMatch(part -> Set.of(".gradle", ".idea", "__pycache__")
                                .contains(part))) {
                    throw new AgentException("GENERATED_OUTPUT_AMBIGUITY", "Generated-looking output outside target requires explicit classification");
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

    /** 生成目录属于具体前端工程；同名 Maven 源目录仍按原规则处理。 */
    private void requireFrontendSourcePaths(Set<String> paths) {
        for (String manifest : paths) {
            if (!manifest.equals("package.json") && !manifest.endsWith("/package.json")) {
                continue;
            }
            String prefix = manifest.substring(0, manifest.length() - "package.json".length());
            for (String directory : List.of("dist", "coverage", ".vite")) {
                if (paths.stream().anyMatch(path -> path.startsWith(prefix + directory + "/"))) {
                    throw new AgentException("GENERATED_OUTPUT_AMBIGUITY", "Frontend generated output is not source");
                }
            }
        }
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
        // identity 仅包含 path/mode/hash，测试与最终捕获使用相同算法。
        String finalSnapshot = snapshot.path("snapshot").asText();
        if (!finalSnapshot.matches("[0-9a-f]{64}")) {
            throw new AgentException("SNAPSHOT_INTEGRITY", "Cannot associate checks with final source");
        }
        List<Map<String, Object>> records = new ArrayList<>();
        for (JsonNode check : session.checks) {
            String status = check.path("classification").asText();
            String directory = check.path("directory").asText(".");
            boolean stale = !finalSnapshot.equals(check.path("snapshot").asText());
            Map<String, Object> record = new LinkedHashMap<>(Map.of(
                    "classification", status, "snapshot", check.path("snapshot").asText(),
                    "stale", stale, "tests", check.path("tests").asInt(), "reports", check.path("reports").asInt(),
                    "executedTests", check.path("executedTests").asInt(), "skipped", check.path("skipped").asInt(),
                    "goal", check.path("goal").asText(), "module", check.path("module").asText(""),
                    "selectedTest", check.path("selectedTest").asText("")));
            record.put("directory", directory);
            record.put("checkType", check.path("checkType").asText("MAVEN"));
            for (String field : List.of("executionStatus", "runner", "configurationSnapshot",
                    "reproducibility", "resolutionSha256", "packageManagerVersion")) {
                if (check.has(field)) {
                    record.put(field, check.path(field).asText());
                }
            }
            if (check.has("sourceView")) {
                record.put("sourceView", check.path("sourceView").deepCopy());
            }
            if (Set.of("npm", "pnpm").contains(check.path("packageManager").asText())) {
                record.put("packageManager", check.path("packageManager").asText());
            }
            for (String key : List.of("failures", "errors")) {
                JsonNode count = check.path(key);
                record.put(key, 0);
                if (count.isIntegralNumber() && count.canConvertToLong() && count.asLong() >= 0) {
                    record.put(key, count.asLong());
                }
            }
            if (check.has("runtimeChanges")) {
                record.put("runtimeChanges", check.path("runtimeChanges").deepCopy());
            }
            if (check.has("environment")) {
                record.put("environment", check.path("environment").deepCopy());
            }
            if (!stale && check.has("testManifest")) {
                record.put("testManifest", check.path("testManifest").deepCopy());
            }
            records.add(record);
        }
        try {
            List<JsonNode> baselineFailures = session.baselineFailures.stream().map(check -> {
                ObjectNode baseline = check.deepCopy();
                baseline.remove(List.of("output", "availableChecks", "preparationDiagnostic"));
                baseline.put("stale", true);
                return (JsonNode) baseline;
            }).toList();
            ObjectNode evidence = json.valueToTree(Map.of("evidenceVersion", 4, "finalSnapshot", finalSnapshot,
                    "sourceView", snapshot.path("sourceView"),
                    "executionBaselineTree", session.executionBaselineTree,
                    "baselineFailures", baselineFailures,
                    "checks", records, "status", records.stream().anyMatch(record -> !((boolean) record.get("stale"))
                            && record.get("classification").equals("TESTS_PASSED")) ? "TESTED" : "NOT_TESTED"));
            boolean comparableBaseline = comparableBaseline(session);
            if (!comparableBaseline && hasExecutedBaselineTests(session)) {
                throw new AgentException("EVIDENCE_INCOMPARABLE", "Baseline checks lack complete comparable inventories or environments; cannot downgrade to legacy evidence");
            }
            if (comparableBaseline) {
                evidence.put("evidenceVersion", 5);
                var baselineChecks = evidence.putArray("baselineChecks");
                for (JsonNode check : session.baselineChecks) {
                    ObjectNode baseline = check.deepCopy();
                    baseline.remove(List.of("output", "availableChecks", "preparationDiagnostic"));
                    baselineChecks.add(baseline);
                }
                Set<String> changedPaths = new TreeSet<>(session.baselineHashes.keySet());
                for (JsonNode file : snapshot.path("files")) {
                    String path = file.path("path").asText();
                    if ((file.path("sha256").asText() + ":" + file.path("mode").asText())
                            .equals(session.baselineHashes.get(path))) {
                        changedPaths.remove(path);
                    } else {
                        changedPaths.add(path);
                    }
                }
                evidence.set("changedPaths", json.valueToTree(changedPaths));
                var comparison = BaselineCheckComparison.compare(evidence);
                evidence.put("verificationLevel", comparison.level());
                evidence.set("baselineDebt", comparison.debt());
            }
            evidence = (ObjectNode) sanitizeNode(evidence);
            AgentCheckEvidence.requireAcceptable(evidence);
            return json.writeValueAsString(evidence);
        } catch (IOException failure) {
            throw new AgentException("TOOL_PROTOCOL", "Cannot encode test evidence", failure);
        }
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
        for (String suffix : List.of("frontend-check", "frontend-prepare", "frontend-proxy", "check", "prepare", "agent", "capture", "seed", "proxy")) {
            stopContainer(name(id, suffix));
        }
    }

    @Override
    public void cleanup(String id) {
        stop(id);
        for (String suffix : List.of("frontend-check", "frontend-prepare", "frontend-proxy", "check", "prepare", "agent", "capture", "seed", "proxy")) {
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
