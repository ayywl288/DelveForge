package com.ayywl.delveforge.application.repositoryanalysis.secret;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 凭据政策的规则集：路径判定与内容净化。
 *
 * <p>全部使用**合成值**，不含任何真实凭据。约定：
 *
 * <pre>
 * CANARY-…             只用于证明「它没有出现在输出里」的字面量
 * 结构断言             键名 / 缩进 / 行数 / 周边内容保留
 * </pre>
 */
class DeterministicRepositorySecretPolicyTest {

    private static final String MARKER = DeterministicRepositorySecretPolicy.REDACTION_MARKER;

    private final DeterministicRepositorySecretPolicy policy =
            new DeterministicRepositorySecretPolicy();

    // ------------------------------------------------------------------ 路径：排除

    @Test
    void excludesEnvFilesInEveryForm() {
        assertTrue(policy.excludes(".env"));
        assertTrue(policy.excludes(".env.local"));
        assertTrue(policy.excludes(".env.production"));
        assertTrue(policy.excludes("config/.env"));
        assertTrue(policy.excludes("config/.env.production"));
        assertTrue(policy.excludes("deploy/production.env"));
    }

    @Test
    void excludesPrivateKeyAndKeystoreExtensions() {
        assertTrue(policy.excludes("server.pem"));
        assertTrue(policy.excludes("certs/private.key"));
        assertTrue(policy.excludes("certs/keystore.p12"));
        assertTrue(policy.excludes("certs/bundle.pfx"));
        assertTrue(policy.excludes("certs/app.jks"));
        assertTrue(policy.excludes("certs/app.keystore"));
        assertTrue(policy.excludes("Certs/Server.PEM"), "扩展名按小写比较");
    }

    @Test
    void excludesPrivateKeyFilesIncludingVariants() {
        assertTrue(policy.excludes("id_rsa"));
        assertTrue(policy.excludes("keys/id_dsa"));
        assertTrue(policy.excludes("keys/id_ecdsa"));
        assertTrue(policy.excludes("keys/id_ed25519"));
        assertTrue(policy.excludes("keys/id_rsa_old"), "备份的私钥还是私钥");
        assertTrue(policy.excludes("keys/id_rsa.bak"));
    }

    /**
     * 公钥不是凭据。
     *
     * <p>把 {@code id_rsa.pub} 一并排除，只会让一个正常文件从分析里消失——
     * 而它恰恰是需要理解这个仓库时可能有用的一份材料。
     */
    @Test
    void doesNotExcludePublicKeys() {
        assertFalse(policy.excludes("id_rsa.pub"));
        assertFalse(policy.excludes("keys/id_ed25519.pub"));
    }

    @Test
    void excludesCredentialDotFiles() {
        assertTrue(policy.excludes(".npmrc"));
        assertTrue(policy.excludes(".pypirc"));
        assertTrue(policy.excludes(".netrc"));
        assertTrue(policy.excludes("home/.npmrc"));
    }

    @Test
    void excludesAwsCredentialsByPathSegment() {
        assertTrue(policy.excludes(".aws/credentials"));
        assertTrue(policy.excludes("home/me/.aws/credentials"));
    }

    /**
     * 不按子串匹配。
     *
     * <p>「名字里含有 {@code .env}」不是排除理由：只有末段正好是那个文件（或那个扩展名）
     * 才算。否则 {@code docs/about.env.md} 这类正常文档会被整份丢掉。
     */
    @Test
    void doesNotExcludeNamesThatMerelyContainThePattern() {
        assertFalse(policy.excludes("myenv"));
        assertFalse(policy.excludes("env"));
        assertFalse(policy.excludes(".envrc"));
        assertFalse(policy.excludes("docs/about.env.md"));
        assertFalse(policy.excludes("docs/notes.keyword"), "不是 .key 扩展名");
        assertFalse(policy.excludes("src/monkey.java"));
        assertFalse(policy.excludes("my.aws/credentials-backup"));
        assertFalse(policy.excludes("credentials"), "只有 .aws/ 目录下的 credentials 才是凭据文件");
        assertFalse(policy.excludes("aws/credentials.md"));
        assertFalse(policy.excludes("src/main/java/com/x/Api.java"));
        assertFalse(policy.excludes("pom.xml"));
        assertFalse(policy.excludes("src/main/resources/application.yml"));
    }

    @Test
    void rejectsNullPath() {
        assertThrows(IllegalArgumentException.class, () -> policy.excludes(null));
    }

    // ------------------------------------------------------------------ 内容：替换

    @Test
    void replacesWholePemPrivateKeyBlock() {
        String content = """
                # deploy notes
                -----BEGIN RSA PRIVATE KEY-----
                CANARY-PEM-BODY-0001
                CANARY-PEM-BODY-0002
                -----END RSA PRIVATE KEY-----
                after = "kept"
                """;

        SanitizedRepositoryMaterial result = policy.sanitize(List.of(file("notes.md", content)));

        assertFalse(result.material().get(0).content().contains("CANARY-PEM-BODY"),
                "私钥块内容必须整体消失");
        assertTrue(result.material().get(0).content().contains(MARKER));
        assertTrue(result.material().get(0).content().contains("# deploy notes"), "周边内容保留");
        assertTrue(result.material().get(0).content().contains("after = \"kept\""));
        assertEquals(1, result.replacedSpans());
    }

    @Test
    void replacesKnownTokenPrefixes() {
        String content = """
                aws = AKIAIOSFODNN7EXAMPLE
                gh = ghp_0123456789abcdefghijklmnopqrstuvwx
                oa = sk-abcdefghijklmnopqrstuvwxyz012345
                slack = xoxb-0123456789-abcdefghijklmn
                google = AIzaSyA0123456789abcdefghijklmnopqrstu
                """;

        String sanitized = sanitized(content);

        for (String canary : List.of("AKIAIOSFODNN7EXAMPLE", "ghp_0123456789abcdefghijklmnopqrstuvwx",
                "sk-abcdefghijklmnopqrstuvwxyz012345", "xoxb-0123456789-abcdefghijklmn",
                "AIzaSyA0123456789abcdefghijklmnopqrstu")) {
            assertFalse(sanitized.contains(canary), "令牌必须被替换: " + canary);
        }
        assertTrue(sanitized.contains("aws = " + MARKER));
        assertTrue(sanitized.contains("gh = " + MARKER));
    }

    @Test
    void replacesAuthorizationHeaderValue() {
        String yaml = """
                headers:
                  Authorization: Bearer CANARY-BEARER-TOKEN-0001
                  accept: application/json
                """;

        String sanitized = sanitized(yaml);

        assertFalse(sanitized.contains("CANARY-BEARER-TOKEN-0001"));
        assertTrue(sanitized.contains("Authorization: Bearer " + MARKER),
                "头名与方案名保留，只有凭据被替换: " + sanitized);
        assertTrue(sanitized.contains("accept: application/json"));
    }

    @Test
    void replacesBasicAuthorizationAndJsonForm() {
        String json = """
                { "headers": { "Authorization": "Basic Y2F1dGlvbjpDQU5BUlktUEFTUy0wMDAx" } }
                """;

        String sanitized = sanitized(json);

        assertFalse(sanitized.contains("Y2F1dGlvbjpDQU5BUlktUEFTUy0wMDAx"));
        assertTrue(sanitized.contains("\"Authorization\": \"Basic " + MARKER + "\""),
                "JSON 形态也要认出来: " + sanitized);
    }

    @Test
    void replacesCredentialPositionAssignmentsKeepingTheKeyName() {
        String yaml = """
                spring:
                  datasource:
                    url: jdbc:mysql://localhost:3306/app
                    username: app
                    password: CANARY-DB-PASSWORD-0001
                    api-key: CANARY-API-KEY-0001
                DB_PASSWORD=CANARY-SHELL-PASSWORD-0002
                authToken = "CANARY-AUTH-TOKEN-0001"
                """;

        String sanitized = sanitized(yaml);

        assertFalse(sanitized.contains("CANARY-DB-PASSWORD-0001"));
        assertFalse(sanitized.contains("CANARY-API-KEY-0001"));
        assertFalse(sanitized.contains("CANARY-SHELL-PASSWORD-0002"));
        assertFalse(sanitized.contains("CANARY-AUTH-TOKEN-0001"));
        assertTrue(sanitized.contains("password: " + MARKER), "键名保留");
        assertTrue(sanitized.contains("api-key: " + MARKER));
        assertTrue(sanitized.contains("DB_PASSWORD=" + MARKER), "shell 形态的键名保留");
        assertTrue(sanitized.contains("authToken = " + MARKER), "camelCase 的键名保留");
        assertTrue(sanitized.contains("url: jdbc:mysql://localhost:3306/app"), "非凭据值不动");
        assertTrue(sanitized.contains("username: app"));
    }

    @Test
    void replacesConnectionStringCredentialsKeepingUserAndHost() {
        String yaml = """
                url: jdbc:mysql://app:CANARY-DB-PASSWORD-0003@db.internal:3306/app
                redis: redis://:CANARY-REDIS-PASSWORD-0001@cache.internal:6379
                """;

        String sanitized = sanitized(yaml);

        assertFalse(sanitized.contains("CANARY-DB-PASSWORD-0003"));
        assertFalse(sanitized.contains("CANARY-REDIS-PASSWORD-0001"));
        assertTrue(sanitized.contains("jdbc:mysql://app:" + MARKER + "@db.internal:3306/app"),
                "用户名与主机保留: " + sanitized);
        assertTrue(sanitized.contains("redis://:" + MARKER + "@cache.internal:6379"));
    }

    // ------------------------------------------------------------------ 占位符与误伤

    /**
     * 处于凭据位置的值一律替换，**不分辨**它看起来像不像真凭据。
     *
     * <p>这是政策的刻意选择：判定「这个值是不是真的」不可能可靠，试图区分只会让行为
     * 不可预测；统一替换让结果只依赖位置与形态。
     */
    @Test
    void replacesPlaceholderShapedValuesToo() {
        String yaml = """
                password: "your-password-here"
                secret: ${DB_SECRET}
                token: changeme
                """;

        String sanitized = sanitized(yaml);

        assertFalse(sanitized.contains("your-password-here"));
        assertFalse(sanitized.contains("${DB_SECRET}"));
        assertFalse(sanitized.contains("changeme"));
        assertTrue(sanitized.contains("password: " + MARKER));
    }

    /**
     * 正常标识符不被破坏。
     *
     * <p>{@code tokenCount} 只是名字里含有 {@code token}，它不是凭据位置——
     * 把它替换掉会让正常代码变得不可读，而那种损失没有任何安全收益。
     */
    @Test
    void doesNotCorruptOrdinaryIdentifiers() {
        String java = """
                int tokenCount = 5;
                String passwordHashLength = "48";
                if (password.equals(other)) { return; }
                secretSanta = null;
                """;

        SanitizedRepositoryMaterial result = policy.sanitize(List.of(file("Api.java", java)));

        assertTrue(result.material().get(0).content().contains("int tokenCount = 5;"),
                "tokenCount 不是凭据位置: " + result.material().get(0).content());
        assertTrue(result.material().get(0).content().contains("passwordHashLength"),
                "passwordHashLength 不以凭据词结尾");
        assertTrue(result.material().get(0).content().contains("if (password.equals(other))"),
                "没有赋值形态，不是凭据位置");
    }

    /**
     * 比较运算符不是赋值：不能被吃掉一个等号。
     *
     * <p>{@code if (password == null)} 若被当成「键 + 赋值符 + 值」，会变成
     * {@code if (password =[redacted-credential] null)}——那改变了模型看到的程序逻辑，
     * 而它并没有带来任何安全收益（那里根本没有凭据）。
     */
    @Test
    void doesNotTreatComparisonOperatorsAsAssignments() {
        String java = """
                if (password == null) { return; }
                if (apiKey === undefined) { return; }
                while (token != expected) { next(); }
                boolean same = secret.equals(other);
                """;

        String sanitized = sanitized(java);

        assertTrue(sanitized.contains("if (password == null)"),
                "== 不是赋值: " + sanitized);
        assertTrue(sanitized.contains("if (apiKey === undefined)"), "=== 不是赋值: " + sanitized);
        assertTrue(sanitized.contains("while (token != expected)"), "!= 不是赋值");
        assertTrue(sanitized.contains("secret.equals(other)"));
    }

    /**
     * 空值之后的下一行配置不能被当成取值。
     *
     * <p>{@code password:} 后面没有取值时，若允许跨行匹配，下一行的键名会被吃掉——
     * 那等于从材料里删掉一条非凭据配置。
     */
    @Test
    void doesNotSwallowTheNextLineWhenTheValueIsEmpty() {
        String yaml = """
                spring:
                  datasource:
                    password:
                    host: localhost
                    port: 3306
                """;

        String sanitized = sanitized(yaml);

        assertTrue(sanitized.contains("host: localhost"),
                "下一行的配置必须保留: " + sanitized);
        assertTrue(sanitized.contains("port: 3306"));
        assertFalse(sanitized.contains("[redacted-credential] host"),
                "不得把下一行键名当成取值: " + sanitized);
    }

    /**
     * 带转义引号的取值整段替换。
     *
     * <p>只替换到第一个引号为止，会把后半段留在请求里——那正好是「声明支持却漏掉」的
     * 一类形态，不能算未知格式。
     */
    @Test
    void replacesQuotedValuesContainingEscapes() {
        String java = "String password = \"pre\\\"CANARY-ESCAPED-0001\";";
        String yaml = "legacySecret: 'pre''CANARY-YAML-0001'";
        String backslashSingle = "token: 'pre\\'CANARY-SINGLE-0001'";

        String sanitized = sanitized(java + "\n" + yaml + "\n" + backslashSingle);

        assertFalse(sanitized.contains("CANARY-ESCAPED-0001"),
                "双引号内的转义引号之后仍然属于取值: " + sanitized);
        assertFalse(sanitized.contains("CANARY-YAML-0001"),
                "YAML 的单引号转义（''）之后仍然属于取值: " + sanitized);
        assertFalse(sanitized.contains("CANARY-SINGLE-0001"),
                "单引号内的反斜杠转义之后仍然属于取值: " + sanitized);
        assertTrue(sanitized.contains("String password = " + MARKER + ";"));
        assertTrue(sanitized.contains("legacySecret: " + MARKER));
    }

    /** 空值没有什么可替换的，也不该把整行吃掉。 */
    @Test
    void leavesEmptyCredentialValuesAlone() {
        assertTrue(sanitized("password:\n").contains("password:"));
    }

    // ------------------------------------------------------------------ 结构保留与确定性

    /**
     * 缩进、行数与周边内容都保留：净化之后这份材料仍然有用。
     */
    @Test
    void keepsStructureSoTheMaterialStaysUseful() {
        String yaml = """
                spring:
                  datasource:
                    driver-class-name: com.mysql.cj.jdbc.Driver
                    password: CANARY-STRUCT-0001
                  redis:
                    host: localhost
                """;

        SanitizedRepositoryMaterial result = policy.sanitize(List.of(file("application.yml", yaml)));
        String sanitized = result.material().get(0).content();

        assertEquals(yaml.lines().count(), sanitized.lines().count(), "行数不变");
        assertTrue(sanitized.contains("    driver-class-name: com.mysql.cj.jdbc.Driver"));
        assertTrue(sanitized.contains("    password: " + MARKER));
        assertTrue(sanitized.contains("  redis:"), "缩进与层级保留");
        assertTrue(sanitized.contains("    host: localhost"));
    }

    @Test
    void keepsRelativePathUnchanged() {
        SanitizedRepositoryMaterial result = policy.sanitize(
                List.of(file("src/main/resources/application.yml", "password: CANARY-0001")));

        assertEquals("src/main/resources/application.yml",
                result.material().get(0).relativePath(),
                "路径是 Evidence 的定位依据，不得改动");
    }

    @Test
    void isDeterministic() {
        List<RepositorySourceFile> input = List.of(
                file("a.yml", "password: CANARY-DET-0001"),
                file("b.java", "String apiKey = \"CANARY-DET-0002\";"));

        SanitizedRepositoryMaterial first = policy.sanitize(input);
        SanitizedRepositoryMaterial second = policy.sanitize(input);

        assertEquals(first.material(), second.material());
        assertEquals(first.replacedSpans(), second.replacedSpans());
    }

    @Test
    void reportsReplacedSpanCountWithoutTheValues() {
        SanitizedRepositoryMaterial result = policy.sanitize(List.of(
                file("a.yml", "password: CANARY-COUNT-0001\npassword: CANARY-COUNT-0002"),
                file("b.java", "class B {}")));

        assertEquals(2, result.replacedSpans());
    }

    @Test
    void rejectsNullMaterial() {
        assertThrows(IllegalArgumentException.class, () -> policy.sanitize(null));
    }

    // ------------------------------------------------------------------ 辅助

    private String sanitized(String content) {
        return policy.sanitize(List.of(file("sample.txt", content)))
                .material().get(0).content();
    }

    private static RepositorySourceFile file(String path, String content) {
        return new RepositorySourceFile(path, content);
    }
}
