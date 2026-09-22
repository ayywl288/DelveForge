package com.ayywl.delveforge.application.repositoryanalysis.workflow;

import java.util.Set;

/**
 * 一次 Repository 分析取哪些材料的策略。
 *
 * <h2>这是 Application 层的 MVP 策略，不是领域规则</h2>
 *
 * <p>DOMAIN_MODEL.md 只要求分析针对一个确定的软件状态并形成可追溯的结论，
 * 没有规定「读多少文件、读多大、先读哪些」。因此下面这些上限与规则都是当前的实现选择：
 * 它们决定分析能看到什么，改变它们不需要改领域模型，但会改变分析结果的质量。
 *
 * <p>它们存在的原因是：把整个 Repository 无界地塞进一次请求既不现实也不安全
 * （内存、token 上限、时间、成本）。因此这里给出确定、可复现的边界。
 *
 * <p>刻意不引入 RAG、Embedding、索引或切分检索：M1 要做的是让一个真实本地仓库跑通，
 * 排序与筛选只要「确定」且有代表性即可，不需要相关性建模。
 *
 * <h2>选择规则</h2>
 *
 * <pre>
 * 不按目录层级筛选：只排除生成物 / 依赖 / 版本控制目录与明显的二进制文件
 * 按用途分成若干类别（见 RepositoryAnalysisMaterialCategory）
 * 每一轮从每个类别各取一个文件，轮流取到预算用尽
 *     类别内的顺序按相对路径升序——顺序确定，因此同一次分析的结果可复现
 *     单个文件超过 maxFileBytes 直接跳过（不读、不截断）
 *     累计大小将达到 maxTotalBytes 时停止
 *     最多取 maxFiles 个文件
 * </pre>
 *
 * <h2>为什么不按层级筛选</h2>
 *
 * <p>此前的策略只读前 4 层。它没有节省任何读取——Workspace 列目录本来就要遍历整棵树——
 * 却把深层的主源码树整体藏了起来：真实仓库（黑马点评）的 96 个 Java 文件都位于第 7–8 层
 * （{@code src/main/java/com/hmdp/...}），于是一次分析里连一个源文件都没有，Profile 只能
 * 描述文档与工具链。目录深度是工程习惯，不是重要性的度量，因此这里不再用它做筛选；
 * 规模仍然由 maxFiles、maxFileBytes 与 maxTotalBytes 三个预算界住。
 *
 * <h2>单位</h2>
 *
 * <p>上限按字节计，因为文件大小来自列目录的结果。内容的字符数不大于字节数（UTF-8），
 * 因此按字节给出的预算是内容规模的一个保守上界。
 *
 * @param maxFiles            最多读取多少个文件
 * @param maxFileBytes        单个文件的最大字节数，超过则跳过该文件
 * @param maxTotalBytes       所有文件内容的累计最大字节数
 * @param excludedDirectories 不进入的目录名（按路径段匹配）
 * @param excludedExtensions  不读取的文件扩展名（小写，含点）
 */
public record RepositoryAnalysisMaterialPolicy(
        int maxFiles,
        int maxFileBytes,
        int maxTotalBytes,
        Set<String> excludedDirectories,
        Set<String> excludedExtensions) {

    public RepositoryAnalysisMaterialPolicy {
        if (maxFiles <= 0) {
            throw new IllegalArgumentException("maxFiles 必须大于 0: " + maxFiles);
        }
        if (maxFileBytes <= 0) {
            throw new IllegalArgumentException("maxFileBytes 必须大于 0: " + maxFileBytes);
        }
        if (maxTotalBytes < maxFileBytes) {
            throw new IllegalArgumentException(
                    "maxTotalBytes 不能小于 maxFileBytes: " + maxTotalBytes + " < " + maxFileBytes);
        }
        if (excludedDirectories == null || excludedExtensions == null) {
            throw new IllegalArgumentException("排除项不能为 null，没有排除项时给出空集合");
        }
        excludedDirectories = Set.copyOf(excludedDirectories);
        excludedExtensions = Set.copyOf(excludedExtensions);
    }

    /**
     * M1 的默认策略。
     *
     * <p>预算数值保持保守：目标是让一个普通中小型仓库得到有代表性的分析，而不是尽量多读。
     * 类别轮转负责「有代表性」，这三个上限负责「有界」。真实使用中如果明显不够，
     * 应当按证据调整，而不是改成「读到没有为止」。
     */
    public static RepositoryAnalysisMaterialPolicy mvpDefault() {
        return new RepositoryAnalysisMaterialPolicy(
                40,
                20_000,
                200_000,
                Set.of(".git", ".idea", ".vscode", "node_modules", "vendor", "target", "build",
                        "dist", "out", "coverage", "__pycache__"),
                Set.of(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico", ".pdf", ".zip", ".gz",
                        ".tar", ".jar", ".war", ".class", ".exe", ".dll", ".so", ".dylib", ".bin",
                        ".woff", ".woff2", ".ttf", ".eot", ".mp3", ".mp4", ".mov"));
    }
}
