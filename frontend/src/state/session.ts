import type { DiscoveryRequestIdentity, DiscoveryOperation } from "../api/types";
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
export const projectKey = "delveforge.project.v1";
export interface SessionIds {
  profileId: string;
  assetIds: string[];
  repositoryIds: string[];
  directionIds: string[];
  planId: string;
}
interface DiscoveryMessage {
  role: "user" | "agent";
  text: string;
  question?: string;
  state?: "pending" | "accepted" | "failed" | "uncertain";
  profileId?: string;
  failed?: boolean;
  createdAt?: string;
}
type AiActivity =
  | { kind: "discovery"; profileId: string }
  | { kind: "repository"; assetId: string }
  | { kind: "directions" }
  | { kind: "planning" };
interface AdoptedPlanSnapshot {
  version: number;
  plan: EvolutionPlan;
  copy: WorkingCopy | null;
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
export function createSession() {
  const ids = reactive(readIds());
  const profile = ref<UserProfile | null>(null);
  const assets = ref<SoftwareAsset[]>([]);
  const repositories = ref<RepositoryProfile[]>([]);
  const directions = ref<ProductDirection[]>([]);
  const plan = ref<EvolutionPlan | null>(null);
  const copy = ref<WorkingCopy | null>(null);
  const sufficiency = ref<Sufficiency | null>(null);
  function acceptSufficiency(value: Sufficiency) {
    sufficiency.value = value;
  }
  const activeQuestion = ref<{ profileId: string; revision: number; text: string } | null>(null);
  const nextQuestion = computed(() =>
    profile.value?.status === "EXPLORING" &&
    activeQuestion.value?.profileId === profile.value.id
      ? activeQuestion.value.text : null,
  );
  const messages = ref<DiscoveryMessage[]>([]);
  const conversationLoading = ref(false);
  const conversationError = ref("");
  const conversationBefore = ref<number | null>(null);
  const pendingDiscovery = ref<Record<string, string>>((() => {
    try {
      const stored: unknown = JSON.parse(localStorage.getItem("delveforge.discovery.pending.v1") ?? "{}");
      if (!stored || typeof stored !== "object" || Array.isArray(stored)) return {};
      return Object.fromEntries(Object.entries(stored).filter(([key, value]) => validId(key) && validId(value)));
    }
    catch { return {}; }
  })());
  let conversationRead = 0;
  const activeAiActivity = ref<(AiActivity & { epoch: number }) | null>(null);
  const aiActivity = computed(() => {
    const current = activeAiActivity.value;
    if (!current || current.epoch !== contextEpoch.value) return null;
    if (current.kind === "discovery" && current.profileId !== profile.value?.id) return null;
    return current;
  });
  const discoveryPending = computed(() => aiActivity.value?.kind === "discovery");
  const discoveryUncertain = computed(() => !!pendingDiscovery.value[profile.value?.id ?? ""] || messages.value.some(message =>
    message.state === "uncertain" && message.profileId === profile.value?.id));
  const busy = ref("");
  const error = ref("");
  const notice = ref("");
  const connected = ref(false);
  const planningAuthorizationIssue = ref<{ assetId: string; code: string } | null>(null);
  const lastChecked = ref("");
  const pollError = ref("");
  const projectContext = ref<ProjectContext | null>(null);
  const projectIssues = ref<string[]>([]);
  const boundProfileRevision = ref<number | null>(null);
  const projects = ref<ProjectSummary[]>([]);
  const planningRounds = ref<ProjectSummary[]>([]);
  const roundsError = ref("");
  const historyLoading = ref(false);
  const historyError = ref("");
  const historyMore = ref(false);
  const showingDeletedProjects = ref(false);
  const projectSyncError = ref(false);
  const contextEpoch = ref(0);
  let historyRequest = 0;
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
  function appendReply(reply: string | undefined, question: string | null) {
    if (!profile.value || profile.value.status !== "EXPLORING") return;
    let followUp = question?.trim() || null;
    if (followUp && messages.value.some(message => message.role === "agent" && message.question === followUp)) {
      notice.value = "Agent 返回了本页已经提过的问题，未重复插入；可以直接补充想法或按需索取下一问。";
      followUp = null;
    }
    let text = reply?.trim() || "";
    if (followUp && !text.includes(followUp)) text = [text, followUp].filter(Boolean).join("\n\n");
    if (text) messages.value.push({ role: "agent", text, ...(followUp ? { question: followUp } : {}) });
    if (followUp) {
      activeQuestion.value = { profileId: profile.value.id, revision: profile.value.revision, text: followUp };
    }
  }
  function requestIdentity(current: UserProfile): DiscoveryRequestIdentity {
    const requestId = crypto.randomUUID();
    const updated = { ...pendingDiscovery.value, [current.id]: requestId };
    localStorage.setItem("delveforge.discovery.pending.v1", JSON.stringify(updated));
    pendingDiscovery.value = updated;
    return { requestId, revision: current.revision,
      ...(projectContext.value?.userProfileId === current.id ? { projectId: projectContext.value.id } : {}) };
  }
  function finishDiscoveryRequest(profileId: string, requestId: string) {
    if (pendingDiscovery.value[profileId] !== requestId) return;
    const updated = { ...pendingDiscovery.value };
    delete updated[profileId];
    pendingDiscovery.value = updated;
    try { localStorage.setItem("delveforge.discovery.pending.v1", JSON.stringify(updated)); } catch { /* 旧编号仍可查询已完成结果。 */ }
  }
  function requireOperation(result: DiscoveryOperation, requestId: string) {
    if (!result || result.requestId !== requestId ||
      !["PENDING", "COMPLETED", "FAILED", "INTERRUPTED", "CANCELLED", "NOT_FOUND"].includes(result.state) ||
      result.state === "COMPLETED" && (!result.result || typeof result.result.sufficient !== "boolean" ||
        !Array.isArray(result.result.missingAreas) || !Number.isInteger(result.revision))) {
      throw new ApiError(0, "RESPONSE_UNREADABLE");
    }
    return result;
  }
  async function loadConversation(more = false) {
    const current = profile.value;
    if (!current) return;
    const epoch = contextEpoch.value;
    const version = ++conversationRead;
    const belongs = () => !disposed && epoch === contextEpoch.value && profile.value?.id === current.id && version === conversationRead;
    conversationLoading.value = true;
    conversationError.value = "";
    try {
      const history = await api.conversation(current.id, more ? conversationBefore.value ?? undefined : undefined,
        projectContext.value?.userProfileId === current.id ? projectContext.value.id : undefined);
      if (!belongs()) return;
      if (!history || !Array.isArray(history.messages) || history.messages.some(message =>
        !["user", "agent"].includes(message.role) || typeof message.text !== "string" ||
        !Number.isSafeInteger(message.sequence) || !validId(message.requestId) ||
        message.question !== null && typeof message.question !== "string")) {
        throw new ApiError(0, "RESPONSE_UNREADABLE");
      }
      const restored: DiscoveryMessage[] = history.messages.map(message => ({ role: message.role,
        text: message.question && !message.text.includes(message.question) ? [message.text, message.question].filter(Boolean).join("\n\n") : message.text,
        ...(message.question ? { question: message.question } : {}), profileId: current.id,
        state: message.role === "user" ? "accepted" : undefined, createdAt: message.createdAt }));
      messages.value = more ? [...restored, ...messages.value] : restored;
      conversationBefore.value = history.before;
      activeQuestion.value = history.unansweredQuestion
        ? { profileId: current.id, revision: profile.value!.revision, text: history.unansweredQuestion } : null;
      if (history.pendingRequestId) pendingDiscovery.value = { ...pendingDiscovery.value, [current.id]: history.pendingRequestId };
      const requestId = pendingDiscovery.value[current.id];
      if (requestId) {
        const result = requireOperation(await api.discoveryRequest(current.id, requestId), requestId);
        if (!belongs()) return;
        if (["COMPLETED", "FAILED", "INTERRUPTED", "CANCELLED"].includes(result.state)) {
          if (result.state === "COMPLETED" && result.revision !== profile.value?.revision) {
            const latest = await api.profile(current.id);
            if (!belongs()) return;
            profile.value = latest;
            sufficiency.value = result.revision === latest.revision ? result.result : null;
          }
          finishDiscoveryRequest(current.id, requestId);
          if (result.state === "COMPLETED" && !history.messages.some(message => message.requestId === requestId)) {
            await loadConversation();
            return;
          }
          if (result.state === "INTERRUPTED") notice.value = "上一轮因服务中断未完成，请核对后重新发送；没有自动重试。";
        }
      }
    } catch {
      if (belongs()) conversationError.value = "暂时无法读取已保存的对话，请重新查询；不会生成替代问题。";
    } finally {
      if (belongs()) conversationLoading.value = false;
    }
  }
  async function reconcileDiscovery() {
    const current = profile.value;
    const requestId = current && pendingDiscovery.value[current.id];
    if (!current || !requestId) return false;
    const epoch = contextEpoch.value;
    const result = requireOperation(await api.discoveryRequest(current.id, requestId), requestId);
    if (disposed || contextEpoch.value !== epoch || profile.value?.id !== current.id) return false;
    if (result.state === "PENDING") {
      notice.value = "上一轮仍在处理，可以稍后再次查询；不会重复发送。";
      return false;
    }
    if (result.state === "NOT_FOUND") {
      notice.value = "暂时没有这轮记录，不会自动重发。可以继续查询，或放弃未完成的本轮后重新输入。";
      return false;
    }
    const latest = await api.profile(current.id);
    if (disposed || contextEpoch.value !== epoch || profile.value?.id !== current.id) return false;
    if (latest.revision !== current.revision) sufficiency.value = null;
    profile.value = latest;
    finishDiscoveryRequest(current.id, requestId);
    if (result.state === "COMPLETED") {
      await loadConversation();
      if (disposed || contextEpoch.value !== epoch || profile.value?.id !== current.id) return false;
      if (result.revision === latest.revision && result.result) acceptSufficiency(result.result);
      notice.value = "已恢复服务端保存的本轮结果，没有重新调用 AI。";
      return true;
    }
    for (const message of messages.value) {
      if (message.profileId === current.id && ["uncertain", "pending"].includes(message.state ?? "")) {
        message.state = "failed";
        message.failed = true;
      }
    }
    notice.value = result.state === "INTERRUPTED" ? "上一轮已中断，未保存对话。可以核对后重新发送。" : "上一轮未被接受，可以核对后重新发送。";
    return false;
  }
  async function refreshProfileAfterFailure() {
    const profileId = ids.profileId;
    const epoch = contextEpoch.value;
    sufficiency.value = null;
    try {
      const latest = await api.profile(profileId);
      if (!disposed && contextEpoch.value === epoch && ids.profileId === profileId) profile.value = latest;
    } catch {
      /* 查询也失败时保留已知画像，并显示原请求失败。 */
    }
  }
  async function askQuestion() {
    const current = profile.value;
    const epoch = contextEpoch.value;
    const belongs = () => !disposed && contextEpoch.value === epoch && profile.value?.id === current?.id;
    if (!current || current.status !== "EXPLORING") return;
    try {
      const identity = requestIdentity(current);
      const result = await api.discoveryQuestion(current.id, current.revision, [], [], identity);
      if (!belongs()) { finishDiscoveryRequest(current.id, identity.requestId); return; }
      if (profile.value?.id !== current.id || profile.value?.revision !== result.revision ||
        profile.value?.status !== "EXPLORING") {
        notice.value = "下一问所依据的画像版本或状态已经变化，未加入聊天。请核对当前画像。";
        return;
      }
      finishDiscoveryRequest(current.id, identity.requestId);
      acceptSufficiency(result);
      appendReply(result.assistantReply, result.nextQuestion);
    } catch (failure) {
      if (!belongs()) return;
      try { if (await reconcileDiscovery()) return; } catch { /* 保留编号，只读查询失败不授权重发。 */ }
      if (!belongs()) return;
      if (failure instanceof ApiError && [400, 404, 502].includes(failure.status)) {
        const requestId = pendingDiscovery.value[current.id];
        if (requestId) finishDiscoveryRequest(current.id, requestId);
      }
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
  // 请求完成不等于采纳；调用方只有拿到仍有效的快照，才能据此结束等待或发送明确操作。
  async function refreshPlan(): Promise<AdoptedPlanSnapshot | null> {
    const value = ids.planId;
    if (!value) return null;
    const version = ++planVersion;
    const result = await api.plan(value);
    const wc = result.workingCopyId ? await api.workingCopy(result.workingCopyId) : null;
    if (version !== planVersion || disposed) return null;
    plan.value = result;
    copy.value = wc;
    ids.planId = result.id;
    remember();
    lastChecked.value = new Date().toLocaleTimeString("zh-CN");
    pollError.value = "";
    return { version, plan: result, copy: wc };
  }
  async function run(label: string, operation: () => Promise<void>, saveContext = true, activity?: AiActivity) {
    if (busy.value) return;
    if (saveContext && projectSyncError.value) {
      error.value = "上次业务对象已保存，但项目关联尚未确认。请重新打开项目核对，不要重复 AI 或执行请求。";
      return;
    }
    if (saveContext && projectIssues.value.some(issue => !issue.startsWith("WORKSPACE_") &&
      (issue.endsWith("_MISSING") || issue.endsWith("_UNREADABLE") || issue === "ASSOCIATION_MISMATCH" ||
        issue === "FLOW_OWNERSHIP_UNKNOWN" || issue === "PROJECT_DELETED"))) {
      error.value = "项目关联资源缺失或不一致，不能继续修改。请核对数据或建立新流程；历史对象没有被删除。";
      return;
    }
    busy.value = label;
    activeAiActivity.value = activity ? { ...activity, epoch: contextEpoch.value } : null;
    const epoch = contextEpoch.value;
    error.value = "";
    notice.value = "";
    try {
      await operation();
      activeAiActivity.value = null;
      if (disposed || saveContext && contextEpoch.value !== epoch) return;
      if (saveContext && projectContext.value) {
        try {
          busy.value = "保存项目关联";
          projectContext.value = await api.updateProject(projectContext.value, { ...ids });
        } catch (failure) {
          projectSyncError.value = true;
          notice.value = "业务请求已返回；项目关联保存失败或结果未知。后端业务对象未删除，请重新打开项目核对。";
          throw failure;
        }
      }
    } catch (failure) {
      if (disposed || saveContext && contextEpoch.value !== epoch) return;
      error.value =
        failure instanceof ApiError
          ? failure.message
          : "操作未完成。请查询当前状态，不要重复提交未知结果的请求。";
    } finally {
      activeAiActivity.value = null;
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
            const profileId = ids.profileId;
            const epoch = contextEpoch.value;
            const belongs = () => !disposed && epoch === contextEpoch.value && ids.profileId === profileId;
            const latest = await api.profile(profileId);
            if (!belongs()) return;
            if (latest.revision !== profile.value?.revision) sufficiency.value = null;
            profile.value = latest;
            sufficiency.value = null;
            const readiness = await api.reviewReadiness(latest.id);
            if (!belongs()) return;
            if (readiness.profileId === latest.id && readiness.revision === latest.revision &&
              readiness.sufficient != null) {
              sufficiency.value = { sufficient: readiness.sufficient!, missingAreas: readiness.missingAreas ?? [], nextQuestion: null };
            }
            await loadConversation();
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
        load("演化计划", async () => { await refreshPlan(); }),
      ]);
      if (failures.length)
        error.value = `无法恢复：${failures.join("、")}。请核对对象 ID、后端数据库及连接，已保存 ID 保留。`;
    }, false);
  }
  async function loadProjects(more = false, deleted = showingDeletedProjects.value) {
    if (historyLoading.value && deleted === showingDeletedProjects.value) return;
    if (deleted !== showingDeletedProjects.value) {
      projects.value = [];
      historyMore.value = false;
    }
    showingDeletedProjects.value = deleted;
    const version = ++historyRequest;
    historyLoading.value = true;
    historyError.value = "";
    try {
      const loaded = await api.projects(more ? projects.value.length : 0, deleted);
      if (disposed || version !== historyRequest) return;
      if (!Array.isArray(loaded)) throw new ApiError(0, "RESPONSE_UNREADABLE");
      projects.value = more ? [...projects.value, ...loaded] : loaded;
      historyMore.value = loaded.length === 50;
    } catch (failure) {
      if (!disposed && version === historyRequest)
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
    if (switching) contextEpoch.value++;
    projectContext.value = workspace.context;
    planningRounds.value = [];
    roundsError.value = "";
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
      assetIds: [...new Set([...(context?.assetIds ?? []), ...workspace.assets.map(item => item.id)])],
      repositoryIds: [...new Set([...(context?.repositoryProfileIds ?? []), ...workspace.repositoryProfiles.map(item => item.id)])],
      directionIds: [...new Set([...(context?.directionIds ?? []), ...workspace.directions.map(item => item.id)])],
      planId: context?.planId ?? workspace.plan?.id ?? "",
    });
    if (switching) {
      messages.value = [];
      conversationBefore.value = null;
      conversationRead++;
      conversationLoading.value = false;
      conversationError.value = "";
    }
    sufficiency.value = null;
    activeQuestion.value = null;
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
  }
  async function openProject(key: string, adopt = true) {
    let stage: string | null = null;
    await run("读取已保存项目", async () => {
      const epoch = contextEpoch.value;
      let workspace = await api.project(key);
      if (disposed || epoch !== contextEpoch.value) return;
        if (workspace.legacy && adopt && !workspace.issues.includes("PROJECT_DELETED")) {
        const planId = workspace.plan?.id;
        const invalid = workspace.issues.some(issue => !issue.startsWith("WORKSPACE_") &&
          (issue.endsWith("_MISSING") || issue.endsWith("_UNREADABLE") || issue === "ASSOCIATION_MISMATCH" ||
            issue === "FLOW_OWNERSHIP_UNKNOWN"));
        if (!planId || invalid) {
          notice.value = "历史项目仅部分可读，未建立新关联。请先核对列出的问题；没有重做任何业务调用。";
        } else {
          const context = await api.adoptPlan(planId);
          workspace = await api.project("project:" + context.id);
        }
      }
      if (disposed || epoch !== contextEpoch.value) return;
      applyWorkspace(workspace);
      connected.value = true;
      stage = workspace.stage;
      if (workspace.userProfile) {
        const restoredEpoch = contextEpoch.value;
        await loadConversation();
        if (disposed || restoredEpoch !== contextEpoch.value || profile.value?.id !== workspace.userProfile.id) return;
        // 恢复服务端版本判断，不在读取时发起 AI 调用。
        try {
          const readiness = await api.reviewReadiness(workspace.userProfile.id);
          if (disposed || restoredEpoch !== contextEpoch.value || profile.value?.id !== workspace.userProfile.id) return;
          if (readiness.profileId === profile.value?.id && readiness.revision === profile.value.revision && readiness.sufficient != null) {
            sufficiency.value = { sufficient: readiness.sufficient!, missingAreas: readiness.missingAreas ?? [], nextQuestion: null };
          }
        } catch {
          if (disposed || restoredEpoch !== contextEpoch.value || profile.value?.id !== workspace.userProfile.id) return;
          notice.value = "项目已读回，画像充分性记录暂时无法读取；没有重新调用 AI。";
        }
      }
    }, false);
    const restoredContext = projectContext.value?.id;
    if (stage) await loadPlanningRounds();
    if (disposed || projectContext.value?.id !== restoredContext) return null;
    return stage;
  }
  async function loadPlanningRounds() {
    const context = projectContext.value;
    if (!context) return;
    const epoch = contextEpoch.value;
    try {
      const result = await api.planningRounds(context.id);
      if (disposed || projectContext.value?.id !== context.id || contextEpoch.value !== epoch) return;
      if (!Array.isArray(result)) throw new ApiError(0, "RESPONSE_UNREADABLE");
      planningRounds.value = result;
      roundsError.value = "";
    } catch {
      if (!disposed && projectContext.value?.id === context.id && contextEpoch.value === epoch) {
        roundsError.value = "规划轮次暂时无法读取。没有生成新计划，请稍后重新查询。";
      }
    }
  }

  function clearNavigation() {
    contextEpoch.value++;
    conversationRead++;
    conversationLoading.value = false;
    conversationError.value = "";
    conversationBefore.value = null;
    planVersion++;
    projectContext.value = null;
    projectIssues.value = [];
    boundProfileRevision.value = null;
    projectSyncError.value = false;
    planningRounds.value = [];
    roundsError.value = "";
    Object.assign(ids, emptyIds());
    profile.value = null;
    assets.value = [];
    repositories.value = [];
    directions.value = [];
    plan.value = null;
    copy.value = null;
    sufficiency.value = null;
    activeQuestion.value = null;
    planningAuthorizationIssue.value = null;
    messages.value = [];
    remember();
    try { localStorage.removeItem(projectKey); } catch {
      notice.value = "浏览器无法清除导航 ID；刷新后仍须核对已删除状态，未决执行标记保留。";
    }
  }

  function deletionBlocked(project: ProjectSummary) {
    return Object.entries(project.stepCounts).some(([status, count]) => count > 0 &&
        !["PENDING_CONFIRMATION", "SUCCEEDED", "FAILED", "CANCELLED"].includes(status)) ||
      !!project.currentRevision && project.currentRevision !== project.lastVerifiedRevision;
  }

  async function changeProjectVisibility(project: ProjectSummary, deleted: boolean) {
    let completed = false;
    if (deleted && deletionBlocked(project)) {
      error.value = "此项目有已授权或未决执行，不能删除。请先查询真实状态，等待标记会保留。";
      return false;
    }
    const epoch = contextEpoch.value;
    await run(deleted ? "从工作台删除项目" : "恢复项目到工作台", async () => {
      if (deleted) await api.deleteProject(project.key, project.version);
      else await api.restoreProject(project.key, project.version);
      if (disposed) return;
      completed = true;
      const currentKey = projectContext.value ? "project:" + projectContext.value.id
        : ids.planId ? "plan:" + ids.planId : "";
      if (currentKey === project.key && contextEpoch.value === epoch) clearNavigation();
      notice.value = deleted ? "项目已从正常工作台移除。业务数据和本地代码保留，可从已删除项目恢复。"
        : "项目已恢复到工作台。方向、计划和验证状态没有改变。";
      await loadProjects(false);
    }, false);
    return completed;
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
    conversationLoading,
    conversationError,
    conversationBefore,
    loadConversation,
    nextQuestion,
    messages,
    aiActivity,
    discoveryPending,
    discoveryUncertain,
    busy,
    error,
    notice,
    connected,
    selected,
    planningAuthorizationIssue,
    lastChecked,
    pollError,
    projectContext,
    projectIssues,
    boundProfileRevision,
    projects,
    planningRounds,
    roundsError,
    loadPlanningRounds,
    historyLoading,
    historyError,
    historyMore,
    showingDeletedProjects,
    deletionBlocked,
    deleteProject: (project: ProjectSummary) => changeProjectVisibility(project, true),
    restoreProject: (project: ProjectSummary) => changeProjectVisibility(project, false),
    projectSyncError,
    contextEpoch,
    loadProjects,
    openProject,
    async initialize() {
      let saved: string | null = null;
      try { saved = localStorage.getItem(projectKey); } catch { /* ID 无法读取时显示工作台。 */ }
      if (saved && validId(saved)) {
        const stage = await openProject("project:" + saved, false);
        if (projectIssues.value.includes("PROJECT_DELETED")) {
          clearNavigation();
          notice.value = "上次打开的项目已从工作台删除，可在已删除项目中恢复。业务数据和本地代码保留。";
          return null;
        }
        return stage;
      }
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
    dispose() {
      disposed = true;
      },
    async importIds(value: SessionIds) {
      if (busy.value) return;
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
      if (discoveryUncertain.value) {
        error.value = "上一轮结果仍不确定，请先查询画像并核对；不会重复发送或并发提交。";
        return;
      }
      await run("理解需求并更新画像", async () => {
        const current = profile.value!;
        const epoch = contextEpoch.value;
        const isCurrent = () => !disposed && contextEpoch.value === epoch && profile.value?.id === current.id;
        const message = reactive<DiscoveryMessage>({ role: "user", text: input,
          profileId: current.id, state: "pending" });
        messages.value.push(message);
        try {
          conversationRead++;
          const identity = requestIdentity(current);
          const result = await api.discoveryTurn(current.id, input, [], [], identity);
          if (!isCurrent()) { finishDiscoveryRequest(current.id, identity.requestId); return; }
          if (profile.value?.revision !== current.revision || profile.value?.status !== current.status ||
            result.profile.id !== current.id || result.profile.revision < current.revision) {
            message.state = "uncertain";
            message.failed = true;
            notice.value = "画像版本或状态已经变化，迟到的对话结果未加入当前会话；请查询画像并核对。";
            return;
          }
          finishDiscoveryRequest(current.id, identity.requestId);
          // 本轮成功响应与随后 run 保存的项目关联是两个结果。
          message.state = "accepted";
          profile.value = result.profile;
          acceptSufficiency(result);
          if (result.previousQuestionState && result.previousQuestionState !== "OPEN") activeQuestion.value = null;
          appendReply(result.assistantReply, result.nextQuestion);
        } catch (failure) {
          if (!isCurrent()) return;
          // 仅已知在保存前拒绝的契约可判为失败；断线、未知 5xx 均可能已提交。
          const rejected = failure instanceof ApiError && (
            failure.status === 400 && failure.code === "INVALID_REQUEST" ||
            failure.status === 404 && failure.code === "NOT_FOUND" ||
            failure.status === 409 && failure.code === "CONFLICT" ||
            failure.status === 502 && failure.code === "EXTERNAL_CAPABILITY_UNAVAILABLE");
          message.state = rejected ? "failed" : "uncertain";
          message.failed = true;
          let reconciled = false;
          try {
            if (await reconcileDiscovery()) return;
            reconciled = !pendingDiscovery.value[current.id];
          } catch { /* 查询失败时保留原请求编号与未决状态，稍后只读重查。 */ }
          if (!isCurrent()) return;
          if (rejected && failure instanceof ApiError && failure.status !== 409) {
            const requestId = pendingDiscovery.value[current.id];
            if (requestId) finishDiscoveryRequest(current.id, requestId);
          }
          if (!reconciled) await refreshProfileAfterFailure();
          throw failure;
        }
      }, true, { kind: "discovery", profileId: profile.value.id });
    },
    async abandonDiscovery() {
      const current = profile.value;
      const requestId = current && pendingDiscovery.value[current.id];
      if (!current || !requestId) return;
      await run("核对并放弃未完成请求", async () => {
        const epoch = contextEpoch.value;
        const result = requireOperation(await api.cancelDiscoveryRequest(current.id, requestId), requestId);
        if (disposed || contextEpoch.value !== epoch || profile.value?.id !== current.id) return;
        if (result.state === "COMPLETED") {
          await reconcileDiscovery();
          return;
        }
        if (!["CANCELLED", "FAILED", "INTERRUPTED"].includes(result.state)) {
          throw new ApiError(0, "RESPONSE_UNREADABLE");
        }
        finishDiscoveryRequest(current.id, requestId);
        for (const message of messages.value) {
          if (message.profileId === current.id && ["pending", "uncertain"].includes(message.state ?? "")) {
            message.state = "failed"; message.failed = true;
          }
        }
        notice.value = "未完成的本轮已放弃；迟到结果不会写入。可以核对后重新发送。";
      }, false);
    },
    async checkDiscoveryStatus() {
      const current = profile.value;
      if (!current) return;
      await run("查询画像状态", async () => {
        if (pendingDiscovery.value[current.id]) {
          await reconcileDiscovery();
          return;
        }
        const epoch = contextEpoch.value;
        const latest = await api.profile(current.id);
        if (disposed || contextEpoch.value !== epoch || profile.value?.id !== current.id) return;
        if (latest.revision !== current.revision || latest.status !== current.status) {
          sufficiency.value = null;
        }
        profile.value = latest;
        await loadConversation();
        if (disposed || contextEpoch.value !== epoch || profile.value?.id !== current.id) return;
        notice.value = discoveryUncertain.value
          ? "画像已查询；当前接口无法确认这条消息是否提交，也无法确认服务端请求是否结束。未重新发送，请核对画像。"
          : "画像已查询，未重新发送消息。";
      }, false);
    },
    async patch(patch: ProfilePatch) {
      if (profile.value)
        await run("保存画像纠正", async () => {
          profile.value = await api.patchProfile(profile.value!.id, patch, profile.value!.revision);
          sufficiency.value = null;
        });
    },
    async assess() {
      if (profile.value)
        await run("评估并请求进入审阅", async () => {
          const assessedRevision = profile.value!.revision;
          sufficiency.value = null;
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
          } catch (failure) {
            sufficiency.value = null;
            profile.value = await api.profile(ids.profileId);
            throw failure;
          }
        });
    },
    async resumeDiscovery() {
      const current = profile.value;
      const epoch = contextEpoch.value;
      const belongs = () => !disposed && contextEpoch.value === epoch && profile.value?.id === current?.id;
      if (current && current.status !== "EXPLORING")
        await run("重新进入探索", async () => {
          try {
            const resumed = await api.resumeDiscovery(current.id, current.status === "CONFIRMED");
            if (!belongs()) return;
            profile.value = resumed;
            sufficiency.value = null;
          } catch (failure) {
            if (!belongs()) return;
            await refreshProfileAfterFailure();
            throw failure;
          }
          // 用户主动返回探索后才索取一次问题；问题失败不撤销已完成的合法状态转换。
          try {
            if (!nextQuestion.value) await askQuestion();
          } catch (failure) {
            if (!belongs()) return;
            notice.value = "返回探索已获成功响应；下一问未取得可用响应，请核对当前画像后再决定是否提问。";
            throw failure;
          }
        }, true, { kind: "discovery", profileId: current.id });
    },
    async continueExploring() {
      if (busy.value || discoveryUncertain.value || !profile.value || profile.value.status !== "EXPLORING") return;
      if (nextQuestion.value) {
        notice.value = "请直接回答聊天区的下一轮问题，可以连续探索。";
        return;
      }
      await run("生成下一轮探索问题", askQuestion, true, { kind: "discovery", profileId: profile.value.id });
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
      }, true, { kind: "repository", assetId: value });
    },
    async discover(repositoryIds: string[]) {
      if (profile.value)
        await run("生成候选方向（调用 AI）", async () => {
          const result = await api.discover(
            profile.value!.id,
            profile.value!.revision,
            repositoryIds,
            projectContext.value,
          );
          directions.value.push(...result.directions);
          ids.directionIds.push(...result.directions.map((item) => item.id));
          remember();
        }, true, { kind: "directions" });
    },
    async decide(value: string, decision: "select" | "reject") {
      await run("保存方向决定", async () => {
        const result = await api.decideDirection(value, decision, projectContext.value);
        directions.value = directions.value.map((item) =>
          item.id === result.id ? result : item,
        );
        directions.value = await Promise.all(
          ids.directionIds.map(api.direction),
        );
        if (plan.value) await refreshPlan();
      });
    },
    async generatePlan(repositoryId: string) {
      const repository = repositories.value.find(
        (item) => item.id === repositoryId,
      );
      const direction = selected.value;
      const context = projectContext.value ? { ...projectContext.value } : null;
      const epoch = contextEpoch.value;
      const current = () => !disposed && contextEpoch.value === epoch &&
        projectContext.value?.id === context?.id;
      if (direction && repository && direction.candidateAssetIds.includes(repository.assetId))
        await run("规划演化（调用 AI）", async () => {
          planningAuthorizationIssue.value = null;
          let accepted = false;
          try {
            const result = await api.generatePlan(
              direction.id,
              repository.assetId,
              repository.id,
              context,
            );
            if (!current()) return;
            if (result.productDirectionId !== direction.id || result.baseAssetId !== repository.assetId ||
              result.baseRepositoryProfileId !== repository.id) throw new ApiError(0, "RESPONSE_UNREADABLE");
            setPlan(result);
            accepted = true;
            activeAiActivity.value = null;
            if (context) {
              busy.value = "核对项目关联";
              // 规划与 planId 关联由后端原子保存；回读版本后再保存导航集合。
              const workspace = await api.project("project:" + context.id);
              if (!current()) return;
              if (workspace.context?.id !== context.id || workspace.context.planId !== result.id)
                throw new ApiError(409, "PROJECT_CONTEXT_CONFLICT");
              projectContext.value = workspace.context;
            }
          } catch (failure) {
            if (!current()) return;
            const rejectedWithoutPlan = failure instanceof ApiError && ["PLANNING_AI_FAILED",
              "PLANNING_PROPOSAL_INVALID", "PLANNING_EVIDENCE_INVALID", "PLANNING_CURRENT_STATE_INVALID",
              "PLANNING_REJECTED"].includes(failure.code);
            if (context && (accepted || !(failure instanceof ApiError) || failure.status === 0 ||
              failure.status >= 500 && !rejectedWithoutPlan)) {
              projectSyncError.value = true;
              notice.value = accepted
                ? "计划已经保存，但项目关联回读失败。请重新打开当前项目核对，不要再次生成。"
                : "规划请求结果尚未确定。请先重新打开当前项目查询，系统不会自动重试付费请求。";
            }
            if (failure instanceof ApiError && ["ASSET_READ_PERMISSION_REQUIRED",
              "ASSET_USAGE_AUTHORIZATION_REQUIRED", "ASSET_LICENSE_INFORMATION_REQUIRED",
              "ASSET_AUTHORIZATION_CONFLICT"].includes(failure.code)) {
              planningAuthorizationIssue.value = { assetId: repository.assetId, code: failure.code };
            }
            throw failure;
          }
          copy.value = null;
        }, true, { kind: "planning" });
    },
    async beginReplanning() {
      const context = projectContext.value;
      if (!context) return false;
      let opened = false;
      await run("建立下一轮规划（不调用 AI）", async () => {
        const successor = await api.beginPlanningRound(context);
        const workspace = await api.project("project:" + successor.id);
        if (disposed) return;
        applyWorkspace(workspace);
        opened = true;
        notice.value = "新规划轮次已建立。请选择基础资产与分析快照；点击生成才会调用 AI。旧计划和副本仍保留。";
      }, false);
      if (opened) await loadPlanningRounds();
      return opened;
    },
    async prepare() {
      if (plan.value)
        await run("准备独立 WorkingCopy", async () => {
          setPlan(await api.prepare(plan.value!.id));
          await refreshPlan();
        });
    },
    async confirmStep(value: string, stillSelected: () => boolean = () => true) {
      const planId = plan.value?.id;
      const epoch = contextEpoch.value;
      const current = () => !disposed && contextEpoch.value === epoch && plan.value?.id === planId && stillSelected();
      if (!planId) return;
      await run("授权当前 Step", async () => {
        try {
          const result = await api.confirmStep(planId, value);
          if (!current()) return;
          setPlan(result);
          await refreshPlan();
        } catch (failure) {
          if (current()) throw failure;
        }
      });
    },
  };
}
export type Session = ReturnType<typeof createSession>;
export type WorkspaceSession = UnwrapNestedRefs<Session>;
