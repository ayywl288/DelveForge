package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.port.workspace.RepositoryCompatibility;
import com.ayywl.delveforge.application.port.workspace.RepositoryCompatibility.Issue;
import com.ayywl.delveforge.application.port.workspace.WorkspaceException;
import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** pinned tree 的唯一兼容性判定；调用者可以读取报告，不能据此推断使用授权或验证通过。 */
public final class GitRepositoryCompatibility {
    public static final String POLICY = "maven-generated-v1";
    public static final long MAX_SOURCE_BYTES = 134217728L;
    public static final int MAX_BASELINE_FILE_BYTES = 16777216;
    public static final int MAX_FILES = 10000;
    public static final int EDITABLE_FILE_BYTES = 262144;
    private final RepositorySecretPolicy secrets;

    public record Entry(String mode, String objectId, long size) {
    }

    public record Inspection(RepositoryCompatibility report, Map<String, Entry> entries, Map<String, byte[]> poms,
            Map<String, RepositoryContentPolicy.Capability> resources) {
        public Inspection {
            entries = Map.copyOf(entries);
            poms = Map.copyOf(poms);
            resources = Map.copyOf(resources);
        }
    }

    public GitRepositoryCompatibility(RepositorySecretPolicy secrets) {
        this.secrets = secrets;
    }

    public Inspection inspect(Path repository, String revision) {
        if (revision == null || !revision.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Compatibility requires a full pinned Git revision");
        }
        String commit = CompatibilityGit.text(repository, null, Map.of(), "rev-parse", "--verify", revision + "^{commit}");
        if (!revision.equals(commit)) {
            throw new WorkspaceException("Pinned repository revision is unavailable");
        }
        String listing = new String(CompatibilityGit.run(repository, null, Map.of(),
                "ls-tree", "-r", "-l", "-z", revision), StandardCharsets.UTF_8);
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, byte[]> poms = new LinkedHashMap<>();
        List<Issue> issues = new ArrayList<>();
        String storage = CompatibilityGit.text(repository, null, Map.of(), "count-objects", "-v");
        long objectKiB = 0;
        for (String line : storage.lines().toList()) {
            if (line.startsWith("alternate:")) {
                issues.add(block("GIT_OBJECT_STORAGE", "", "仓库依赖外部 Git 对象存储，无法建立独立准备预算。"));
            }
            String[] field = line.split(": ", 2);
            if (field.length == 2 && Set.of("size", "size-pack", "size-garbage").contains(field[0])) {
                objectKiB += Long.parseLong(field[1]);
            }
        }
        if (objectKiB > 524288) {
            issues.add(block("GIT_OBJECT_STORAGE", "", "Git 对象存储超过 512 MiB 的隔离克隆预算。"));
        }
        Set<String> folded = new HashSet<>();
        Map<String, String> prefixes = new LinkedHashMap<>();
        long bytes = 0;
        for (String line : listing.split("\u0000")) {
            if (line.isEmpty()) {
                continue;
            }
            int tab = line.indexOf('\t');
            String path = line.substring(tab + 1);
            String[] meta = line.substring(0, tab).trim().split("\\s+");
            boolean prefixConflict = false;
            String prefix = "";
            for (String part : path.split("/")) {
                prefix = prefix.isEmpty() ? part : prefix + "/" + part;
                String previous = prefixes.putIfAbsent(prefix.toLowerCase(Locale.ROOT), prefix);
                prefixConflict |= previous != null && !previous.equals(prefix);
            }
            if (!safePath(path) || prefixConflict || !folded.add(path.toLowerCase(Locale.ROOT))) {
                if (secrets.excludes(path)) {
                    issues.add(new Issue("REQUIRES_EXPLICIT_USER_DECISION", "PROTECTED_CREDENTIAL_PATH", path,
                            "仓库包含受保护的凭据路径；请在保留原资产的前提下明确处理方式。", true));
                } else {
                    issues.add(block("GIT_PATH_UNSUPPORTED", path, "路径不安全或存在大小写冲突；请明确仓库路径结构。"));
                }
                continue;
            }
            if (meta.length != 4 || !Set.of("100644", "100755").contains(meta[0]) || !meta[1].equals("blob")) {
                issues.add(block("GIT_TYPE_UNSUPPORTED", path, "软链接、子模块或特殊 Git 条目需要明确支持。"));
                continue;
            }
            if (!meta[3].matches("[0-9]+")) {
                throw new WorkspaceException("Pinned Git blob metadata is unavailable");
            }
            long size = Long.parseLong(meta[3]);
            entries.put(path, new Entry(meta[0], meta[2], size));
            bytes += size;
            if (size > MAX_BASELINE_FILE_BYTES || bytes > MAX_SOURCE_BYTES || entries.size() > MAX_FILES) {
                issues.add(block("SOURCE_LIMIT", path, "源码快照超过有界预算：单文件 16 MiB、总量 128 MiB、10000 文件。"));
                break;
            }
            if (path.equals("pom.xml") || path.endsWith("/pom.xml")) {
                if (size > EDITABLE_FILE_BYTES) {
                    issues.add(new Issue("REQUIRES_EXPLICIT_USER_DECISION", "MAVEN_CONFIGURATION", path,
                            "POM 超过静态解析预算；环境预检将单独诊断构建能力。", false));
                } else {
                    poms.put(path, blob(repository, meta[2]));
                }
            }
        }
        Set<String> rawPomPaths = Set.copyOf(poms.keySet());
        Map<String, byte[]> buildPoms = new LinkedHashMap<>();
        for (var pom : poms.entrySet()) {
            if (!pom.getKey().startsWith("src/") && !pom.getKey().contains("/src/")
                    && generatedRoot(pom.getKey(), rawPomPaths) == null) {
                buildPoms.put(pom.getKey(), pom.getValue());
            }
        }
        MavenRepositoryLayout layout = null;
        try {
            layout = MavenRepositoryLayout.inspect(buildPoms);
        } catch (MavenRepositoryLayout.Unsupported failure) {
            // 构建布局/JDK 不是 Git 安全准入；原生命令负责报告实际构建能力。
            issues.add(new Issue("REQUIRES_EXPLICIT_USER_DECISION", failure.code(), "",
                    failure.getMessage() + " Agent 将读取仓库指引并通过原生命令确认构建能力。", false));
        }
        Map<String, byte[]> modulePoms = new LinkedHashMap<>(buildPoms);
        List<String> generated = new ArrayList<>();
        List<String> immutable = new ArrayList<>();
        Map<String, RepositoryContentPolicy.Capability> resources = new LinkedHashMap<>();
        RepositoryContentPolicy contentPolicy = new RepositoryContentPolicy(secrets);
        int textFiles = 0;
        int opaqueFiles = 0;
        if (issues.stream().noneMatch(issue -> issue.code().equals("SOURCE_LIMIT"))) {
            for (var item : entries.entrySet()) {
                String path = item.getKey();
                Entry entry = item.getValue();
                if (path.equals(".mvn/extensions.xml") || path.endsWith("/.mvn/extensions.xml")) {
                    issues.add(new Issue("REQUIRES_EXPLICIT_USER_DECISION", "MAVEN_EXTENSION_UNSUPPORTED", path,
                            "项目使用 Maven 扩展；不影响安全保留，执行前需核对受控环境。", false));
                }
                if (modulePoms.containsKey(path)) {
                    String pom = utf8(modulePoms.get(path));
                    if (pom != null && pom.matches("(?s).*<(repositories|pluginRepositories)(\\s[^>]*)?>.*")) {
                        issues.add(new Issue("REQUIRES_EXPLICIT_USER_DECISION", "CUSTOM_DEPENDENCY_REPOSITORY", path,
                                "声明了额外依赖仓库；受控下载仍仅允许 Maven Central，私有凭据与仓库访问不会自动启用。", false));
                    }
                    if (pom != null && pom.matches("(?s).*<systemPath(\\s[^>]*)?>.*")) {
                        issues.add(new Issue("REQUIRES_EXPLICIT_USER_DECISION", "LOCAL_BUILD_PREREQUISITE", path,
                                "构建依赖本地 systemPath；需要明确受控容器中可用的依赖，不自动改写 POM。", false));
                    }
                }
                if (path.equals(".mvn/maven.config") || path.endsWith("/.mvn/maven.config")) {
                    String config = utf8(blob(repository, entry.objectId()));
                    if (config == null || config.matches("(?s).*(--(settings|global-settings|file|toolchains|activate-profiles)"
                            + "|(^|\\s)-[Psfgt]|maven\\.compiler\\.(release|source|target)|java\\.version).*")) {
                        issues.add(new Issue("REQUIRES_EXPLICIT_USER_DECISION", "MAVEN_LOCAL_CONFIGURATION", path,
                                "项目 Maven 参数改变入口、profile、JDK 或外部配置；需要明确受控构建选择。", false));
                    }
                }
                String root = generatedRoot(path, modulePoms.keySet());
                if (root != null) {
                    if (provenGenerated(repository, path, root, entries)) {
                        generated.add(path);
                    } else {
                        issues.add(new Issue("REQUIRES_EXPLICIT_USER_DECISION", "GENERATED_OUTPUT_AMBIGUITY", path,
                                "target 中包含无法证明为生成物的内容；保留文件并明确其用途。", true));
                    }
                    continue;
                }
                byte[] raw = entry.size() <= EDITABLE_FILE_BYTES || secrets.excludes(path)
                        ? blob(repository, entry.objectId()) : null;
                if (path.equals(".gitattributes") || path.endsWith("/.gitattributes")) {
                    String attributes = raw == null ? null : utf8(raw);
                    if (attributes == null || attributes.matches(
                            "(?s).*(filter=|export-ignore|export-subst|working-tree-encoding|\\bident\\b).*")) {
                        issues.add(block("GIT_ATTRIBUTES_UNSUPPORTED", path, "自定义 Git 内容转换需要明确支持。"));
                    }
                }
                RepositoryContentPolicy.Capability capability = raw == null ? null : contentPolicy.classify(path, raw);
                if (capability != null && capability.kind() != RepositoryContentPolicy.Kind.SOURCE) {
                    resources.put(path, capability);
                    immutable.add(path);
                    issues.add(new Issue("SUPPORT_WITH_BOUNDED_ADAPTATION", capability.executionAvailable()
                            ? "PUBLIC_READ_ONLY_RESOURCE" : "PRESERVED_ONLY_RESOURCE", path,
                            capability.executionAvailable() ? "已校验公开资源；保留原字节，执行与模型可读，真实只读挂载禁止修改。"
                                    : "仅保留在可信 Git；不提供模型或执行容器。依赖它的检查可能不可用或失败。", false));
                    continue;
                }
                if (entry.size() > EDITABLE_FILE_BYTES) {
                    opaqueFiles++;
                    immutable.add(path);
                    continue;
                }
                String content = utf8(raw);
                if (content == null) {
                    opaqueFiles++;
                    immutable.add(path);
                } else {
                    textFiles++;
                    if (!secrets.sanitize(List.of(new RepositorySourceFile(path, content)))
                            .material().getFirst().content().equals(content)) {
                        issues.add(new Issue("SUPPORT_WITH_BOUNDED_ADAPTATION", "SANITIZED_MODEL_MATERIAL", path,
                                "分析材料保持既有保守净化；执行和修改能力由内容策略及行级保护另行约束。", false));
                    }
                }
            }
        }
        boolean blocked = issues.stream().anyMatch(Issue::blocking);
        if (!generated.isEmpty()) {
            issues.add(new Issue("SAFE_TO_AUTOMATE", "GENERATED_OUTPUT_PREPARATION", "",
                    "将在隔离副本中移除已证明的 Maven 生成物，保留原仓库和完整来源记录。", false));
        }
        RepositoryCompatibility report = new RepositoryCompatibility(revision,
                blocked ? "BLOCKED" : generated.isEmpty() ? "COMPATIBLE" : "PREPARATION_REQUIRED", POLICY,
                layout == null ? null : layout.directory(), modulePoms.keySet().stream()
                        .map(path -> path.equals("pom.xml") ? "." : path.substring(0, path.length() - 8)).sorted().toList(),
                layout == null ? null : layout.recommendedJdk(), bytes, entries.size(), textFiles, opaqueFiles,
                generated.stream().sorted().toList(), immutable.stream().sorted().toList(), issues);
        return new Inspection(report, entries, modulePoms, resources);
    }

    public static boolean safePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.length() > 512
                || path.contains("\\") || path.contains(":") || path.chars().anyMatch(c -> c < 32 || c == 127)
                || path.contains("\"")) {
            return false;
        }
        String[] parts = path.split("/");
        for (int position = 0; position < parts.length; position++) {
            String part = parts[position];
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(" ") || part.endsWith(".")
                    || Set.of(".git", ".hg", ".svn", ".ssh", ".aws", ".gnupg", ".m2")
                            .contains(part.toLowerCase(Locale.ROOT))
                    || part.toLowerCase(Locale.ROOT).startsWith(".env") && position < parts.length - 1
                    || Set.of(".npmrc", ".pypirc", ".netrc").contains(part.toLowerCase(Locale.ROOT))
                            && position < parts.length - 1
                    || part.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?")) {
                return false;
            }
        }
        return true;
    }

    private static Issue block(String code, String path, String action) {
        return new Issue("GENUINELY_UNSUPPORTED", code, path, action, true);
    }

    static String generatedRoot(String path, Set<String> poms) {
        String parent = "";
        for (String part : path.split("/")) {
            if (part.equals("target") && poms.contains(parent + "pom.xml")
                    && !poms.contains(parent + "target/pom.xml")) {
                return parent;
            }
            parent += part + "/";
        }
        return null;
    }

    private static boolean provenGenerated(Path repository, String path, String root, Map<String, Entry> entries) {
        String relative = path.substring((root + "target/").length());
        Entry entry = entries.get(path);
        if (relative.startsWith("classes/") || relative.startsWith("test-classes/")) {
            boolean test = relative.startsWith("test-classes/");
            String name = relative.substring(relative.indexOf('/') + 1);
            String sourceRoot = root + (test ? "src/test/" : "src/main/");
            Entry resource = entries.get(sourceRoot + "resources/" + name);
            if (resource != null && resource.objectId().equals(entry.objectId())) {
                return true;
            }
            if (name.endsWith(".class")) {
                String owner = name.substring(0, name.length() - 6).split("\\$")[0];
                if (entries.containsKey(sourceRoot + "java/" + owner + ".java")) {
                    return compiledClass(blob(repository, entry.objectId()), name.substring(0, name.length() - 6));
                }
            }
            return false;
        }
        if (relative.matches("maven-status/maven-compiler-plugin/(compile|testCompile)/[A-Za-z0-9_-]+/(createdFiles|inputFiles)\\.lst")) {
            String text = utf8(blob(repository, entry.objectId()));
            if (text == null) {
                return false;
            }
            boolean test = relative.contains("/testCompile/");
            String base = root + (test ? "src/test/java/" : "src/main/java/");
            for (String line : text.split("\\R")) {
                if (line.isBlank()) {
                    continue;
                }
                String normalized = line.trim().replace('\\', '/');
                if (relative.endsWith("inputFiles.lst")) {
                    if (entries.keySet().stream().noneMatch(p -> p.startsWith(base)
                            && (normalized.endsWith("/" + p.substring(root.length())) || normalized.equals(p)))) {
                        return false;
                    }
                } else if (!entries.containsKey(root + "target/" + (test ? "test-classes/" : "classes/") + normalized)) {
                    return false;
                }
            }
            return true;
        }
        if (relative.matches("(surefire|failsafe)-reports/TEST-[^/]+\\.xml")) {
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                var parser = factory.newDocumentBuilder();
                parser.setErrorHandler(new DefaultHandler() {
                    @Override
                    public void fatalError(SAXParseException error) throws SAXException {
                        throw error;
                    }
                });
                Element report = parser.parse(new ByteArrayInputStream(blob(repository, entry.objectId())))
                        .getDocumentElement();
                var suites = report.getTagName().equals("testsuite") ? List.of(report)
                        : report.getTagName().equals("testsuites")
                                ? java.util.stream.IntStream.range(0, report.getElementsByTagName("testsuite").getLength())
                                        .mapToObj(i -> (Element) report.getElementsByTagName("testsuite").item(i)).toList()
                                : List.<Element>of();
                return !suites.isEmpty() && suites.stream().allMatch(suite -> {
                    String name = suite.getAttribute("name").split("\\$")[0].replace('.', '/');
                    return suite.getAttribute("tests").matches("[0-9]+")
                            && suite.getAttribute("failures").matches("[0-9]+")
                            && suite.getAttribute("errors").matches("[0-9]+")
                            && entries.containsKey(root + "src/test/java/" + name + ".java");
                });
            } catch (Exception failure) {
                return false;
            }
        }
        if (relative.matches("(surefire|failsafe)-reports/[^/]+\\.txt")) {
            String text = utf8(blob(repository, entry.objectId()));
            String xml = root + "target/" + relative.substring(0, relative.lastIndexOf('/') + 1) + "TEST-"
                    + relative.substring(relative.lastIndexOf('/') + 1, relative.length() - 4) + ".xml";
            return text != null && text.matches("(?s).*Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+.*")
                    && entries.containsKey(xml) && provenGenerated(repository, xml, root, entries);
        }
        return false;
    }

    private static boolean compiledClass(byte[] bytes, String expectedName) {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != 0xcafebabe) {
                return false;
            }
            input.readInt();
            int count = input.readUnsignedShort();
            Object[] pool = new Object[count];
            for (int i = 1; i < count; i++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> pool[i] = input.readUTF();
                    case 7 -> pool[i] = input.readUnsignedShort();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 5, 6 -> {
                        input.skipNBytes(8);
                        i++;
                    }
                    case 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 15 -> input.skipNBytes(3);
                    default -> {
                        return false;
                    }
                }
            }
            input.readUnsignedShort();
            int type = input.readUnsignedShort();
            return type < pool.length && pool[type] instanceof Integer name
                    && name < pool.length && expectedName.equals(pool[name]);
        } catch (Exception failure) {
            return false;
        }
    }

    private static byte[] blob(Path repository, String id) {
        return CompatibilityGit.run(repository, null, Map.of(), "cat-file", "blob", id);
    }

    private static String utf8(byte[] bytes) {
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return value.indexOf('\0') >= 0 ? null : value;
        } catch (CharacterCodingException failure) {
            return null;
        }
    }
}
