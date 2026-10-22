import { createApp, h, nextTick, reactive } from "vue";
import { afterEach, expect, it, vi } from "vitest";
import EvolutionView from "../src/views/EvolutionView.vue";
import { api } from "../src/api/client";
import type { AgentCandidate, AgentRun, Delivery } from "../src/api/agentTypes";
import type { EvolutionPlan } from "../src/api/types";
import { createSession } from "../src/state/session";
import { copy, plan } from "./fixtures";

class Events extends EventTarget {
  static instances: Events[] = [];
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;
  readonly url: string;
  constructor(url: string) { super(); this.url = url; Events.instances.push(this); }
  close() { this.closed = true; }
  fact(sequence: number, kind = "TOOL_FINISHED") {
    this.dispatchEvent(new MessageEvent("activity", { data: JSON.stringify({ sequence, runId: "run-1", kind,
      summary: '{"tool":"read_file","path":"src/Counter.java","durationMs":12}', at: "2026-10-22T01:00:00Z" }) }));
  }
}
const running = (): AgentRun => ({ id: "run-1", planId: "plan-1", stepId: "step-1", workingCopyId: "copy-1",
  baseline: "a".repeat(40), state: "RUNNING", startedAt: "2026-10-22T01:00:00Z", endedAt: null, reason: null,
  candidateId: null, cancelRequested: false, retained: false, modelCalls: 2, toolCalls: 3, tokens: null });
const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); Events.instances = []; vi.restoreAllMocks(); });
async function settle() { for (let i = 0; i < 16; i++) { await Promise.resolve(); await nextTick(); } }
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

it("only an explicit start sends the request; real tool events are ordered and deduplicated", async () => {
  const start = vi.spyOn(api, "startAgent").mockResolvedValue(running());
  const { root } = mount(); await settle();
  expect(start).not.toHaveBeenCalled();
  button(root, "开始 Agent").click(); await settle();
  expect(start).toHaveBeenCalledExactlyOnceWith("plan-1", "step-1", undefined);
  const stream = Events.instances.at(-1)!;
  expect(stream.url).toContain("/agent-runs/run-1/stream?after=0");
  stream.fact(3); stream.fact(3); stream.fact(2); stream.fact(4); await settle();
  expect(root.querySelectorAll(".agent-timeline li")).toHaveLength(2);
  expect(root.textContent).toContain("src/Counter.java");
  expect(root.textContent).toContain("durationMs: 12");
  expect(root.textContent).not.toMatch(/\d+%/);
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
  expect(root.textContent).toContain("NOT_TESTED");
  expect(root.textContent).toContain("交付阻塞");
  expect(button(root, "重新核对并交付已有结果")).toBeDefined();
  expect(start).not.toHaveBeenCalled();
});

it("disconnect and reconnect preserve visible events; changing pages closes the subscription", async () => {
  const { root, app } = mount([running()]); await settle();
  const stream = Events.instances.at(-1)!;
  stream.fact(1); await settle();
  stream.onerror?.(); await settle();
  expect(root.textContent).toContain("正在重连并补齐事件");
  stream.onopen?.(); stream.fact(1); stream.fact(2); await settle();
  expect(root.querySelectorAll(".agent-timeline li")).toHaveLength(2);
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
  button(root, "取消运行").click(); await settle();
  expect(cancel).toHaveBeenCalledExactlyOnceWith("run-1");
  expect(button(root, "正在取消").disabled).toBe(true);
  expect(start).not.toHaveBeenCalled();
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
  chooseStep(root, "step-2"); await settle();
  response.resolve(running()); await settle();
  expect(root.querySelector<HTMLSelectElement>("#agent-step")!.value).toBe("step-2");
  expect(root.textContent).not.toContain("Agent：编码中");
  expect(Events.instances).toHaveLength(0);
  expect(start).toHaveBeenCalledExactlyOnceWith("plan-1", "step-1", undefined);
  expect(button(root, "确认此步骤").disabled).toBe(false);
  vi.mocked(api.agentRuns).mockResolvedValue([running()]);
  chooseStep(root, "step-1"); await settle();
  expect(root.textContent).toContain("Agent：编码中");
  expect(Events.instances.at(-1)?.url).toContain("run-1");
  expect(start).toHaveBeenCalledTimes(1);
});

it("a late Cancel cannot replace another Run or its active subscription", async () => {
  const response = deferred<AgentRun>();
  const cancel = vi.spyOn(api, "cancelAgent").mockReturnValue(response.promise);
  const second: AgentRun = { ...running(), id: "run-2", state: "FAILED", reason: "selected-run-two" };
  const { root } = mount([running(), second]); await settle();
  vi.mocked(api.agentRun).mockImplementation(async id => id === second.id ? second : running());
  button(root, "取消运行").click(); await settle();
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
  button(root, "取消运行").click(); await settle();
  chooseRun(root, 1); await settle();
  expect(button(root, "取消运行").disabled).toBe(false);
  button(root, "取消运行").click(); await settle();
  old.reject(new Error("stale failure")); await settle();
  expect(root.textContent).not.toContain("stale failure");
  expect(button(root, "取消运行").disabled).toBe(true);
  fresh.reject(new Error("current failure")); await settle();
  expect(root.textContent).toContain("current failure");
  expect(button(root, "取消运行").disabled).toBe(false);
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
  expect(root.textContent).toContain("candidate-2 blocked");
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
  expect(root.textContent).toContain("exact conflict");
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
  button(root, "取消运行").click(); await settle();
  vi.mocked(api.agentRun).mockResolvedValue({ ...running(), state: "SUCCEEDED", candidateId: "candidate-1" });
  Events.instances.at(-1)!.onopen?.(); await settle();
  expect(root.textContent).toContain("Agent：Candidate 已冻结");
  response.resolve({ ...running(), cancelRequested: true }); await settle();
  expect(root.textContent).toContain("Agent：Candidate 已冻结");
  expect(button(root, "重新核对并交付已有结果").disabled).toBe(false);
});

it.each(["MUTATION_NOT_STARTED", "DELIVERY_FAILURE"])("recovery retry is offered only for a proven untouched rejection (%s)", async reason => {
  const frozen: AgentRun = { ...running(), state: "SUCCEEDED", candidateId: "candidate-1" };
  vi.spyOn(api, "candidate").mockResolvedValue(savedCandidate());
  vi.spyOn(api, "delivery").mockResolvedValue({ candidateId: "candidate-1", state: "RECOVERY_REQUIRED", revision: null, reason });
  const { root } = mount([frozen]); await settle();
  expect(Boolean(button(root, "重新核对并交付已有结果"))).toBe(reason === "MUTATION_NOT_STARTED");
});

it("ignores a stale diff body and its pending cleanup after selecting another Run", async () => {
  const body = deferred<string>();
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, text: () => body.promise }));
  const first: AgentRun = { ...running(), state: "SUCCEEDED", candidateId: "candidate-1" };
  const second: AgentRun = { ...running(), id: "run-2", state: "FAILED", reason: "selected-run-two" };
  vi.spyOn(api, "candidate").mockResolvedValue(savedCandidate());
  vi.spyOn(api, "delivery").mockResolvedValue(null);
  const { root } = mount([first, second]); await settle();
  vi.mocked(api.agentRun).mockImplementation(async id => id === second.id ? second : first);
  button(root, "读取最终差异").click(); await settle();
  chooseRun(root, 1); await settle();
  body.reject(new Error("old diff failure")); await settle();
  expect(root.textContent).toContain("selected-run-two");
  expect(root.textContent).not.toContain("old diff failure");
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
  expect(button(root, "确认此步骤").disabled).toBe(false);
  expect(refresh).not.toHaveBeenCalled();
});
