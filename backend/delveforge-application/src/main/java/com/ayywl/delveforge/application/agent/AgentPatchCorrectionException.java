package com.ayywl.delveforge.application.agent;

/** 只用于已证明未应用的补丁修正停止；不是应用后失败的恢复凭据。 */
public final class AgentPatchCorrectionException extends AgentException {
    public AgentPatchCorrectionException(boolean repeated) {
        super(repeated ? "PATCH_REPEATED_CONFLICT" : "PATCH_CORRECTION_LIMIT",
                "Patch correction stopped before application");
    }
}
