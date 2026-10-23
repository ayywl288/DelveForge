import type { EvolutionPlan, EvolutionStep, WorkingCopy } from "../api/types";

// 仅解释当前前端快照中的前提；授权、物理 revision 和运行占用仍由后端重查。
export function stepPrerequisiteIssue(plan: EvolutionPlan, step: EvolutionStep, copy: WorkingCopy | null): string {
  if (plan.status === "PROPOSED") return "请先在演化规划页审阅计划并准备隔离副本，再授权此 Step。";
  if (plan.status === "SUPERSEDED") return "这份计划已失效，仅供查看历史，不能继续授权或执行。";
  if (plan.status !== "ACTIVE") return "当前计划不处于进行中，不能授权或开始新的 Step。";
  if (!copy) return "尚未读取可用的隔离副本，请前往演化规划核对准备状态。";
  if (copy.id !== plan.workingCopyId || copy.sourceAssetId !== plan.baseAssetId) {
    return "隔离副本与计划的绑定不一致，请重新查询计划及副本。";
  }
  if (!["READY", "EVOLVING"].includes(copy.status)) return "隔离副本尚不可用，请先核对副本状态。";
  if (!copy.lastVerifiedRevision?.trim() || copy.currentRevision !== copy.lastVerifiedRevision) {
    return "当前代码与最近验证版本不一致，不能授权或开始新的 Step。";
  }
  if (step.baselineRevision && step.baselineRevision !== copy.lastVerifiedRevision) {
    return "此 Step 的执行基线与最近验证版本不一致，请核对历史记录。";
  }
  const index = plan.steps.findIndex(item => item.id === step.id);
  if (index < 0) return "此 Step 不属于当前计划，请重新查询。";
  if (plan.steps.slice(0, index).some(item => item.status !== "SUCCEEDED")) {
    return "请先完成并验证前序 Step，再授权或开始此步骤。";
  }
  if (plan.steps.some(item => item.status === "RECOVERY_REQUIRED")) {
    return "计划存在尚未解决的 Step 状态，需先核对恢复情况。";
  }
  return "";
}
