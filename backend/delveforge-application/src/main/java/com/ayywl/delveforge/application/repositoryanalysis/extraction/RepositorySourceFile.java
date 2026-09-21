package com.ayywl.delveforge.application.repositoryanalysis.extraction;

/**
 * 一份交给 AI 分析的 Repository 文件。
 *
 * <p>它是 AI 请求的输入材料，不是领域对象：路径始终是相对于 Repository 根目录的相对路径
 * （与 Workspace 边界一致，不携带宿主机绝对路径），内容是已经从 Workspace 读出的文本。
 *
 * <p>本类型只承载材料，不判断这份材料是否足以支撑分析——读什么、读多少由调用方决定，
 * 本 Task 不设计材料选取策略。
 *
 * @param relativePath 相对于 Repository 根目录的路径，不得为空
 * @param content      该文件的文本内容，可以为空字符串（空文件是合法事实）
 */
public record RepositorySourceFile(String relativePath, String content) {

    public RepositorySourceFile {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Repository 文件必须给出相对路径");
        }
        if (content == null) {
            throw new IllegalArgumentException(
                    "Repository 文件的内容不能为 null: " + relativePath);
        }
    }
}
