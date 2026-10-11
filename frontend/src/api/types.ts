export interface Evidence {
  sourceType: string;
  sourceRef: string;
  claim: string;
  confidence: number | null;
  confirmed: boolean;
}
export interface EvidenceBasis {
  evidence: Evidence;
  origin: {
    kind: string;
    userProfileId: string | null;
    userProfileRevision: number | null;
    repositoryProfileId: string | null;
  };
}
export const profileSections = [
  { key: "interests", label: "兴趣", hint: "你愿意长期投入的事情" },
  { key: "behaviors", label: "行为", hint: "日常工作与使用习惯" },
  { key: "painPoints", label: "痛点", hint: "反复遇到的不便" },
  {
    key: "technicalCapabilities",
    label: "技术能力",
    hint: "已有经验与希望练习的技术",
  },
  { key: "projectGoals", label: "项目目标", hint: "希望做出的产品与结果" },
  { key: "constraints", label: "约束", hint: "时间、资源与使用边界" },
] as const;
export type ProfileSection = (typeof profileSections)[number]["key"];
export type ProfilePatch = Partial<Record<ProfileSection, string[]>>;
export interface UserProfile extends Record<ProfileSection, string[]> {
  id: string;
  status: "EXPLORING" | "REVIEWING" | "CONFIRMED";
  revision: number;
  evidence: Evidence[];
}
export interface Sufficiency {
  sufficient: boolean;
  missingAreas: string[];
  nextQuestion: string | null;
}
export interface DiscoveryTurn extends Sufficiency {
  profile: UserProfile;
}
export interface Assessment extends Sufficiency {
  profileStatus: UserProfile["status"];
}
export interface DiscoveryQuestion extends Sufficiency {
  revision: number;
}
export type UsageAuthorization = "ALLOWED" | "DENIED" | "UNCLEAR";
export interface AssetInput {
  location: string;
  readPermissionAllowed: boolean;
  licenseInfo: string | null;
  usageAuthorization: UsageAuthorization;
}
export interface SoftwareAsset extends AssetInput {
  authorizationRevision: number;
  id: string;
  type: string;
  source: string;
}
export type AssetAuthorizationInput = Omit<AssetInput, "location"> & {
  expectedAuthorizationRevision: number;
};
export interface RepositoryProfile {
  id: string;
  assetId: string;
  analyzedRevision: string;
  purpose: string;
  techStack: string[];
  modules: string[];
  capabilities: string[];
  reusableAssets: string[];
  limitations: string[];
  risks: string[];
  evidence: Evidence[];
}
export interface ProductDirection {
  id: string;
  userProfileId: string;
  userProfileRevision: number;
  repositoryProfileIds: string[];
  title: string;
  problem: string;
  targetProduct: string;
  userFit: string;
  candidateAssetIds: string[];
  differentiation: string;
  technicalValue: string;
  estimatedComplexity: string;
  risks: string[];
  status: "CANDIDATE" | "SELECTED" | "REJECTED" | "SUPERSEDED";
  evidenceSupport: {
    userNeed: EvidenceBasis[];
    userFit: EvidenceBasis[];
    reusableCapability: EvidenceBasis[];
  };
}
export interface ExecutionResult {
  candidateRevision: string;
  changes: string[];
  affectedPaths: string[];
  warnings: string[];
  errors: string[];
  executedAt: string;
}
export interface VerificationResult {
  candidateRevision: string;
  passed: boolean;
  failures: string[];
  summary: string;
  verifiedAt: string;
  checks: {
    action: string;
    target: string;
    criterionIndexes: number[];
    passed: boolean;
    summary: string;
  }[];
}
export interface EvolutionStep {
  id: string;
  planId: string;
  goal: string;
  scope: string;
  plannedChanges: string[];
  verificationCriteria: { kind: string; description: string }[];
  status:
    | "PENDING_CONFIRMATION"
    | "READY"
    | "EXECUTING"
    | "VERIFYING"
    | "VERIFICATION_FAILED"
    | "ROLLING_BACK"
    | "SUCCEEDED"
    | "FAILED"
    | "RECOVERY_REQUIRED"
    | "CANCELLED";
  baselineRevision: string | null;
  executionResult: ExecutionResult | null;
  verificationResult: VerificationResult | null;
}
export interface EvolutionPlan {
  id: string;
  productDirectionId: string;
  baseAssetId: string;
  baseRepositoryProfileId: string;
  workingCopyId: string | null;
  currentState: {
    summary: string;
    capabilities: string[];
    modules: string[];
    limitations: string[];
  };
  targetState: {
    problem: string;
    targetProduct: string;
    differentiation: string;
  };
  reusableCapabilities: string[];
  changes: string[];
  risks: string[];
  evidence: EvidenceBasis[];
  steps: EvolutionStep[];
  status: "PROPOSED" | "ACTIVE" | "COMPLETED" | "ABANDONED" | "SUPERSEDED";
}
export interface WorkspaceConfiguration {
  root: string | null;
  existingRoots: string[];
}
export interface WorkingCopy {
  id: string;
  sourceAssetId: string;
  sourceRevision: string;
  location: string;
  currentRevision: string;
  lastVerifiedRevision: string;
  status:
    | "CREATING"
    | "READY"
    | "FAILED"
    | "EVOLVING"
    | "RECOVERY_REQUIRED"
    | "CLOSED"
    | "UNRECOVERABLE";
}

export type ProjectStage = "DISCOVERY" | "REPOSITORY" | "DIRECTION" | "PLANNING" | "EVOLUTION";
export interface ProjectContext {
  id: string;
  name: string;
  userProfileId: string | null;
  assetIds: string[];
  repositoryProfileIds: string[];
  directionIds: string[];
  planId: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
}
export interface ProjectSummary {
  key: string;
  name: string;
  legacy: boolean;
  stage: ProjectStage;
  direction: string | null;
  repositories: { assetId: string; location: string }[];
  lastActivity: string | null;
  totalSteps: number;
  succeededSteps: number;
  stepCounts: Record<string, number>;
  planId: string | null;
  planStatus: string | null;
  workingCopyId: string | null;
  currentRevision: string | null;
  lastVerifiedRevision: string | null;
  issues: string[];
}
export interface ProjectWorkspace {
  key: string;
  context: ProjectContext | null;
  legacy: boolean;
  userProfile: UserProfile | null;
  assets: SoftwareAsset[];
  repositoryProfiles: RepositoryProfile[];
  directions: ProductDirection[];
  plan: EvolutionPlan | null;
  workingCopy: WorkingCopy | null;
  stage: ProjectStage;
  boundUserProfileRevision: number | null;
  issues: string[];
}
export type ResourceKind = "USER_PROFILE" | "SOFTWARE_ASSET" | "REPOSITORY_PROFILE";
export interface ResourceSummary {
  id: string;
  summary: string;
  status: string | null;
  revision: number | null;
  assetId: string | null;
  location: string | null;
  analyzedRevision: string | null;
}
