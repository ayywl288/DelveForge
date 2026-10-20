import { createApp, nextTick, reactive } from "vue";
import { afterEach, expect, it, vi } from "vitest";
import EvolutionView from "../src/views/EvolutionView.vue";
import { api } from "../src/api/client";
import { createSession } from "../src/state/session";
import type { StepFinalChanges } from "../src/api/types";
import { copy, successfulPlan } from "./fixtures";

const cleanups: (() => void)[] = [];
afterEach(() => { cleanups.splice(0).forEach(fn => fn()); vi.restoreAllMocks(); });
async function settle() { for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick(); } }
function result(stepId = "step-1", path = "first.java"): StepFinalChanges {
  return { planId: "plan-1", stepId, baselineRevision: "a".repeat(40), verifiedRevision: "b".repeat(40),
    baselineTree: "tree-a", verifiedTree: "tree-b", availability: "AVAILABLE", reason: null,
    files: [{ path, changeType: "MODIFIED" }], counts: { added: 0, modified: 1, deleted: 0 } };
}
function mount() {
  const s = reactive(createSession()); s.plan = successfulPlan(); s.copy = copy();
  s.plan.steps.push({ ...structuredClone(successfulPlan().steps[0]!), id: "step-2", goal: "第二个步骤" });
  const root = document.createElement("div"); document.body.append(root);
  const app = createApp(EvolutionView, { s }); app.mount(root);
  cleanups.push(() => { app.unmount(); s.dispose(); root.remove(); });
  return { s, root };
}
function tab(root: HTMLElement, id: string) { return root.querySelector<HTMLButtonElement>(`#step-tab-${id}`)!; }
it("switches only one panel without changing the URL or making business writes", async () => {
  const get = vi.spyOn(api, "finalChanges").mockResolvedValue(result("step-2"));
  const { s, root } = mount(); await settle();
  const writes = [vi.spyOn(s, "execute"), vi.spyOn(s, "repair"), vi.spyOn(s, "retry"), vi.spyOn(s, "rollback")];
  const url = location.href;
  expect(get).not.toHaveBeenCalled();
  for (const id of ["changes", "trace", "verification", "history", "current"]) {
    tab(root, id).click(); await settle();
    expect(root.querySelectorAll('[role="tabpanel"]')).toHaveLength(1);
    expect(root.querySelectorAll('[role="tab"][aria-selected="true"]')).toHaveLength(1);
    expect(root.querySelector('[role="tabpanel"]')?.getAttribute("aria-labelledby")).toBe(`step-tab-${id}`);
    expect(root.querySelector(".execution-action")?.textContent).toContain("此步骤已验证通过");
  }
  expect(get).toHaveBeenCalledTimes(1);
  expect(location.href).toBe(url);
  writes.forEach(write => expect(write).not.toHaveBeenCalled());
});
it("keyboard arrows wrap, Home/End move focus and only the selected tab is tabbable", async () => {
  vi.spyOn(api, "finalChanges").mockResolvedValue(result("step-2"));
  const { root } = mount(); await settle();
  for (const [from, key, to] of [["current", "ArrowLeft", "history"], ["history", "ArrowRight", "current"],
    ["current", "End", "history"], ["history", "Home", "current"], ["current", "ArrowRight", "changes"]]) {
    const event = new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true });
    tab(root, from!).dispatchEvent(event); await settle();
    expect(event.defaultPrevented).toBe(true);
    expect(document.activeElement).toBe(tab(root, to!));
    expect(tab(root, to!).tabIndex).toBe(0);
    expect([...root.querySelectorAll<HTMLElement>('[role="tab"]')].filter(el => el.tabIndex === 0)).toHaveLength(1);
  }
});
it("keeps the current tab on a same-Step refresh and resets tab and scroll on Step or Plan change", async () => {
  const { s, root } = mount(); await settle();
  tab(root, "history").click(); await settle();
  const panel = root.querySelector<HTMLElement>('[role="tabpanel"]')!; panel.scrollTop = 300;
  s.plan = structuredClone(successfulPlan());
  s.plan.steps.push({ ...structuredClone(successfulPlan().steps[0]!), id: "step-2", goal: "第二个步骤" });
  await settle();
  expect(tab(root, "history").getAttribute("aria-selected")).toBe("true");
  expect(root.querySelector('[role="tabpanel"]')).toBe(panel);
  root.querySelector<HTMLButtonElement>(".step-select")!.click(); await settle();
  expect(tab(root, "current").getAttribute("aria-selected")).toBe("true");
  expect(root.querySelector<HTMLElement>('[role="tabpanel"]')!.scrollTop).toBe(0);
  tab(root, "trace").click(); await settle();
  s.plan = { ...s.plan!, id: "other-plan" }; await settle();
  expect(tab(root, "current").getAttribute("aria-selected")).toBe("true");
});
it("ignores a final-changes request that arrives after leaving its tab and selecting another Step", async () => {
  let resolve!: (value: StepFinalChanges) => void;
  vi.spyOn(api, "finalChanges").mockImplementationOnce(() => new Promise(r => { resolve = r; }))
    .mockResolvedValueOnce(result("step-1", "correct-step.java"));
  const { root } = mount(); await settle();
  tab(root, "changes").click(); await settle();
  expect(root.textContent).toContain("正在只读查询");
  root.querySelector<HTMLButtonElement>(".step-select")!.click(); await settle();
  tab(root, "changes").click(); await settle();
  resolve(result("step-2", "late-wrong-step.java")); await settle();
  expect(root.textContent).toContain("correct-step.java");
  expect(root.textContent).not.toContain("late-wrong-step.java");
});
it("keeps Recovery and UNKNOWN warnings visible in every tab without clearing the receipt", async () => {
  const { s, root } = mount(); await settle();
  s.observeExecution(false);
  s.plan!.steps[1]!.status = "RECOVERY_REQUIRED";
  s.copy!.status = "RECOVERY_REQUIRED";
  const receipt = { planId: "plan-1", stepId: "step-2", startedAt: new Date().toISOString(), requestId: "unknown-tabs" };
  s.receipt = receipt;
  for (const id of ["changes", "trace", "verification", "history", "current"]) {
    tab(root, id).click(); await settle();
    expect(root.querySelector(".execution-notice")?.textContent).toContain("执行结果待核对");
    expect(root.querySelector(".execution-detail > .panel-header")?.textContent).toContain("RECOVERY_REQUIRED");
    expect(root.querySelector(".execution-action")?.textContent).toContain("不能重复提交");
    expect(s.receipt).toEqual(receipt);
  }
});
