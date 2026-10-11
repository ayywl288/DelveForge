import { createApp, nextTick, reactive } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../src/App.vue";
import PlanView from "../src/views/PlanView.vue";
import EvolutionView from "../src/views/EvolutionView.vue";
import ProjectsView from "../src/views/ProjectsView.vue";
import { api, ApiError } from "../src/api/client";
import type { ProjectContext, ProjectSummary, ProjectWorkspace } from "../src/api/types";
import { createSession, executionKey, projectKey, type Session } from "../src/state/session";
import { asset, copy, direction, plan, profile, repository, successfulPlan } from "./fixtures";

const sessions: Session[] = [];
const cleanups: (() => void)[] = [];
afterEach(() => {
  cleanups.splice(0).forEach(fn => fn());
  sessions.splice(0).forEach(s => s.dispose());
  vi.restoreAllMocks();
});
function make() {
  const session = createSession(); sessions.push(session); return session;
}
function workspace(id: string, withPlan = false): ProjectWorkspace {
  const user = { ...profile(), id: "shared-user", status: "CONFIRMED" as const, revision: 2 };
  const a = { ...asset(), id: "asset-" + id, location: "C:/trusted/" + id };
  const repo = { ...repository(), id: "repo-" + id, assetId: a.id };
  const d = { ...direction(), id: "direction-" + id, userProfileId: user.id, repositoryProfileIds: [repo.id],
    candidateAssetIds: [a.id], status: "SELECTED" as const };
  const p = { ...plan(), id: "plan-" + id, productDirectionId: d.id, baseAssetId: a.id,
    baseRepositoryProfileId: repo.id, workingCopyId: "copy-" + id };
  p.steps = p.steps.map(step => ({ ...step, planId: p.id, id: id + "-step" }));
  const wc = { ...copy(), id: "copy-" + id, sourceAssetId: a.id };
  const context: ProjectContext = { id, name: "项目 " + id, userProfileId: user.id, assetIds: [a.id],
    repositoryProfileIds: [repo.id], directionIds: withPlan ? [d.id] : [], planId: withPlan ? p.id : null,
    createdAt: "2026-10-11T00:00:00Z", updatedAt: "2026-10-11T00:00:00Z", version: 0,
    flowId: id, previousContextId: null, deletedAt: null };
  return { key: "project:" + id, context, legacy: false, userProfile: user, assets: [a], repositoryProfiles: [repo],
    directions: withPlan ? [d] : [], plan: withPlan ? p : null, workingCopy: withPlan ? wc : null,
    stage: withPlan ? "EVOLUTION" : "DIRECTION", boundUserProfileRevision: withPlan ? 2 : null, issues: [] };
}
function stubReads(...values: ProjectWorkspace[]) {
  vi.spyOn(api, "planningRounds").mockResolvedValue([]);
  vi.spyOn(api, "project").mockImplementation(async key => {
    const value = values.find(item => item.key === key);
    if (!value) throw new ApiError(404, "NOT_FOUND");
    return structuredClone(value);
  });
  vi.spyOn(api, "reviewReadiness").mockResolvedValue({ profileId: "shared-user", revision: 2, sufficient: true });
}
async function settle() {
  for (let i = 0; i < 35; i++) { await Promise.resolve(); await nextTick(); }
}

function summary(saved: ProjectWorkspace): ProjectSummary {
  return { key: saved.key, name: saved.context?.name ?? "旧项目", legacy: saved.legacy, stage: saved.stage,
    direction: null, repositories: [], lastActivity: null, totalSteps: saved.plan ? 4 : 0, succeededSteps: 1,
    stepCounts: { SUCCEEDED: 1, PENDING_CONFIRMATION: 3 }, planId: saved.plan?.id ?? null,
    planStatus: saved.plan?.status ?? null, workingCopyId: saved.workingCopy?.id ?? null,
    currentRevision: "c822cc3", lastVerifiedRevision: "c822cc3", issues: [], version: 0, deletedAt: null };
}

function planningWorkspace(id: string) {
  const saved = workspace(id, true);
  saved.plan = null;
  saved.workingCopy = null;
  saved.context!.planId = null;
  saved.stage = "PLANNING";
  return saved;
}

describe("规划拒绝、重读与请求生命周期", () => {
  it("同一合法对象直接重提与重新加载使用相同 DTO，领域拒绝不自动付费重试", async () => {
    const saved = planningWorkspace("retry"); stubReads(saved);
    const generated = { ...plan(), id: "plan-retry", productDirectionId: "direction-retry",
      baseAssetId: "asset-retry", baseRepositoryProfileId: "repo-retry", workingCopyId: null };
    const generate = vi.spyOn(api, "generatePlan").mockRejectedValue(new ApiError(502, "PLANNING_CURRENT_STATE_INVALID"));
    vi.spyOn(api, "updateProject").mockImplementation(async context => context);
    const s = make(); await s.openProject(saved.key);
    await s.generatePlan("repo-retry");
    expect(s.error.value).toContain("现状条目"); expect(s.error.value).not.toContain("未收到对象");
    expect(s.plan.value).toBeNull(); expect(s.projectSyncError.value).toBe(false);
    await settle(); expect(generate).toHaveBeenCalledTimes(1);
    await s.generatePlan("repo-retry");
    expect(generate).toHaveBeenCalledTimes(2);
    expect(generate.mock.calls[0]).toEqual(generate.mock.calls[1]);
    await s.restore();
    generate.mockResolvedValue(generated);
    vi.mocked(api.project).mockResolvedValue({ ...saved, plan: generated,
      context: { ...saved.context!, planId: generated.id, version: 1 } });
    await s.generatePlan("repo-retry");
    expect(generate.mock.calls[0]).toEqual(generate.mock.calls[2]);
    expect(s.plan.value?.id).toBe(generated.id);
    expect(s.projectContext.value?.planId).toBe(generated.id);
    expect(generate).toHaveBeenCalledTimes(3);
  });

  it("已保存计划的关联回读失败保留计划 ID 并阻止重复生成，断线须先核对", async () => {
    const saved = planningWorkspace("uncertain"); stubReads(saved);
    const generate = vi.spyOn(api, "generatePlan").mockResolvedValue({ ...plan(), id: "persisted-plan",
      productDirectionId: "direction-uncertain", baseAssetId: "asset-uncertain", baseRepositoryProfileId: "repo-uncertain" });
    const s = make(); await s.openProject(saved.key);
    vi.mocked(api.project).mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
    await s.generatePlan("repo-uncertain");
    expect(s.ids.planId).toBe("persisted-plan"); expect(s.projectSyncError.value).toBe(true);
    expect(s.notice.value).toContain("计划已经保存");
    await s.generatePlan("repo-uncertain"); expect(generate).toHaveBeenCalledTimes(1);

    const disconnected = planningWorkspace("disconnect");
    stubReads(disconnected); await s.openProject(disconnected.key);
    generate.mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
    await s.generatePlan("repo-disconnect");
    expect(s.projectSyncError.value).toBe(true); expect(s.notice.value).toContain("结果尚未确定");
    await s.generatePlan("repo-disconnect"); expect(generate).toHaveBeenCalledTimes(2);
  });

  it("延迟规划期间阻止上下文切换；页面销毁后迟到结果不覆盖新会话 ID", async () => {
    const a = planningWorkspace("a"); const b = planningWorkspace("b"); stubReads(a, b);
    let finish!: (value: ReturnType<typeof plan>) => void;
    const generate = vi.spyOn(api, "generatePlan").mockReturnValue(new Promise(resolve => { finish = resolve; }));
    const s = make(); await s.openProject(a.key);
    const pending = s.generatePlan("repo-a"); await settle();
    await s.openProject(b.key); expect(s.projectContext.value?.id).toBe("a");
    s.dispose(); const next = make(); await next.openProject(b.key);
    finish({ ...plan(), id: "late-plan", productDirectionId: "direction-a", baseAssetId: "asset-a", baseRepositoryProfileId: "repo-a" });
    await pending;
    expect(next.projectContext.value?.id).toBe("b"); expect(next.ids.planId).toBe("");
    expect(localStorage.getItem(projectKey)).toBe("b"); expect(s.ids.planId).toBe("");
    expect(generate).toHaveBeenCalledOnce();
  });

  it("共享分析快照切换项目也要求重新明确选择，不保留上一方向的基础表单", async () => {
    const a = planningWorkspace("a"); const b = planningWorkspace("b");
    b.assets = a.assets; b.repositoryProfiles = a.repositoryProfiles;
    b.context!.assetIds = a.context!.assetIds; b.context!.repositoryProfileIds = a.context!.repositoryProfileIds;
    b.directions[0]!.candidateAssetIds = a.context!.assetIds; stubReads(a, b);
    vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "E:/trusted/workspaces", existingRoots: [] });
    const generate = vi.spyOn(api, "generatePlan"); const s = reactive(make()); await s.openProject(a.key);
    const root = document.createElement("div"); document.body.append(root);
    const app = createApp(PlanView, { s }); app.mount(root); cleanups.push(() => { app.unmount(); root.remove(); });
    await settle();
    const select = root.querySelector("#plan-base") as HTMLSelectElement;
    select.value = "repo-a"; select.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
    expect(select.value).toBe("repo-a");
    await s.openProject(b.key); await settle();
    expect((root.querySelector("#plan-base") as HTMLSelectElement).value).toBe("");
    expect(generate).not.toHaveBeenCalled();
  });
});

describe("工作台软删除与恢复", () => {
  it("删除当前导航记录只清理 ID，保留另一项目的未决 execute 标记", async () => {
    const saved = workspace("deleted", true); stubReads(saved);
    const marker = { planId: "plan-other", stepId: "other-step", startedAt: new Date().toISOString() };
    localStorage.setItem(executionKey, JSON.stringify(marker));
    vi.spyOn(api, "projects").mockResolvedValue([]);
    const remove = vi.spyOn(api, "deleteProject").mockResolvedValue({ key: saved.key, version: 1, deletedAt: new Date().toISOString() });
    const execute = vi.spyOn(api, "execute"); const generate = vi.spyOn(api, "generatePlan");
    const s = make(); await s.openProject(saved.key);
    expect(await s.deleteProject(summary(saved))).toBe(true);
    expect(remove).toHaveBeenCalledWith(saved.key, 0); expect(s.projectContext.value).toBeNull();
    expect(s.ids.planId).toBe(""); expect(localStorage.getItem(projectKey)).toBeNull();
    expect(JSON.parse(localStorage.getItem(executionKey)!)).toEqual(marker);
    expect(execute).not.toHaveBeenCalled(); expect(generate).not.toHaveBeenCalled();
  });

  it("结果未知或 READY 不允许删除，过期版本错误保留当前项目", async () => {
    const saved = workspace("pending", true); stubReads(saved);
    localStorage.setItem(executionKey, JSON.stringify({ planId: saved.plan!.id, stepId: "pending-step", startedAt: "now" }));
    const remove = vi.spyOn(api, "deleteProject"); const s = make(); await s.openProject(saved.key);
    expect(await s.deleteProject(summary(saved))).toBe(false); expect(remove).not.toHaveBeenCalled();
    localStorage.removeItem(executionKey); s.receipt.value = null;
    expect(await s.deleteProject({ ...summary(saved), stepCounts: { READY: 1 } })).toBe(false);
    remove.mockRejectedValue(new ApiError(409, "PROJECT_CONTEXT_CONFLICT"));
    expect(await s.deleteProject(summary(saved))).toBe(false);
    expect(s.projectContext.value?.id).toBe("pending"); expect(s.error.value).toContain("版本已变化");
  });

  it("删除确认和恢复都有明确操作，不会调用 AI、准备副本或执行", async () => {
    const saved = workspace("visible"); const item = summary(saved);
    vi.spyOn(api, "projects").mockImplementation(async (_offset, deleted) => deleted
      ? [{ ...item, version: 1, deletedAt: "2026-10-11T00:00:00Z" }] : [item]);
    vi.spyOn(api, "resources").mockResolvedValue([]);
    const remove = vi.spyOn(api, "deleteProject").mockResolvedValue({ key: item.key, version: 1, deletedAt: "2026-10-11T00:00:00Z" });
    const restore = vi.spyOn(api, "restoreProject").mockResolvedValue({ key: item.key, version: 2, deletedAt: null });
    const execute = vi.spyOn(api, "execute"); const prepare = vi.spyOn(api, "prepare");
    const s = reactive(make());
    const root = document.createElement("div"); document.body.append(root);
    const app = createApp(ProjectsView, { s }); app.mount(root); cleanups.push(() => { app.unmount(); root.remove(); });
    await settle();
    const button = [...root.querySelectorAll("button")].find(item => item.textContent === "删除项目")!;
    (button as HTMLButtonElement).click(); await settle();
    expect(root.querySelector("dialog[open]")?.textContent).toContain("不会删除"); expect(remove).not.toHaveBeenCalled();
    ([...root.querySelectorAll("dialog button")].find(item => item.textContent === "确认删除项目") as HTMLButtonElement).click();
    await settle(); expect(remove).toHaveBeenCalledOnce();
    ([...root.querySelectorAll("button")].find(item => item.textContent === "已删除项目") as HTMLButtonElement).click();
    await settle();
    ([...root.querySelectorAll("button")].find(item => item.textContent === "恢复到工作台") as HTMLButtonElement).click();
    await settle(); expect(restore).toHaveBeenCalledWith(item.key, 1);
    expect(execute).not.toHaveBeenCalled(); expect(prepare).not.toHaveBeenCalled();
  });

  it("刷新读回已删除上下文时返回工作台，不能静默写画像或重新认领", async () => {
    const saved = workspace("deleted-refresh", true);
    saved.context!.deletedAt = "2026-10-11T00:00:00Z"; saved.issues = ["PROJECT_DELETED"];
    stubReads(saved); localStorage.setItem(projectKey, saved.context!.id);
    const s = make(); const create = vi.spyOn(api, "createProfile"); const adopt = vi.spyOn(api, "adoptPlan");
    await s.openProject(saved.key, false); await s.createProfile(); expect(create).not.toHaveBeenCalled();
    expect(await s.initialize()).toBeNull(); expect(s.projectContext.value).toBeNull();
    expect(s.plan.value).toBeNull(); expect(adopt).not.toHaveBeenCalled();
  });

  it("切换已删除列表后丢弃延迟的正常列表，不把另一个列表覆盖回来", async () => {
    const saved = workspace("filter"); let finish!: (value: ProjectSummary[]) => void;
    vi.spyOn(api, "projects").mockImplementation((_offset, deleted) => deleted
      ? Promise.resolve([{ ...summary(saved), deletedAt: "2026-10-11T00:00:00Z" }])
      : new Promise(resolve => { finish = resolve; }));
    const s = make(); const pending = s.loadProjects(false, false); await s.loadProjects(false, true);
    finish([summary(saved)]); await pending;
    expect(s.showingDeletedProjects.value).toBe(true); expect(s.projects.value[0]?.deletedAt).toBeTruthy();
  });
});

describe("项目上下文恢复与隔离", () => {
  it("历史归属不明时保留读取但禁止业务写入和自动认领", async () => {
    const saved = workspace("ambiguous", true); saved.legacy = true; saved.context = null;
    saved.key = "plan:plan-ambiguous"; saved.issues = ["FLOW_OWNERSHIP_UNKNOWN"];
    stubReads(saved);
    const adopt = vi.spyOn(api, "adoptPlan"); const discover = vi.spyOn(api, "discover");
    const s = make(); await s.openProject(saved.key); await s.discover(["repo-ambiguous"]);
    expect(s.plan.value?.id).toBe("plan-ambiguous");
    expect(adopt).not.toHaveBeenCalled(); expect(discover).not.toHaveBeenCalled();
  });
  it("方向选择发送当前项目版本并回读旧计划失效，不影响另一个项目", async () => {
    const a = workspace("a", true); const b = workspace("b", true);
    const next = { ...a.directions[0]!, id: "direction-next", status: "CANDIDATE" as const };
    a.directions.push(next); a.context!.directionIds.push(next.id);
    stubReads(a, b);
    const decide = vi.spyOn(api, "decideDirection").mockResolvedValue({ ...next, status: "SELECTED" });
    vi.spyOn(api, "direction").mockImplementation(async id => id === next.id ? { ...next, status: "SELECTED" }
      : { ...a.directions[0]!, status: "SUPERSEDED" });
    vi.spyOn(api, "plan").mockResolvedValue({ ...a.plan!, status: "SUPERSEDED" });
    vi.spyOn(api, "workingCopy").mockResolvedValue(a.workingCopy!);
    vi.spyOn(api, "updateProject").mockImplementation(async context => ({ ...context, version: context.version + 1 }));
    const s = make(); await s.openProject(a.key); await s.decide(next.id, "select");
    expect(decide).toHaveBeenCalledWith(next.id, "select", a.context);
    expect(s.plan.value?.status).toBe("SUPERSEDED"); expect(s.selected.value?.id).toBe(next.id);
    await s.openProject(b.key); expect(s.plan.value?.status).toBe("ACTIVE"); expect(s.selected.value?.id).toBe("direction-b");
  });

  it("失效计划明确进入关联新轮次，仍须点击正常规划，旧计划与副本保持历史", async () => {
    const old = workspace("old", true); old.plan!.status = "SUPERSEDED";
    old.directions[0]!.status = "SUPERSEDED";
    const selected = { ...old.directions[0]!, id: "current", status: "SELECTED" as const };
    old.directions.push(selected); old.context!.directionIds.push(selected.id);
    const next = structuredClone(old); next.key = "project:next";
    next.context = { ...old.context!, id: "next", previousContextId: "old", planId: null };
    next.plan = null; next.workingCopy = null; next.stage = "PLANNING";
    stubReads(old, next);
    const begin = vi.spyOn(api, "beginPlanningRound").mockResolvedValue(next.context!);
    vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "E:/trusted/workspaces", existingRoots: [] });
    const generate = vi.spyOn(api, "generatePlan"); const execute = vi.spyOn(api, "execute");
    const s = reactive(make()); await s.openProject(old.key);
    const root = document.createElement("div"); document.body.append(root);
    const app = createApp(PlanView, { s }); app.mount(root); cleanups.push(() => { app.unmount(); root.remove(); });
    await settle();
    expect(root.textContent).toContain("这份计划已失效"); expect(root.textContent).toContain("当前选中方向");
    const action = [...root.querySelectorAll("button")].find(button => button.textContent?.includes("基于当前方向重新规划"))!;
    action.click(); await settle();
    expect(begin).toHaveBeenCalledOnce(); expect(s.projectContext?.id).toBe("next");
    expect(s.ids.planId).toBe(""); expect(s.copy).toBeNull();
    expect(root.querySelector("#plan-base")).not.toBeNull();
    expect(old.plan?.id).toBe("plan-old"); expect(old.workingCopy?.id).toBe("copy-old");
    expect(generate).not.toHaveBeenCalled(); expect(execute).not.toHaveBeenCalled();
  });

  it("旧轮次查询迟到不能写入新项目，打开与刷新不自动规划或执行", async () => {
    const a = workspace("a", true); const b = workspace("b", true); stubReads(a, b);
    let finish!: (value: ProjectSummary[]) => void;
    vi.mocked(api.planningRounds).mockReturnValueOnce(new Promise(resolve => { finish = resolve; }));
    const generate = vi.spyOn(api, "generatePlan"); const execute = vi.spyOn(api, "execute");
    const s = make(); const first = s.openProject(a.key); await settle();
    await s.openProject(b.key); finish([{ key: a.key } as ProjectSummary]);
    expect(await first).toBeNull(); expect(s.planningRounds.value).toEqual([]);
    expect(s.projectContext.value?.id).toBe("b");
    expect(generate).not.toHaveBeenCalled(); expect(execute).not.toHaveBeenCalled();
  });

  it("同一画像的两个仓库流程切换精确关联，并清除上一流程聊天与草稿身份", async () => {
    const one = workspace("one"); const two = workspace("two"); stubReads(one, two);
    const s = make(); await s.openProject(one.key);
    s.messages.value.push({ role: "user", text: "本页私有草稿" });
    await s.openProject(two.key);
    expect(s.profile.value?.id).toBe("shared-user");
    expect(s.ids.repositoryIds).toEqual(["repo-two"]);
    expect(s.assets.value.map(item => item.id)).toEqual(["asset-two"]);
    expect(s.messages.value).toEqual([]);
    expect(localStorage.getItem(projectKey)).toBe("two");
    expect(localStorage.getItem(projectKey)).not.toContain("项目");
    expect(api.project).toHaveBeenCalledTimes(2);
  });

  it("同一仓库不同 Plan 不按路径或最新时间合并，恢复准确步骤与 Verified B", async () => {
    const one = workspace("one", true); const two = workspace("two", true);
    two.assets = one.assets; two.repositoryProfiles = one.repositoryProfiles;
    two.context!.assetIds = one.context!.assetIds; two.context!.repositoryProfileIds = one.context!.repositoryProfileIds;
    two.directions[0]!.candidateAssetIds = one.context!.assetIds;
    two.directions[0]!.repositoryProfileIds = one.context!.repositoryProfileIds;
    const saved = successfulPlan().steps[0]!;
    one.plan!.steps = [{ ...saved, id: "one-step", planId: "plan-one" }, ...[2, 3, 4].map(n => ({
      ...plan().steps[0]!, id: "one-step-" + n, planId: "plan-one", status: "PENDING_CONFIRMATION" as const,
    }))];
    one.workingCopy!.currentRevision = "b".repeat(40); one.workingCopy!.lastVerifiedRevision = "b".repeat(40);
    stubReads(one, two); const s = make(); await s.openProject(two.key); await s.openProject(one.key);
    expect(s.plan.value?.id).toBe("plan-one"); expect(s.plan.value?.steps).toHaveLength(4);
    expect(s.plan.value?.steps.filter(step => step.status === "SUCCEEDED")).toHaveLength(1);
    expect(s.copy.value?.lastVerifiedRevision).toBe("b".repeat(40));
    expect(s.directions.value[0]?.id).toBe("direction-one");
  });

  it("浏览器重建会话只按保存项目 ID GET，不生成历史对话或重新调用业务 POST", async () => {
    const saved = workspace("saved", true); stubReads(saved);
    localStorage.setItem(projectKey, "saved");
    const execute = vi.spyOn(api, "execute"); const discover = vi.spyOn(api, "discover");
    const prepare = vi.spyOn(api, "prepare"); const analyze = vi.spyOn(api, "analyze");
    const question = vi.spyOn(api, "discoveryQuestion"); const update = vi.spyOn(api, "updateProject");
    const s = make(); expect(await s.initialize()).toBe("EVOLUTION");
    expect(s.messages.value).toEqual([]); expect(s.nextQuestion.value).toBeNull();
    for (const spy of [execute, discover, prepare, analyze, question, update]) expect(spy).not.toHaveBeenCalled();
  });

  it("旧 Plan 明确打开时只认领上下文，后续刷新不重复认领或执行", async () => {
    const saved = workspace("adopted", true); const legacy = { ...saved, key: "plan:plan-adopted", legacy: true, context: null };
    stubReads(saved, legacy);
    const adopt = vi.spyOn(api, "adoptPlan").mockResolvedValue(saved.context!);
    const execute = vi.spyOn(api, "execute"); const s = make();
    await s.openProject(legacy.key); await s.restore();
    expect(adopt).toHaveBeenCalledOnce(); expect(adopt).toHaveBeenCalledWith("plan-adopted");
    expect(s.ids.planId).toBe("plan-adopted"); expect(execute).not.toHaveBeenCalled();
  });

  it("新建空流程与复用确认画像都不自动调用 Provider 或修改旧画像", async () => {
    const blank = workspace("blank"); blank.context!.userProfileId = null;
    blank.context!.assetIds = []; blank.context!.repositoryProfileIds = [];
    blank.userProfile = null; blank.assets = []; blank.repositoryProfiles = []; blank.stage = "DISCOVERY";
    const reused = workspace("reuse"); reused.assets = []; reused.repositoryProfiles = [];
    reused.context!.assetIds = []; reused.context!.repositoryProfileIds = []; reused.stage = "REPOSITORY";
    stubReads(blank, reused);
    vi.spyOn(api, "createProject").mockResolvedValueOnce(blank.context!).mockResolvedValueOnce(reused.context!);
    const profileCreate = vi.spyOn(api, "createProfile"); const patch = vi.spyOn(api, "patchProfile");
    const turn = vi.spyOn(api, "discoveryTurn"); const s = make();
    expect(await s.newProject("空流程", null)).toBe("DISCOVERY"); expect(s.profile.value).toBeNull();
    expect(await s.newProject("复用流程", "shared-user")).toBe("REPOSITORY");
    expect(s.profile.value?.status).toBe("CONFIRMED");
    for (const spy of [profileCreate, patch, turn]) expect(spy).not.toHaveBeenCalled();
  });

  it("明确选择已有分析按保存的 assetId 读取和保存，不重新分析", async () => {
    const saved = workspace("new"); saved.context!.assetIds = []; saved.context!.repositoryProfileIds = [];
    saved.assets = []; saved.repositoryProfiles = []; stubReads(saved);
    vi.spyOn(api, "repository").mockResolvedValue(repository()); vi.spyOn(api, "asset").mockResolvedValue(asset());
    const update = vi.spyOn(api, "updateProject").mockImplementation(async (context, ids) => ({ ...context,
      version: context.version + 1, assetIds: ids.assetIds, repositoryProfileIds: ids.repositoryIds }));
    const analyze = vi.spyOn(api, "analyze"); const s = make(); await s.openProject(saved.key);
    await s.selectResource("REPOSITORY_PROFILE", "repo-1");
    expect(api.asset).toHaveBeenCalledWith("asset-1"); expect(analyze).not.toHaveBeenCalled();
    expect(update.mock.calls[0]?.[0].id).toBe("new"); expect(s.ids.repositoryIds).toEqual(["repo-1"]);
  });

  it("上一项目的慢轮询响应不能覆盖已切换项目", async () => {
    const one = workspace("one", true); const two = workspace("two", true); stubReads(one, two);
    let complete!: (value: ReturnType<typeof plan>) => void;
    vi.spyOn(api, "plan").mockReturnValue(new Promise(resolve => { complete = resolve; }));
    vi.spyOn(api, "workingCopy").mockResolvedValue(one.workingCopy!);
    const s = make(); await s.openProject(one.key); const pending = s.refreshPlan();
    await s.openProject(two.key); complete(one.plan!); await pending;
    expect(s.plan.value?.id).toBe("plan-two"); expect(s.copy.value?.id).toBe("copy-two");
    expect(s.ids.planId).toBe("plan-two"); expect(s.projectContext.value?.id).toBe("two");
  });

  it("正在提交业务时不能切换到另一流程；不会把切换误作取消执行", async () => {
    const one = workspace("one", true); const two = workspace("two", true); stubReads(one, two);
    const s = make(); await s.openProject(one.key); s.busy.value = "执行当前 Step";
    s.executionPending.value = true;
    expect(await s.openProject(two.key)).toBeNull();
    expect(await s.newProject("不要并发创建", null)).toBeNull();
    expect(s.projectContext.value?.id).toBe("one"); expect(s.executionPending.value).toBe(true);
    expect(api.project).toHaveBeenCalledOnce();
  });

  it("断线标记跨项目保留，新流程只查询自己的 Plan，禁止自动或重复 execute", async () => {
    const marker = { planId: "old-plan", stepId: "old-step", startedAt: "2026-10-11T00:00:00Z" };
    localStorage.setItem(executionKey, JSON.stringify(marker));
    const other = workspace("other", true); stubReads(other);
    vi.spyOn(api, "plan").mockResolvedValue(other.plan!); vi.spyOn(api, "workingCopy").mockResolvedValue(other.workingCopy!);
    const execute = vi.spyOn(api, "execute"); const s = make(); await s.openProject(other.key);
    await s.refreshPlan(); await s.execute("other-step");
    expect(api.plan).toHaveBeenCalledWith("plan-other"); expect(execute).not.toHaveBeenCalled();
    expect(s.receipt.value).toEqual(marker); expect(localStorage.getItem(executionKey)).not.toBeNull();
  });

  it("业务成功但上下文 CAS 失败时报告两种事实，不自动重放 AI 或覆盖其它项目", async () => {
    const saved = workspace("one"); stubReads(saved);
    vi.spyOn(api, "asset").mockResolvedValue(asset());
    const update = vi.spyOn(api, "updateProject").mockRejectedValue(new ApiError(409, "PROJECT_CONTEXT_CONFLICT"));
    const s = make(); await s.openProject(saved.key); await s.selectResource("SOFTWARE_ASSET", "asset-1");
    expect(s.error.value).toContain("版本已变化"); expect(s.notice.value).toContain("业务请求已返回");
    expect(s.projectSyncError.value).toBe(true); await s.selectResource("SOFTWARE_ASSET", "asset-1");
    expect(update).toHaveBeenCalledOnce();
    await s.openProject(saved.key); expect(s.projectSyncError.value).toBe(false);
  });

  it("历史目录未允许与资源不一致真实展示，不能悄悄生成新 WorkingCopy", async () => {
    const saved = workspace("old", true); saved.issues = ["WORKSPACE_ROOT_NOT_ALLOWED", "ASSOCIATION_MISMATCH"];
    stubReads(saved); const prepare = vi.spyOn(api, "prepare"); const execute = vi.spyOn(api, "execute");
    const s = make(); await s.openProject(saved.key); await s.prepare(); await s.execute("old-step");
    expect(s.projectIssues.value).toEqual(saved.issues); expect(prepare).not.toHaveBeenCalled(); expect(execute).not.toHaveBeenCalled();
    expect(s.plan.value?.id).toBe("plan-old");
  });

  it("会话卸载后的慢业务响应不再提交上下文，也不覆写浏览器中另一会话的 ID", async () => {
    const saved = workspace("old"); stubReads(saved);
    let complete!: (value: ReturnType<typeof asset>) => void;
    vi.spyOn(api, "asset").mockReturnValue(new Promise(resolve => { complete = resolve; }));
    const update = vi.spyOn(api, "updateProject");
    const s = make(); await s.openProject(saved.key);
    const pending = s.selectResource("SOFTWARE_ASSET", "asset-1");
    s.dispose(); localStorage.setItem(projectKey, "another");
    complete(asset()); await pending;
    expect(update).not.toHaveBeenCalled(); expect(localStorage.getItem(projectKey)).toBe("another");
  });

  it("Verified B 恢复后的下一步授权与执行按钮可用，但渲染和查看不会自动提交", async () => {
    const saved = workspace("continued", true);
    const first = { ...successfulPlan().steps[0]!, id: "first", planId: "plan-continued" };
    const second = { ...plan().steps[0]!, id: "second", planId: "plan-continued",
      status: "PENDING_CONFIRMATION" as const };
    saved.plan!.steps = [first, second]; saved.workingCopy!.status = "EVOLVING";
    saved.workingCopy!.currentRevision = "b".repeat(40); saved.workingCopy!.lastVerifiedRevision = "b".repeat(40);
    stubReads(saved);
    vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "E:/trusted/workspaces", existingRoots: [] });
    const confirm = vi.spyOn(api, "confirmStep"); const execute = vi.spyOn(api, "execute");
    const s = reactive(make()); await s.openProject(saved.key);
    const root = document.createElement("div"); document.body.append(root);
    const app = createApp(PlanView, { s }); app.mount(root);
    const authorize = [...root.querySelectorAll("button")].find(item => item.textContent?.includes("审阅并授权此 Step"));
    expect(authorize?.disabled).toBe(false); expect(confirm).not.toHaveBeenCalled();
    app.unmount(); s.plan!.steps[1]!.status = "READY";
    const evolution = createApp(EvolutionView, { s }); evolution.mount(root);
    cleanups.push(() => { evolution.unmount(); root.remove(); });
    const run = [...root.querySelectorAll("button")].find(item => item.textContent?.includes("审阅风险并执行此 Step"));
    expect(run?.disabled).toBe(false); expect(execute).not.toHaveBeenCalled();
    s.copy!.currentRevision = "c".repeat(40); await nextTick(); expect(run?.disabled).toBe(true);
  });

  it("恢复 Verified B 的 EVOLVING 副本后，允许用户明确执行已授权下一步，仍拒绝未验证 Candidate", async () => {
    const saved = workspace("continued", true);
    const first = { ...successfulPlan().steps[0]!, id: "first", planId: "plan-continued" };
    const second = { ...plan().steps[0]!, id: "second", planId: "plan-continued" };
    saved.plan!.steps = [first, second]; saved.workingCopy!.status = "EVOLVING";
    saved.workingCopy!.currentRevision = "b".repeat(40); saved.workingCopy!.lastVerifiedRevision = "b".repeat(40);
    stubReads(saved);
    vi.spyOn(api, "plan").mockResolvedValue(saved.plan!); vi.spyOn(api, "workingCopy").mockResolvedValue(saved.workingCopy!);
    const execute = vi.spyOn(api, "execute").mockRejectedValue(new ApiError(502, "EXTERNAL_CAPABILITY_UNAVAILABLE"));
    const s = make(); await s.openProject(saved.key); await s.execute("second");
    expect(execute).toHaveBeenCalledOnce(); expect(execute).toHaveBeenCalledWith("plan-continued", "second");
    expect(s.receipt.value).toBeNull();
    vi.mocked(api.workingCopy).mockResolvedValue({ ...saved.workingCopy!, currentRevision: "c".repeat(40) });
    await s.execute("second"); expect(execute).toHaveBeenCalledOnce();
  });
});

it("项目工作台用紧凑列表显示真实计数，明确创建与恢复，UUID 恢复仅在高级区", async () => {
  const saved = workspace("saved", true);
  const summary: ProjectSummary = { key: saved.key, name: "已保存 REST 项目", legacy: false, stage: "EVOLUTION",
    direction: "REST", repositories: [{ assetId: "asset-saved", location: "C:/trusted/rest" }],
    lastActivity: "2026-10-11T00:00:00Z", totalSteps: 4, succeededSteps: 1,
    stepCounts: { SUCCEEDED: 1, PENDING_CONFIRMATION: 3 }, planId: "plan-saved", planStatus: "ACTIVE",
    workingCopyId: "copy-saved", currentRevision: "c822cc3", lastVerifiedRevision: "c822cc3", issues: [],
    version: 0, deletedAt: null };
  vi.spyOn(api, "connectivity").mockResolvedValue({ service: "backend", status: "UP", timestamp: "" });
  vi.spyOn(api, "projects").mockResolvedValue([summary]); vi.spyOn(api, "resources").mockResolvedValue([]);
  stubReads(saved); const execute = vi.spyOn(api, "execute");
  const root = document.createElement("div"); document.body.append(root);
  const app = createApp(App); app.mount(root); cleanups.push(() => app.unmount()); await settle();
  expect(root.querySelector("h1")?.textContent).toContain("项目工作台");
  expect(root.querySelector(".project-row")?.textContent).toContain("1/4");
  expect(root.querySelector(".project-details")?.textContent).toContain("c822cc3");
  expect(root.querySelector(".advanced-recovery")?.textContent).toContain("恢复已知对象");
  (root.querySelector(".project-actions button") as HTMLButtonElement).click(); await settle();
  expect(root.querySelector("h1")?.textContent).toContain("验证演化"); expect(execute).not.toHaveBeenCalled();
  (root.querySelector(".project-entry") as HTMLButtonElement).click(); await settle();
  (root.querySelector(".new-project summary") as HTMLElement).click(); await nextTick();
  expect(root.querySelector(".new-project")?.hasAttribute("open")).toBe(true);
  const input = root.querySelector("#project-name") as HTMLInputElement;
  input.value = "全新项目"; input.dispatchEvent(new Event("input", { bubbles: true })); await nextTick();
  const blank = workspace("blank"); blank.userProfile = null; blank.stage = "DISCOVERY";
  blank.context!.userProfileId = null; blank.context!.assetIds = []; blank.context!.repositoryProfileIds = [];
  blank.assets = []; blank.repositoryProfiles = [];
  vi.mocked(api.project).mockResolvedValue(blank); vi.spyOn(api, "createProject").mockResolvedValue(blank.context!);
  const form = root.querySelector(".new-project form")!; form.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })); await settle();
  expect(root.querySelector("h1")?.textContent).toContain("需求探索"); expect(root.textContent).toContain("开始需求探索");
});

it("列表保留完整技术信息与需要核对的问题，没有 Plan 时不显示虚构进度或状态", async () => {
  const saved = workspace("metadata", true);
  const revision = "c822cc384234342a95f5239f2b4ce060002f0d15";
  const planned = { ...summary(saved), currentRevision: revision, lastVerifiedRevision: revision,
    repositories: [{ assetId: "asset-metadata", location: "E:/trusted/rest-service" }],
    issues: ["PROFILE_BASIS_CHANGED"] };
  const empty = { ...summary(workspace("new")), totalSteps: 0, succeededSteps: 0, stepCounts: {},
    planStatus: null, currentRevision: null, lastVerifiedRevision: null };
  vi.spyOn(api, "projects").mockResolvedValue([planned, empty]);
  vi.spyOn(api, "resources").mockResolvedValue([]);
  const execute = vi.spyOn(api, "execute"); const generate = vi.spyOn(api, "generatePlan");
  const s = reactive(make()); const root = document.createElement("div"); document.body.append(root);
  const app = createApp(ProjectsView, { s }); app.mount(root);
  cleanups.push(() => { app.unmount(); root.remove(); }); await settle();
  const rows = root.querySelectorAll(".project-row");
  expect(rows).toHaveLength(2);
  expect(rows[0]!.querySelector(".project-details")?.hasAttribute("open")).toBe(false);
  (rows[0]!.querySelector(".project-details summary") as HTMLElement).click(); await nextTick();
  expect(rows[0]!.querySelector(".project-details")?.hasAttribute("open")).toBe(true);
  expect(rows[0]!.querySelector(".project-metadata")?.textContent).toContain(revision);
  expect(rows[0]!.querySelector(".project-metadata")?.textContent).toContain("E:/trusted/rest-service");
  expect(rows[0]!.querySelector(".project-row-issues summary")?.textContent).toContain("1 项需要核对");
  expect(rows[1]!.querySelector(".project-progress")).toBeNull();
  expect(rows[1]!.querySelector(".badge")).toBeNull();
  expect(rows[1]!.textContent).toContain("尚未生成计划");
  expect(rows[1]!.textContent).toContain("未保存活动时间");
  expect(execute).not.toHaveBeenCalled(); expect(generate).not.toHaveBeenCalled();
});

it("展开新流程并选择已确认画像后，仍由用户提交精确的复用身份", async () => {
  const saved = workspace("reuse-ui"); stubReads(saved);
  vi.spyOn(api, "projects").mockResolvedValue([]);
  vi.spyOn(api, "resources").mockResolvedValue([
    { id: "shared-user", summary: "已确认画像", status: "CONFIRMED", revision: 2,
      location: null, assetId: null, analyzedRevision: null },
    { id: "draft-user", summary: "探索画像", status: "EXPLORING", revision: 0,
      location: null, assetId: null, analyzedRevision: null },
  ]);
  const create = vi.spyOn(api, "createProject").mockResolvedValue(saved.context!);
  const createProfile = vi.spyOn(api, "createProfile"); const execute = vi.spyOn(api, "execute");
  const s = reactive(make()); const root = document.createElement("div"); document.body.append(root);
  const app = createApp(ProjectsView, { s }); app.mount(root);
  cleanups.push(() => { app.unmount(); root.remove(); }); await settle();
  (root.querySelector(".new-project summary") as HTMLElement).click();
  (root.querySelector('input[value="reuse"]') as HTMLInputElement).click(); await settle();
  const input = root.querySelector("#project-name") as HTMLInputElement;
  input.value = "复用流程"; input.dispatchEvent(new Event("input", { bubbles: true })); await nextTick();
  const select = root.querySelector("#project-profile") as HTMLSelectElement;
  expect([...select.options].map(option => option.value)).toEqual(["", "shared-user"]);
  expect(create).not.toHaveBeenCalled();
  select.value = "shared-user"; select.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
  root.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true })); await settle();
  expect(create).toHaveBeenCalledWith("复用流程", "shared-user");
  expect(createProfile).not.toHaveBeenCalled(); expect(execute).not.toHaveBeenCalled();
});
