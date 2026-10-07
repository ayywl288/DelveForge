package com.ayywl.delveforge.application.evolution.preparation;

import java.util.List;

/** AI 未接受提案，不拥有领域状态或修改权限。 */
public record CodingProposal(String expectedRevision, String summary, List<String> declaredAffectedPaths,
        String unifiedDiff, List<VerificationAction> verificationStrategy) {
    public CodingProposal {
        declaredAffectedPaths = List.copyOf(declaredAffectedPaths);
        verificationStrategy = List.copyOf(verificationStrategy);
    }

    public enum Action { BUILD, TEST, FOCUSED_TEST, DIFF_CHECK }

    /** target 是验证意图，不是可执行 shell 命令；后续验证能力须重新解析允许的动作。 */
    public record VerificationAction(Action action, String target, List<Integer> criterionIndexes) {
        public VerificationAction {
            criterionIndexes = List.copyOf(criterionIndexes);
        }
    }
}
