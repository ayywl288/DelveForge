import { createApp, defineComponent, h, nextTick, reactive } from "vue";
import { afterEach, expect, it, vi } from "vitest";
import StepFinalChanges from "../src/components/StepFinalChanges.vue";
import EvolutionView from "../src/views/EvolutionView.vue";
import { api, ApiError } from "../src/api/client";
import type { StepFinalChanges as Result } from "../src/api/types";
import { createSession } from "../src/state/session";
import { copy, successfulPlan } from "./fixtures";

const cleanups: (() => void)[] = [];
afterEach(() => { cleanups.splice(0).forEach(fn => fn()); vi.restoreAllMocks(); });
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick(); } }
function response(stepId = "step-1"): Result {
  return { planId: "plan-1", stepId, baselineRevision: "a".repeat(40), verifiedRevision: "b".repeat(40),
    baselineTree: "tree-a", verifiedTree: "tree-b", availability: "AVAILABLE", reason: null,
    files: [{ path: "whole-step.java", changeType: "ADDED" }], counts: { added: 1, modified: 0, deleted: 0 } };
}
function mount() {
  const props = reactive({ planId: "plan-1", step: successfulPlan().steps[0]! });
  const root = document.createElement("div"); document.body.append(root);
  const app = createApp(defineComponent({ setup: () => () => h(StepFinalChanges, props) })); app.mount(root);
  cleanups.push(() => { app.unmount(); root.remove(); });
  return { props, root };
}
it("shows actual cumulative files separately from the latest ExecutionResult", async () => {
  vi.spyOn(api, "finalChanges").mockResolvedValue(response());
  const { root } = mount(); await settle();
  expect(root.textContent).toContain("whole-step.java");
  expect(root.textContent).not.toContain("src/ReportService.java");
  expect(api.finalChanges).toHaveBeenCalledWith("plan-1", "step-1");
});
it("available empty result differs from unavailable historical objects", async () => {
  const apiCall = vi.spyOn(api, "finalChanges").mockResolvedValue({ ...response(), files: [], counts: { added: 0, modified: 0, deleted: 0 } });
  const { root } = mount(); await settle();
  expect(root.textContent).toContain("最终没有净文件变化");
  apiCall.mockResolvedValue({ ...response(), availability: "UNAVAILABLE", reason: "HISTORICAL_OBJECT_MISSING", files: null, counts: null });
  root.querySelector("button")!.click(); await settle();
  expect(root.textContent).toContain("历史 Git 对象已不可用");
  expect(root.textContent).not.toContain("0 个文件");
});
it("ignores a late response after selecting another Step", async () => {
  let resolveFirst!: (value: Result) => void;
  vi.spyOn(api, "finalChanges").mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve; }))
    .mockResolvedValueOnce({ ...response("step-2"), files: [{ path: "second.java", changeType: "MODIFIED" }] });
  const { props, root } = mount();
  props.step = { ...props.step, id: "step-2" }; await settle();
  resolveFirst(response()); await settle();
  expect(root.textContent).toContain("second.java");
  expect(root.textContent).not.toContain("whole-step.java");
});
it("does not query Git for an unfinished Step", async () => {
  vi.spyOn(api, "finalChanges").mockResolvedValue(response());
  const { props, root } = mount(); await settle();
  vi.mocked(api.finalChanges).mockClear(); props.step = { ...props.step, status: "READY" }; await settle();
  expect(root.textContent).toContain("没有最终 Verified Revision");
  expect(api.finalChanges).not.toHaveBeenCalled();
});
it("network failure does not fabricate an empty difference", async () => {
  vi.spyOn(api, "finalChanges").mockRejectedValue(new Error("isolated connection lost"));
  const { root } = mount(); await settle();
  expect(root.querySelector('[role="alert"]')?.textContent).toContain("最终差异不可用");
  expect(root.textContent).not.toContain("0 个文件");
});
it("rejects a response for a different saved Verified Revision", async () => {
  vi.spyOn(api, "finalChanges").mockResolvedValue({ ...response(), verifiedRevision: "c".repeat(40) });
  const { root } = mount(); await settle();
  expect(root.textContent).toContain("保存版本不一致");
  expect(root.textContent).not.toContain("whole-step.java");
});
it("history success is independent of the WorkingCopy latest revision and Tabs are exclusive", async () => {
  vi.spyOn(api, "finalChanges").mockResolvedValue(response());
  const s = reactive(createSession()); s.plan = successfulPlan();
  s.copy = { ...copy(), currentRevision: "d".repeat(40), lastVerifiedRevision: "d".repeat(40) };
  const execute = vi.spyOn(s, "execute"); const retry = vi.spyOn(s, "retry");
  const root = document.createElement("div"); document.body.append(root);
  const app = createApp(EvolutionView, { s }); app.mount(root);
  cleanups.push(() => { app.unmount(); s.dispose(); root.remove(); }); await settle();
  expect(root.querySelector(".success-note")?.textContent).toContain("此 Step 验证通过");
  expect(root.querySelector(".success-note")?.textContent).toContain("b".repeat(40));
  const tabs = [...root.querySelectorAll<HTMLButtonElement>('[role="tab"]')];
  expect(tabs.map(tab => tab.textContent)).toEqual(["当前状态", "最终变更", "演化轨迹", "验证详情", "完整历史"]);
  expect(root.querySelectorAll('[role="tabpanel"]')).toHaveLength(1);
  expect(root.querySelector('#step-final-changes')).toBeNull();
  tabs[4]!.click(); await settle();
  expect(root.querySelector('#execution-goal')).toBeNull();
  root.querySelector<HTMLDetailsElement>("#step-complete-history > details")!.open = true;
  await settle(); expect(execute).not.toHaveBeenCalled(); expect(retry).not.toHaveBeenCalled();
});

it("an old backend 404 is explained without claiming the request fields are invalid", async () => {
  vi.spyOn(api, "finalChanges").mockRejectedValue(new ApiError(404, "INVALID_REQUEST"));
  const { root } = mount(); await settle();
  expect(root.textContent).toContain("HTTP 404");
  expect(root.textContent).toContain("确认后端已构建并运行");
  expect(root.textContent).not.toContain("请求字段无效");
});
