package com.ayywl.delveforge.application.repositoryanalysis.extraction;

/**
 * AI 提出的一条 Repository 分析依据。
 *
 * <p>它对应领域中的 Evidence，但在被接受之前只是 AI 边界上的中间数据：
 * 是否成立、是否被记录，由后续创建 Repository Profile 的 Application / Domain 决定。
 *
 * <p>与 User Profile 的 Evidence 不同，这里的 {@code sourceRef} 由模型给出：Repository 分析的
 * 依据必须能够指回 Repository 中具体的代码、配置或结构，而那是模型在材料里看到的东西。
 * 因此它的含义保持最小——{@code sourceRef} 就是材料中的一个相对路径，
 * 本 Task 不为它设计更细的定位方式（行号、片段、符号等）。
 *
 * <p>可信程度与确认状态刻意不由模型给出：模型可以指出「依据在哪里」，
 * 但不能自行断言这条依据有多可信、或者已经被确认。
 *
 * @param claim      该依据支撑的判断，不得为空
 * @param sourceRef  Repository 中可定位到该依据的相对路径，不得为空
 */
public record RepositoryEvidenceProposal(String claim, String sourceRef) {

    public RepositoryEvidenceProposal {
        if (claim == null || claim.isBlank()) {
            throw new IllegalArgumentException("Evidence 必须给出 claim");
        }
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new IllegalArgumentException("Repository Evidence 必须给出 sourceRef");
        }
    }
}
