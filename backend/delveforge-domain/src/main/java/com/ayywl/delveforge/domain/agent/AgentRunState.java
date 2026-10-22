package com.ayywl.delveforge.domain.agent;

public enum AgentRunState {
    CREATED, PREPARING, RUNNING, FINALIZING, SUCCEEDED, FAILED, CANCELLED, INTERRUPTED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
    }

    public boolean canAdvanceTo(AgentRunState next) {
        if (terminal()) {
            return false;
        }
        return next == FAILED || next == CANCELLED || next == INTERRUPTED
                || this == CREATED && next == PREPARING
                || this == PREPARING && next == RUNNING
                || this == RUNNING && next == FINALIZING
                || this == FINALIZING && next == SUCCEEDED;
    }
}
