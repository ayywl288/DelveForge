package com.ayywl.delveforge.application.port.workspace;

/**
 * 实际源仓库偏离规划基线，或托管副本已偏离本次操作的可信状态。
 */
public class WorkingCopyBasisChangedException extends RuntimeException {
    public WorkingCopyBasisChangedException(String message) {
        super(message);
    }
}
