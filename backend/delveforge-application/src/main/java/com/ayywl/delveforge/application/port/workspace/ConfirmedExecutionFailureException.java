package com.ayywl.delveforge.application.port.workspace;

import com.ayywl.delveforge.domain.evolution.ExecutionFailureResult;

/** 仅由已结束且明确失败的受控执行构造；超时、中断、崩溃或丢失结果不能使用此类型。 */
public final class ConfirmedExecutionFailureException extends RuntimeException {
    private final ExecutionFailureResult result;

    public ConfirmedExecutionFailureException(ExecutionFailureResult result) {
        super("Controlled execution failure: " + result.phase());
        this.result = result;
    }

    public ExecutionFailureResult result() { return result; }
}
