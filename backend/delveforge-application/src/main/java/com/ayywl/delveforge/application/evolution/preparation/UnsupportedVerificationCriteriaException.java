package com.ayywl.delveforge.application.evolution.preparation;

import java.util.List;

/** 当前执行器无法为既有标准提供自动证据；索引从 1 开始，不携带标准的自由文本。 */
public final class UnsupportedVerificationCriteriaException extends RuntimeException {

    private final List<Integer> criterionIndexes;

    public UnsupportedVerificationCriteriaException(List<Integer> criterionIndexes) {
        super("Execution verification criteria are unsupported");
        this.criterionIndexes = List.copyOf(criterionIndexes);
    }

    public List<Integer> criterionIndexes() {
        return criterionIndexes;
    }
}
