// 仅供自动化测试使用的合成数据。不是真实 Provider 或 Verified B 证据。
import type {
  EvolutionPlan,
  ProductDirection,
  RepositoryProfile,
  SoftwareAsset,
  UserProfile,
  WorkingCopy,
} from "../src/api/types";
export const profile = (): UserProfile => ({
  id: "profile-1",
  revision: 1,
  status: "EXPLORING",
  interests: [],
  behaviors: [],
  painPoints: [],
  technicalCapabilities: [],
  projectGoals: [],
  constraints: [],
  evidence: [],
});
export const asset = (): SoftwareAsset => ({
  id: "asset-1",
  type: "GIT_REPOSITORY",
  source: "USER_SPECIFIED",
  location: "C:\\projects\\sample",
  readPermissionAllowed: true,
  licenseInfo: "MIT",
  usageAuthorization: "ALLOWED",
});
export const repository = (): RepositoryProfile => ({
  id: "repo-1",
  assetId: "asset-1",
  analyzedRevision: "a".repeat(40),
  purpose: "Java 报表工具",
  techStack: ["Java", "Maven"],
  modules: ["reports"],
  capabilities: ["导出报表"],
  reusableAssets: ["ReportService"],
  limitations: ["只能手动执行"],
  risks: ["插件会在宿主执行"],
  evidence: [],
});
export const direction = (): ProductDirection => ({
  id: "direction-1",
  userProfileId: "profile-1",
  userProfileRevision: 2,
  repositoryProfileIds: ["repo-1"],
  title: "定时报表",
  problem: "每周手工重复导出",
  targetProduct: "可配置的定时报表工具",
  userFit: "减少重复工作",
  candidateAssetIds: ["asset-1"],
  differentiation: "复用原有导出",
  technicalValue: "练习调度与测试",
  estimatedComplexity: "小",
  risks: ["时区"],
  status: "CANDIDATE",
  evidenceSupport: { userNeed: [], userFit: [], reusableCapability: [] },
});
export const plan = (): EvolutionPlan => ({
  id: "plan-1",
  productDirectionId: "direction-1",
  baseAssetId: "asset-1",
  baseRepositoryProfileId: "repo-1",
  workingCopyId: "copy-1",
  currentState: {
    summary: "手动导出",
    capabilities: ["报表"],
    modules: ["reports"],
    limitations: ["手工"],
  },
  targetState: {
    problem: "重复操作",
    targetProduct: "定时报表",
    differentiation: "保留原有逻辑",
  },
  reusableCapabilities: ["ReportService"],
  changes: ["新增调度配置"],
  risks: ["宿主插件"],
  evidence: [],
  status: "ACTIVE",
  steps: [
    {
      id: "step-1",
      planId: "plan-1",
      goal: "支持周期调度",
      scope: "reports",
      plannedChanges: ["新增调度配置"],
      verificationCriteria: [
        { kind: "TESTS_PASS", description: "调度测试通过" },
      ],
      status: "READY",
      baselineRevision: null,
      executionResult: null,
      verificationResult: null,
    },
  ],
});
export const copy = (): WorkingCopy => ({
  id: "copy-1",
  sourceAssetId: "asset-1",
  location: "C:\\isolated\\copy-1",
  sourceRevision: "a".repeat(40),
  currentRevision: "a".repeat(40),
  lastVerifiedRevision: "a".repeat(40),
  status: "READY",
});
export function successfulPlan(): EvolutionPlan {
  const result = plan();
  result.status = "COMPLETED";
  result.steps[0]!.status = "SUCCEEDED";
  result.steps[0]!.baselineRevision = "a".repeat(40);
  result.steps[0]!.executionResult = {
    candidateRevision: "b".repeat(40),
    changes: ["新增调度配置"],
    affectedPaths: ["src/ReportService.java"],
    warnings: [],
    errors: [],
    executedAt: "2026-10-10T00:00:00Z",
  };
  result.steps[0]!.verificationResult = {
    candidateRevision: "b".repeat(40),
    passed: true,
    failures: [],
    summary: "合成测试验证结果",
    verifiedAt: "2026-10-10T00:01:00Z",
    checks: [
      {
        action: "TEST",
        target: "",
        criterionIndexes: [1],
        passed: true,
        summary: "合成测试通过",
      },
    ],
  };
  return result;
}
