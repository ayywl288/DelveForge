package com.ayywl.delveforge.application.repositoryanalysis.map;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 按路径确定性地判断一个已提交文件属于哪一类材料、是什么语言、可能扮演什么角色。
 *
 * <pre>
 * committed relative path
 *         ↓  文件名 · 扩展名 · 路径段
 * materialKind + language + roleHints
 * </pre>
 *
 * <h2>只依据路径，不看内容</h2>
 *
 * <p>本类是一个纯函数：同样的路径永远得到同样的结论。它不读文件、不访问 Workspace、
 * 不调用模型。因此同一个 revision 上重建 Map 会得到逐项相同的分类。
 *
 * <h2>blob 大小不参与分类</h2>
 *
 * <p>{@code RepositoryMapEntry} 会保留每个文件的 blob 大小，供后续阶段判断「值不值得读」。
 * 但**当前分类不依据大小做任何分支**。
 *
 * <p>原因是：没有内容可看时，「多大算大」「多大的文件一定不是手写代码」只能靠一个拍出来的
 * 常量回答。M1 复盘已经记录过这类做法的代价——加一个权重常量只是把问题往后推
 * （{@code docs/retrospectives/m1-repository-analysis.md} §8）。在真实证据出现之前，
 * 让大小停留在描述符里，比让它决定一个文件的归类更诚实。
 *
 * <h2>误判的代价被刻意限制</h2>
 *
 * <p>分类一定会错：命名惯例可以被违反，扩展名也可能名不副实。因此本类遵守两条约束：
 *
 * <pre>
 * 分类不排除任何文件     判错的文件仍然留在 Map 里，只是进入不同的候选组
 * 拿不准用 UNKNOWN       不为了「看起来整齐」把文件塞进一个语义不符的类别
 * </pre>
 *
 * <h2>覆盖面</h2>
 *
 * <p>规则按「当前真实验证仓库（黑马点评，Java + Maven + JMeter + RocketMQ）」设计，
 * 并对其它语言保持可表示：不认识的扩展名仍然是源码或 OTHER，只是角色提示为
 * {@link RepositoryRoleHint#UNKNOWN}。
 *
 * <p>已知未覆盖：文件名采用下划线惯例的语言（例如 {@code shop_service.py}）不会从文件名
 * 得到角色提示——但路径段规则（{@code service/}、{@code controllers/}…）与语言无关，
 * 对这些文件仍然有效。这是当前选择的边界，不是本类无法表达的东西。
 */
public final class RepositoryPathClassifier {

    // ---------------------------------------------------------------------
    // 规则表
    // ---------------------------------------------------------------------

    /**
     * 结构性生成物与依赖目录：这些名字不可能同时是源码包，因此在任何层级都判定为生成物。
     *
     * <p>取舍依据不是「这个名字看起来像不像生成物」，而是**同一个名字可不可能是一条正常的
     * 业务包路径**。{@code node_modules}、{@code __pycache__} 不可能；{@code build}、
     * {@code vendor} 完全可能（构建管理、供应商管理）。后者见
     * {@link #BUILD_OUTPUT_DIRECTORIES}。
     */
    private static final Set<String> GENERATED_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "__pycache__");

    /**
     * 构建输出与依赖目录名：**只在源码树之外**才判定为生成物。
     *
     * <p>这些名字在工程里表示构架输出或第三方依赖，但它们同样是可以出现在源码树里的普通
     * 包名：
     *
     * <pre>
     * src/main/java/com/acme/build/BuildService.java      ← 构建管理，是业务代码
     * src/main/java/com/acme/vendor/VendorService.java    ← 供应商管理，是业务代码
     * target/classes/com/acme/BuildService.class          ← 构建输出，不是业务代码
     * </pre>
     *
     * <p>只看目录名会把前一类的业务实现整体排除出候选组。这恰好是 M1 层级筛选制造盲区的
     * 同一个错误——**一个文件因为命名而被判成不该看**。因此这里加上位置上下文：位于源码树内
     * 的同名目录不作数。
     *
     * <p>不对称是刻意的：把业务代码误判为生成物会让它从所有候选组消失；把构建输出误判为
     * 源码只是多一个候选。前者不可接受，后者可以接受。
     */
    private static final Set<String> BUILD_OUTPUT_DIRECTORIES = Set.of(
            "target", "build", "dist", "out", "coverage", "vendor");

    /**
     * 源码根目录名：路径中出现其中之一，说明它位于源码树内。
     *
     * <p>这份清单刻意给得宽一些，因为漏判（把一个源码根当成非源码树）会重新引入
     * 「业务代码被当成生成物」，而多判只是让某个构建输出目录里的源码进入候选。
     * 覆盖主流工程的源码根命名。
     */
    private static final Set<String> SOURCE_ROOT_DIRECTORIES = Set.of(
            "src", "source", "sources", "app", "lib", "libs", "internal", "cmd", "pkg",
            "packages", "modules");

    /** 生成物形态的文件名：锁文件。 */
    private static final Set<String> LOCK_FILE_NAMES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "composer.lock",
            "poetry.lock", "cargo.lock", "gemfile.lock", "gradle.lockfile");

    /** 构建元数据文件名。 */
    private static final Set<String> BUILD_FILE_NAMES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
            "settings.gradle.kts", "gradle.properties", "build.xml",
            "package.json", "tsconfig.json", "jsconfig.json",
            "go.mod", "go.sum", "cargo.toml", "pyproject.toml", "setup.py", "setup.cfg",
            "requirements.txt", "gemfile", "composer.json",
            "makefile", "cmakelists.txt");

    /** 部署与运行时编排的文件名。 */
    private static final Set<String> DEPLOYMENT_FILE_NAMES = Set.of(
            "dockerfile", "docker-compose.yml", "docker-compose.yaml", "compose.yml",
            "compose.yaml", ".dockerignore", "procfile", "jenkinsfile", "chart.yaml",
            "vagrantfile");

    /** 部署与运行时编排的目录段。 */
    private static final Set<String> DEPLOYMENT_DIRECTORIES = Set.of(
            "k8s", "kubernetes", "helm", "charts", "deploy", "deployment", "docker", ".github");

    /** 部署相关扩展名（基础设施即代码）。 */
    private static final Set<String> DEPLOYMENT_EXTENSIONS = Set.of(".tf", ".tfvars");

    /** 数据 schema 扩展名。 */
    private static final Set<String> SCHEMA_EXTENSIONS = Set.of(".sql", ".ddl");

    /** 源码扩展名：与 {@code RepositoryAnalysisMaterialCategory.SOURCE_CODE} 保持一致。 */
    private static final Set<String> SOURCE_EXTENSIONS = Set.of(
            ".java", ".kt", ".kts", ".scala", ".groovy",
            ".py", ".js", ".jsx", ".ts", ".tsx", ".vue",
            ".go", ".rs", ".c", ".h", ".cpp", ".cc", ".hpp", ".cs",
            ".rb", ".php", ".swift", ".dart", ".lua");

    /** 脚本与自动化扩展名。 */
    private static final Set<String> SCRIPT_EXTENSIONS = Set.of(
            ".sh", ".bash", ".zsh", ".bat", ".cmd", ".ps1", ".ps");

    /** 文档扩展名。 */
    private static final Set<String> DOCUMENTATION_EXTENSIONS = Set.of(
            ".md", ".markdown", ".rst", ".adoc", ".txt");

    /** 文档文件名前缀（大小写不敏感），用于没有扩展名或惯例命名的说明文件。 */
    private static final List<String> DOCUMENTATION_NAME_PREFIXES = List.of(
            "readme", "license", "licence", "changelog", "contributing", "notice", "authors");

    /** 配置扩展名。 */
    private static final Set<String> CONFIGURATION_EXTENSIONS = Set.of(
            ".yaml", ".yml", ".properties", ".conf", ".ini", ".toml", ".env", ".xml", ".json",
            ".cfg");

    /** 配置形态的文件名（点文件没有扩展名，按名字识别）。 */
    private static final Set<String> CONFIGURATION_FILE_NAMES = Set.of(
            ".gitignore", ".gitattributes", ".editorconfig", ".npmrc", ".nvmrc", ".env");

    /** 测试定义与测试代码的目录段。 */
    private static final Set<String> TEST_DIRECTORIES = Set.of(
            "test", "tests", "__tests__", "spec", "specs");

    /** 按 Java 惯例命名的测试类后缀（大小写敏感，避免 {@code Audit} 这类误判）。 */
    private static final List<String> TEST_STEMS_JAVA_STYLE = List.of(
            "Test", "Tests", "IT", "TestCase");

    /**
     * 采用「类名以 {@code Test} 结尾」这一命名惯例的语言扩展名。
     *
     * <p>这条规则只对这些语言成立。它不是「凡是名字以 Test 结尾就叫测试」：
     *
     * <pre>
     * src/components/SpeedTest.vue   测速产品里的一个组件，是源码
     * docs/LoadTest.md               压测说明文档，是文档
     * src/service/ShopServiceTest.java   测试类
     * </pre>
     *
     * <p>JS / TS / Python 各自有更明确的命名惯例（{@code *.test.ts}、{@code test_*.py}），
     * 由 {@link #TEST_SUFFIXES_LOWER_CASE} 单独处理，不借用 Java 的规则。
     */
    private static final Set<String> CAMEL_CASE_TEST_EXTENSIONS = Set.of(
            ".java", ".kt", ".kts", ".scala", ".groovy", ".cs");

    /** 按 JS/TS/Python 惯例命名的测试文件后缀（小写匹配）。 */
    private static final List<String> TEST_SUFFIXES_LOWER_CASE = List.of(
            ".test.js", ".spec.js", ".test.jsx", ".spec.jsx",
            ".test.ts", ".spec.ts", ".test.tsx", ".spec.tsx",
            ".test.vue", ".spec.vue", "_test.py");

    /** Python 的另一种测试命名：{@code test_*.py}（前缀而非后缀，因此单独判断）。 */
    private static final String PYTHON_TEST_PREFIX = "test_";

    /** 工具与自动化目录：承载脚本而非产品代码。 */
    private static final Set<String> SCRIPT_DIRECTORIES = Set.of(
            "script", "scripts", "tools", "tool", "ci", "cd", "automation", "harness",
            "ops", "jmeter");

    /** 路径段 → 角色提示。 */
    private static final Set<String> CONFIG_SEGMENTS = Set.of(
            "config", "configs", "configuration", "bootstrap");

    /**
     * 接口入口的目录段。
     *
     * <p>刻意不含 {@code web}：前端工程的 {@code web/} 目录同样常见，把它算作后端接口入口
     * 会让一个 Vue 组件得到 {@code API_ENTRY} 这个明显错误的提示。
     */
    private static final Set<String> API_SEGMENTS = Set.of(
            "controller", "controllers", "api", "rest", "endpoint", "endpoints", "http");

    private static final Set<String> SERVICE_SEGMENTS = Set.of(
            "service", "services", "usecase", "usecases", "manager", "managers");

    private static final Set<String> DOMAIN_SEGMENTS = Set.of(
            "domain", "model", "models", "entity", "entities", "aggregate", "aggregates",
            "dto", "dtos", "vo", "vos", "pojo", "pojos");

    private static final Set<String> PERSISTENCE_SEGMENTS = Set.of(
            "mapper", "mappers", "repository", "repositories", "dao", "daos",
            "persistence", "mybatis");

    private static final Set<String> INTEGRATION_SEGMENTS = Set.of(
            "client", "clients", "integration", "integrations", "gateway", "gateways",
            "adapter", "adapters", "remote", "mq", "messaging",
            "producer", "producers", "consumer", "consumers");

    private static final Set<String> UTILITY_SEGMENTS = Set.of(
            "util", "utils", "helper", "helpers", "common", "support");

    /** 类名后缀（去掉扩展名之后比较，大小写敏感）→ 角色提示。 */
    private static final List<String> CONFIG_STEMS = List.of(
            "Config", "Configuration", "AutoConfiguration", "Properties",
            "Application", "Bootstrap", "Main");

    private static final List<String> API_STEMS = List.of(
            "Controller", "Endpoint", "Resource", "Api");

    private static final List<String> SERVICE_STEMS = List.of(
            "Service", "ServiceImpl", "UseCase", "UseCaseImpl", "Manager");

    private static final List<String> DOMAIN_STEMS = List.of(
            "Entity", "Aggregate", "ValueObject", "Dto", "Vo", "Model");

    private static final List<String> PERSISTENCE_STEMS = List.of(
            "Mapper", "Repository", "Dao", "DAO");

    private static final List<String> INTEGRATION_STEMS = List.of(
            "Client", "Gateway", "Producer", "Consumer", "Adapter");

    private static final List<String> UTILITY_STEMS = List.of(
            "Util", "Utils", "Helper", "Support");

    private RepositoryPathClassifier() {
    }

    /**
     * 一个文件的确定性分类结果。
     *
     * <p>它是纯派生数据，不构成第二份状态：只由路径算出，不缓存、不覆盖任何东西。
     *
     * @param materialKind 这是什么材料，不得为 {@code null}
     * @param language     按扩展名识别的语言，不得为 {@code null}
     * @param roleHints    结构角色提示；源码至少有一个（拿不准时是
     *                     {@link RepositoryRoleHint#UNKNOWN}），非源码为空
     */
    public record Classification(
            RepositoryMaterialKind materialKind,
            RepositoryLanguage language,
            List<RepositoryRoleHint> roleHints) {

        public Classification {
            if (materialKind == null) {
                throw new IllegalArgumentException("Classification 必须指定 materialKind");
            }
            if (language == null) {
                throw new IllegalArgumentException("Classification 必须指定 language");
            }
            if (roleHints == null) {
                throw new IllegalArgumentException("Classification 的 roleHints 不能为 null");
            }
            roleHints = List.copyOf(roleHints);
        }
    }

    /**
     * 按提交树中的相对路径判断一个文件的材料类别、语言与结构角色。
     *
     * @param relativePath 相对于 Workspace 根目录的路径，不得为空白
     * @return 确定性分类结果
     * @throws IllegalArgumentException relativePath 为空白
     */
    public static Classification classify(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException(
                    "RepositoryPathClassifier 的 relativePath 不能为空");
        }

        List<String> segments = directorySegments(relativePath);
        String fileName = fileName(relativePath);
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        String extension = extension(lowerFileName);

        if (isGeneratedOrVendor(segments, lowerFileName)) {
            return plain(RepositoryMaterialKind.GENERATED_VENDOR, languageOf(extension));
        }
        if (isDeployment(segments, lowerFileName, extension)) {
            return plain(RepositoryMaterialKind.DEPLOYMENT, languageOf(extension));
        }
        if (BUILD_FILE_NAMES.contains(lowerFileName)) {
            return plain(RepositoryMaterialKind.BUILD_METADATA, languageOf(extension));
        }
        if (SCHEMA_EXTENSIONS.contains(extension)) {
            return plain(RepositoryMaterialKind.DATA_SCHEMA, languageOf(extension));
        }
        if (isTestCode(segments, fileName, lowerFileName, extension)) {
            // 测试代码不参与「业务实现在哪」的判断，因此不给结构角色提示：
            // 给它 API_ENTRY / APPLICATION_SERVICE 之类的提示会把测试与产品代码混为一谈。
            return plain(RepositoryMaterialKind.TEST_CODE, languageOf(extension));
        }
        if (isScriptAutomation(segments, extension)) {
            return plain(RepositoryMaterialKind.SCRIPT_AUTOMATION, languageOf(extension));
        }
        if (SOURCE_EXTENSIONS.contains(extension)) {
            return new Classification(
                    RepositoryMaterialKind.SOURCE_CODE,
                    languageOf(extension),
                    sourceRoles(segments, fileName));
        }
        if (isDocumentation(lowerFileName, extension)) {
            return plain(RepositoryMaterialKind.DOCUMENTATION, languageOf(extension));
        }
        if (CONFIGURATION_EXTENSIONS.contains(extension)
                || CONFIGURATION_FILE_NAMES.contains(lowerFileName)) {
            return plain(RepositoryMaterialKind.CONFIGURATION, languageOf(extension));
        }
        return plain(RepositoryMaterialKind.OTHER, languageOf(extension));
    }

    // ---------------------------------------------------------------------
    // 类别判断
    // ---------------------------------------------------------------------

    private static boolean isGeneratedOrVendor(List<String> segments, String lowerFileName) {
        for (String segment : segments) {
            if (GENERATED_DIRECTORIES.contains(segment)) {
                return true;
            }
        }
        if (hasBuildOutputOutsideSourceTree(segments)) {
            return true;
        }
        return LOCK_FILE_NAMES.contains(lowerFileName)
                || lowerFileName.endsWith(".min.js")
                || lowerFileName.endsWith(".min.css");
    }

    /**
     * 构建输出目录名是否出现在源码树之外。
     *
     * <p>由**最先出现的那个上下文目录**决定：先遇到源码根，说明整体在源码树内；先遇到构建
     * 输出目录，说明整体在输出目录里。目录的嵌套顺序本身就是上下文——只看「有没有出现过」
     * 会让 {@code build/libs/app.jar} 因为 {@code libs} 而被误判成源码树内。
     *
     * <pre>
     * src/main/java/com/acme/build/…   先遇到 src     → 源码树内，build 只是包名
     * build/libs/app.jar               先遇到 build   → 构建输出
     * </pre>
     */
    private static boolean hasBuildOutputOutsideSourceTree(List<String> segments) {
        for (String segment : segments) {
            if (SOURCE_ROOT_DIRECTORIES.contains(segment)) {
                return false;
            }
            if (BUILD_OUTPUT_DIRECTORIES.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDeployment(List<String> segments, String lowerFileName,
                                        String extension) {
        if (DEPLOYMENT_FILE_NAMES.contains(lowerFileName)
                || DEPLOYMENT_EXTENSIONS.contains(extension)) {
            return true;
        }
        for (String segment : segments) {
            if (DEPLOYMENT_DIRECTORIES.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 该文件是不是测试代码或测试定义。
     *
     * <p>每条规则都限制在它真正适用的文件类型上。测试命名是**按语言**成立的惯例，
     * 不是「名字里出现 Test 就是测试」：把 Java 的类名惯例套到所有文件上，
     * 会让一个测速组件（{@code SpeedTest.vue}）或一份压测说明（{@code LoadTest.md}）
     * 被判成测试代码，从而退出所有候选组。
     */
    private static boolean isTestCode(List<String> segments, String fileName,
                                      String lowerFileName, String extension) {
        // JMeter 计划是测试定义：它描述的是「怎么测」，不是「产品实现什么」。
        if (".jmx".equals(extension)) {
            return true;
        }
        boolean sourceFile = SOURCE_EXTENSIONS.contains(extension);

        // 测试目录只对源码文件成立：src/test 下的一个 fixture .json 是资源，不是测试。
        if (sourceFile) {
            for (String segment : segments) {
                if (TEST_DIRECTORIES.contains(segment)) {
                    return true;
                }
            }
        }
        // 下面两条后缀本身就带着扩展名，因此天然只作用于对应的语言。
        for (String suffix : TEST_SUFFIXES_LOWER_CASE) {
            if (lowerFileName.endsWith(suffix)) {
                return true;
            }
        }
        if (lowerFileName.startsWith(PYTHON_TEST_PREFIX) && lowerFileName.endsWith(".py")) {
            return true;
        }
        if (CAMEL_CASE_TEST_EXTENSIONS.contains(extension)) {
            String stem = stemOf(fileName);
            for (String suffix : TEST_STEMS_JAVA_STYLE) {
                // 大小写敏感：小写比较会让 Audit.java 被当成 *IT.java
                if (stem.endsWith(suffix) && stem.length() > suffix.length()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isScriptAutomation(List<String> segments, String extension) {
        if (SCRIPT_EXTENSIONS.contains(extension)) {
            return true;
        }
        if (!".py".equals(extension)) {
            return false;
        }
        for (String segment : segments) {
            if (SCRIPT_DIRECTORIES.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDocumentation(String lowerFileName, String extension) {
        if (DOCUMENTATION_EXTENSIONS.contains(extension)) {
            return true;
        }
        for (String prefix : DOCUMENTATION_NAME_PREFIXES) {
            if (lowerFileName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------
    // 角色提示
    // ---------------------------------------------------------------------

    /**
     * 一个源文件可能扮演的结构角色。
     *
     * <p>路径段与文件名各自提供信号，两者都采纳：一个类既可能因为位于 {@code service/} 下
     * 被判为应用服务，也可能因为叫 {@code *Mapper} 被判为持久化，两者同时成立时都保留。
     *
     * <p>结果按枚举声明顺序返回，因此对同一个路径是确定的。
     */
    private static List<RepositoryRoleHint> sourceRoles(List<String> segments, String fileName) {
        EnumSet<RepositoryRoleHint> hints = EnumSet.noneOf(RepositoryRoleHint.class);
        String stem = stemOf(fileName);

        for (String segment : segments) {
            addIf(hints, CONFIG_SEGMENTS, segment, RepositoryRoleHint.CONFIG_BOOTSTRAP);
            addIf(hints, API_SEGMENTS, segment, RepositoryRoleHint.API_ENTRY);
            addIf(hints, SERVICE_SEGMENTS, segment, RepositoryRoleHint.APPLICATION_SERVICE);
            addIf(hints, DOMAIN_SEGMENTS, segment, RepositoryRoleHint.DOMAIN_MODEL);
            addIf(hints, PERSISTENCE_SEGMENTS, segment, RepositoryRoleHint.PERSISTENCE);
            addIf(hints, INTEGRATION_SEGMENTS, segment, RepositoryRoleHint.INTEGRATION);
            addIf(hints, UTILITY_SEGMENTS, segment, RepositoryRoleHint.UTILITY);
        }

        addIfStemEndsWith(hints, stem, CONFIG_STEMS, RepositoryRoleHint.CONFIG_BOOTSTRAP);
        addIfStemEndsWith(hints, stem, API_STEMS, RepositoryRoleHint.API_ENTRY);
        addIfStemEndsWith(hints, stem, SERVICE_STEMS, RepositoryRoleHint.APPLICATION_SERVICE);
        addIfStemEndsWith(hints, stem, DOMAIN_STEMS, RepositoryRoleHint.DOMAIN_MODEL);
        addIfStemEndsWith(hints, stem, PERSISTENCE_STEMS, RepositoryRoleHint.PERSISTENCE);
        addIfStemEndsWith(hints, stem, INTEGRATION_STEMS, RepositoryRoleHint.INTEGRATION);
        addIfStemEndsWith(hints, stem, UTILITY_STEMS, RepositoryRoleHint.UTILITY);

        if (hints.isEmpty()) {
            // 看不出来就是看不出来。这里不猜一个「最像」的角色。
            return List.of(RepositoryRoleHint.UNKNOWN);
        }
        return List.copyOf(hints);
    }

    private static void addIf(EnumSet<RepositoryRoleHint> hints, Set<String> segments,
                              String segment, RepositoryRoleHint hint) {
        if (segments.contains(segment)) {
            hints.add(hint);
        }
    }

    private static void addIfStemEndsWith(EnumSet<RepositoryRoleHint> hints, String stem,
                                          List<String> suffixes, RepositoryRoleHint hint) {
        for (String suffix : suffixes) {
            if (stem.length() > suffix.length() && stem.endsWith(suffix)) {
                hints.add(hint);
                return;
            }
        }
    }

    // ---------------------------------------------------------------------
    // 路径解析
    // ---------------------------------------------------------------------

    private static Classification plain(RepositoryMaterialKind kind, RepositoryLanguage language) {
        return new Classification(kind, language, List.of());
    }

    /**
     * 按扩展名识别语言；没有映射一律 {@link RepositoryLanguage#UNKNOWN}。
     *
     * <p>映射表覆盖与 {@code RepositoryAnalysisMaterialCategory.SOURCE_CODE} 一致的源码扩展名、
     * Java 工程常见的配置与标记语言，以及真实验证仓库中出现的类型。多义扩展名按最常见的
     * 用途取一个（{@code .h} 记为 C），并在枚举的文档里说明这可能不准。
     */
    private static RepositoryLanguage languageOf(String extension) {
        return switch (extension) {
            case ".java" -> RepositoryLanguage.JAVA;
            case ".kt", ".kts" -> RepositoryLanguage.KOTLIN;
            case ".scala" -> RepositoryLanguage.SCALA;
            case ".groovy" -> RepositoryLanguage.GROOVY;
            case ".py" -> RepositoryLanguage.PYTHON;
            case ".js", ".jsx" -> RepositoryLanguage.JAVASCRIPT;
            case ".ts", ".tsx" -> RepositoryLanguage.TYPESCRIPT;
            case ".vue" -> RepositoryLanguage.VUE;
            case ".go" -> RepositoryLanguage.GO;
            case ".rs" -> RepositoryLanguage.RUST;
            case ".c", ".h" -> RepositoryLanguage.C;
            case ".cpp", ".cc", ".hpp" -> RepositoryLanguage.CPP;
            case ".cs" -> RepositoryLanguage.C_SHARP;
            case ".rb" -> RepositoryLanguage.RUBY;
            case ".php" -> RepositoryLanguage.PHP;
            case ".swift" -> RepositoryLanguage.SWIFT;
            case ".dart" -> RepositoryLanguage.DART;
            case ".lua" -> RepositoryLanguage.LUA;
            case ".sh", ".bash", ".zsh", ".bat", ".cmd", ".ps1", ".ps" ->
                    RepositoryLanguage.SHELL;
            case ".sql", ".ddl" -> RepositoryLanguage.SQL;
            case ".xml", ".xsd", ".xsl" -> RepositoryLanguage.XML;
            case ".yaml", ".yml" -> RepositoryLanguage.YAML;
            case ".json" -> RepositoryLanguage.JSON;
            case ".toml" -> RepositoryLanguage.TOML;
            case ".properties" -> RepositoryLanguage.PROPERTIES;
            case ".md", ".markdown" -> RepositoryLanguage.MARKDOWN;
            case ".rst", ".adoc", ".txt" -> RepositoryLanguage.TEXT;
            default -> RepositoryLanguage.UNKNOWN;
        };
    }

    private static String fileName(String relativePath) {
        int separator = relativePath.lastIndexOf('/');
        return relativePath.substring(separator + 1);
    }

    /** 目录段（不含文件名），小写。路径分隔符固定为 {@code /}。 */
    private static List<String> directorySegments(String relativePath) {
        List<String> segments = new ArrayList<>();
        for (String segment : relativePath.split("/")) {
            if (!segment.isEmpty()) {
                segments.add(segment.toLowerCase(Locale.ROOT));
            }
        }
        if (!segments.isEmpty()) {
            segments.remove(segments.size() - 1);
        }
        return segments;
    }

    /**
     * 扩展名（含点，小写）；没有扩展名时返回空串。
     *
     * <p>以点开头的隐藏文件不算「有扩展名」：{@code .gitignore} 的扩展名是空串，
     * 而不是 {@code .gitignore}。
     */
    private static String extension(String lowerFileName) {
        int dot = lowerFileName.lastIndexOf('.');
        return dot > 0 ? lowerFileName.substring(dot) : "";
    }

    /** 去掉最后一个扩展名之后的文件名部分。没有扩展名时就是文件名本身。 */
    private static String stemOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
