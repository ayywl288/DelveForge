package com.ayywl.delveforge.application.repositoryanalysis.scout;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import java.util.List;

/**
 * AI 提出的一个查看区域：一个标签，加上一组**尚未校验**的引用。
 *
 * <pre>
 * label      「这一组文件打算用来看什么」——查看意图，不是结论
 * fileRefs   模型建议优先读取的编号，按它给出的顺序
 * </pre>
 *
 * <h2>它是 AI 通信协议的一部分</h2>
 *
 * <p>与本包里的其它 {@code Ai*} 类型一样，它是不可信输入：引用还没有被解析成真实描述符，
 * 因此这里没有任何可追溯到仓库的事实。{@code label} 也只是模型的说法。
 *
 * <p>它刻意不承载任何分析结论：没有能力、没有复用资产、没有风险、没有置信度。
 * 那些属于最终 Repository Analysis，不属于「去哪里看」。
 *
 * @param label    查看区域的简短标签，不得为 {@code null} 或空白
 * @param fileRefs 该区域建议优先查看的引用，不得为 {@code null} 或空，元素不得为 {@code null}
 */
public record AiRepositoryScoutFocusArea(String label, List<RepositoryFileReference> fileRefs) {

    public AiRepositoryScoutFocusArea {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Scout 查看区域必须给出 label");
        }
        if (fileRefs == null || fileRefs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Scout 查看区域的 fileRefs 不能为空: " + label);
        }
        for (RepositoryFileReference reference : fileRefs) {
            if (reference == null) {
                throw new IllegalArgumentException(
                        "Scout 查看区域的 fileRefs 不能包含 null: " + label);
            }
        }
        fileRefs = List.copyOf(fileRefs);
    }
}
