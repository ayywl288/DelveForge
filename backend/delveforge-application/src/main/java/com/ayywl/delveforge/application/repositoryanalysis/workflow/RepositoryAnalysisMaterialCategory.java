package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import java.util.Locale;
import java.util.Set;

/**
 * 分析材料按用途分的类别。
 *
 * <h2>这是 Application 层的 MVP 分类，不是领域规则</h2>
 *
 * <p>它存在的唯一目的，是让一次分析的材料有机会覆盖 Repository 的不同侧面
 * （工程元数据、源码、配置、文档、脚本），而不是让路径排序靠前的某一类文件占满全部预算。
 * 真实仓库 Smoke Test 暴露的正是这个问题：材料的 39 个文件里没有一个 Java 源文件，
 * 分析只能停在文档与工具链层面。
 *
 * <p>分类只依据文件路径（名称与扩展名），不看内容、不做相关性打分，因此结果稳定可复现。
 * 它不追求准确：一个文件归到哪一类，只影响它在轮转里的排队位置，不影响它是否可读。
 *
 * <p>这是 MVP 的粗分类：出现新的重要文件类型时按需在下面的集合里增加，
 * 不要把它扩成"什么文件更重要"的评分体系。
 */
public enum RepositoryAnalysisMaterialCategory {

    /** 工程与构建元数据：说明这是什么工程、依赖什么。 */
    BUILD_METADATA(Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradle.properties", "build.xml",
            "package.json", "tsconfig.json",
            "go.mod", "cargo.toml", "pyproject.toml", "setup.py", "requirements.txt",
            "gemfile", "composer.json",
            "makefile", "cmakelists.txt", "dockerfile"), Set.of()),

    /** 源代码。 */
    SOURCE_CODE(Set.of(), Set.of(
            ".java", ".kt", ".kts", ".scala", ".groovy",
            ".py", ".js", ".jsx", ".ts", ".tsx", ".vue",
            ".go", ".rs", ".c", ".h", ".cpp", ".cc", ".hpp", ".cs",
            ".rb", ".php", ".swift", ".dart", ".lua", ".sql")),

    /** 配置。 */
    CONFIGURATION(Set.of(), Set.of(
            ".yaml", ".yml", ".properties", ".conf", ".ini", ".toml", ".env", ".xml", ".json")),

    /** 文档。 */
    DOCUMENTATION(Set.of(), Set.of(".md", ".markdown", ".rst", ".adoc", ".txt")),

    /** 脚本与可复用资产。 */
    SCRIPT(Set.of(), Set.of(".sh", ".bash", ".zsh", ".bat", ".cmd", ".ps1", ".ps")),

    /** 其余内容（数据文件、锁文件、无扩展名文件等）。 */
    OTHER(Set.of(), Set.of());

    private final Set<String> fileNames;

    private final Set<String> extensions;

    RepositoryAnalysisMaterialCategory(Set<String> fileNames, Set<String> extensions) {
        this.fileNames = fileNames;
        this.extensions = extensions;
    }

    /**
     * 按路径判断类别。
     *
     * <p>先看完整文件名（{@code pom.xml} 属于构建元数据，而不是归到 {@code .xml} 的配置），
     * 再看扩展名，都不匹配归入 {@link #OTHER}。
     *
     * <p>比较不区分大小写；路径分隔符固定为 {@code /}（Workspace 边界上的相对路径）。
     */
    public static RepositoryAnalysisMaterialCategory of(String relativePath) {
        int separator = relativePath.lastIndexOf('/');
        String fileName = relativePath.substring(separator + 1).toLowerCase(Locale.ROOT);

        for (RepositoryAnalysisMaterialCategory category : values()) {
            if (category.fileNames.contains(fileName)) {
                return category;
            }
        }

        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return OTHER;
        }
        String extension = fileName.substring(dot);
        for (RepositoryAnalysisMaterialCategory category : values()) {
            if (category.extensions.contains(extension)) {
                return category;
            }
        }
        return OTHER;
    }
}
