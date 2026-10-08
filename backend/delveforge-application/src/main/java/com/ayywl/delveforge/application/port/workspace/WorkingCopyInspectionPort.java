package com.ayywl.delveforge.application.port.workspace;

import java.util.List;
import java.util.Map;

/**
 * 只读核对托管副本的实际基线、干净状态、隔离及路径可用性，不授予修改能力。
 */
public interface WorkingCopyInspectionPort {

    void requireTrustedState(WorkspaceRef copy, WorkspaceRef source, String expectedRevision);

    void requireSafePaths(WorkspaceRef copy, List<String> normalizedRelativePaths);

    /** 核对已有修改目标的 blob 可无损表示为 UTF-8，且与调用内原文完全相同。 */
    void requireExactSourceText(WorkspaceRef copy, String revision, Map<String, String> expectedOriginals);
}
