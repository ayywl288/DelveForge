package com.ayywl.delveforge.application.agent;

/** 命令已经执行但捕获失败；携带受控终态，调用方必须记录并停止，不能重放。 */
public final class AgentCommandCaptureException extends AgentException {
    private final String result;

    public AgentCommandCaptureException(String result, RuntimeException cause) {
        super("COMMAND_CAPTURE_FAILED", "Command executed; post-execution capture failed", cause);
        this.result = result;
    }

    public String result() {
        return result;
    }
}
