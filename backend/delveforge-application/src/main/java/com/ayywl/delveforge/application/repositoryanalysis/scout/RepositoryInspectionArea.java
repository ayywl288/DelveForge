package com.ayywl.delveforge.application.repositoryanalysis.scout;

import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryFileReference;
import com.ayywl.delveforge.application.repositoryanalysis.map.RepositoryMapEntry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一个已经校验过的查看区域：标签 + 真实描述符。
 *
 * <pre>
 * label      「打算去哪里看」的提示
 * entries    已经解析回 Map 描述符的文件，按查看顺序
 * </pre>
 *
 * <h2>它仍然不是结论</h2>
 *
 * <p>引用被校验只说明「这些文件确实存在、确实是本次提供的源码候选」，
 * 不说明它们实现了什么。{@code label} 也仍然是模型的查看意图，而不是仓库能力。
 * 从「打算去看这里」到「这里说明了什么」之间隔着真正的读取与 Repository Analysis。
 *
 * <h2>不变量</h2>
 *
 * <p>区域内不得出现同一个引用两次。这条在解析层已经被拒绝（那里能给出正确的失败语义），
 * 这里再强制一次，是为了让「可信结果不会自相矛盾」由类型本身保证：
 * 一个区域内重复的文件既不表达额外优先级，也不表达额外范围，构造出来就是错的。
 *
 * @param label   查看区域的简短标签，不得为 {@code null} 或空白
 * @param entries 该区域已经解析的描述符，不得为 {@code null} 或空，元素不得为 {@code null}
 * @throws IllegalArgumentException 参数不满足上述约束，或区域内出现重复引用
 */
public record RepositoryInspectionArea(String label, List<RepositoryMapEntry> entries) {

    public RepositoryInspectionArea {
        if (label == null || label.isBlank()) {
            throw new IllegalArgumentException("Repository Inspection Area 必须给出 label");
        }
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "Repository Inspection Area 的 entries 不能为空: " + label);
        }
        Set<RepositoryFileReference> seen = new LinkedHashSet<>();
        for (RepositoryMapEntry entry : entries) {
            if (entry == null) {
                throw new IllegalArgumentException(
                        "Repository Inspection Area 的 entries 不能包含 null: " + label);
            }
            if (!seen.add(entry.reference())) {
                throw new IllegalArgumentException(
                        "Repository Inspection Area 内不得重复引用同一个文件: "
                                + entry.reference().value());
            }
        }
        entries = List.copyOf(entries);
    }
}
