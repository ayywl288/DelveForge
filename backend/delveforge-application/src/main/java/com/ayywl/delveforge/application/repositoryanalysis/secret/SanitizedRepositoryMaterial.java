package com.ayywl.delveforge.application.repositoryanalysis.secret;

import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过凭据边界之后的材料：**模型可见**的那一份，加上替换计数。
 *
 * <pre>
 * 读取到的材料（raw）
 *         ↓  凭据边界
 * SanitizedRepositoryMaterial（safe/model-visible）
 * </pre>
 *
 * <h2>它与原始材料同形，因此必须靠类型名而不是结构来区分</h2>
 *
 * <p>内容仍然是「相对路径 + 文本」，路径一个都没变（变了就没法与 Evidence 对应）。
 * 它与输入的唯一差别在于**文本已经过净化**，而这一点在结构上看不出来——
 * 所以这里用一个独立类型承载它，让「这一份是给模型的」成为调用链上显式的事实，
 * 而不是一句注释。
 *
 * <h2>替换计数是安全诊断</h2>
 *
 * <p>{@code replacedSpans} 只记「替换了几处」，不含被替换的取值，也不含它所在的路径。
 * 它是给运维看的一行聚合数字：知道边界起了作用，但看不出起了什么作用。
 *
 * @param material      净化后的材料，不得为 {@code null}，元素不得为 {@code null}
 * @param replacedSpans 本次替换掉的凭据字面量处数，不得为负数
 */
public record SanitizedRepositoryMaterial(List<RepositorySourceFile> material,
                                          int replacedSpans) {

    public SanitizedRepositoryMaterial {
        if (material == null) {
            throw new IllegalArgumentException(
                    "SanitizedRepositoryMaterial 的 material 不能为 null");
        }
        if (replacedSpans < 0) {
            throw new IllegalArgumentException(
                    "SanitizedRepositoryMaterial 的 replacedSpans 不能为负数: " + replacedSpans);
        }
        List<RepositorySourceFile> copy = new ArrayList<>(material.size());
        for (RepositorySourceFile file : material) {
            if (file == null) {
                throw new IllegalArgumentException(
                        "SanitizedRepositoryMaterial 的 material 不能包含 null");
            }
            copy.add(file);
        }
        material = List.copyOf(copy);
    }

    public int size() {
        return material.size();
    }
}
