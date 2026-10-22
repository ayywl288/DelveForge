import { createApp, h, nextTick, reactive } from "vue";
import { afterEach, expect, it, vi } from "vitest";
import EvolutionView from "../src/views/EvolutionView.vue";
import { api } from "../src/api/client";
import type { AgentRun } from "../src/api/agentTypes";
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
