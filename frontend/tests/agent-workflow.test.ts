import { createApp, h, nextTick, reactive } from "vue";
import { readFileSync } from "node:fs";
import { afterEach, expect, it, vi } from "vitest";
import EvolutionView from "../src/views/EvolutionView.vue";
import { api } from "../src/api/client";
import type { AgentCandidate, AgentRun, Delivery } from "../src/api/agentTypes";
import type { EvolutionPlan } from "../src/api/types";
import { createSession } from "../src/state/session";
import { copy, plan, repository } from "./fixtures";

class Events extends EventTarget {
  static instances: Events[] = [];
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;
  readonly url: string;
  constructor(url: string) { super(); this.url = url; Events.instances.push(this); }
  close() { this.closed = true; }
  fact(sequence: number, kind = "TOOL_FINISHED", summary = '{"tool":"read_file","path":"src/Counter.java","durationMs":12}') {
    this.dispatchEvent(new MessageEvent("activity", { data: JSON.stringify({ sequence, runId: "run-1", kind,
      summary, at: "2026-10-22T01:00:00Z" }) }));
  }
}
const running = (): AgentRun => ({ id: "run-1", planId: "plan-1", stepId: "step-1", workingCopyId: "copy-1",
  baseline: "a".repeat(40), state: "RUNNING", startedAt: "2026-10-22T01:00:00Z", endedAt: null, reason: null,
  candidateId: null, cancelRequested: false, retained: false, modelCalls: 2, toolCalls: 3, tokens: null });
const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); Events.instances = []; vi.restoreAllMocks(); vi.useRealTimers(); });
async function settle() { for (let i = 0; i < 16; i++) { await Promise.resolve(); await nextTick(); } }
async function acknowledgeBudget(root: Element) {
  const checkbox = root.querySelector<HTMLInputElement>(".budget-acknowledgment input")!;
  checkbox.checked = true;
  checkbox.dispatchEvent(new Event("change", { bubbles: true }));
  await settle();
  button(root, "确认费用并开始").click();
  await settle();
}
function mount(history: AgentRun[] = []) {
  vi.stubGlobal("EventSource", Events);
  vi.spyOn(api, "agentRuns").mockResolvedValue(history);
  vi.spyOn(api, "agentRun").mockResolvedValue(history[0] ?? running());
  const s = reactive(createSession()); s.plan = plan(); s.copy = copy(); s.ids.planId = "plan-1";
  const root = document.createElement("div"); document.body.append(root);
  const app = createApp({ render: () => h(EvolutionView, { s }) }); app.mount(root);
  cleanup.push(() => { app.unmount(); s.dispose(); root.remove(); });
  return { root, s, app };
}
async function expandExploration(root: Element) {
  const summary = root.querySelector<HTMLDetailsElement>(".exploration-summary")!;
  if (!summary.open) summary.querySelector<HTMLElement>("summary")!.click();
  await settle();
}
function button(root: Element, label: string) {
  return [...root.querySelectorAll("button")].find(item => item.textContent?.trim() === label)!;
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
function chooseStep(root: Element, id: string) {
  const selector = root.querySelector<HTMLSelectElement>("#agent-step")!;
  selector.value = id;
  selector.dispatchEvent(new Event("change", { bubbles: true }));
}
function chooseRun(root: Element, index: number) {
  root.querySelectorAll<HTMLButtonElement>(".text-button")[index]!.click();
}
function savedCandidate(id = "candidate-1", runId = "run-1"): AgentCandidate {
  return { id, runId, baseline: "a".repeat(40), expectedTree: "b".repeat(40), sha256: "f".repeat(64),
    changedPaths: [id + ".java"], diffBytes: 120, testEvidence: '{"status":"NOT_TESTED"}',
    createdAt: running().startedAt };
}

it("submits explicitly selected check directories only after the existing paid confirmation", async () => {
  const start = vi.spyOn(api, "startAgent").mockResolvedValue(running());
  const { root } = mount(); await settle();
  const directories = root.querySelector<HTMLInputElement>("#agent-check-directories")!;
  directories.value = "server, apps/web";
  directories.dispatchEvent(new Event("input", { bubbles: true }));
  button(root, "开始 Agent").click(); await settle();
  expect(start).not.toHaveBeenCalled();
  await acknowledgeBudget(root);
  expect(start).toHaveBeenCalledExactlyOnceWith("plan-1", "step-1", true, undefined, ["server", "apps/web"]);
});

it("shows actual preflight choices without treating the baseline as final verification", async () => {
  const preparing: AgentRun = { ...running(), state: "PREPARING", modelCalls: 0, toolCalls: 0, tokens: 0 };
  const { root } = mount([preparing]); await settle();
  Events.instances.at(-1)!.fact(1, "ENVIRONMENT_PREFLIGHT", "SELECTION_REQUIRED\n" + JSON.stringify({
    availableDirectories: ["server", "apps/web"], probes: [], reason: "Choose explicit directories",
  }));
  await settle();
  const preflight = root.querySelector(".preflight-summary")!;
  expect(preflight.textContent).toContain("本次未调用模型");
  expect(preflight.textContent).toContain("server");
  expect(preflight.textContent).toContain("apps/web");
  expect(preflight.textContent).toContain("不代表最终修改通过检查");
});

it("only an explicit start sends the request; real tool events are ordered and deduplicated", async () => {
  const start = vi.spyOn(api, "startAgent").mockResolvedValue(running());
  const { root } = mount(); await settle();
  expect(root.querySelector('.operations-workspace')?.getAttribute('data-task-state')).toBe('prepare');
  expect(root.querySelector('.agent-controls')!.compareDocumentPosition(root.querySelector('.agent-run-panel')!)
    & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(start).not.toHaveBeenCalled();
  button(root, "开始 Agent").click(); await settle();
  expect(root.querySelector("dialog")!.textContent).toContain("API 调用会产生费用");
  expect(button(root, "确认费用并开始").disabled).toBe(true);
  button(root, "确认费用并开始").dispatchEvent(new MouseEvent("click", { bubbles: true })); await settle();
  expect(start).not.toHaveBeenCalled();
  await acknowledgeBudget(root);
  expect(root.querySelector('.operations-workspace')?.getAttribute('data-task-state')).toBe('activity');
  expect(root.querySelector('.agent-run-panel')!.compareDocumentPosition(root.querySelector('.agent-controls')!)
    & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(start).toHaveBeenCalledExactlyOnceWith("plan-1", "step-1", true, undefined);
  const stream = Events.instances.at(-1)!;
  expect(stream.url).toContain("/agent-runs/run-1/stream?after=0");
  stream.fact(3); stream.fact(3); stream.fact(2); stream.fact(4); await settle();
  await expandExploration(root);
  expect(root.querySelectorAll(".exploration-operations li")).toHaveLength(2);
  expect(root.textContent).toContain("src/Counter.java");
  expect([...root.querySelectorAll(".activity-duration")].map(node => node.textContent)).toEqual(["内部 12 ms", "内部 12 ms"]);
  expect(root.querySelector(".agent-timeline pre")).toBeNull();
  expect(root.textContent).not.toMatch(/\d+%/);
});

it("closing or changing Step invalidates the current budget acknowledgment", async () => {
  const start = vi.spyOn(api, "startAgent");
  const { root, s } = mount(); await settle();
  button(root, "开始 Agent").click(); await settle();
  const checkbox = root.querySelector<HTMLInputElement>(".budget-acknowledgment input")!;
  checkbox.checked = true; checkbox.dispatchEvent(new Event("change", { bubbles: true })); await settle();
  button(root, "返回审阅").click(); await settle();
  button(root, "开始 Agent").click(); await settle();
  expect(checkbox.checked).toBe(false);
  checkbox.checked = true; checkbox.dispatchEvent(new Event("change", { bubbles: true })); await settle();
  s.plan!.steps.push({ ...s.plan!.steps[0]!, id: "another", status: "PENDING_CONFIRMATION" });
  await settle();
  chooseStep(root, "another"); await settle();
  expect(checkbox.checked).toBe(false);
  button(root, "确认费用并开始").dispatchEvent(new MouseEvent("click", { bubbles: true })); await settle();
  expect(start).not.toHaveBeenCalled();
});

it("correlated SSE activity updates in place and replay never duplicates a completed operation", async () => {
  const { root } = mount([running()]); await settle();
  const stream = Events.instances.at(-1)!;
  const metadata = { metadataVersion: 1, toolCallId: "9d52e412-e03d-4419-a2fb-56322848b598",
    tool: "read_file", path: "src/Counter.java" };
  stream.fact(1, "TOOL_STARTED", JSON.stringify(metadata)); await settle();
  await expandExploration(root);
  const summary = root.querySelector(".exploration-summary")!;
  expect(summary.querySelector(".exploration-count")?.textContent).toContain("1 次文件操作");
  const row = root.querySelector(".exploration-operations li")!;
  expect(row.querySelector('[role="status"]')?.textContent).toBe("进行中");
  const result = JSON.stringify({ ...metadata, ok: true, durationMs: 0, invocationDurationNs: 15000000, classification: "RESULT" });
  stream.fact(2, "TOOL_FINISHED", result); await settle();
  stream.onerror?.(); stream.onopen?.();
  stream.fact(1, "TOOL_STARTED", JSON.stringify(metadata)); stream.fact(2, "TOOL_FINISHED", result); await settle();
  expect(root.querySelectorAll(".exploration-operations li")).toHaveLength(1);
  expect(root.querySelector(".exploration-operations li")).toBe(row);
  expect(root.querySelector(".exploration-summary")).toBe(summary);
  expect(root.querySelector(".agent-timeline li")).toBeNull();
  expect(row.getAttribute("data-tone")).toBe("completed");
  expect(row.querySelector(".activity-duration")?.textContent).toBe("15 ms");
  expect(row.querySelector(".activity-target")?.textContent).toBe("src/Counter.java");
  expect(root.querySelector(".agent-usage-notice")).toBeNull();
  expect(row.textContent).not.toContain("RESULT");
});

it("persisted live and failed events override activity animation while the Run overview awaits synchronization", async () => {
  const { root } = mount([{ ...running(), state: "PREPARING" }]); await settle();
  const stream = Events.instances.at(-1)!;
  stream.fact(1, "MODEL_REQUEST", "request=1"); await settle();
  expect(root.querySelector(".run-status")?.textContent).toContain("准备沙箱");
  expect(root.querySelector(".model-waiting")).not.toBeNull();
  expect(root.querySelector(".snapshot-note")?.textContent).toContain("运行概览待同步");
  stream.fact(2, "FAILED", "POLICY_REJECTED"); await settle();
  expect(root.querySelector(".is-active, .model-waiting")).toBeNull();
  expect(root.querySelector(".current-detail")?.textContent).toContain("POLICY_REJECTED");
  expect(root.querySelector(".agent-changes")?.textContent).toContain("此 Run 尚无冻结 Candidate");
});

it("a saved Candidate is displayed with honest NOT_TESTED evidence and never starts coding again", async () => {
  const frozen = { ...running(), state: "SUCCEEDED" as const, candidateId: "candidate-1" };
  const start = vi.spyOn(api, "startAgent");
  vi.spyOn(api, "candidate").mockResolvedValue({ id: "candidate-1", runId: "run-1", baseline: frozen.baseline,
    expectedTree: "b".repeat(40), sha256: "f".repeat(64), changedPaths: ["src/Counter.java"], diffBytes: 120,
    testEvidence: '{"status":"NOT_TESTED"}', createdAt: frozen.startedAt });
  vi.spyOn(api, "delivery").mockResolvedValue({ candidateId: "candidate-1", state: "DELIVERY_BLOCKED",
    revision: null, reason: "AUTHORIZATION_CHANGED" });
  const { root } = mount([frozen]); await settle();
  expect(button(root, "重新开始编码").disabled).toBe(true);
  expect(root.querySelector(".evidence-status")?.textContent).toContain("未运行测试");
  expect(root.textContent).toContain("交付受阻");
  expect(button(root, "重新核对并交付已有结果")).toBeDefined();
  const activity = root.querySelector(".activity-section")!;
  const changes = root.querySelector(".agent-changes")!;
  expect(root.querySelectorAll(".agent-changes")).toHaveLength(1);
  expect(activity.compareDocumentPosition(changes) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(activity.compareDocumentPosition(root.querySelector(".run-statistics")!) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(start).not.toHaveBeenCalled();
});

it("disconnect and reconnect preserve visible events; changing pages closes the subscription", async () => {
  const { root, app } = mount([running()]); await settle();
  const stream = Events.instances.at(-1)!;
  stream.fact(1); await settle();
  stream.onerror?.(); await settle();
  expect(root.textContent).toContain("正在重连并补齐事件");
  stream.onopen?.(); stream.fact(1); stream.fact(2); await settle();
  await expandExploration(root);
  expect(root.querySelectorAll(".exploration-operations li")).toHaveLength(2);
  app.unmount();
  expect(stream.closed).toBe(true);
});

it("a successful reconnect clears a transient status read error", async () => {
  const { root } = mount([running()]);
  vi.mocked(api.agentRun).mockRejectedValueOnce(new Error("temporary offline"));
  await settle();
  expect(root.textContent).toContain("temporary offline");
  Events.instances.at(-1)!.onopen?.();
  await settle();
  expect(root.textContent).not.toContain("temporary offline");
});

it("cancels the actual running identity and does not automatically retry a failed Run", async () => {
  const cancel = vi.spyOn(api, "cancelAgent").mockResolvedValue({ ...running(), cancelRequested: true });
  const start = vi.spyOn(api, "startAgent");
  const { root } = mount([running()]); await settle();
  button(root, "停止运行").click(); await settle();
  expect(cancel).toHaveBeenCalledExactlyOnceWith("run-1");
  expect(button(root, "正在停止").disabled).toBe(true);
  expect(start).not.toHaveBeenCalled();
});

it("keeps one persistent stop control targeting the active Run while browsing historical activity", async () => {
  const response=deferred<AgentRun>();
  const historical:AgentRun={...running(),id:'old',state:'FAILED',reason:'historical-failure'};
  const cancel=vi.spyOn(api,'cancelAgent').mockReturnValue(response.promise);
  const {root}=mount([running(),historical]); await settle();
  vi.mocked(api.agentRun).mockImplementation(async id=>id==='old'?historical:running());
  chooseRun(root,1); await settle();
  expect(root.querySelectorAll('.monitor-stop button')).toHaveLength(1);
  expect(root.querySelector('.monitor-stop')?.closest('.run-summary')).toBeNull();
  expect(root.querySelector('.monitor-stop')?.textContent).toContain('正在查看历史记录');
  const stop=button(root,'停止运行'); stop.click(); stop.click(); await settle();
  expect(cancel).toHaveBeenCalledExactlyOnceWith('run-1');
  expect(stop.disabled).toBe(true);
  expect(root.querySelector('.monitor-stop')?.textContent).toContain('停止请求发送中');
  response.resolve({...running(),cancelRequested:true}); await settle();
  expect(button(root,'正在停止').disabled).toBe(true);
  expect(root.textContent).toContain('historical-failure');
  expect(Events.instances.at(-1)?.url).toContain('old');
});

it("accepted cancellation stays pending through a stale poll and disappears only after an actual terminal state", async () => {
  vi.useFakeTimers();
  vi.spyOn(api,'cancelAgent').mockResolvedValue({...running(),cancelRequested:true});
  const {root}=mount([running()]); await settle();
  button(root,'停止运行').click(); await settle();
  expect(root.querySelector('.monitor-stop')?.textContent).toContain('停止请求已接受');
  Events.instances.at(-1)!.fact(1,'MODEL_REQUEST','request=3'); await vi.advanceTimersByTimeAsync(200); await settle();
  expect(button(root,'正在停止').disabled).toBe(true);
  expect(root.querySelector('.activity-bar,.model-waiting')).toBeNull();
  vi.mocked(api.agentRun).mockResolvedValue({...running(),state:'CANCELLED',cancelRequested:true});
  Events.instances.at(-1)!.fact(2,'CANCELLED'); await vi.advanceTimersByTimeAsync(200); await settle();
  expect(root.querySelector('.monitor-stop')).toBeNull();
  expect(root.querySelector('.run-status')?.textContent).toContain('已取消');
  expect(root.querySelector('.activity-bar,.is-active,.model-waiting')).toBeNull();
});

it("a poll started before cancellation cannot restore a terminal Run to running", async () => {
  const poll=deferred<AgentRun>();
  vi.spyOn(api,'cancelAgent').mockResolvedValue({...running(),state:'CANCELLED',cancelRequested:true});
  const {root}=mount([running()]); await settle();
  vi.mocked(api.agentRun).mockReturnValueOnce(poll.promise);
  Events.instances.at(-1)!.onopen?.(); await settle();
  button(root,'停止运行').click(); await settle();
  expect(root.querySelector('.monitor-stop')).toBeNull();
  poll.resolve(running()); await settle();
  expect(root.querySelector('.monitor-stop')).toBeNull();
  expect(root.querySelector('.run-status')?.textContent).toContain('已取消');
});

it("refresh reloads saved Run history and the event stream without issuing a business POST", async () => {
  const start = vi.spyOn(api, "startAgent");
  const { root } = mount([{ ...running(), state: "INTERRUPTED", reason: "PROCESS_RESTART" }]); await settle();
  expect(root.textContent).toContain("运行已中断");
  expect(root.textContent).toContain("PROCESS_RESTART");
  button(root, "刷新记录").click(); await settle();
  expect(Events.instances[0]?.closed).toBe(true);
  expect(api.agentRuns).toHaveBeenCalledTimes(2);
  expect(start).not.toHaveBeenCalled();
});

it("ignores a late Start after a Step switch and reloads its persisted Run on return", async () => {
  const response = deferred<AgentRun>();
  const start = vi.spyOn(api, "startAgent").mockReturnValue(response.promise);
  const { root, s } = mount(); await settle();
  s.plan!.steps.push({ ...s.plan!.steps[0]!, id: "step-2", goal: "second selected step", status: "PENDING_CONFIRMATION" });
  button(root, "开始 Agent").click(); button(root, "开始 Agent").click(); await settle();
  await acknowledgeBudget(root);
  chooseStep(root, "step-2"); await settle();
  response.resolve(running()); await settle();
  expect(root.querySelector<HTMLSelectElement>("#agent-step")!.value).toBe("step-2");
  expect(root.textContent).not.toContain("编码中");
  expect(Events.instances).toHaveLength(0);
  expect(start).toHaveBeenCalledExactlyOnceWith("plan-1", "step-1", true, undefined);
  expect(button(root, "确认此步骤")).toBeUndefined();
  expect(root.querySelector(".step-lock-reason")?.textContent).toContain("前序 Step");
  vi.mocked(api.agentRuns).mockResolvedValue([running()]);
  chooseStep(root, "step-1"); await settle();
  expect(root.textContent).toContain("编码中");
  expect(Events.instances.at(-1)?.url).toContain("run-1");
  expect(start).toHaveBeenCalledTimes(1);
});

it("a late Cancel cannot replace another Run or its active subscription", async () => {
  const response = deferred<AgentRun>();
  const cancel = vi.spyOn(api, "cancelAgent").mockReturnValue(response.promise);
  const second: AgentRun = { ...running(), id: "run-2", state: "FAILED", reason: "selected-run-two" };
  const { root } = mount([running(), second]); await settle();
  vi.mocked(api.agentRun).mockImplementation(async id => id === second.id ? second : running());
  button(root, "停止运行").click(); await settle();
  chooseRun(root, 1); await settle();
  const currentStream = Events.instances.at(-1)!;
  response.resolve({ ...running(), cancelRequested: true }); await settle();
  expect(root.textContent).toContain("selected-run-two");
  expect(root.querySelectorAll(".text-button")[1]!.getAttribute("aria-pressed")).toBe("true");
  expect(currentStream.url).toContain("run-2");
  expect(Events.instances.at(-1)).toBe(currentStream);
  expect(cancel).toHaveBeenCalledExactlyOnceWith("run-1");
});

it("a stale action error cannot clear the new selection's pending action or error", async () => {
  const old = deferred<AgentRun>();
  const fresh = deferred<AgentRun>();
  const second: AgentRun = { ...running(), id: "run-2" };
  vi.spyOn(api, "cancelAgent").mockReturnValueOnce(old.promise).mockReturnValueOnce(fresh.promise);
  const { root } = mount([running(), second]); await settle();
  vi.mocked(api.agentRun).mockImplementation(async id => id === second.id ? second : running());
  button(root, "停止运行").click(); await settle();
  chooseRun(root, 1); await settle();
  expect(button(root, "停止运行").disabled).toBe(false);
  button(root, "停止运行").click(); await settle();
  old.reject(new Error("stale failure")); await settle();
  expect(root.textContent).not.toContain("stale failure");
  expect(button(root, "正在停止").disabled).toBe(true);
  fresh.reject(new Error("current failure")); await settle();
  expect(root.textContent).toContain("current failure");
  expect(button(root, "停止运行").disabled).toBe(false);
});

it("a late Delivery cannot replace the selected Candidate or its Delivery", async () => {
  const response = deferred<Delivery>();
  const first: AgentRun = { ...running(), state: "SUCCEEDED", candidateId: "candidate-1" };
  const second: AgentRun = { ...first, id: "run-2", candidateId: "candidate-2" };
  vi.spyOn(api, "candidate").mockImplementation(async id => savedCandidate(id, id === "candidate-1" ? "run-1" : "run-2"));
  vi.spyOn(api, "delivery").mockImplementation(async candidateId => ({ candidateId, state: "DELIVERY_BLOCKED",
    revision: null, reason: candidateId + " blocked" }));
  const deliver = vi.spyOn(api, "deliver").mockReturnValue(response.promise);
  const { root } = mount([first, second]); await settle();
  vi.mocked(api.agentRun).mockImplementation(async id => id === second.id ? second : first);
  button(root, "重新核对并交付已有结果").click(); await settle();
  chooseRun(root, 1); await settle();
  response.resolve({ candidateId: "candidate-1", state: "COMPLETED", revision: "c".repeat(40), reason: null });
  await settle();
  expect(root.querySelector(".run-status")?.textContent).toBe("交付受阻");
  expect(root.querySelector(".final-file-list")?.textContent).toContain("candidate-2.java");
  expect(root.querySelector(".agent-changes .resource-warning")?.textContent).toContain("正式交付受阻");
  expect(button(root, "重新核对并交付已有结果").disabled).toBe(false);
  expect(root.textContent).toContain("candidate-2.java");
  expect(root.textContent).not.toContain("Delivery：交付完成");
  expect(button(root, "重新核对并交付已有结果").disabled).toBe(false);
  expect(deliver).toHaveBeenCalledExactlyOnceWith("candidate-1");
});

it("current Delivery responses and errors remain visible and release pending controls", async () => {
  const first: AgentRun = { ...running(), state: "SUCCEEDED", candidateId: "candidate-1" };
  vi.spyOn(api, "candidate").mockResolvedValue(savedCandidate());
  vi.spyOn(api, "delivery").mockResolvedValue(null);
  const response = deferred<Delivery>();
  const deliver = vi.spyOn(api, "deliver").mockReturnValueOnce(response.promise)
    .mockRejectedValueOnce(new Error("delivery unavailable"));
  const { root } = mount([first]); await settle();
  button(root, "重新核对并交付已有结果").click(); await settle();
  expect(button(root, "重新核对并交付已有结果").disabled).toBe(true);
  response.resolve({ candidateId: "candidate-1", state: "DELIVERY_BLOCKED", revision: null, reason: "exact conflict" });
  await settle();
  expect(root.querySelector(".run-status")?.textContent).toBe("交付受阻");
  expect(root.querySelector(".agent-changes .resource-warning")?.textContent).toContain("正式交付受阻");
  expect(button(root, "重新核对并交付已有结果").disabled).toBe(false);
  button(root, "重新核对并交付已有结果").click(); await settle();
  expect(root.textContent).toContain("delivery unavailable");
  expect(button(root, "重新核对并交付已有结果").disabled).toBe(false);
  expect(deliver).toHaveBeenCalledTimes(2);
});

it("background Candidate arrival invalidates an old Cancel result without stranding pending controls", async () => {
  const response = deferred<AgentRun>();
  vi.spyOn(api, "cancelAgent").mockReturnValue(response.promise);
  vi.spyOn(api, "candidate").mockResolvedValue(savedCandidate());
  vi.spyOn(api, "delivery").mockResolvedValue(null);
  const { root } = mount([running()]); await settle();
  button(root, "停止运行").click(); await settle();
  vi.mocked(api.agentRun).mockResolvedValue({ ...running(), state: "SUCCEEDED", candidateId: "candidate-1" });
  Events.instances.at(-1)!.onopen?.(); await settle();
  expect(root.textContent).toContain("候选已冻结，等待交付");
  response.resolve({ ...running(), cancelRequested: true }); await settle();
  expect(root.textContent).toContain("候选已冻结，等待交付");
  expect(button(root, "重新核对并交付已有结果").disabled).toBe(false);
});

it.each(["MUTATION_NOT_STARTED", "DELIVERY_FAILURE", "Official Git/index/content cannot be proven; preserved for inspection"])("offers an explicit server-guarded recheck without starting another Run (%s)", async reason => {
  const frozen: AgentRun = { ...running(), state: "SUCCEEDED", candidateId: "candidate-1" };
  vi.spyOn(api, "candidate").mockResolvedValue(savedCandidate());
  vi.spyOn(api, "delivery").mockResolvedValue({ candidateId: "candidate-1", state: "RECOVERY_REQUIRED", revision: null, reason });
  const { root } = mount([frozen]); await settle();
  const deliver = vi.spyOn(api, "deliver").mockResolvedValue({ candidateId: "candidate-1", state: "RECOVERY_REQUIRED", revision: null, reason });
  const start = vi.spyOn(api, "startAgent");
  expect(root.textContent).toContain("只有版本、文件内容和交付历史均通过安全检查才会继续");
  button(root, "重新核对并交付已有结果").click(); await settle();
  expect(deliver).toHaveBeenCalledExactlyOnceWith("candidate-1");
  expect(start).not.toHaveBeenCalled();
  expect(root.querySelector(".agent-changes .resource-warning")?.textContent).toContain("后续执行已停止");
});

it("ignores late file classification after switching to another Candidate", async () => {
  const body = deferred<string>();
  const secondDiff = "diff --git a/candidate-2.java b/candidate-2.java\nnew file mode 100644\n--- /dev/null\n+++ b/candidate-2.java\n@@ -0,0 +1 @@\n+new\n";
  const fetchDiff = vi.fn().mockResolvedValueOnce({ ok: true, text: () => body.promise })
    .mockResolvedValueOnce({ ok: true, text: async () => secondDiff });
  vi.stubGlobal("fetch", fetchDiff);
  const first: AgentRun = { ...running(), state: "SUCCEEDED", candidateId: "candidate-1" };
  const second: AgentRun = { ...running(), id: "run-2", state: "SUCCEEDED", candidateId: "candidate-2" };
  vi.spyOn(api, "candidate").mockImplementation(async id => savedCandidate(id, id === "candidate-2" ? "run-2" : "run-1"));
  vi.spyOn(api, "delivery").mockResolvedValue(null);
  const { root } = mount([first, second]); await settle();
  vi.mocked(api.agentRun).mockImplementation(async id => id === second.id ? second : first);
  chooseRun(root, 1); await settle();
  expect(root.querySelector(".file-change-type")?.getAttribute("data-type")).toBe("ADDED");
  body.resolve("diff --git a/candidate-1.java b/candidate-1.java\ndeleted file mode 100644\n--- a/candidate-1.java\n+++ /dev/null\n");
  await settle();
  expect(root.querySelector(".final-file-list")?.textContent).toContain("candidate-2.java");
  expect(root.querySelector(".final-file-list")?.textContent).not.toContain("candidate-1.java");
  expect(root.querySelector(".file-change-type")?.getAttribute("data-type")).toBe("ADDED");
  expect(fetchDiff).toHaveBeenCalledTimes(2);
  expect(root.querySelector(".final-diff, .candidate-technical, .agent-changes pre")).toBeNull();
  expect(button(root, "刷新记录").disabled).toBe(false);
});

it.each([false, true])("confirmation does not adopt a response or error after selection changes (failure=%s)", async failure => {
  const response = deferred<EvolutionPlan>();
  vi.spyOn(api, "confirmStep").mockReturnValue(response.promise);
  const refresh = vi.spyOn(api, "plan");
  const { root, s } = mount(); await settle();
  s.plan!.steps[0]!.status = "PENDING_CONFIRMATION";
  s.plan!.steps.push({ ...s.plan!.steps[0]!, id: "step-2", goal: "second step" });
  await settle();
  button(root, "确认此步骤").click(); await settle();
  chooseStep(root, "step-2"); await settle();
  if (failure) response.reject(new Error("stale confirmation"));
  else response.resolve({ ...plan(), id: "stale-plan" });
  await settle();
  expect(s.plan!.id).toBe("plan-1");
  expect(s.error).toBe("");
  expect(root.querySelector<HTMLSelectElement>("#agent-step")!.value).toBe("step-2");
  expect(button(root, "确认此步骤")).toBeUndefined();
  expect(root.querySelector(".step-lock-reason")?.textContent).toContain("前序 Step");
  expect(refresh).not.toHaveBeenCalled();
});

it("separates Step configuration, expandable changes, JDK selection and explicit action", async () => {
  const response = deferred<AgentRun>();
  const start = vi.spyOn(api, "startAgent").mockReturnValue(response.promise);
  const { root, s } = mount(); await settle();
  expect(root.querySelector(".step-configuration #agent-step")).not.toBeNull();
  expect(root.querySelector<HTMLDetailsElement>(".planned-changes")!.open).toBe(false);
  expect(root.querySelector(".planned-changes")!.textContent).toContain(s.plan!.steps[0]!.plannedChanges[0]);
  expect(root.querySelector(".jdk-field #agent-jdk")).not.toBeNull();
  expect(root.querySelector(".start-action button")!.textContent).toBe("开始 Agent");
  expect(root.querySelector(".selected-step-summary")!.textContent).not.toContain(s.plan!.steps[0]!.goal);
  const jdk = root.querySelector<HTMLSelectElement>("#agent-jdk")!;
  jdk.value = "21"; jdk.dispatchEvent(new Event("change", { bubbles: true })); await settle();
  button(root, "开始 Agent").click(); button(root, "开始 Agent").click(); await settle();
  await acknowledgeBudget(root);
  expect(start).toHaveBeenCalledExactlyOnceWith("plan-1", "step-1", true, 21);
  expect(button(root, "开始 Agent").disabled).toBe(true);
  response.resolve(running()); await settle();
  expect(button(root, "重新开始编码").disabled).toBe(true);
});

it("explains failed Docker preparation and resource reconciliation without inventing a Candidate", async () => {
  const failed: AgentRun = { ...running(), state: "FAILED", reason: "DOCKER_FAILURE", modelCalls: 0, toolCalls: 0 };
  const start = vi.spyOn(api, "startAgent");
  const candidate = vi.spyOn(api, "candidate");
  const final = vi.spyOn(api, "finalChanges");
  const { root } = mount([failed]); await settle();
  const stream = Events.instances.at(-1)!;
  stream.fact(1, "PREPARING", "PREPARING");
  stream.fact(2, "FAILED", "DOCKER_FAILURE");
  stream.fact(3, "CLEANUP_BLOCKED", "Owned resources require reconciliation: run-1"); await settle();
  expect(root.textContent).toContain("Docker 沙箱准备失败，尚未开始编码");
  expect(root.querySelector(".resource-warning[role='alert']")?.textContent).toContain("资源协调待核对");
  expect(root.querySelector(".resource-warning")?.closest("details")).toBeNull();
  expect(root.querySelector(".run-metadata")).toBeNull();
  expect(root.querySelector(".run-reason code")?.textContent).toBe("DOCKER_FAILURE");
  expect(root.querySelector(".run-statistics")?.textContent).toContain("0");
  expect(root.querySelector(".run-status[role='status']")?.textContent).toContain("本次运行失败");
  expect(root.querySelector(".agent-changes")?.textContent).toContain("此 Run 尚无冻结 Candidate");
  expect(root.querySelector('[data-sequence="3"]')?.getAttribute("data-tone")).toBe("blocked");
  expect(root.querySelectorAll(".is-active")).toHaveLength(0);
  expect(start).not.toHaveBeenCalled(); expect(candidate).not.toHaveBeenCalled(); expect(final).not.toHaveBeenCalled();
  expect(button(root, "重新开始编码").disabled).toBe(false);
  vi.spyOn(api, "startAgent").mockResolvedValue({ ...running(), id: "new-run" });
  button(root, "重新开始编码").click(); await settle();
  expect(root.querySelector<HTMLInputElement>(".budget-acknowledgment input")!.checked).toBe(false);
  await acknowledgeBudget(root);
  expect(start).toHaveBeenCalledExactlyOnceWith("plan-1", "step-1", true, undefined);
});

it("unifies Run status, failure and times without duplicating diagnostics or exposing technical identities", async () => {
  const failed: AgentRun = { ...running(), id: "run-with-a-long-identity-".repeat(8), state: "FAILED",
    reason: "DOCKER_FAILURE", modelCalls: 0, toolCalls: 0, baseline: "0123456789abcdef".repeat(4),
    endedAt: "2026-10-22T01:02:03Z" };
  const { root } = mount([failed]); await settle();
  const overview = root.querySelector(".developer-details")!;
  const value = (label: string) => [...overview.querySelectorAll("dt")]
    .find(node => node.textContent === label)?.nextElementSibling;
  expect(root.querySelectorAll(".run-status")).toHaveLength(1);
  expect(overview.querySelector(".run-reason code")?.textContent).toBe("DOCKER_FAILURE");
  expect(value("开始时间")?.tagName).toBe("DD");
  expect(value("开始时间")?.querySelector("time")?.getAttribute("datetime")).toBe(failed.startedAt);
  expect(value("结束时间")?.querySelector("time")?.getAttribute("datetime")).toBe(failed.endedAt);
  expect(value("开始时间")?.textContent).toBe(new Date(failed.startedAt).toLocaleString());
  expect(root.querySelector(".run-metadata, #run-technical-title")).toBeNull();
  expect((overview as HTMLDetailsElement).open).toBe(false);
  expect(overview.querySelector(".run-technical")?.textContent).toContain(failed.id);
  expect(root.querySelector(".run-status")?.textContent).not.toContain(failed.id);
  expect(root.textContent).not.toContain(failed.baseline);
  expect(api.agentRun).toHaveBeenCalledWith(failed.id);
  expect(failed.baseline).toBe("0123456789abcdef".repeat(4));
  expect(root.querySelector(".run-failure")?.closest("details")).toBeNull();
});

it("missing diagnostic values remain honest and do not imply that a running Run has ended", async () => {
  const { root } = mount([running()]); await settle();
  const fields = root.querySelector(".run-times")!;
  expect([...fields.querySelectorAll("dt")].map(node => node.textContent))
    .toEqual(["开始时间", "结束时间"]);
  expect(fields.querySelectorAll("dd")[1]?.textContent).toBe("未记录");
  expect(root.querySelector(".run-reason")).toBeNull();
  expect(fields.querySelectorAll("time")).toHaveLength(1);
  expect(root.querySelector(".run-status")?.textContent).toContain("编码中");
});

it.each(["CREATED", "PREPARING", "RUNNING", "FINALIZING", "FAILED", "CANCELLED", "INTERRUPTED", "SUCCEEDED"] as const)
  ("uses real Run state in the status summary without implying Delivery acceptance (%s)", async state => {
    const actual: AgentRun = { ...running(), state, candidateId: state === "SUCCEEDED" ? "candidate-1" : null };
    vi.spyOn(api, "candidate").mockResolvedValue(savedCandidate());
    vi.spyOn(api, "delivery").mockResolvedValue(null);
    const start = vi.spyOn(api, "startAgent");
    const { root } = mount([actual]); await settle();
    const summary = root.querySelector(".run-summary")!;
    expect(summary.getAttribute("data-state")).toBe(state);
    expect(summary.querySelector('[role="status"]')?.getAttribute("data-state")).toBe(state);
    expect(summary.querySelector('.navigation-icon[aria-hidden="true"]')).not.toBeNull();
    expect(summary.textContent).not.toContain("交付完成");
    if (state === "SUCCEEDED") expect(summary.textContent).toContain("等待正式交付确认");
    if (["FAILED", "CANCELLED", "INTERRUPTED"].includes(state)) expect(summary.textContent).toContain("本次编码未交付");
    expect(start).not.toHaveBeenCalled();
  });

it.each([null, 0, 1234567])("separates actual usage statistics and honestly formats reported tokens (%s)", async tokens => {
  const { root } = mount([{ ...running(), tokens }]); await settle();
  const statistics = root.querySelector('.run-statistics[aria-label="已保存运行统计"]')!;
  expect([...statistics.querySelectorAll("dt")].map(node => node.textContent)).toEqual(["模型调用", "工具调用", "累计 tokens"]);
  expect([...statistics.querySelectorAll("dd")].map(node => node.textContent?.trim())).toEqual([
    "2 次", "3 次", tokens === null ? "未报告" : tokens.toLocaleString("zh-CN"),
  ]);
  expect(statistics.textContent).not.toContain("%");
});

it("updates reported tokens only from the existing SSE-triggered status read", async () => {
  vi.useFakeTimers();
  const { root } = mount([{ ...running(), tokens: 1200 }]); await settle();
  const tokens = () => root.querySelectorAll(".run-statistics dd")[2]!.textContent;
  expect(tokens()).toBe("1,200");
  vi.mocked(api.agentRun).mockResolvedValue({ ...running(), tokens: 15234 });
  Events.instances.at(-1)!.fact(1, "MODEL_COMPLETED"); await settle();
  expect(tokens()).toBe("1,200");
  await vi.advanceTimersByTimeAsync(200); await settle();
  expect(tokens()).toBe("15,234");
  vi.mocked(api.agentRun).mockResolvedValue({ ...running(), tokens: null });
  Events.instances.at(-1)!.fact(2, "MODEL_REQUEST");
  await vi.advanceTimersByTimeAsync(200); await settle();
  expect(tokens()).toBe("未报告");
});

it("expands the complete planned changes in order without a nested heading or modifying the Step", async () => {
  const { root, s } = mount(); await settle();
  const changes = ["保留现有实现，补充完整的中文配置说明。".repeat(20), "增加测试\n保留多行内容", "更新文档"];
  s.plan!.steps[0]!.plannedChanges = changes; await settle();
  const disclosure = root.querySelector<HTMLDetailsElement>(".planned-changes")!;
  expect(disclosure.open).toBe(false);
  expect(disclosure.querySelector("summary")?.textContent).toContain("计划变更 · 3 项");
  expect(disclosure.querySelectorAll("h3, h4, .content-list")).toHaveLength(0);
  disclosure.querySelector("summary")!.click(); await settle();
  expect(disclosure.open).toBe(true);
  expect([...disclosure.querySelectorAll("li")].map(node => node.textContent)).toEqual(changes);
  expect(s.plan!.steps[0]!.plannedChanges).toEqual(changes);
});

it("shows active persisted activity independently of SSE connectivity and keeps completed tools static", async () => {
  const { root } = mount([running()]); await settle();
  const stream = Events.instances.at(-1)!;
  stream.fact(1, "MODEL_REQUEST", "request=1"); await settle();
  expect(root.querySelector('.current-activity[data-active="true"]')?.textContent).toContain("Agent 正在规划下一步");
  stream.onerror?.(); await settle();
  expect(root.querySelector(".run-status")?.textContent).toContain("编码中");
  expect(root.querySelector(".stream-status")?.textContent).toContain("正在重连");
  stream.fact(2, "TOOL_FINISHED", '{"tool":"run_checks","ok":false,"classification":"BUILD_FAILED","durationMs":12}'); await settle();
  expect(root.querySelectorAll(".is-active")).toHaveLength(0);
  expect(root.querySelector<HTMLDetailsElement>(".check-summary")!.open).toBe(false);
  root.querySelector<HTMLElement>(".check-summary summary")!.click(); await settle();
  expect(root.querySelector('[data-sequence="2"]')?.getAttribute("data-tone")).toBe("failed");
  expect(root.querySelector('[data-sequence="2"] strong')?.textContent).toContain("运行 Maven 检查");
  expect(root.querySelector('[data-sequence="2"] .activity-result code')?.textContent).toBe("BUILD_FAILED");
  expect(root.querySelector('[data-sequence="2"] strong')?.textContent).not.toContain("durationMs");
});

it("does not describe an unreadable saved Candidate as absent", async () => {
  const frozen: AgentRun = { ...running(), state: "SUCCEEDED", candidateId: "candidate-1" };
  vi.spyOn(api, "candidate").mockRejectedValue(new Error("candidate unavailable"));
  vi.spyOn(api, "delivery").mockResolvedValue(null);
  const { root } = mount([frozen]); await settle();
  expect(root.querySelector(".agent-changes")?.textContent).toContain("已记录冻结 Candidate，但详情尚未读回");
  expect(root.querySelector(".agent-changes")?.textContent).not.toContain("尚无冻结 Candidate");
  expect(root.querySelector('[role="alert"]')?.textContent).toContain("candidate unavailable");
});

it("keeps the latest 1000 persisted events ordered and deduplicated across replay", async () => {
  const { root } = mount([running()]); await settle();
  const stream = Events.instances.at(-1)!;
  for (let i = 1; i <= 1002; i++) stream.fact(i);
  stream.onerror?.(); stream.onopen?.(); stream.fact(1001); stream.fact(1002); await settle();
  expect(root.querySelector(".exploration-count")?.textContent).toContain("1000 次文件操作");
  await expandExploration(root);
  expect(root.querySelectorAll(".exploration-operations li")).toHaveLength(40);
  expect(root.querySelector(".exploration-operations li")?.getAttribute("data-sequence")).toBe("3");
  while (root.querySelector(".more-exploration")) {
    root.querySelector<HTMLButtonElement>(".more-exploration")!.click(); await settle();
  }
  const rows = root.querySelectorAll(".exploration-operations li");
  expect(rows).toHaveLength(1000);
  expect(rows[0]?.getAttribute("data-sequence")).toBe("3");
  expect(rows[999]?.getAttribute("data-sequence")).toBe("1002");
  expect(root.textContent).toContain("已加载最近 1000 条事件");
});

it.each(["AVAILABLE", "UNAVAILABLE"] as const)("completed Delivery retains read-only cumulative final changes (%s)", async availability => {
  const fetchDiff = vi.fn();
  vi.stubGlobal("fetch", fetchDiff);
  const frozen: AgentRun = { ...running(), state: "SUCCEEDED", candidateId: "candidate-1" };
  vi.spyOn(api, "candidate").mockResolvedValue(savedCandidate());
  vi.spyOn(api, "delivery").mockResolvedValue({ candidateId: "candidate-1", state: "COMPLETED", revision: "b".repeat(40), reason: null });
  const final = vi.spyOn(api, "finalChanges").mockResolvedValue({ planId: "plan-1", stepId: "step-1",
    baselineRevision: "a".repeat(40), verifiedRevision: "b".repeat(40), baselineTree: "tree-a", verifiedTree: "tree-b",
    availability, reason: availability === "UNAVAILABLE" ? "HISTORICAL_OBJECT_MISSING" : null,
    files: availability === "AVAILABLE" ? [{ path: "cumulative.java", changeType: "MODIFIED" }] : null,
    counts: availability === "AVAILABLE" ? { added: 0, modified: 1, deleted: 0 } : null });
  const { root, s } = mount([frozen]);
  s.plan!.steps[0] = { ...s.plan!.steps[0]!, status: "SUCCEEDED", baselineRevision: "a".repeat(40), deliveryRevision: "b".repeat(40) };
  await settle();
  expect(root.querySelector(".candidate-overview .file-change-summary")).toBeNull();
  expect(root.querySelector(".final-file-list")?.textContent ?? "").not.toContain("candidate-1.java");
  expect(root.querySelector(".evidence-status")?.textContent).toContain("未运行测试");
  expect(root.querySelector<HTMLDetailsElement>(".agent-changes .result-developer")?.open).toBe(false);
  expect(root.querySelector(".agent-changes pre")).toBeNull();
  expect(root.querySelector(".agent-changes")?.textContent).not.toContain("NOT_TESTED");
  expect(root.querySelector<HTMLDetailsElement>(".agent-changes .result-developer")?.open).toBe(false);
  expect(root.querySelector(".agent-changes")?.textContent).not.toContain("查看最终差异");
  expect(root.querySelector("#step-final-changes")?.textContent).toContain(availability === "AVAILABLE" ? "cumulative.java" : "历史 Git 对象已不可用");
  expect(root.textContent).toContain("已正式接受的完整步骤变更");
  expect(final).toHaveBeenCalledExactlyOnceWith("plan-1", "step-1");
  expect(fetchDiff).not.toHaveBeenCalled();
  expect(button(root, "重新核对并交付已有结果")).toBeUndefined();
});


it("an incompatible sandbox shows actionable rollout guidance without starting or delivering", async () => {
  const start = vi.spyOn(api, "startAgent");
  const deliver = vi.spyOn(api, "deliver");
  const failed: AgentRun = { ...running(), state: "FAILED", reason: "SANDBOX_PROTOCOL_INCOMPATIBLE",
    modelCalls: 0, toolCalls: 0 };
  const { root } = mount([failed]); await settle();
  expect(root.querySelector('[role="alert"]')?.textContent).toContain("沙箱工具协议不兼容");
  expect(root.textContent).toContain("显式配置镜像后再重启应用");
  expect(root.textContent).toContain("docs/LOCAL_SETUP.md");
  expect(root.querySelector(".run-reason code")?.textContent).toBe("SANDBOX_PROTOCOL_INCOMPATIBLE");
  expect(start).not.toHaveBeenCalled();
  expect(deliver).not.toHaveBeenCalled();
  expect(root.querySelector(".candidate-evidence")).toBeNull();
});


it("stop control has a dedicated red icon treatment and an immediate disabled pending state", async () => {
  const response = deferred<AgentRun>();
  const cancel = vi.spyOn(api, "cancelAgent").mockReturnValue(response.promise);
  const { root } = mount([running()]); await settle();
  const stop = button(root, "停止运行");
  expect(stop.classList.contains("stop-run")).toBe(true);
  expect(stop.querySelector("svg path")?.getAttribute("d")).toBe("M6 6h12v12H6z");
  const view = readFileSync("src/views/EvolutionView.vue", "utf8");
  expect(view).toMatch(/\.stop-run[^{}]*\{[^}]*color:\s*var\(--danger\)/);
  expect(view).toMatch(/\.stop-run:focus-visible[^{}]*\{[^}]*outline:\s*2px solid var\(--danger\)/);
  stop.click(); await settle();
  expect(button(root, "正在停止")).toBe(stop);
  expect(stop.disabled).toBe(true);
  expect(root.querySelector(".monitor-stop")?.textContent).toContain("停止请求发送中");
  response.resolve({ ...running(), cancelRequested: true }); await settle();
  expect(button(root, "正在停止").disabled).toBe(true);
  expect(cancel).toHaveBeenCalledExactlyOnceWith("run-1");
});


it("keeps a failed historical attempt failed after later official Delivery, refresh and Run switching", async () => {
  vi.spyOn(api,"startAgent");
  const accepted:AgentRun={...running(),state:"SUCCEEDED",candidateId:"candidate-1"};
  const old:AgentRun={...running(),id:"old-run",state:"FAILED",reason:"POLICY_REJECTED"};
  vi.spyOn(api,"candidate").mockResolvedValue(savedCandidate());
  vi.spyOn(api,"delivery").mockResolvedValue({candidateId:"candidate-1",state:"COMPLETED",revision:"b".repeat(40),reason:null});
  vi.spyOn(api,"finalChanges").mockResolvedValue({planId:"plan-1",stepId:"step-1",baselineRevision:"a".repeat(40),
    verifiedRevision:"b".repeat(40),baselineTree:"tree-a",verifiedTree:"tree-b",availability:"AVAILABLE",reason:null,
    files:[{path:"official.java",changeType:"ADDED"}],counts:{added:1,modified:0,deleted:0}});
  const {root,s}=mount([accepted,old]);
  s.plan!.steps[0]={...s.plan!.steps[0]!,status:"SUCCEEDED",baselineRevision:"a".repeat(40),deliveryRevision:"b".repeat(40)};
  await settle();
  vi.mocked(api.agentRun).mockImplementation(async id=>id===old.id?old:accepted);
  expect(root.querySelector(".run-status")?.textContent).toBe("步骤已完成");
  expect(root.querySelector(".step-prerequisite")).toBeNull();
  expect(root.querySelectorAll(".file-change-summary")).toHaveLength(1);
  expect(root.querySelector(".final-file-list")?.textContent).toContain("official.java");
  expect(root.querySelector(".final-file-list")?.textContent).not.toContain("candidate-1.java");
  chooseRun(root,1);await settle();
  expect(root.querySelector(".run-status")?.textContent).toBe("本次运行失败");
  expect(root.querySelector(".file-change-summary")).toBeNull();
  chooseRun(root,0);await settle();
  expect(root.querySelector(".run-status")?.textContent).toBe("步骤已完成");
  button(root,"刷新记录").click();await settle();
  expect(root.querySelector(".run-status")?.textContent).toBe("步骤已完成");
  expect(root.querySelectorAll(".run-history button")[1]?.textContent).toContain("本次运行失败");
  expect(api.startAgent).not.toHaveBeenCalled();
});


it("loads official Delivery for a nonselected historical success without changing the selected failed Run", async () => {
  const failed:AgentRun={...running(),id:"latest-failed",state:"FAILED",reason:"POLICY_REJECTED"};
  const saved:AgentRun={...running(),state:"SUCCEEDED",candidateId:"candidate-1"};
  const accepted=deferred<Delivery|null>();
  const deliveries=vi.spyOn(api,"delivery").mockReturnValue(accepted.promise);
  const {root}=mount([failed,saved]);await settle();
  expect(deliveries).toHaveBeenCalledWith("candidate-1");
  expect(root.querySelectorAll(".run-choice strong")[1]?.textContent).toBe("候选已冻结，交付状态待核对");
  accepted.resolve({candidateId:"candidate-1",state:"COMPLETED",revision:"b".repeat(40),reason:null});await settle();
  expect(root.querySelectorAll(".run-choice strong")[1]?.textContent).toBe("步骤已完成");
  expect(root.querySelector(".run-status")?.textContent).toBe("本次运行失败");
  expect(root.querySelector(".agent-changes .file-change-summary")).toBeNull();
});

it("defaults to automatic JDK and confines Maven overrides to relevant advanced settings", async () => {
  const {root, s} = mount(); await settle();
  const settings = root.querySelector<HTMLDetailsElement>(".advanced-run-settings")!;
  expect(settings.open).toBe(false);
  expect(root.querySelector<HTMLSelectElement>("#agent-jdk")!.value).toBe("");
  expect(settings.textContent).toContain("目标项目构建 JDK（Agent 沙箱）");
  expect(settings.textContent).toContain("Maven 工程根目录覆盖");
  expect(settings.textContent).toContain("不影响 Agent 仓库探索或修改授权");
  s.repositories = [{...repository(), id: s.plan!.baseRepositoryProfileId, techStack: ["Python", "Flask"]}]; await settle();
  expect(root.querySelector("#agent-jdk")).toBeNull();
  s.repositories[0]!.techStack = ["Java 17", "Maven"]; await settle();
  expect(root.querySelector("#agent-jdk")).not.toBeNull();
});
it("keeps successful preflight compact and exposes blockers outside diagnostics", async () => {
  const {root} = mount([running()]); await settle();
  const stream = Events.instances.at(-1)!;
  stream.fact(1, "ENVIRONMENT_PREFLIGHT", "READY\n" + JSON.stringify({probes: [{directory: "backend", goal: "test", output: "long-probe-output"}]})); await settle();
  expect(root.querySelector(".preflight-status")?.textContent).toBe("运行环境已准备");
  expect(root.querySelector(".preflight-summary")?.closest(".developer-details")).not.toBeNull();
  expect(root.querySelector<HTMLDetailsElement>(".developer-details")?.open).toBe(false);
  stream.fact(2, "ENVIRONMENT_PREFLIGHT", "ENVIRONMENT_BLOCKED\n" + JSON.stringify({reason: "Docker 未启动，请启动后重试"})); await settle();
  expect(root.querySelector(".preflight-status")?.textContent).toContain("Docker 未启动，请启动后重试");
  expect(root.querySelector(".preflight-status")?.closest("details")).toBeNull();
});
