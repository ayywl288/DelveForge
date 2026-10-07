package com.ayywl.delveforge.application.port.workspace;

import java.util.List;

/**
 * 只读核对托管副本的实际基线、干净状态、隔离及路径可用性，不授予修改能力。
 */
public interface WorkingCopyInspectionPort {
    void requireTrustedState(WorkspaceRef copy, WorkspaceRef source, String expectedRevision);
    void requireSafePaths(WorkspaceRef copy, List<String> normalizedRelativePaths);
}
