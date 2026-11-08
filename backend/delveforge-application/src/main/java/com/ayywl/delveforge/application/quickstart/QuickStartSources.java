package com.ayywl.delveforge.application.quickstart;

import com.ayywl.delveforge.application.port.workspace.WorkspaceEntry;
import com.ayywl.delveforge.application.port.workspace.WorkspaceReadPort;
import com.ayywl.delveforge.application.port.workspace.WorkspaceRef;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretBoundaryException;
import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Quick Start 专用的可信读取与出站净化；不改变通用模型读取或执行材料能力。 */
final class QuickStartSources {
    private static final int MAX_FILES = 32;
    private static final int MAX_CONTEXT = 48000;
    private static final int MAX_ENV_VARIABLES = 256;
    private static final int MAX_ENV_CHARACTERS = 12000;
    private static final Pattern LINK = Pattern.compile("\\[[^]\\r\\n]*]\\(<?([^\\s)>]+)>?(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern ENV_KEY = Pattern.compile("(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=.*");
    private static final Pattern CREDENTIAL_BLOCK = Pattern.compile(
            "(?im)^.*(?:password|passwd|pwd|secret|token|credentials?|api[_-]?key|access[_-]?key|private[_-]?key)"
                    + "[\"']?[\\t ]*:[\\t ]*(?:[|>#][^\\r\\n]*|\"[^\"\\r\\n]*|'[^'\\r\\n]*)?$");
    private static final Set<String> MANIFESTS = Set.of("package.json", "pom.xml", "mvnw", "mvnw.cmd",
            "maven-wrapper.properties", "gradlew", "gradlew.bat", "gradle-wrapper.properties", "build.gradle",
            "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradle.properties", "go.mod",
            "cargo.toml", "pyproject.toml", "requirements.txt", "pipfile", "gemfile", "composer.json",
            "makefile", "cmakelists.txt", ".nvmrc", ".node-version", ".python-version", "pnpm-workspace.yaml",
            "package-lock.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock");
    private final WorkspaceReadPort workspace;
    private final RepositorySecretPolicy secrets;

    QuickStartSources(WorkspaceReadPort workspace, RepositorySecretPolicy secrets) {
        this.workspace = workspace;
        this.secrets = secrets;
    }

    record Material(String projectName, String text, List<String> paths) {
    }

    Material collect(QuickStartStore.Basis basis) {
        WorkspaceRef ref = new WorkspaceRef(basis.location());
        List<WorkspaceEntry> tree = workspace.listEntries(ref, basis.revision(), "", 8).stream()
                .filter(entry -> !entry.directory() && entry.size() <= 65536 && allowedPath(entry.relativePath()))
                .sorted(Comparator.comparingInt((WorkspaceEntry entry) -> priority(entry.relativePath()))
                        .thenComparingInt(entry -> entry.relativePath().split("/").length)
                        .thenComparing(WorkspaceEntry::relativePath)).toList();
        Map<String, WorkspaceEntry> available = new LinkedHashMap<>();
        tree.forEach(entry -> available.put(entry.relativePath(), entry));
        List<String> queue = new ArrayList<>(tree.stream().filter(entry -> useful(entry.relativePath()))
                .map(WorkspaceEntry::relativePath).toList());
        Map<String, String> selected = new LinkedHashMap<>();
        StringBuilder material = new StringBuilder();
        int attempts = 0;
        for (int index = 0; index < queue.size() && selected.size() < MAX_FILES && attempts < 64; index++) {
            String path = queue.get(index);
            if (selected.containsKey(path)) {
                continue;
            }
            attempts++;
            // 仅此处读取可信原文；完整净化先于截断，原文不进入日志、存储或 AiGateway。
            String content = safeContent(path, workspace.readFile(ref, basis.revision(), path));
            if (content == null || content.isBlank()) {
                continue;
            }
            if (rootReadme(path)) {
                Matcher links = LINK.matcher(content);
                int position = index + 1;
                while (links.find() && position <= index + 16) {
                    String linked = localDocument(links.group(1));
                    if (linked != null && available.containsKey(linked) && !selected.containsKey(linked)) {
                        queue.add(position++, linked);
                    }
                }
            }
            String header = "\n--- " + path + " ---\n";
            int remaining = Math.min(12000, MAX_CONTEXT - material.length() - header.length() - 24);
            if (remaining <= 0) {
                break;
            }
            String excerpt = content.length() <= remaining ? content : content.substring(0, remaining);
            // 不把截断的最后一行包装成完整命令。
            if (excerpt.length() < content.length()) {
                int newline = excerpt.lastIndexOf('\n');
                excerpt = (newline < 0 ? "" : excerpt.substring(0, newline)) + "\n[材料已截断]\n";
            }
            selected.put(path, excerpt);
            material.append(header).append(excerpt);
        }
        String fallback = basis.repositoryLocation().replace('\\', '/').replaceAll("/+$", "");
        fallback = fallback.substring(fallback.lastIndexOf('/') + 1);
        String identity = selected.entrySet().stream().filter(entry -> rootReadme(entry.getKey()))
                .flatMap(entry -> entry.getValue().lines()).filter(line -> line.matches("#\\s+.+"))
                .map(line -> line.replaceFirst("^#\\s+", "").replaceAll("\\s+#+$", "")
                        .replaceAll("\\[([^]]+)]\\([^)]*\\)", "$1").strip())
                .filter(line -> line.length() <= 160 && !line.contains("[redacted") && !line.contains("!["))
                .findFirst().orElse(fallback);
        return new Material(secrets.sanitizeOutput(identity), material.toString(), List.copyOf(selected.keySet()));
    }

    private boolean allowedPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (path.contains("\\") || path.contains("\n") || path.contains("\r") || path.length() > 240
                || !secrets.sanitizeOutput(path).equals(path)
                || lower.matches("(?:^|.*/)(?:node_modules|vendor|target|build|dist|\\.git|\\.aws|\\.ssh)(?:/.*)?")) {
            return false;
        }
        return name(path).equals(".env.example") || !secrets.excludes(path);
    }

    private String safeContent(String path, String raw) {
        if (raw.length() > 65536 || raw.indexOf('\0') >= 0 || raw.indexOf('\uFFFD') >= 0) {
            return null;
        }
        if (name(path).equals(".env.example")) {
            return environmentNames(raw);
        }
        // 多行配置凭据、私钥块和常见命令行口令无法靠单行键值净化保留结构，整份放弃。
        if (raw.matches("(?is).*-----BEGIN [A-Z ]*PRIVATE KEY-----.*")
                || CREDENTIAL_BLOCK.matcher(raw).find()
                || raw.matches("(?is).*(?:--(?:password|token|secret|requirepass|api-key)[=\\s\"']"
                        + "|mysql[^\\r\\n]*\\s-p[^\\s]).*")) {
            return null;
        }
        try {
            // Maven 等 XML 中的凭据元素不属于既有赋值语法；专用路径先移除其整个取值。
            String xmlSafe = raw.replaceAll("(?is)(<([\\w.-]*(?:password|passwd|pwd|secret|token|credentials?|apiKey))"
                    + "\\b[^>]*>).*?(</\\2\\s*>)", "$1[redacted-credential]$3");
            return secrets.sanitizeOutput(secrets.sanitizeSource(path, xmlSafe));
        } catch (RepositorySecretBoundaryException unsafe) {
            return null;
        }
    }

    private static String environmentNames(String raw) {
        String placeholder = "=<自行配置>\n";
        String truncated = "[变量列表已截断]\n";
        StringBuilder result = new StringBuilder(MAX_ENV_CHARACTERS);
        Set<String> names = new HashSet<>();
        // 不保留任何取值或注释；在追加前限额，避免高密度变量和长变量名放大中间分配。
        for (Iterator<String> lines = raw.lines().iterator(); lines.hasNext();) {
            Matcher match = ENV_KEY.matcher(lines.next().strip());
            if (!match.matches()) {
                continue;
            }
            String name = match.group(1);
            if (names.contains(name)) {
                continue;
            }
            if (names.size() >= MAX_ENV_VARIABLES
                    || result.length() + name.length() + placeholder.length() + truncated.length() > MAX_ENV_CHARACTERS) {
                result.append(truncated);
                break;
            }
            names.add(name);
            result.append(name).append(placeholder);
        }
        return result.toString();
    }

    private static String localDocument(String link) {
        try {
            URI uri = URI.create(link);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getQuery() != null) {
                return null;
            }
            String path = uri.normalize().getPath();
            return path != null && !path.startsWith("/") && !path.contains("..") && document(path) ? path : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean rootReadme(String path) {
        return !path.contains("/") && name(path).startsWith("readme");
    }

    private static boolean document(String path) {
        return path.toLowerCase(Locale.ROOT).matches(".*\\.(md|markdown|txt|rst)");
    }

    private static int priority(String path) {
        if (rootReadme(path)) {
            return 0;
        }
        if (document(path)) {
            return 1;
        }
        return name(path).contains("lock") ? 3 : 2;
    }

    private static boolean useful(String path) {
        String file = name(path);
        String lower = path.toLowerCase(Locale.ROOT);
        return file.startsWith("readme") || MANIFESTS.contains(file) || file.equals(".env.example")
                || file.startsWith("dockerfile") || file.matches("(docker-)?compose.*\\.ya?ml")
                || file.matches("application.*\\.(ya?ml|properties)") || file.startsWith("vite.config.")
                || document(path) && file.matches(".*(setup|develop|install|deploy|getting.started|quick.start|开发|安装|部署|启动|环境).*")
                || file.matches("(start|dev|run|serve|setup|install|bootstrap)([-.].*)?\\.(sh|ps1|cmd|bat)")
                || lower.matches("(?:.*/)?scripts/[^/]+\\.(sh|ps1|cmd|bat)");
    }
}
