import type { EvolutionStep, ExecutionResult, VerificationResult } from "../api/types";

export interface StepTraceNode {
  key: string;
  label: string;
  result: string;
  time: string | null;
  tone: "success" | "danger" | "neutral";
  details: string[];
}
export interface StepTraceRound { key: string; label: string; nodes: StepTraceNode[] }
type Repairs = NonNullable<EvolutionStep["repairAuthorizations"]>;
type Facts = Pick<EvolutionStep, "executionResult" | "verificationResult" | "executionFailureResult"
  | "automaticRollbackResult" | "rollbackAuthorization" | "rollbackResult">;

// 按快照归属排列；授权时间不是执行开始时间，不用时间排序或浏览器 receipt 补事件。
export function stepEvolutionTrace(step: EvolutionStep): StepTraceRound[] {
  function round(key: string, label: string, repairs: Repairs, facts: Facts): StepTraceRound {
    const nodes: StepTraceNode[] = [];
    function add(label: string, result: string, time: string | null, details: string[], tone: StepTraceNode["tone"] = "neutral") {
      nodes.push({ key: `${key}-${nodes.length}`, label, result, time, details, tone });
    }
    function candidate(execution: ExecutionResult | null, verification: VerificationResult | null, repaired: boolean) {
      if (execution) add(repaired ? "Repair Candidate" : "Candidate", "执行产生候选版本", execution.executedAt,
        [`Candidate ${execution.candidateRevision}`, `本轮 ${execution.affectedPaths.length} 个文件`, ...execution.changes]);
      if (verification) add("Verification", verification.passed ? "PASS" : "FAIL", verification.verifiedAt,
        [`Candidate ${verification.candidateRevision}`, verification.summary, ...verification.failures],
        verification.passed ? "success" : "danger");
    }
    repairs.forEach((repair, index) => {
      candidate(repair.previousExecution, repair.previousVerification, index > 0);
      add("Repair", "已独立授权", repair.authorizedAt, [`授权 ${repair.id}`, `基于 ${repair.candidateRevision}`]);
    });
    candidate(facts.executionResult, facts.verificationResult, repairs.length > 0);
    const failure = facts.executionFailureResult;
    if (failure) add("Execution", "FAIL · 执行明确失败", failure.failedAt,
      [`失败 ${failure.id}`, `阶段 ${failure.phase}`, failure.diagnostic, `起点 ${failure.startingRevision}`], "danger");
    const authorization = facts.rollbackAuthorization;
    if (authorization) add("User Rollback", "已独立授权", authorization.authorizedAt,
      [`授权 ${authorization.id}`, `Candidate ${authorization.candidateRevision}`, `baseline ${authorization.baselineRevision}`]);
    const rollback = facts.automaticRollbackResult ?? facts.rollbackResult;
    if (rollback) add(facts.automaticRollbackResult ? "Automatic Rollback" : "User Rollback",
      rollback.restored ? "恢复确认 · FAILED" : "恢复未确认 · RECOVERY_REQUIRED", rollback.completedAt,
      [rollback.diagnostic, `baseline ${rollback.baselineRevision}`, "状态来自回滚结果，未记录独立状态切换时间"],
      rollback.restored ? "neutral" : "danger");
    return { key, label, nodes };
  }
  const rounds = (step.retryAuthorizations ?? []).map((retry, index) => {
    const item = round(`attempt-${index}`, `第 ${index + 1} 轮 · 已归档`, retry.previousRepairs, {
      executionResult: retry.previousExecution, verificationResult: retry.previousVerification,
      executionFailureResult: retry.previousExecutionFailure, automaticRollbackResult: retry.previousAutomaticRollback,
      rollbackAuthorization: retry.previousRollbackAuthorization, rollbackResult: retry.previousRollbackResult,
    });
    item.nodes.push({ key: `${item.key}-retry-${retry.id}`, label: "Retry", result: "已授权完整重试",
      time: retry.authorizedAt, tone: "neutral", details: [`授权 ${retry.id}`, `此前失败 ${retry.failedCheckpointId}`,
        "此时间为授权时间，不代表已再次 Execute"] });
    return item;
  });
  const current = round("current", `第 ${rounds.length + 1} 轮 · 当前`, step.repairAuthorizations ?? [], step);
  current.nodes.push({ key: "current-status", label: "当前状态", result: step.status, time: null,
    tone: step.status === "SUCCEEDED" ? "success" : ["RECOVERY_REQUIRED", "VERIFICATION_FAILED"].includes(step.status) ? "danger" : "neutral",
    details: [step.status === "READY" ? "READY 不证明准备请求已结束；Retry 授权后仍须显式执行。" : "状态切换时间未记录。"] });
  rounds.push(current);
  return rounds;
}
