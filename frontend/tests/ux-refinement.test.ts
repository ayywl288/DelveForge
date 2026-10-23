import { createApp, nextTick, reactive, type Component } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../src/App.vue";
import { api, ApiError } from "../src/api/client";
import type { DiscoveryQuestion, DiscoveryTurn, UserProfile } from "../src/api/types";
import * as sessionModule from "../src/state/session";
import type { WorkspaceSession } from "../src/state/session";
import DiscoveryView from "../src/views/DiscoveryView.vue";
import PlanView from "../src/views/PlanView.vue";
import EvolutionView from "../src/views/EvolutionView.vue";
import { asset, copy, direction, plan, profile, repository } from "./fixtures";

const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.restoreAllMocks(); });
function session() {
  const raw = sessionModule.createSession();
  cleanup.push(raw.dispose);
  const s = reactive(raw);
  s.profile = profile(); s.ids.profileId = s.profile.id;
  return { raw, s };
}
function mount(view: Component, s: WorkspaceSession, extra = {}) {
  const root = document.createElement("div"); document.body.append(root);
  const app = createApp(view, { s, ...extra }); app.mount(root);
  cleanup.push(() => { app.unmount(); root.remove(); });
  return root;
}
function button(root: Element, label: string) {
  return [...root.querySelectorAll<HTMLButtonElement>("button")].find(item => item.textContent?.trim() === label);
}
async function settle() {
  for (let i = 0; i < 25; i++) { await Promise.resolve(); await nextTick(); }
}
function deferred<T>() {
  let resolve!: (result: T) => void;
  let reject!: (failure: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
const question = (): DiscoveryQuestion => ({ revision: 1, sufficient: false,
  missingAreas: ["使用场景"], nextQuestion: "服务端实际生成的问题？" });

describe("Discovery 全部提问入口和可访问消息", () => {
  it.each(["CONFIRMED", "REVIEWING"] as const)("%s 返回探索的转换和提问均显示同一等待气泡", async status => {
    const resumed = deferred<UserProfile>(), requested = deferred<DiscoveryQuestion>();
    const resume = vi.spyOn(api, "resumeDiscovery").mockReturnValue(resumed.promise);
    const ask = vi.spyOn(api, "discoveryQuestion").mockReturnValue(requested.promise);
    const { s } = session(); s.profile = { ...profile(), status };
    const root = mount(DiscoveryView, s);
    const pending = s.resumeDiscovery(); await settle();
    expect(root.querySelector(".assistant-waiting")).not.toBeNull();
    expect(root.querySelector<HTMLElement>(".chat-log")?.style.display).not.toBe("none");
    expect(root.querySelector(".chat-intro")).toBeNull();
    expect(root.querySelector(".request-note")).toBeNull();
    expect(resume).toHaveBeenCalledExactlyOnceWith("profile-1", status === "CONFIRMED");
    expect(ask).not.toHaveBeenCalled();
    resumed.resolve(profile()); await settle();
    expect(s.profile?.status).toBe("EXPLORING");
    expect(root.querySelector(".assistant-waiting")).not.toBeNull();
    await s.continueExploring();
    expect(ask).toHaveBeenCalledTimes(1);
    requested.resolve(question()); await pending; await settle();
    expect(s.discoveryPending).toBe(false);
    expect(root.querySelector(".assistant-waiting")).toBeNull();
    expect(s.messages).toEqual([{ role: "agent", text: question().nextQuestion }]);
    expect(root.querySelector(".message-author")?.classList.contains("sr-only")).toBe(true);
  });

  it.each([false, true])("主动下一问的等待在成功或失败后清除 (failure=%s)", async failure => {
    const requested = deferred<DiscoveryQuestion>();
    const ask = vi.spyOn(api, "discoveryQuestion").mockReturnValue(requested.promise);
    vi.spyOn(api, "profile").mockResolvedValue(profile());
    const { s } = session(); const root = mount(DiscoveryView, s);
    button(root, "请 Agent 提出下一问")!.click(); await settle();
    expect(s.discoveryPending).toBe(true);
    expect(root.querySelector(".chat-intro")).toBeNull();
    expect(root.querySelector(".waiting-dots")?.getAttribute("aria-hidden")).toBe("true");
    expect(root.querySelectorAll(".waiting-dots i")).toHaveLength(3);
    expect(root.querySelector(".assistant-waiting .sr-only")?.textContent).toContain("正在整理");
    expect(root.querySelector(".assistant-waiting [role='status']")).toBeNull();
    await s.continueExploring(); expect(ask).toHaveBeenCalledTimes(1);
    if (failure) requested.reject(new ApiError(502, "EXTERNAL_CAPABILITY_UNAVAILABLE"));
    else requested.resolve(question());
    await settle();
    expect(s.discoveryPending).toBe(false);
    expect(root.querySelector(".assistant-waiting")).toBeNull();
    expect(s.messages).toHaveLength(failure ? 0 : 1);
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it("已接受的返回探索转换不会因下一问失败被撤销或伪造问题", async () => {
    vi.spyOn(api, "resumeDiscovery").mockResolvedValue(profile());
    const ask = vi.spyOn(api, "discoveryQuestion").mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
    vi.spyOn(api, "profile").mockResolvedValue(profile());
    const { s } = session(); s.profile = { ...profile(), status: "CONFIRMED" };
    const root = mount(DiscoveryView, s);
    await s.resumeDiscovery(); await settle();
    expect(s.profile?.status).toBe("EXPLORING");
    expect(s.notice).toContain("返回探索已获成功响应");
    expect(s.messages).toHaveLength(0);
    expect(s.error).toBeTruthy();
    expect(root.querySelector(".assistant-waiting")).toBeNull();
    expect(ask).toHaveBeenCalledTimes(1);
  });

  it("返回探索转换失败不索取下一问，无问题响应不伪造消息", async () => {
    vi.spyOn(api, "resumeDiscovery").mockRejectedValue(new ApiError(409, "CONFLICT"));
    vi.spyOn(api, "profile").mockResolvedValue({ ...profile(), status: "REVIEWING" });
    const ask = vi.spyOn(api, "discoveryQuestion").mockResolvedValue({ ...question(), nextQuestion: null });
    const { s } = session(); s.profile = { ...profile(), status: "REVIEWING" };
    await s.resumeDiscovery();
    expect(s.discoveryPending).toBe(false); expect(s.profile?.status).toBe("REVIEWING");
    expect(ask).not.toHaveBeenCalled();
    s.profile = profile(); await s.continueExploring();
    expect(s.messages).toHaveLength(0); expect(s.nextQuestion).toBeNull();
  });

  it.each(["profile", "project", "failure"])("提问迟到不会修改已切换的上下文：%s", async changed => {
    const requested = deferred<DiscoveryQuestion>();
    vi.spyOn(api, "discoveryQuestion").mockReturnValue(requested.promise);
    const read = vi.spyOn(api, "profile");
    const { s } = session(); const pending = s.continueExploring();
    if (changed === "profile") { s.profile = { ...profile(), id: "other-profile" }; s.ids.profileId = "other-profile"; }
    else s.contextEpoch++;
    expect(s.discoveryPending).toBe(false);
    if (changed === "failure") requested.reject(new ApiError(0, "CONNECTION_LOST"));
    else requested.resolve(question());
    await pending;
    expect(s.messages).toHaveLength(0); expect(s.sufficiency).toBeNull(); expect(s.nextQuestion).toBeNull();
    expect(read).not.toHaveBeenCalled(); expect(s.error).toBe("");
  });

  it("切换项目后返回探索的迟到转换不会覆盖新画像或额外索取问题", async () => {
    const resumed = deferred<UserProfile>();
    vi.spyOn(api, "resumeDiscovery").mockReturnValue(resumed.promise);
    const ask = vi.spyOn(api, "discoveryQuestion");
    const { s } = session(); s.profile = { ...profile(), status: "CONFIRMED" };
    const pending = s.resumeDiscovery(); s.contextEpoch++;
    s.profile = { ...profile(), id: "new-profile", revision: 8 };
    resumed.resolve(profile()); await pending;
    expect(s.profile?.id).toBe("new-profile"); expect(s.profile?.revision).toBe(8);
    expect(ask).not.toHaveBeenCalled(); expect(s.messages).toHaveLength(0);
  });
});

describe("AI 局部等待和跨页面兜底", () => {
  it("普通对话只由聊天气泡反馈，切走后才显示全局等待", async () => {
    const { raw, s } = session();
    vi.spyOn(raw, "initialize").mockResolvedValue(null);
    vi.spyOn(sessionModule, "createSession").mockReturnValue(raw);
    const requested = deferred<DiscoveryTurn>();
    vi.spyOn(api, "discoveryTurn").mockReturnValue(requested.promise);
    const root = mount(App, s); await settle();
    root.querySelectorAll<HTMLButtonElement>(".workflow-navigation button")[0]!.click(); await settle();
    const pending = s.turn("实际用户输入"); await settle();
    expect(root.querySelector(".global-request")).toBeNull();
    expect(root.querySelectorAll(".assistant-waiting")).toHaveLength(1);
    root.querySelectorAll<HTMLButtonElement>(".workflow-navigation button")[1]!.click(); await settle();
    expect(root.querySelectorAll(".global-request")).toHaveLength(1);
    requested.resolve({ ...question(), profile: profile() }); await pending; await settle();
    root.querySelectorAll<HTMLButtonElement>(".workflow-navigation button")[0]!.click(); await settle();
    expect(root.querySelector(".global-request")).toBeNull();
    expect(root.querySelector(".accepted-state")?.getAttribute("aria-label")).toContain("不表示送达或已读回执");
    expect([...root.querySelectorAll(".message-author")].every(item => item.classList.contains("sr-only"))).toBe(true);
  });
  it.each(["repository", "directions", "planning"] as const)("%s 只显示一处等待，离开后显示全局兜底且不重复请求", async kind => {
    const { raw, s } = session();
    s.profile = { ...profile(), revision: 2, status: "CONFIRMED" };
    s.assets = [asset()]; s.repositories = [repository()];
    if (kind === "planning") s.directions = [{ ...direction(), status: "SELECTED" }];
    vi.spyOn(raw, "initialize").mockResolvedValue(null);
    vi.spyOn(sessionModule, "createSession").mockReturnValue(raw);
    vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "C:/synthetic", existingRoots: [] });
    const analyzed = deferred<ReturnType<typeof repository>>();
    const discovered = deferred<{ directions: ReturnType<typeof direction>[] }>();
    const planned = deferred<ReturnType<typeof plan>>();
    const analyze = vi.spyOn(api, "analyze").mockReturnValue(analyzed.promise);
    const discover = vi.spyOn(api, "discover").mockReturnValue(discovered.promise);
    const generate = vi.spyOn(api, "generatePlan").mockReturnValue(planned.promise);
    const root = mount(App, s); await settle();
    const index = { repository: 1, directions: 2, planning: 3 }[kind];
    root.querySelectorAll<HTMLButtonElement>(".workflow-navigation button")[index]!.click(); await settle();
    let pending: Promise<void>;
    if (kind === "repository") pending = s.analyze("asset-1");
    else if (kind === "directions") pending = s.discover(["repo-1"]);
    else pending = s.generatePlan("repo-1");
    await settle();
    expect(root.querySelectorAll(".inline-busy")).toHaveLength(1);
    expect(root.querySelector(".inline-busy")?.getAttribute("role")).toBe("status");
    expect(root.querySelector(".global-request")).toBeNull();
    if (kind === "repository") await s.analyze("asset-1");
    else if (kind === "directions") await s.discover(["repo-1"]);
    else await s.generatePlan("repo-1");
    expect(analyze.mock.calls.length + discover.mock.calls.length + generate.mock.calls.length).toBe(1);
    root.querySelectorAll<HTMLButtonElement>(".workflow-navigation button")[0]!.click(); await settle();
    expect(root.querySelectorAll(".global-request")).toHaveLength(1);
    expect(root.querySelector(".inline-busy")).toBeNull();
    root.querySelectorAll<HTMLButtonElement>(".workflow-navigation button")[index]!.click(); await settle();
    expect(root.querySelectorAll(".inline-busy")).toHaveLength(1);
    expect(root.querySelector(".global-request")).toBeNull();
    if (kind === "repository") analyzed.resolve({ ...repository(), id: "new-analysis" });
    else if (kind === "directions") discovered.resolve({ directions: [direction()] });
    else planned.reject(new ApiError(0, "CONNECTION_LOST"));
    await pending; await settle();
    expect(s.aiActivity).toBeNull();
    expect(root.querySelector(".inline-busy")).toBeNull();
    expect(root.querySelector(".global-request")).toBeNull();
    expect(analyze.mock.calls.length + discover.mock.calls.length + generate.mock.calls.length).toBe(1);
    if (kind === "planning") expect(s.error).toBeTruthy();
  });
});

describe("准备入口、Step 前提和精简实施标题", () => {
  function planning() {
    vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "C:/synthetic", existingRoots: [] });
    const { s } = session(); s.plan = plan(); s.ids.planId = s.plan.id;
    s.plan.status = "PROPOSED"; s.plan.workingCopyId = null;
    s.plan.steps[0]!.status = "PENDING_CONFIRMATION";
    return s;
  }
  it("准备入口在概览之后、路线之前；审阅及确认后才准备，成功立即显示副本和授权入口", async () => {
    const s = planning();
    const active = { ...plan(), steps: [{ ...plan().steps[0]!, status: "PENDING_CONFIRMATION" as const }] };
    const prepare = vi.spyOn(api, "prepare").mockResolvedValue(active);
    vi.spyOn(api, "plan").mockResolvedValue(active);
    vi.spyOn(api, "workingCopy").mockResolvedValue(copy());
    const confirm = vi.spyOn(api, "confirmStep");
    const root = mount(PlanView, s); await settle();
    const overview = root.querySelector(".state-comparison")!.closest(".panel")!;
    const preparation = root.querySelector(".prepare-box")!, roadmap = root.querySelector(".roadmap")!;
    expect(overview.compareDocumentPosition(preparation) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(preparation.compareDocumentPosition(roadmap) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(button(root, "审阅并授权此 Step")!.disabled).toBe(true);
    expect(root.querySelector(".step-lock-reason")?.textContent).toContain("准备隔离副本");
    expect(button(root, "准备隔离副本")!.disabled).toBe(true);
    const review = root.querySelector<HTMLInputElement>(".prepare-box input")!;
    review.checked = true; review.dispatchEvent(new Event("change", { bubbles: true })); await settle();
    button(root, "准备隔离副本")!.click(); await settle();
    expect(prepare).not.toHaveBeenCalled(); expect(confirm).not.toHaveBeenCalled();
    button(root, "准备 WorkingCopy")!.click(); await settle();
    expect(prepare).toHaveBeenCalledExactlyOnceWith("plan-1");
    expect(s.plan?.status).toBe("ACTIVE"); expect(s.copy?.id).toBe("copy-1");
    expect(preparation.textContent).toContain(copy().location);
    expect(button(root, "准备隔离副本")).toBeUndefined();
    expect(button(root, "审阅并授权此 Step")!.disabled).toBe(false);
    expect(confirm).not.toHaveBeenCalled();
  });

  it.each(["PROPOSED", "SUPERSEDED", "missing", "untrusted", "binding", "earlier", "recovery"] as const)(
    "%s 的 Step 前提在规划及实施页一致，不能过早确认", async blocked => {
      const s = planning(); s.plan!.status = "ACTIVE"; s.plan!.workingCopyId = "copy-1"; s.copy = copy();
      if (blocked === "PROPOSED" || blocked === "SUPERSEDED") s.plan!.status = blocked;
      if (blocked === "missing") s.copy = null;
      if (blocked === "untrusted") s.copy!.currentRevision = "b".repeat(40);
      if (blocked === "binding") s.copy!.sourceAssetId = "other-asset";
      if (blocked === "earlier") s.plan!.steps.unshift({ ...s.plan!.steps[0]!, id: "earlier", status: "READY" });
      if (blocked === "recovery") s.plan!.steps.push({ ...s.plan!.steps[0]!, id: "unresolved", status: "RECOVERY_REQUIRED" });
      vi.spyOn(api, "agentRuns").mockResolvedValue([]);
      const confirm = vi.spyOn(api, "confirmStep");
      const start = vi.spyOn(api, "startAgent");
      const planningRoot = mount(PlanView, s);
      const evolutionRoot = mount(EvolutionView, s); await settle();
      if (blocked === "earlier") {
        const toggles = planningRoot.querySelectorAll<HTMLButtonElement>(".roadmap-toggle"); toggles[1]!.click();
        const select = evolutionRoot.querySelector<HTMLSelectElement>("#agent-step")!;
        select.value = "step-1"; select.dispatchEvent(new Event("change", { bubbles: true })); await settle();
      }
      expect(button(planningRoot, "审阅并授权此 Step")!.disabled).toBe(true);
      const reason = planningRoot.querySelector(".step-lock-reason")!.textContent!.replace("查看上方准备入口 ↑", "").trim();
      expect(evolutionRoot.querySelector(".step-lock-reason")?.textContent).toContain(reason);
      expect(button(evolutionRoot, "确认此步骤")).toBeUndefined();
      expect(confirm).not.toHaveBeenCalled(); expect(start).not.toHaveBeenCalled();
      if (blocked === "SUPERSEDED") expect(planningRoot.querySelector(".prepare-box")).toBeNull();
    });

  it("Step 选择器独立标识目标，正文保留状态、范围及改动，合法 Step 仍可明确确认", async () => {
    const s = planning(); s.plan!.status = "ACTIVE"; s.plan!.workingCopyId = "copy-1"; s.copy = copy();
    vi.spyOn(api, "agentRuns").mockResolvedValue([]);
    const confirm = vi.spyOn(s, "confirmStep").mockResolvedValue();
    const root = mount(EvolutionView, s); await settle();
    const goal = s.plan!.steps[0]!.goal;
    expect(root.querySelector("#agent-step")?.textContent).toContain(goal);
    expect([...root.querySelectorAll("h3")].some(heading => heading.textContent === goal)).toBe(false);
    expect(root.querySelector(".selected-step-summary")?.textContent).toContain(s.plan!.steps[0]!.scope);
    expect(root.textContent).toContain(s.plan!.steps[0]!.plannedChanges[0]);
    button(root, "确认此步骤")!.click(); await settle();
    expect(confirm).toHaveBeenCalledTimes(1);
  });

  it("READY Step 的开始按钮也核对可信副本和绑定，未通过时不发送 Agent 请求", async () => {
    const s = planning(); s.plan = plan(); s.copy = copy();
    s.copy.currentRevision = "b".repeat(40);
    vi.spyOn(api, "agentRuns").mockResolvedValue([]);
    const start = vi.spyOn(api, "startAgent");
    const root = mount(EvolutionView, s); await settle();
    expect(button(root, "开始 Agent")!.disabled).toBe(true);
    expect(root.querySelector(".step-lock-reason")?.textContent).toContain("最近验证版本不一致");
    button(root, "开始 Agent")!.click(); await settle(); expect(start).not.toHaveBeenCalled();
    s.copy.currentRevision = s.copy.lastVerifiedRevision; await settle();
    expect(button(root, "开始 Agent")!.disabled).toBe(false);
    s.copy.id = "wrong-copy"; await settle();
    expect(button(root, "开始 Agent")!.disabled).toBe(true);
    expect(root.querySelector(".step-lock-reason")?.textContent).toContain("绑定不一致");
    expect(start).not.toHaveBeenCalled();
  });
});
