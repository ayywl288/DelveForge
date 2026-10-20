package com.ayywl.delveforge.application.evolution.preparation;

/**
 * 可选的只读宿主机审查 Port，只能否决已校验提案，不能授予 Step 执行许可。
 * 提案仍留在本次准备调用内；审查结束后必须重新核对领域与 Workspace 资格。
 */
@FunctionalInterface
public interface ExecutionProposalReviewPort {
    ExecutionProposalReviewPort NONE = proposal -> { };

    void review(ValidatedExecutionProposal proposal);
}
