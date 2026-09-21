package com.ayywl.delveforge.application.repositoryanalysis.extraction;

import java.util.List;

/**
 * AI 对一次 Repository 分析提出的结构化结果。
 *
 * <p>它是 AI 边界上的中间数据，不是领域对象（DOMAIN_MODEL.md §14.11）：
 * 在被 Repository Profile Aggregate 接受之前，它不构成任何合法领域状态，
 * 也不携带领域身份、revision 或生命周期。
 *
 * <p>字段与 {@code RepositoryProfile} 需要的分析内容一一对应，但这里不复制领域规则：
 * 「内容是否合法」由 Aggregate 判定（RULE-DOM-002），本类型只承载解析出来的值。
 *
 * <p>与 User Profile 的提议不同，这里的字段都是「本次分析的完整结论」而不是增量：
 * 一次 Repository 分析没有「上一版 Profile」可以合并，缺少某个字段意味着模型没有
 * 回答该部分，因此解析层把「字段缺失」当作不合法，而不是当作「本次不涉及」。
 *
 * @param purpose        该 Repository 当前解决的问题或主要用途
 * @param techStack      主要技术栈，可以为空
 * @param modules        主要业务或技术模块，可以为空
 * @param capabilities   当前已经具备的核心能力，可以为空
 * @param reusableAssets 具有直接复用或演化价值的能力、模块或实现，可以为空
 * @param limitations    当前项目的重要限制，可以为空
 * @param risks          对后续演化可能产生影响的技术风险，可以为空
 * @param evidence       支撑以上判断的依据，可以为空
 */
public record RepositoryAnalysisProposal(
        String purpose,
        List<String> techStack,
        List<String> modules,
        List<String> capabilities,
        List<String> reusableAssets,
        List<String> limitations,
        List<String> risks,
        List<RepositoryEvidenceProposal> evidence) {
}
