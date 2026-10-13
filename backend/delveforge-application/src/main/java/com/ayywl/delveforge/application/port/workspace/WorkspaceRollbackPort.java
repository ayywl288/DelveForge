package com.ayywl.delveforge.application.port.workspace;

import java.time.Instant;

/** 窄恢复能力：只恢复本次已授权 Step 的精确 baseline，不清理额外用户文件。 */
public interface WorkspaceRollbackPort {
    RestoredBaseline restoreBaseline(AuthorizedStepRollback authorization);

    /** 仅在实际 HEAD、index 与工作树均核对后返回；不是通用 Git 命令或 Recovery 请求。 */
    record RestoredBaseline(String revision, Instant restoredAt) {}
}
