import type { ProjectStage } from "../api/types";

export const stageLabels: Record<ProjectStage, string> = {
  DISCOVERY: "需求探索", REPOSITORY: "理解仓库", DIRECTION: "产品方向", PLANNING: "演化规划", EVOLUTION: "验证演化",
};
export function stageIndex(stage: string | null): number {
  return ["DISCOVERY", "REPOSITORY", "DIRECTION", "PLANNING", "EVOLUTION"].indexOf(stage ?? "");
}
const issues: Record<string, string> = {
  ASSOCIATION_MISMATCH: "保存的对象关联不一致，不能视为完整恢复。请核对数据，不能继续执行。",
  PROFILE_BASIS_CHANGED: "当前画像的版本或确认状态已变化；历史方向和 Plan 仍绑定原来的画像版本。",
  DIRECTION_NOT_SELECTED: "此 Plan 的方向当前不是已选择状态。历史结果可查看，继续操作仍受原领域规则限制。",
  ASSET_AUTHORIZATION_REQUIRED: "基础仓库当前授权不足。请显式补充许可与授权，历史结果没有被改写。",
  EXECUTION_IN_PROGRESS_OR_UNRESOLVED: "后端保存了执行中或验证中的检查点。请只读查询；恢复页面不代表重启、取消或重试执行。",
  WORKSPACE_ROOT_NOT_ALLOWED: "WorkingCopy 位于当前配置未允许的历史 Root。需要按本地说明配置 existingRoots 并重启；不要移动或重建旧副本。",
  WORKSPACE_DIRECTORY_MISSING: "WorkingCopy 目录缺失。历史检查结果仍可查看，不能认为物理代码可用。",
  WORKSPACE_PATH_INVALID: "保存的 WorkingCopy 路径不符合当前存储边界。",
  WORKSPACE_PATH_UNAVAILABLE: "WorkingCopy 路径不可读取或存在链接重定向，不能继续执行。",
};
export function projectIssueText(code: string): string {
  return issues[code] ?? (code.endsWith("_MISSING") ? "关联资源不存在，项目仅部分恢复。"
    : code.endsWith("_UNREADABLE") ? "关联资源无法读取，项目仅部分恢复。" : "此项目有需要核对的问题。");
}
