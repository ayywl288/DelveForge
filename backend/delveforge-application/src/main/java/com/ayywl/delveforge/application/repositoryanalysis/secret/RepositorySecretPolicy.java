package com.ayywl.delveforge.application.repositoryanalysis.secret;

import com.ayywl.delveforge.application.repositoryanalysis.extraction.RepositorySourceFile;
import java.util.List;

/**
 * 分析摘要、指南和运行输出的尽力净化，以及历史凭据规则。
 * ADR-0027 起，这些规则不决定选定仓库的执行、文件读取或修改资格。
 * Agent 文件工具返回准确源码；本接口的识别规则不保证任意 Shell 输出保密。
 */
public interface RepositorySecretPolicy {

    /**
     * 这份描述符是否属于「按定义就是凭据载体」的路径，因而整份不进入材料。
     *
     * <p>判定只看路径，不看内容——调用方在**读取之前**用它，因此此刻还没有内容可看。
     * 内容能力策略还可在可信读取后整份保留资源；该判断只负责无内容时的预过滤。
     *
     * @param relativePath 仓库内相对路径，不得为 {@code null}
     * @return 该文件是否应当被排除
     */
    boolean excludes(String relativePath);

    /**
     * 把读到的材料转成**模型可见**的材料：替换掉识别出的凭据字面量。
     *
     * <p>相对路径不变——它是 Evidence 定位文件的依据，改了就不可追溯。
     *
     * @param files 本次分析读到的材料，不得为 {@code null}，元素不得为 {@code null}
     * @return 净化后的材料与替换计数
     * @throws RepositorySecretBoundaryException 边界无法安全完成这次净化
     */
    SanitizedRepositoryMaterial sanitize(List<RepositorySourceFile> files);

    /** 需要净化摘要的读取视图（例如 Quick Start）；不是 Agent 编辑原文。 */
    default String sanitizeSource(String relativePath, String content) {
        return sanitize(List.of(new RepositorySourceFile(relativePath, content))).material().getFirst().content();
    }

    /** 明确的私钥、令牌格式、认证头或连接串；未知实现沿用行保护，保持保守。 */
    default boolean containsHighRiskCredential(String relativePath, String content) {
        return !mutationProtectedLines(relativePath, content).isEmpty();
    }

    /** 检查输出没有完整源码语境；净化已识别形态，不证明任意程序输出无秘密。 */
    default String sanitizeOutput(String content) {
        return sanitize(List.of(new RepositorySourceFile("agent-output.txt", content)))
                .material().getFirst().content();
    }

    /** 写入边界的受保护物理行；未知实现保持整文件保守拒绝，不改变出站净化合同。 */
    default List<Integer> mutationProtectedLines(String relativePath, String content) {
        String visible = sanitize(List.of(new RepositorySourceFile(relativePath, content)))
                .material().getFirst().content();
        if (visible.equals(content)) {
            return List.of();
        }
        return java.util.stream.IntStream.rangeClosed(1, content.split("\n", -1).length).boxed().toList();
    }

}
