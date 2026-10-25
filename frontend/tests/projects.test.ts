import { createApp, nextTick, reactive } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import PlanView from "../src/views/PlanView.vue";
import { api, ApiError } from "../src/api/client";
import type { ProjectContext, ProjectSummary, ProjectWorkspace } from "../src/api/types";
import { createSession, projectKey, type Session } from "../src/state/session";
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
});


it("requires an explicit confirmation before replacing an active plan", async () => {
  const saved = workspace("replan-confirm", true); stubReads(saved);
  vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "synthetic", existingRoots: [] });
  const raw = make(); await raw.openProject(saved.key); const s = reactive(raw);
  const begin = vi.spyOn(s, "beginReplanning").mockResolvedValue(true);
  const root = document.createElement("div"); document.body.append(root);
  const app = createApp(PlanView, { s }); app.mount(root);
  cleanups.push(() => { app.unmount(); root.remove(); }); await settle();
  const button = (label: string) => [...root.querySelectorAll<HTMLButtonElement>("button")]
    .find(item => item.textContent?.trim() === label)!;
  button("重新规划").click(); await settle();
  expect(begin).not.toHaveBeenCalled();
  button("开始新一轮规划").click(); await settle();
  expect(begin).toHaveBeenCalledOnce();
});
