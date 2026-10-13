package com.ayywl.delveforge.application.port.workspace;

/** 恢复未被确认；保留有限阶段与可用的实际 HEAD，不暴露 Git 输出或用户文件内容。 */
public final class WorkingCopyRollbackException extends WorkspaceException {
    private final String phase;
    private final String observedRevision;
    private final Integer exitCode;

    public WorkingCopyRollbackException(String phase, String observedRevision, RuntimeException cause) {
        this(phase, observedRevision, null, cause);
    }

    public WorkingCopyRollbackException(String phase, String observedRevision, Integer exitCode, RuntimeException cause) {
        super("Working Copy rollback was not confirmed at " + phase, cause);
        this.phase = phase;
        this.observedRevision = observedRevision;
        this.exitCode = exitCode;
    }

    public String phase() { return phase; }
    public String observedRevision() { return observedRevision; }
    public Integer exitCode() { return exitCode; }
}
