package com.ayywl.delveforge.infrastructure.workspace;

import com.ayywl.delveforge.application.repositoryanalysis.secret.RepositorySecretPolicy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Git 保留与模型/执行/修改能力分开；未知敏感资源留在主机，不以文件名或扫描未命中证明安全。 */
public final class RepositoryContentPolicy {
    public enum Kind { SOURCE, PUBLIC_RESOURCE, PRESERVED_ONLY }

    public record Capability(Kind kind, Map<String, String> settings) {
        public Capability {
            settings = Map.copyOf(settings);
        }

        public boolean preserved() { return true; }
        public boolean modelVisible() { return kind == Kind.SOURCE; }
        public boolean executionAvailable() { return kind != Kind.PRESERVED_ONLY; }
        public boolean mutable() { return kind == Kind.SOURCE; }
    }

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|passwd|pwd|secret|token|credential|api.?key|access.?key|private.?key|auth).*");
    private static final Set<String> NPM_BOOLEAN = Set.of("shamefully-hoist", "auto-install-peers",
            "strict-peer-dependencies", "fund", "audit", "update-notifier", "legacy-peer-deps", "install-links");
    private static final Pattern ORDINARY_SOURCE = Pattern.compile(".*\\.(java|kt|js|mjs|cjs|ts|tsx|jsx|vue|html)");
    private final RepositorySecretPolicy secrets;

    public RepositoryContentPolicy(RepositorySecretPolicy secrets) {
        this.secrets = secrets;
    }

    public Capability classify(String path, byte[] bytes) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        String text = utf8(bytes);
        if (name.startsWith(".env")) {
            return capability(text != null && secrets.mutationProtectedLines("resource.properties", text).isEmpty()
                    && environment(text) ? Kind.PUBLIC_RESOURCE : Kind.PRESERVED_ONLY);
        }
        if (name.equals(".npmrc")) {
            Map<String, String> settings = text == null
                    || !secrets.mutationProtectedLines("resource.properties", text).isEmpty() ? null : npm(text);
            return settings == null ? capability(Kind.PRESERVED_ONLY) : new Capability(Kind.PUBLIC_RESOURCE, settings);
        }
        if (name.endsWith(".pem") || name.endsWith(".crt") || name.endsWith(".cer")) {
            return capability(publicCertificate(bytes, text) ? Kind.PUBLIC_RESOURCE : Kind.PRESERVED_ONLY);
        }
        if (secrets.excludes(path)) {
            return capability(Kind.PRESERVED_ONLY);
        }
        if (text == null && ORDINARY_SOURCE.matcher(name).matches()) {
            // 未能在现有文本预算内识别的源码不能据此获得“未命中高风险”的执行资格。
            return capability(Kind.PRESERVED_ONLY);
        }
        if (text != null && (ORDINARY_SOURCE.matcher(name).matches()
                ? secrets.containsHighRiskCredential(path, text) : !secrets.mutationProtectedLines(path, text).isEmpty())) {
            return capability(Kind.PRESERVED_ONLY);
        }
        return capability(Kind.SOURCE);
    }

    private static Capability capability(Kind kind) {
        return new Capability(kind, Map.of());
    }

    /** 缺少可执行源码或构建描述时不能用剩余测试制造验证成功；资源缺失仍须显式记执行投影。 */
    public static boolean buildInput(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.matches(".*\\.(java|kt|js|mjs|cjs|ts|tsx|jsx|vue|html|gradle)")
                || Set.of("pom.xml", "package.json", "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "pnpm-workspace.yaml")
                        .contains(name) || path.startsWith(".mvn/") || path.contains("/.mvn/");
    }

    private static String utf8(byte[] bytes) {
        if (bytes.length > 262144) {
            return null;
        }
        try {
            String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return value.indexOf('\0') < 0 ? value : null;
        } catch (CharacterCodingException failure) {
            return null;
        }
    }

    private static boolean environment(String text) {
        if (text.length() > 65536) {
            return false;
        }
        Set<String> keys = new java.util.HashSet<>();
        for (String raw : text.split("\\R", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator < 1) {
                return false;
            }
            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,127}") || !keys.add(key)) {
                return false;
            }
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.isEmpty() || value.matches("\\$\\{[A-Za-z_][A-Za-z0-9_]{0,127}:?}")) {
                continue;
            }
            if (SENSITIVE_KEY.matcher(key).matches() || !publicValue(key, value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean publicValue(String key, String value) {
        String upper = key.toUpperCase(Locale.ROOT);
        if (value.matches("true|false|development|production|test|local|hash|history|[0-9]{1,5}")) {
            return true;
        }
        if (upper.matches(".*(URL|ORIGIN|TARGET|API|HOST|BASE|PATH|PREFIX).*")
                && value.matches("/(?!/)[A-Za-z0-9_./-]{0,255}") && !value.contains("..")) {
            return true;
        }
        if (upper.matches(".*(URL|ORIGIN|TARGET|API|HOST|BASE).*") && value.length() <= 512) {
            try {
                URI uri = URI.create(value);
                return uri.getScheme() != null && Set.of("http", "https").contains(uri.getScheme()) && uri.getHost() != null
                        && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                        && (uri.getRawPath() == null || uri.getRawPath().matches("[A-Za-z0-9_./-]*"));
            } catch (IllegalArgumentException failure) {
                return false;
            }
        }
        return false;
    }

    private static Map<String, String> npm(String text) {
        if (text.length() > 16384) {
            return null;
        }
        Map<String, String> settings = new LinkedHashMap<>();
        for (String raw : text.split("\\R", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            String[] field = line.split("=", 2);
            if (field.length != 2) {
                return null;
            }
            String key = field[0].strip();
            String value = field[1].strip();
            boolean allowed = key.equals("registry") && value.matches(
                    "https://registry\\.(npmjs\\.org|npmmirror\\.com|npm\\.taobao\\.org|yarnpkg\\.com)/?")
                    || NPM_BOOLEAN.contains(key) && Set.of("true", "false").contains(value)
                    || Set.of("ignore-scripts", "strict-ssl", "verify-store-integrity").contains(key)
                            && value.equals("true");
            if (!allowed || settings.putIfAbsent(key, value) != null) {
                return null;
            }
        }
        return settings;
    }

    private static boolean publicCertificate(byte[] bytes, String text) {
        if (text == null || !text.strip().matches(
                "(?s)(-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\r\\n]+-----END CERTIFICATE-----\\s*)+")) {
            return false;
        }
        try {
            return !CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(bytes)).isEmpty();
        } catch (java.security.cert.CertificateException failure) {
            return false;
        }
    }
}
