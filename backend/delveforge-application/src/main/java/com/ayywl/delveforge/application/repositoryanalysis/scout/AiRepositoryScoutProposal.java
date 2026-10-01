package com.ayywl.delveforge.application.repositoryanalysis.scout;

import java.util.List;

/**
 * AI 提出的 Repository 侦察提议：若干有序的查看区域。
 *
 * <pre>
 * RepositoryMap 的描述符清单 → Scout → AiRepositoryScoutProposal
 *                                          ↓ 引用校验
 *                                   RepositoryInspectionPlan
 * </pre>
 *
 * <h2>它回答的只是「去哪里看」</h2>
 *
 * <p>提议不包含任何关于仓库的结论。模型看到的是文件名、路径、大小与结构提示——看不到代码，
 * 因此它没有资格断言「这个项目实现了什么」。它只能指出「接下来应该读哪些文件」。
 *
 * <p>这个边界同时也是 {@code label} 的边界：查看区域的标签是**查看意图**，
 * 不是已经确认的仓库能力、可复用资产或风险。后者只有内容真的被读进来、
 * 经过 Repository Analysis 的解析与校验之后才成立。
 *
 * <h2>不可信输入</h2>
 *
 * <p>本类型是 AI 通信协议的一部分，不是领域对象，也不是可信结果。它里面每个引用的合法性
 * 都由 {@link RepositoryScoutProposalResolver} 依据建立本次调用时的 {@link RepositoryScoutInputs}
 * 校验；校验通过之后才产生 {@link RepositoryInspectionPlan}。
 *
 * <p>本类型只校验形状（非空、元素非空）。区域数量、引用格式与区域内重复这些**与模型约定的
 * 契约**由 {@link RepositoryScoutProposalParser} 负责拒绝——那里才知道「这份内容来自模型」，
 * 因而能给出正确的失败语义。
 *
 * @param focusAreas 有序的查看区域，不得为 {@code null} 或空，元素不得为 {@code null}
 */
public record AiRepositoryScoutProposal(List<AiRepositoryScoutFocusArea> focusAreas) {

    /** 一次侦察要求的最少查看区域数。 */
    public static final int MIN_FOCUS_AREAS = 3;

    /** 一次侦察要求的最多查看区域数。 */
    public static final int MAX_FOCUS_AREAS = 6;

    public AiRepositoryScoutProposal {
        if (focusAreas == null || focusAreas.isEmpty()) {
            throw new IllegalArgumentException("Scout 提议必须给出 focusAreas");
        }
        for (AiRepositoryScoutFocusArea area : focusAreas) {
            if (area == null) {
                throw new IllegalArgumentException("Scout 提议的 focusAreas 不能包含 null");
            }
        }
        focusAreas = List.copyOf(focusAreas);
    }
}
