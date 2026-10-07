package com.ayywl.delveforge.application.evolution.preparation;

import java.util.List;

/** 仅 Application 校验器可构造；调用内结果不能替代 mutation 前的重新核对。 */
public final class ValidatedExecutionProposal {
    private final ExecutionPreparationBasis basis;
    private final CodingProposal proposal;
    private final List<String> modelVisiblePaths;

    ValidatedExecutionProposal(ExecutionPreparationBasis basis, CodingProposal proposal, List<String> visible) {
        this.basis = basis;
        this.proposal = proposal;
        this.modelVisiblePaths = List.copyOf(visible);
    }

    public ExecutionPreparationBasis basis() { return basis; }
    public CodingProposal proposal() { return proposal; }
    public List<String> modelVisiblePaths() { return modelVisiblePaths; }
}
