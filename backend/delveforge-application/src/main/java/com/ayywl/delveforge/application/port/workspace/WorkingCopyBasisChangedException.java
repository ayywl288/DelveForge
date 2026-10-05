package com.ayywl.delveforge.application.port.workspace;

/**
 * 实际源仓库已偏离请求使用的规划基线。
 */
public class WorkingCopyBasisChangedException extends RuntimeException {
    public WorkingCopyBasisChangedException(String message) {
        super(message);
    }
}
