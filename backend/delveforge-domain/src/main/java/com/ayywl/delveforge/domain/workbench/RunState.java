package com.ayywl.delveforge.domain.workbench;

public enum RunState {
    QUEUED,
    RUNNING,
    STOPPING,
    RECONCILING,
    BLOCKED,
    PAUSED,
    COMPLETED,
    STOPPED,
    FAILED;

    public boolean ownsForeground() {
        return this == RUNNING || this == STOPPING || this == RECONCILING || this == BLOCKED;
    }
}
