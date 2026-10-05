package com.ayywl.delveforge.application.port.workspace;

/**
 * 环境准备能力与仓库读取、EvolutionStep 代码修改能力独立。
 * 输入仅为技术引用，不接收 Plan / Profile / Direction 或授权对象。
 * Adapter 管理配置的托管根目录，directoryName 只能指定其直接子目录。
 */
public interface WorkingCopyProvisioningPort {
    /**
     * 独立 clone、checkout 并核对精确 source revision。
     * 拒绝已移动的源 HEAD、已有或不安全的目标目录。
     * 失败时尽力清理本次调用创建的目录，不能处理其他目录。
     */
    PreparedWorkspace provision(WorkspaceRef source, String revision, String directoryName);

    /**
     * Domain 或数据库提交失败后，尽力补偿清理本次拥有的托管准备目录。
     * 清理失败必须报告，不能静默视为成功。
     */
    void discard(PreparedWorkspace candidate);
}
