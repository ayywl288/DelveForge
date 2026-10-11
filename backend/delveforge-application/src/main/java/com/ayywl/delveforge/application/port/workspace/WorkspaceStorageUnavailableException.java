package com.ayywl.delveforge.application.port.workspace;

/** 后端配置的托管存储不可用；不会改用其他根目录或接管已有目标。 */
public final class WorkspaceStorageUnavailableException extends WorkspaceException {

    public WorkspaceStorageUnavailableException(String message) {
        super(message);
    }

    public WorkspaceStorageUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
