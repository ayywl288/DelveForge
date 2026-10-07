package com.ayywl.delveforge.application.port.workspace;

/**
 * Workspace Gateway 的代码修改能力。
 *
 * <p>该能力单独成 Port，使只读流程无法通过类型系统获得修改权限（RULE-ARCH-010）。
 *
 * <p>在 MVP 中，Evolution Execution 是唯一允许请求该能力的业务流程（RULE-ARCH-011）。
 * 即使在该流程内：
 *
 * <pre>
 * write capability
 *     ≠
 * unrestricted filesystem access
 * </pre>
 *
 * <p>调用方必须传入当前已授权的 Evolution Step 所绑定 Working Copy 对应的
 * {@link AuthorizedStepExecution}，不得对原始 Software Asset 调用本接口（RULE-DOM-005）。
 * 写能力不等于无限制文件系统访问：具体作用范围由 Adapter 依据绑定副本和校验补丁限定。
 *
 * <p>实现由 Infrastructure 层提供。
 */
public interface WorkspaceMutationPort {

    /** 只应用已授权检查点绑定的校验提案，返回独立检查后的实际 Candidate 事实。 */
    CandidateMutationResult applyCandidate(AuthorizedStepExecution execution);
}
