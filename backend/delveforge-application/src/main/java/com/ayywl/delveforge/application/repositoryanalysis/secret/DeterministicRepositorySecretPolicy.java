package com.ayywl.delveforge.application.repositoryanalysis.secret;

import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则写死在代码里的凭据政策（ADR-0006）。
 *
 * <h2>为什么不做成可配置</h2>
 *
 * <p>可配置就意味着可以被静默放宽：改一行 yaml 就能让一条规则失效，而这次分析走的仍然是
 * 「看起来正常」的路径。ADR-0006 明确要求规则是**代码定义的确定性政策**，
 * 它的每一次变化都必须经过代码审查与测试，而不是运维调参。
 *
 * <h2>规则只有两类，都是枚举 + 形态判断</h2>
 *
 * <pre>
 * 路径   整份文件按定义就是凭据载体        → 不读（{@link #excludes}）
 * 内容   值处在凭据位置或形态像已知令牌    → 替换（{@link #sanitize}）
 * </pre>
 *
 * <p>两类都是**已知形态的集合**，因此覆盖范围是可以陈述的：命中什么就保护什么。
 * 未知格式、拼接构造、编码分片都不在其中——这写在 ADR-0006 的「不保证」一节里。
 *
 * <h2>替换保留结构</h2>
 *
 * <p>键名、缩进、行数与周边内容都保留，只把值换成 {@link #REDACTION_MARKER}。
 * 这样模型仍然看得出「这里配了一个口令」，而看不到口令本身——分析价值的损失因此很小。
 * 私钥块这类整块凭据没有「键名」可保留，整块替换。
 *
 * <h2>不判断真假</h2>
 *
 * <p>处于凭据位置的值一律替换，包括 {@code "your-password-here"}、{@code ${DB_PASSWORD}}、
 * {@code changeme} 这类看起来是示例的值。判定「这个值是不是真凭据」本身不可能可靠，
 * 试图区分只会让行为不可预测；统一替换让结果只依赖**位置与形态**，因此可以写出确定的测试。
 *
 * <h2>它是纯函数</h2>
 *
 * <p>没有 logger（因此边界自己不可能把看到的内容写进日志）、不持有任何能力、
 * 不修改入参。同样的输入永远得到同样的输出。
 */
public final class DeterministicRepositorySecretPolicy implements RepositorySecretPolicy {

    /** 被替换掉的凭据字面量的占位文本。刻意不像任何真实取值，也不含 `$` 与反斜杠。 */
    public static final String REDACTION_MARKER = "[redacted-credential]";

    /** 文件名精确等于其中之一即排除（按小写比较）。 */
    private static final Set<String> EXCLUDED_EXACT_NAMES =
            Set.of(".env", ".npmrc", ".pypirc", ".netrc");

    /** 文件名以其中之一结尾即排除（按小写比较）。 */
    private static final Set<String> EXCLUDED_EXTENSIONS =
            Set.of(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore");

    /**
     * 私钥文件名：{@code id_rsa} 及其变体（{@code id_rsa_old}、{@code id_rsa.bak} …）。
     *
     * <p>{@code .pub} 是公钥，不是凭据，因此由 {@link #isPrivateKeyFileName} 单独放行。
     */
    private static final Pattern PRIVATE_KEY_FILE_NAME =
            Pattern.compile("id_(?:rsa|dsa|ecdsa|ed25519)(?:[._-].*)?");

    /** 凭据字段名：以凭据词结尾的标识符（{@code password}、{@code DB_PASSWORD}、{@code authToken} …）。 */
    private static final String CREDENTIAL_KEY =
            "[A-Za-z0-9_\\-]*(?:credentials|credential|password|passwd|pwd|secret|token"
                    + "|api[_-]?key|access[_-]?key|private[_-]?key|client[_-]?secret"
                    + "|auth[_-]?token)";

    /**
     * 内容规则，按顺序应用。
     *
     * <p>顺序有意义：私钥块最大，先整体吃掉；userinfo（{@code user:password@host}）在
     * 赋值规则之前，避免连接串被赋值规则按「键名 + 值」的半截形态处理。
     */
    private static final List<ContentRule> CONTENT_RULES = List.of(
            // 1. PEM 私钥块（含 OPENSSH / RSA / EC / ENCRYPTED 等形态）：整块替换
            new ContentRule(Pattern.compile(
                    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?"
                            + "-----END [A-Z ]*PRIVATE KEY-----"), REDACTION_MARKER),

            // 2. 已知令牌 / Key 前缀
            new ContentRule(Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), REDACTION_MARKER),
            new ContentRule(Pattern.compile(
                    "\\b(?:gh[pousr]|github_pat)_[A-Za-z0-9_]{16,}\\b"), REDACTION_MARKER),
            new ContentRule(Pattern.compile(
                    "\\bsk-[A-Za-z0-9_\\-]{16,}\\b"), REDACTION_MARKER),
            new ContentRule(Pattern.compile(
                    "\\bxox[baprs]-[A-Za-z0-9\\-]{10,}\\b"), REDACTION_MARKER),
            new ContentRule(Pattern.compile(
                    "\\bAIza[0-9A-Za-z_\\-]{30,}\\b"), REDACTION_MARKER),
            new ContentRule(Pattern.compile(
                    "\\bya29\\.[0-9A-Za-z_\\-]{20,}\\b"), REDACTION_MARKER),
            new ContentRule(Pattern.compile(
                    "\\beyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\b"),
                    REDACTION_MARKER),

            // 3. Authorization 头的取值：保留头名与方案名，替换凭据部分
            new ContentRule(Pattern.compile(
                    "(?i)(authorization[\"']?\\s*[:=]\\s*[\"']?(?:bearer|basic)\\s+)"
                            + "[A-Za-z0-9._~+/=\\-]+"), "$1" + REDACTION_MARKER),

            // 4. 连接串 / URI 里的 userinfo：保留用户名与主机，替换口令
            new ContentRule(Pattern.compile(
                    "([A-Za-z][A-Za-z0-9+.\\-]*://[^/\\s:@\"']*):([^/\\s@\"']+)@"),
                    "$1:" + REDACTION_MARKER + "@"),

            // 5. 凭据位置的赋值：保留键名与分隔符，替换取值（含引号内的整体）
            new ContentRule(Pattern.compile(
                    "(?im)((?:^|[^A-Za-z0-9_\\-])[\"']?" + CREDENTIAL_KEY
                            + "[\"']?\\s*[:=]\\s*)"
                            + "(\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\"'\\s,;#}\\]]+)"),
                    "$1" + REDACTION_MARKER));

    // ------------------------------------------------------------------ 路径

    @Override
    public boolean excludes(String relativePath) {
        if (relativePath == null) {
            throw new IllegalArgumentException("判断路径是否排除必须给出相对路径");
        }

        String name = fileNameOf(relativePath).toLowerCase(Locale.ROOT);
        return EXCLUDED_EXACT_NAMES.contains(name)
                || name.startsWith(".env.")
                || name.endsWith(".env")
                || hasExcludedExtension(name)
                || isPrivateKeyFileName(name)
                || isAwsCredentialsFile(relativePath);
    }

    /**
     * 末段文件名。
     *
     * <p>只取末段参与判定，避免 {@code notes/about.env.md} 这类「只是名字里含有 {@code .env}」
     * 被误判成凭据文件。
     */
    private static String fileNameOf(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        return lastSlash < 0 ? normalized : normalized.substring(lastSlash + 1);
    }

    /** 按**整条末段**匹配扩展名，因此 {@code notes.keyword} 不会被当成 {@code .key}。 */
    private static boolean hasExcludedExtension(String lowerCaseName) {
        for (String extension : EXCLUDED_EXTENSIONS) {
            if (lowerCaseName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 私钥文件名，但**放行公钥**。
     *
     * <p>{@code id_rsa.pub} 是公钥，公开它不构成泄漏，把它一并排除只会让一个正常文件
     * 从分析里消失。变体（{@code id_rsa_old}）仍然排除——备份的私钥还是私钥。
     */
    private static boolean isPrivateKeyFileName(String lowerCaseName) {
        if (!PRIVATE_KEY_FILE_NAME.matcher(lowerCaseName).matches()) {
            return false;
        }
        return !lowerCaseName.endsWith(".pub");
    }

    /**
     * {@code .aws/credentials}：按**路径分段**判定，不是按子串。
     *
     * <p>这样 {@code my.aws/credentials-backup} 或 {@code aws/credentials.md} 不会被误伤。
     */
    private static boolean isAwsCredentialsFile(String relativePath) {
        String[] segments = relativePath.replace('\\', '/').split("/");
        if (segments.length < 2) {
            return false;
        }
        int last = segments.length - 1;
        return ".aws".equalsIgnoreCase(segments[last - 1])
                && "credentials".equalsIgnoreCase(segments[last]);
    }

    // ------------------------------------------------------------------ 内容

    @Override
    public SanitizedRepositoryMaterial sanitize(List<RepositorySourceFile> files) {
        if (files == null) {
            throw new IllegalArgumentException("凭据边界必须给出材料");
        }

        List<RepositorySourceFile> sanitized = new ArrayList<>(files.size());
        int replacedSpans = 0;
        for (RepositorySourceFile file : files) {
            if (file == null) {
                throw new IllegalArgumentException("凭据边界收到的材料不能包含 null");
            }
            Replacement replacement = replaceIn(file.content());
            replacedSpans += replacement.count();
            // 相对路径原样保留：它是 Evidence 定位文件的依据，改了就不可追溯。
            sanitized.add(new RepositorySourceFile(file.relativePath(), replacement.text()));
        }
        return new SanitizedRepositoryMaterial(sanitized, replacedSpans);
    }

    private static Replacement replaceIn(String content) {
        String text = content;
        int count = 0;
        for (ContentRule rule : CONTENT_RULES) {
            Replacement applied = rule.applyTo(text);
            text = applied.text();
            count += applied.count();
        }
        return new Replacement(text, count);
    }

    /** 一条内容规则：匹配什么、替换成什么。 */
    private record ContentRule(Pattern pattern, String replacement) {

        /**
         * 应用本规则，返回替换后的文本与替换处数。
         *
         * <p>用 {@link Matcher#appendReplacement} 的标准写法：它自己会把两次匹配之间的原文
         * 一并追加，因此这里**不要**再手动补一次——那会把原文按匹配次数复制若干份。
         */
        Replacement applyTo(String text) {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                return new Replacement(text, 0);
            }
            StringBuilder out = new StringBuilder(text.length());
            int count = 0;
            do {
                matcher.appendReplacement(out, replacement);
                count++;
            } while (matcher.find());
            matcher.appendTail(out);
            return new Replacement(out.toString(), count);
        }
    }

    /** 一次替换的结果。 */
    private record Replacement(String text, int count) {
    }
}
