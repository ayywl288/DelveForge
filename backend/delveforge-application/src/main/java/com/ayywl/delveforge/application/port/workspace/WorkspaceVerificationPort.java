package com.ayywl.delveforge.application.port.workspace;

/** 有限 Workspace 验证能力，只返回实际技术事实；Step 成功与 revision 信任由 Domain 决定。 */
public interface WorkspaceVerificationPort {

    /** mutation 前检查仓库及验证意图是否在支持范围内，不代表验证已通过。 */
    void requireSupported(CandidateVerification request);

    /** 在请求指定的 Candidate 上执行检查；不得用其他 revision 或旧报告替代本次证据。 */
    VerificationFacts verify(CandidateVerification request);
}
