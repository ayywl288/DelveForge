import type {
  Assessment,
  AssetInput,
  DiscoveryTurn,
  DiscoveryQuestion,
  EvolutionPlan,
  ProductDirection,
  ProfilePatch,
  RepositoryProfile,
  SoftwareAsset,
  UserProfile,
  WorkingCopy,
} from "./types";

const messages: Record<string, string> = {
  INVALID_REQUEST: "请求字段无效，请检查输入。",
  NOT_FOUND: "对象不存在，请检查恢复 ID 和后端数据库。",
  CONFLICT: "当前版本、授权或状态不允许此操作，请先刷新并审阅。",
  EXTERNAL_CAPABILITY_UNAVAILABLE:
    "AI 调用未获得可用结果。请查询当前状态后再决定下一步。",
  INTERNAL_ERROR: "后端操作失败，请核对状态并查看后端的安全诊断日志。",
};
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string) {
    super(
      messages[code] ??
        (status
          ? `请求失败（HTTP ${status}）。请刷新当前状态。`
          : "连接中断或响应无法读取，结果尚不确定。请先查询当前状态。"),
    );
    this.status = status;
    this.code = code;
  }
}
export async function request<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      method,
      headers:
        body === undefined ? undefined : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: "no-store",
      // GET 有界等待；同步 AI / execute POST 不设置前端期限，也不自动重试。
      signal: method === "GET" ? AbortSignal.timeout(15_000) : undefined,
    });
  } catch {
    throw new ApiError(0, "CONNECTION_LOST");
  }
  if (!response.ok) {
    let code = "";
    try {
      const payload: unknown = await response.json();
      if (
        payload &&
        typeof payload === "object" &&
        "code" in payload &&
        typeof payload.code === "string" &&
        payload.code in messages
      )
        code = payload.code;
    } catch {
      /* 代理可能返回 HTML；不向页面或日志传播原始正文。 */
    }
    throw new ApiError(response.status, code);
  }
  try {
    return (await response.json()) as T;
  } catch {
    throw new ApiError(0, "RESPONSE_UNREADABLE");
  }
}
const id = encodeURIComponent;
const profile = (value: string) => `/user-profiles/${id(value)}`;
const plan = (value: string) => `/evolution-plans/${id(value)}`;
export const api = {
  connectivity: () =>
    request<{ service: string; status: string; timestamp: string }>(
      "/system/connectivity",
    ),
  createProfile: () => request<UserProfile>("/user-profiles", "POST"),
  profile: (value: string) => request<UserProfile>(profile(value)),
  discoveryTurn: (value: string, input: string, previousQuestions: string[] = []) =>
    request<DiscoveryTurn>(`${profile(value)}/discovery-turn`, "POST", {
      input,
      previousQuestions,
    }),
  discoveryQuestion: (value: string, revision: number, previousQuestions: string[]) =>
    request<DiscoveryQuestion>(`${profile(value)}/discovery-question`, "POST", {
      revision,
      previousQuestions,
    }),
  patchProfile: (value: string, patch: ProfilePatch) =>
    request<UserProfile>(profile(value), "PATCH", patch),
  assess: (value: string, revision?: number) =>
    request<Assessment>(`${profile(value)}/sufficiency-assessment`, "POST",
      revision === undefined ? undefined : { revision }),
  reviewReadiness: (value: string) =>
    request<{ profileId: string; revision: number; sufficient: true | null }>(`${profile(value)}/review-readiness`),
  confirmProfile: (value: string, revision: number) =>
    request<UserProfile>(`${profile(value)}/confirm`, "POST", { revision }),
  resumeDiscovery: (value: string, confirmed: boolean) =>
    request<UserProfile>(
      `${profile(value)}/${confirmed ? "reopen-discovery" : "continue-discovery"}`,
      "POST",
    ),
  registerAsset: (input: AssetInput) =>
    request<SoftwareAsset>("/software-assets", "POST", input),
  asset: (value: string) =>
    request<SoftwareAsset>(`/software-assets/${id(value)}`),
  analyze: (value: string) =>
    request<RepositoryProfile>(
      `/software-assets/${id(value)}/analysis`,
      "POST",
    ),
  repository: (value: string) =>
    request<RepositoryProfile>(`/repository-profiles/${id(value)}`),
  discover: (
    userProfileId: string,
    expectedRevision: number,
    repositoryProfileIds: string[],
  ) =>
    request<{ directions: ProductDirection[] }>(
      "/product-directions/discovery",
      "POST",
      { userProfileId, expectedRevision, repositoryProfileIds },
    ),
  direction: (value: string) =>
    request<ProductDirection>(`/product-directions/${id(value)}`),
  decideDirection: (value: string, decision: "select" | "reject") =>
    request<ProductDirection>(
      `/product-directions/${id(value)}/${decision}`,
      "POST",
    ),
  generatePlan: (
    productDirectionId: string,
    baseAssetId: string,
    baseRepositoryProfileId: string,
  ) =>
    request<EvolutionPlan>("/evolution-plans/planning", "POST", {
      productDirectionId,
      baseAssetId,
      baseRepositoryProfileId,
    }),
  plan: (value: string) => request<EvolutionPlan>(plan(value)),
  prepare: (value: string) =>
    request<EvolutionPlan>(`${plan(value)}/prepare`, "POST"),
  confirmStep: (value: string, step: string) =>
    request<EvolutionPlan>(`${plan(value)}/steps/${id(step)}/confirm`, "POST"),
  execute: (value: string, step: string) =>
    request<EvolutionPlan>(`${plan(value)}/steps/${id(step)}/execute`, "POST"),
  workingCopy: (value: string) =>
    request<WorkingCopy>(`/working-copies/${id(value)}`),
};
