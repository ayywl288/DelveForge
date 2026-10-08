package com.ayywl.delveforge.application.port.workspace;

/**
 * Evolution Execution 专用的受控修改能力，与只读 Port 独立，避免分析流程获得写权限。
 * 请求必须绑定已授权 Step 的 WorkingCopy 和校验补丁；Infrastructure 不得修改原资产，
 * 也不得将此能力解释为任意文件系统访问（RULE-ARCH-010、RULE-ARCH-011、RULE-DOM-005）。
 */
public interface WorkspaceMutationPort {

    /** 只应用已授权检查点绑定的校验提案，返回独立检查后的实际 Candidate 事实。 */
    CandidateMutationResult applyCandidate(AuthorizedStepExecution execution);
}
