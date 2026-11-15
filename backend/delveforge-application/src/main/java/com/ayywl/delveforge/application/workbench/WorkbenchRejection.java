package com.ayywl.delveforge.application.workbench;

/** 供本地工作台接口归类的业务拒绝，技术故障保持独立。 */
public final class WorkbenchRejection extends RuntimeException {
    public enum Kind {
        INVALID,
        MISSING,
        BUSY,
        FORBIDDEN
    }

    private final Kind kind;

    public WorkbenchRejection(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
