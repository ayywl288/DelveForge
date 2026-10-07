package com.ayywl.delveforge.application.port.workspace;

/** 待修改 blob 无法无损表示为本次提案使用的 UTF-8 原文。 */
public final class WorkingCopyTextNotExactException extends RuntimeException {
    public WorkingCopyTextNotExactException(String message) {
        super(message);
    }
}
