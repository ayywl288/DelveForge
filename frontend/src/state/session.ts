import { computed, reactive, ref, type UnwrapNestedRefs } from "vue";
import { api, ApiError } from "../api/client";
import { repositoryLocationKey } from "./repositoryLocation";
import type {
  AssetInput,
  AssetAuthorizationInput,
  EvolutionPlan,
  ProductDirection,
  ProfilePatch,
  RepositoryProfile,
  SoftwareAsset,
  Sufficiency,
  UserProfile,
  WorkingCopy,
  ProjectContext,
  ProjectSummary,
  ProjectWorkspace,
  ResourceKind,
} from "../api/types";

export const sessionKey = "delveforge.session.v1";
export const executionKey = "delveforge.execution.v1";
export const projectKey = "delveforge.project.v1";
export interface SessionIds {
  profileId: string;
  assetIds: string[];
  repositoryIds: string[];
  directionIds: string[];
  planId: string;
}
export interface ExecutionReceipt {
  planId: string;
  stepId: string;
  startedAt: string;
}
const emptyIds = (): SessionIds => ({
  profileId: "",
  assetIds: [],
  repositoryIds: [],
  directionIds: [],
  planId: "",
});
const validId = (value: unknown): value is string =>
  typeof value === "string" && /^[a-zA-Z0-9_-]{1,160}$/.test(value);
function readIds(): SessionIds {
  try {
    const saved = JSON.parse(
      localStorage.getItem(sessionKey) ?? "{}",
    ) as Record<string, unknown>;
    const list = (name: string) =>
      Array.isArray(saved[name])
        ? (saved[name] as unknown[]).filter(validId).slice(0, 100)
        : [];
    return {
      profileId: validId(saved.profileId) ? saved.profileId : "",
      planId: validId(saved.planId) ? saved.planId : "",
      assetIds: list("assetIds"),
      repositoryIds: list("repositoryIds"),
      directionIds: list("directionIds"),
    };
  } catch {
    return emptyIds();
  }
}
function readReceipt(): ExecutionReceipt | null {
  try {
    const saved = JSON.parse(
      localStorage.getItem(executionKey) ?? "null",
    ) as ExecutionReceipt | null;
    return saved &&
      validId(saved.planId) &&
      validId(saved.stepId) &&
      typeof saved.startedAt === "string"
      ? saved
      : null;
  } catch {
    return null;
  }
}
export function createSession() {
  const ids = reactive(readIds());
  const profile = ref<UserProfile | null>(null);
  const assets = ref<SoftwareAsset[]>([]);
  const repositories = ref<RepositoryProfile[]>([]);
  const directions = ref<ProductDirection[]>([]);
  const plan = ref<EvolutionPlan | null>(null);
  const copy = ref<WorkingCopy | null>(null);
  const sufficiency = ref<Sufficiency | null>(null);
  const reviewChoicePending = ref(false);
  const reviewChoiceShown = new Set<string>();
  function acceptSufficiency(value: Sufficiency) {
    sufficiency.value = value;
    if (value.sufficient && profile.value?.status === "EXPLORING" &&
      !reviewChoiceShown.has(profile.value.id)) {
      reviewChoiceShown.add(profile.value.id);
      reviewChoicePending.value = true;
    }
  }
  const activeQuestion = ref<{ profileId: string; revision: number; text: string } | null>(null);
  const nextQuestion = computed(() =>
    profile.value?.status === "EXPLORING" &&
    activeQuestion.value?.profileId === profile.value.id &&
    activeQuestion.value?.revision === profile.value.revision
      ? activeQuestion.value.text : null,
  );
  const messages = ref<
    { role: "user" | "agent"; text: string; failed?: boolean }[]
  >([]);
  const busy = ref("");
  const error = ref("");
  const notice = ref("");
  const connected = ref(false);
  const executionPending = ref(false);
  const executionRequest = ref<ExecutionReceipt | null>(null);
  const planningAuthorizationIssue = ref<{ assetId: string; code: string } | null>(null);
  const receipt = ref(readReceipt());
  const lastChecked = ref("");
  const pollError = ref("");
  const projectContext = ref<ProjectContext | null>(null);
  const projectIssues = ref<string[]>([]);
  const boundProfileRevision = ref<number | null>(null);
  const projects = ref<ProjectSummary[]>([]);
  const historyLoading = ref(false);
  const historyError = ref("");
  const historyMore = ref(false);
  const projectSyncError = ref(false);
  const contextEpoch = ref(0);
  let historyRequest = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  let observingExecution = false;
  let polling = false;
  let disposed = false;
  let planVersion = 0;
  const selected = computed(
    () =>
      directions.value.find((direction) => direction.status === "SELECTED") ??
      null,
  );
  const remember = () => {
    if (disposed) return;
    try {
      localStorage.setItem(sessionKey, JSON.stringify(ids));
    } catch {
      notice.value =
        "浏览器无法保存对象 ID；刷新后请使用恢复入口。执行操作需要可用的本地存储。";
    }
  };
  function acceptAsset(asset: SoftwareAsset) {
    if (disposed) return;
    const index = assets.value.findIndex(item => item.id === asset.id);
    if (index >= 0) assets.value[index] = asset;
    else assets.value.push(asset);
    if (!ids.assetIds.includes(asset.id)) ids.assetIds.push(asset.id);
    remember();
  }
  function appendQuestion(question: string | null) {
    activeQuestion.value = null;
    const text = question?.trim();
    if (!text || !profile.value || profile.value.status !== "EXPLORING") return;
    if (messages.value.some((message) => message.role === "agent" && message.text.trim() === text)) {
      notice.value = "Agent 返回了本页已经提过的问题，未重复插入；可以直接补充想法或按需索取下一问。";
      return;
    }
    messages.value.push({ role: "agent", text });
    activeQuestion.value = { profileId: profile.value.id, revision: profile.value.revision, text };
  }
  function previousQuestions() {
    return messages.value.filter(message => message.role === "agent" && message.text.length <= 512)
      .map(message => message.text).slice(-8);
  }
  async function refreshProfileAfterFailure() {
    sufficiency.value = null;
    activeQuestion.value = null;
    try {
      profile.value = await api.profile(ids.profileId);
    } catch {
      /* 查询也失败时保留已知画像，并显示原请求失败。 */
    }
  }
  async function askQuestion() {
    const current = profile.value;
    if (!current || current.status !== "EXPLORING") return;
    try {
      const result = await api.discoveryQuestion(current.id, current.revision, previousQuestions());
      if (disposed) return;
      if (profile.value?.id !== current.id || profile.value?.revision !== result.revision ||
        profile.value?.status !== "EXPLORING") {
        notice.value = "下一问所依据的画像版本或状态已经变化，未加入聊天。请核对当前画像。";
        return;
      }
      acceptSufficiency(result);
      appendQuestion(result.nextQuestion);
    } catch (failure) {
      await refreshProfileAfterFailure();
      throw failure;
    }
  }
  function setPlan(value: EvolutionPlan) {
    if (disposed) return;
    planVersion++;
    plan.value = value;
    ids.planId = value.id;
    remember();
  }
  async function refreshPlan() {
    const value = ids.planId || (!projectContext.value ? receipt.value?.planId : "");
    if (!value) return;
    const version = ++planVersion;
    const result = await api.plan(value);
    const wc = result.workingCopyId
      ? await api.workingCopy(result.workingCopyId)
      : null;
    if (version !== planVersion || disposed) return;
    plan.value = result;
    copy.value = wc;
    ids.planId = result.id;
    remember();
    lastChecked.value = new Date().toLocaleTimeString("zh-CN");
    pollError.value = "";
    // 仅完成的持久化结果能消除断线的不确定性；READY 不能证明后台准备已结束。
    if (receipt.value?.planId === result.id) {
      const step = result.steps.find(
        (item) => item.id === receipt.value?.stepId,
      );
      if (
        step &&
        [
          "SUCCEEDED",
          "FAILED",
          "CANCELLED",
          "RECOVERY_REQUIRED",
          "VERIFICATION_FAILED",
        ].includes(step.status)
      )
        clearReceipt();
    }
  }
  function clearReceipt() {
    try {
      localStorage.removeItem(executionKey);
      receipt.value = null;
    } catch {
      notice.value = "无法清除本浏览器的等待标记，请检查浏览器存储设置。";
    }
  }
  async function run(label: string, operation: () => Promise<void>, saveContext = true) {
    if (busy.value) return;
    if (saveContext && projectSyncError.value) {
      error.value = "上次业务对象已保存，但项目关联尚未确认。请重新打开项目核对，不要重复 AI 或执行请求。";
      return;
    }
    if (saveContext && projectIssues.value.some(issue => !issue.startsWith("WORKSPACE_") &&
      (issue.endsWith("_MISSING") || issue.endsWith("_UNREADABLE") || issue === "ASSOCIATION_MISMATCH"))) {
      error.value = "项目关联资源缺失或不一致，不能继续修改。请核对数据或建立新流程；历史对象没有被删除。";
      return;
    }
    busy.value = label;
    error.value = "";
    notice.value = "";
    try {
      await operation();
      if (disposed) return;
      if (saveContext && projectContext.value) {
        try {
          projectContext.value = await api.updateProject(projectContext.value, { ...ids });
        } catch (failure) {
          projectSyncError.value = true;
          notice.value = "业务请求已返回；项目关联保存失败或结果未知。后端业务对象未删除，请重新打开项目核对。";
          throw failure;
        }
      }
    } catch (failure) {
      error.value =
        failure instanceof ApiError
          ? failure.message
          : "操作未完成。请查询当前状态，不要重复提交未知结果的请求。";
    } finally {
      busy.value = "";
    }
  }
  async function restore() {
    if (projectContext.value) {
      await openProject("project:" + projectContext.value.id, false);
      return;
    }
    await run("读取已保存对象", async () => {
      connected.value = false;
      await api.connectivity();
      connected.value = true;
      const failures: string[] = [];
      const load = async (label: string, fn: () => Promise<void>) => {
        try {
          await fn();
        } catch {
          failures.push(label);
        }
      };
      await Promise.all([
        load("画像", async () => {
          if (ids.profileId) {
            const latest = await api.profile(ids.profileId);
            if (latest.revision !== profile.value?.revision) sufficiency.value = null;
            profile.value = latest;
            reviewChoicePending.value = false;
            sufficiency.value = null;
            const readiness = await api.reviewReadiness(latest.id);
            if (readiness.profileId === latest.id && readiness.revision === latest.revision &&
              readiness.sufficient === true) {
              reviewChoiceShown.add(latest.id);
              sufficiency.value = { sufficient: true, missingAreas: [], nextQuestion: null };
            }
          }
        }),
        load("仓库授权", async () => {
          assets.value = await Promise.all(ids.assetIds.map(api.asset));
        }),
        load("仓库分析", async () => {
          repositories.value = await Promise.all(
            ids.repositoryIds.map(api.repository),
          );
        }),
        load("产品方向", async () => {
          directions.value = await Promise.all(
            ids.directionIds.map(api.direction),
          );
        }),
        load("演化计划", refreshPlan),
      ]);
      if (failures.length)
        error.value = `无法恢复：${failures.join("、")}。请核对对象 ID、后端数据库及连接，已保存 ID 保留。`;
    }, false);
    schedulePoll();
  }
  function schedulePoll() {
    clearTimeout(timer);
    if (disposed || !observingExecution || polling) return;
    const observingReceipt = receipt.value && (!projectContext.value || receipt.value.planId === ids.planId);
    if (!observingReceipt && !executionPending.value && !plan.value?.steps.some(step =>
      ["EXECUTING", "VERIFYING"].includes(step.status))) return;
    timer = setTimeout(async () => {
      if (disposed || !observingExecution) return;
      polling = true;
      try {
        await refreshPlan();
      } catch {
        pollError.value = "状态查询失败，稍后继续只读查询；不会重复 execute。";
      } finally {
        polling = false;
        schedulePoll();
      }
    }, 3_000);
  }
  function observeExecution(active: boolean) {
    observingExecution = active;
    if (active) schedulePoll();
    else clearTimeout(timer);
  }
  function storageChanged(event: StorageEvent) {
    if (event.key === executionKey || event.key === null) {
      receipt.value = readReceipt();
      schedulePoll();
    }
  }
  window.addEventListener("storage", storageChanged);
  async function execute(stepId: string) {
    if (busy.value || receipt.value || !navigator.locks) {
      error.value =
        "已有执行等待核对，或浏览器不支持跨标签页执行锁。请使用本机现代浏览器并先查询状态。";
      return;
    }
    await run("执行当前 Step", async () => {
      await navigator.locks.request(
        "delveforge.execute",
        { ifAvailable: true },
        async (lock) => {
          if (!lock || readReceipt()) throw new Error("execution locked");
          await refreshPlan();
          const current = plan.value;
          const index =
            current?.steps.findIndex((step) => step.id === stepId) ?? -1;
          if (
            !current ||
            index < 0 ||
            current.status !== "ACTIVE" ||
            current.steps[index]?.status !== "READY" ||
            current.steps
              .slice(0, index)
              .some((step) => step.status !== "SUCCEEDED") ||
            !copy.value ||
            !["READY", "EVOLVING"].includes(copy.value.status) ||
            copy.value.currentRevision !== copy.value.lastVerifiedRevision
          ) {
            throw new ApiError(409, "CONFLICT");
          }
          const marker: ExecutionReceipt = {
            planId: current.id,
            stepId,
            startedAt: new Date().toISOString(),
          };
          // 写入失败时停止发送；刷新后只恢复 ID 与等待标记，不重放任何命令。
          localStorage.setItem(executionKey, JSON.stringify(marker));
          receipt.value = marker;
          executionRequest.value = marker;
          executionPending.value = true;
          schedulePoll();
          try {
            setPlan(await api.execute(current.id, stepId));
            await refreshPlan();
            clearReceipt();
          } catch (failure) {
            let checked = false;
            try {
              await refreshPlan();
              checked = true;
            } catch {
              pollError.value = "执行结果及当前状态均未能读取，保留等待标记。";
            }
            // 后端明确返回业务错误且查询证实仍未产生 Candidate，才允许下一次显式操作。
            if (
              checked &&
              failure instanceof ApiError &&
              failure.status > 0 &&
              failure.code in
                {
                  INVALID_REQUEST: 1,
                  NOT_FOUND: 1,
                  CONFLICT: 1,
                  EXTERNAL_CAPABILITY_UNAVAILABLE: 1,
                  INTERNAL_ERROR: 1,
                  ASSET_TYPE_UNSUPPORTED: 1,
                  ASSET_READ_PERMISSION_REQUIRED: 1,
                  ASSET_USAGE_AUTHORIZATION_REQUIRED: 1,
                  ASSET_LICENSE_INFORMATION_REQUIRED: 1,
                  ASSET_AUTHORIZATION_CONFLICT: 1,
                } &&
              plan.value?.steps.find((step) => step.id === stepId)?.status ===
                "READY" &&
              !plan.value.steps.find((step) => step.id === stepId)
                ?.executionResult &&
              copy.value && ["READY", "EVOLVING"].includes(copy.value.status) &&
              copy.value.currentRevision === copy.value.lastVerifiedRevision
            )
              clearReceipt();
            throw failure;
          } finally {
            executionPending.value = false;
            executionRequest.value = null;
            schedulePoll();
          }
        },
      );
    });
  }
  async function loadProjects(more = false) {
    if (historyLoading.value) return;
    const version = ++historyRequest;
    historyLoading.value = true;
    historyError.value = "";
    try {
      const loaded = await api.projects(more ? projects.value.length : 0);
      if (disposed || version !== historyRequest) return;
      if (!Array.isArray(loaded)) throw new ApiError(0, "RESPONSE_UNREADABLE");
      projects.value = more ? [...projects.value, ...loaded] : loaded;
      historyMore.value = loaded.length === 50;
    } catch (failure) {
      historyError.value = failure instanceof ApiError ? failure.message : "历史项目读取失败，请核对连接。";
    } finally {
      if (version === historyRequest) historyLoading.value = false;
    }
  }
  function applyWorkspace(workspace: ProjectWorkspace) {
    if (disposed) return;
    const previousKey = projectContext.value ? "project:" + projectContext.value.id
      : ids.planId ? "plan:" + ids.planId : "";
    const switching = previousKey !== workspace.key;
    planVersion++;
    clearTimeout(timer);
    if (switching) contextEpoch.value++;
    projectContext.value = workspace.context;
    projectIssues.value = workspace.issues;
    boundProfileRevision.value = workspace.boundUserProfileRevision;
    projectSyncError.value = false;
    profile.value = workspace.userProfile;
    assets.value = workspace.assets;
    repositories.value = workspace.repositoryProfiles;
    directions.value = workspace.directions;
    plan.value = workspace.plan;
    copy.value = workspace.workingCopy;
    const context = workspace.context;
    Object.assign(ids, {
      profileId: context?.userProfileId ?? workspace.userProfile?.id ?? "",
      assetIds: context?.assetIds ?? workspace.assets.map(item => item.id),
      repositoryIds: context?.repositoryProfileIds ?? workspace.repositoryProfiles.map(item => item.id),
      directionIds: context?.directionIds ?? workspace.directions.map(item => item.id),
      planId: context?.planId ?? workspace.plan?.id ?? "",
    });
    if (switching) messages.value = [];
    sufficiency.value = null;
    activeQuestion.value = null;
    reviewChoicePending.value = false;
    planningAuthorizationIssue.value = null;
    lastChecked.value = new Date().toLocaleTimeString("zh-CN");
    pollError.value = "";
    remember();
    try {
      if (context) localStorage.setItem(projectKey, context.id);
      else localStorage.removeItem(projectKey);
    } catch {
      notice.value = "浏览器无法保存项目 ID；刷新后请从项目工作台重新打开。";
    }
    schedulePoll();
  }
  async function openProject(key: string, adopt = true) {
    let stage: string | null = null;
    await run("读取已保存项目", async () => {
      let workspace = await api.project(key);
      if (workspace.legacy && adopt) {
        const planId = workspace.plan?.id;
        const invalid = workspace.issues.some(issue => !issue.startsWith("WORKSPACE_") &&
          (issue.endsWith("_MISSING") || issue.endsWith("_UNREADABLE") || issue === "ASSOCIATION_MISMATCH"));
        if (!planId || invalid) {
          notice.value = "历史项目仅部分可读，未建立新关联。请先核对列出的问题；没有重做任何业务调用。";
        } else {
          const context = await api.adoptPlan(planId);
          workspace = await api.project("project:" + context.id);
        }
      }
      applyWorkspace(workspace);
      connected.value = true;
      stage = workspace.stage;
      if (workspace.userProfile) {
        // 恢复只读充分记录，不重构历史问答，也不自动发起追问。
        try {
          const readiness = await api.reviewReadiness(workspace.userProfile.id);
          if (readiness.revision === workspace.userProfile.revision && readiness.sufficient === true) {
            reviewChoiceShown.add(workspace.userProfile.id);
            sufficiency.value = { sufficient: true, missingAreas: [], nextQuestion: null };
          }
        } catch {
          notice.value = "项目已读回，画像充分性记录暂时无法读取；没有重新调用 AI。";
        }
      }
    }, false);
    return stage;
  }
  return {
    ids,
    profile,
    assets,
    repositories,
    directions,
    plan,
    copy,
    sufficiency,
    reviewChoicePending,
    dismissReviewChoice() { reviewChoicePending.value = false; },
    nextQuestion,
    messages,
    busy,
    error,
    notice,
    connected,
    selected,
    executionPending,
    executionRequest,
    planningAuthorizationIssue,
    receipt,
    lastChecked,
    pollError,
    projectContext,
    projectIssues,
    boundProfileRevision,
    projects,
    historyLoading,
    historyError,
    historyMore,
    projectSyncError,
    contextEpoch,
    loadProjects,
    openProject,
    async initialize() {
      let saved: string | null = null;
      try { saved = localStorage.getItem(projectKey); } catch { /* ID 无法读取时显示工作台。 */ }
      if (saved && validId(saved)) return openProject("project:" + saved, false);
      await restore();
      return null;
    },
    async newProject(name: string, confirmedProfileId: string | null) {
      let stage: string | null = null;
      await run("新建项目上下文", async () => {
        const created = await api.createProject(name, confirmedProfileId);
        const workspace = await api.project("project:" + created.id);
        applyWorkspace(workspace);
        stage = workspace.stage;
      }, false);
      return stage;
    },
    async selectResource(kind: Exclude<ResourceKind, "USER_PROFILE">, id: string) {
      await run("复用已有仓库资源", async () => {
        if (kind === "SOFTWARE_ASSET") {
          acceptAsset(await api.asset(id));
        } else {
          const repo = await api.repository(id);
          const asset = await api.asset(repo.assetId);
          acceptAsset(asset);
          if (!repositories.value.some(item => item.id === repo.id)) repositories.value.push(repo);
          if (!ids.repositoryIds.includes(repo.id)) ids.repositoryIds.push(repo.id);
          remember();
        }
      });
    },
    run,
    restore,
    refreshPlan,
    observeExecution,
    execute,
    dispose() {
      disposed = true;
      clearTimeout(timer);
      window.removeEventListener("storage", storageChanged);
    },
    async importIds(value: SessionIds) {
      if (busy.value || receipt.value) return;
      const all = [
        value.profileId,
        value.planId,
        ...value.assetIds,
        ...value.repositoryIds,
        ...value.directionIds,
      ].filter(Boolean);
      if (all.some((item) => !validId(item))) {
        error.value = "ID 只能包含字母、数字、短横线和下划线，长度不超过 160。";
        return;
      }
      Object.assign(ids, value);
      projectContext.value = null;
      projectIssues.value = [];
      boundProfileRevision.value = null;
      projectSyncError.value = false;
      contextEpoch.value++;
      planVersion++;
      localStorage.removeItem(projectKey);
      profile.value = null;
      assets.value = [];
      repositories.value = [];
      directions.value = [];
      plan.value = null;
      copy.value = null;
      sufficiency.value = null;
      activeQuestion.value = null;
      messages.value = [];
      remember();
      await restore();
    },
    async createProfile() {
      await run("创建探索", async () => {
        profile.value = await api.createProfile();
        ids.profileId = profile.value.id;
        sufficiency.value = null;
        activeQuestion.value = null;
        messages.value = [];
        remember();
      });
    },
    async turn(input: string) {
      if (!profile.value || profile.value.status !== "EXPLORING" || !input.trim()) return;
      await run("理解需求并更新画像", async () => {
        const message = { role: "user" as const, text: input };
        messages.value.push(message);
        try {
          activeQuestion.value = null;
          const result = await api.discoveryTurn(profile.value!.id, input, previousQuestions());
          profile.value = result.profile;
          acceptSufficiency(result);
          appendQuestion(result.nextQuestion);
        } catch (failure) {
          const last = messages.value.at(-1);
          if (last) last.failed = true;
          await refreshProfileAfterFailure();
          throw failure;
        }
      });
    },
    async patch(patch: ProfilePatch) {
      if (profile.value)
        await run("保存画像纠正", async () => {
          profile.value = await api.patchProfile(profile.value!.id, patch);
          sufficiency.value = null;
          activeQuestion.value = null;
        });
    },
    async assess() {
      if (profile.value)
        await run("评估并请求进入审阅", async () => {
          const assessedRevision = profile.value!.revision;
          sufficiency.value = null;
          activeQuestion.value = null;
          try {
            const result = await api.assess(profile.value!.id, assessedRevision);
            profile.value = await api.profile(ids.profileId);
            if (profile.value.revision !== assessedRevision) {
              notice.value = "画像版本在评估期间发生了变化；当前内容已重新读取，请审阅当前版本。";
              return;
            }
            sufficiency.value = result;
            if (!result.sufficient) {
              notice.value = "本版没有可复用的充分结果，本次评估仍需补充；未向聊天插入新问题。";
            }
          } catch (failure) {
            await refreshProfileAfterFailure();
            throw failure;
          }
        });
    },
    async confirmProfile(revision: number) {
      if (profile.value)
        await run("确认已审阅画像", async () => {
          try {
            profile.value = await api.confirmProfile(
              profile.value!.id,
              revision,
            );
            activeQuestion.value = null;
          } catch (failure) {
            sufficiency.value = null;
            profile.value = await api.profile(ids.profileId);
            throw failure;
          }
        });
    },
    async resumeDiscovery() {
      if (profile.value && profile.value.status !== "EXPLORING")
        await run("重新进入探索", async () => {
          try {
            profile.value = await api.resumeDiscovery(
              profile.value!.id,
              profile.value!.status === "CONFIRMED",
            );
            sufficiency.value = null;
            activeQuestion.value = null;
          } catch (failure) {
            await refreshProfileAfterFailure();
            throw failure;
          }
          // 用户主动返回探索后才索取一次问题；问题失败不撤销已完成的合法状态转换。
          await askQuestion();
        });
    },
    async continueExploring() {
      if (busy.value || !profile.value || profile.value.status !== "EXPLORING") return;
      if (nextQuestion.value) {
        notice.value = "请直接回答聊天区的下一轮问题，可以连续探索。";
        return;
      }
      await run("生成下一轮探索问题", askQuestion);
    },
    async register(input: AssetInput) {
      await run("注册仓库授权", async () => {
        const known = assets.value.filter(asset =>
          repositoryLocationKey(asset.location) === repositoryLocationKey(input.location));
        if (known.length) {
          notice.value = "此仓库已注册，请复用已登记卡片；补充许可证或授权请在原记录上明确保存。";
          return;
        }
        const existing = await api.assetsAtLocation(input.location);
        if (existing.length) {
          existing.forEach(acceptAsset);
          notice.value = "找到已注册仓库，已显示原记录。未改变授权；有多个历史身份时请核对分析绑定。";
          return;
        }
        try {
          acceptAsset(await api.registerAsset(input));
        } catch (failure) {
          if (failure instanceof ApiError && failure.code === "ASSET_ALREADY_REGISTERED") {
            (await api.assetsAtLocation(input.location)).forEach(acceptAsset);
          }
          throw failure;
        }
      });
    },
    async updateAssetAuthorization(value: string, input: AssetAuthorizationInput) {
      let saved = false;
      await run("保存显式仓库授权", async () => {
        try {
          acceptAsset(await api.updateAssetAuthorization(value, input));
          saved = true;
          if (planningAuthorizationIssue.value?.assetId === value) planningAuthorizationIssue.value = null;
          notice.value = "授权信息已保存；原资产、仓库分析和已选择方向保留，可以继续规划。";
        } catch (failure) {
          if (failure instanceof ApiError && failure.code === "ASSET_AUTHORIZATION_CONFLICT") {
            try { acceptAsset(await api.asset(value)); } catch { /* 保留已知状态，不自动重试修改。 */ }
          }
          throw failure;
        }
      });
      return saved;
    },
    async loadAsset(value: string) {
      let loaded = false;
      await run("读取规划所引用的仓库授权", async () => {
        acceptAsset(await api.asset(value));
        loaded = true;
      }, false);
      return loaded;
    },
    async analyze(value: string) {
      await run("分析仓库（调用 AI）", async () => {
        const result = await api.analyze(value);
        repositories.value.push(result);
        ids.repositoryIds.push(result.id);
        remember();
      });
    },
    async discover(repositoryIds: string[]) {
      if (profile.value)
        await run("生成候选方向（调用 AI）", async () => {
          const result = await api.discover(
            profile.value!.id,
            profile.value!.revision,
            repositoryIds,
          );
          directions.value.push(...result.directions);
          ids.directionIds.push(...result.directions.map((item) => item.id));
          remember();
        });
    },
    async decide(value: string, decision: "select" | "reject") {
      await run("保存方向决定", async () => {
        const result = await api.decideDirection(value, decision);
        directions.value = directions.value.map((item) =>
          item.id === result.id ? result : item,
        );
        directions.value = await Promise.all(
          ids.directionIds.map(api.direction),
        );
      });
    },
    async generatePlan(repositoryId: string) {
      const repository = repositories.value.find(
        (item) => item.id === repositoryId,
      );
      if (selected.value && repository)
        await run("规划演化（调用 AI）", async () => {
          planningAuthorizationIssue.value = null;
          try {
            setPlan(await api.generatePlan(
              selected.value!.id,
              repository.assetId,
              repository.id,
            ));
          } catch (failure) {
            if (failure instanceof ApiError && ["ASSET_READ_PERMISSION_REQUIRED",
              "ASSET_USAGE_AUTHORIZATION_REQUIRED", "ASSET_LICENSE_INFORMATION_REQUIRED",
              "ASSET_AUTHORIZATION_CONFLICT"].includes(failure.code)) {
              planningAuthorizationIssue.value = { assetId: repository.assetId, code: failure.code };
            }
            throw failure;
          }
          copy.value = null;
        });
    },
    async prepare() {
      if (plan.value)
        await run("准备独立 WorkingCopy", async () => {
          setPlan(await api.prepare(plan.value!.id));
          await refreshPlan();
        });
    },
    async confirmStep(value: string) {
      if (plan.value)
        await run("授权当前 Step", async () => {
          setPlan(await api.confirmStep(plan.value!.id, value));
          await refreshPlan();
        });
    },
  };
}
export type Session = ReturnType<typeof createSession>;
export type WorkspaceSession = UnwrapNestedRefs<Session>;
