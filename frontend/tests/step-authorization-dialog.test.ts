import { createApp, nextTick, reactive } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../src/api/client";
import { createSession } from "../src/state/session";
import PlanView from "../src/views/PlanView.vue";
import { copy, plan } from "./fixtures";

const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.restoreAllMocks(); });
async function settle() {
  for (let i = 0; i < 15; i++) { await Promise.resolve(); await nextTick(); }
}
function button(root: Element, label: string) {
  return [...root.querySelectorAll<HTMLButtonElement>("button")].find(item => item.textContent?.trim() === label)!;
}
function setup() {
  vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "C:/synthetic", existingRoots: [] });
  const s = reactive(createSession()); s.plan = plan(); s.copy = copy();
  const step = s.plan.steps[0]!;
  s.plan.steps = [
    { ...step, id: "prior-z", status: "SUCCEEDED" },
    { ...step, id: "prior-a", status: "SUCCEEDED" },
    { ...step, id: "intended-step", status: "PENDING_CONFIRMATION",
      goal: "完整目标只在路线中显示，不应重复到授权弹窗。".repeat(5),
      scope: "reports / scheduler\n保留原有导出能力；本次仅调整调度与相关测试。" },
  ];
  const confirm = vi.spyOn(s, "confirmStep").mockResolvedValue();
  const start = vi.spyOn(api, "startAgent");
  const root = document.createElement("div"); document.body.append(root);
  const app = createApp(PlanView, { s }); app.mount(root);
  cleanup.push(() => { app.unmount(); s.dispose(); root.remove(); });
  return { s, root, confirm, start };
}
async function open(root: HTMLElement) {
  button(root, "审阅并授权此 Step").click(); await settle();
  return root.querySelector<HTMLDialogElement>(".step-authorization-dialog")!;
}

describe("Step 授权弹窗的精简内容和既有保护", () => {
  it("按当前实际顺序展示 Step 编号和完整范围，不重复目标；说明独立于范围", async () => {
    const { s, root, confirm, start } = setup();
    const dialog = await open(root);
    expect(dialog.open).toBe(true);
    expect(dialog.querySelector("h2")?.textContent).toBe("确认授权此步骤？");
    expect(dialog.querySelector(".step-authorization-identity")?.textContent).toBe("Step 03");
    expect(dialog.textContent).not.toContain(s.plan!.steps[2]!.goal);
    const scope = dialog.querySelector(".step-authorization-scope")!;
    expect(scope.getAttribute("aria-label")).toBe("本次授权范围");
    expect(scope.querySelector("h3")?.textContent).toBe("本次授权范围");
    expect(scope.querySelector("p")?.textContent).toBe(s.plan!.steps[2]!.scope);
    expect(scope.querySelector("p")?.getAttribute("tabindex")).toBe("0");
    const note = dialog.querySelector(".step-authorization-note")!;
    expect(note.textContent).toBe("仅授权当前步骤，不会立即运行 Agent，也不会授权后续步骤。");
    expect(scope.contains(note)).toBe(false);
    expect(scope.compareDocumentPosition(note) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect([...dialog.querySelectorAll(".actions button")].map(item => item.textContent?.trim()))
      .toEqual(["返回审阅", "确认授权"]);
    expect(button(dialog, "返回审阅").classList.contains("secondary")).toBe(true);
    expect(confirm).not.toHaveBeenCalled(); expect(start).not.toHaveBeenCalled();
  });

  it("返回审阅关闭弹窗，不授权或执行任何步骤", async () => {
    const { root, confirm, start } = setup(); const dialog = await open(root);
    button(dialog, "返回审阅").click(); await settle();
    expect(dialog.open).toBe(false);
    expect(confirm).not.toHaveBeenCalled(); expect(start).not.toHaveBeenCalled();
  });

  it("确认只授权选定的实际 Step ID，不自动运行 Agent", async () => {
    const { root, confirm, start } = setup(); const dialog = await open(root);
    expect(button(dialog, "确认授权").disabled).toBe(false);
    button(dialog, "确认授权").click(); await settle();
    expect(confirm).toHaveBeenCalledExactlyOnceWith("intended-step");
    expect(dialog.open).toBe(false); expect(start).not.toHaveBeenCalled();
  });

  it.each(["busy", "superseded", "revision", "earlier", "removed"] as const)(
    "打开后发生 %s 变化时仍阻止授权", async changed => {
      const { s, root, confirm, start } = setup(); const dialog = await open(root);
      if (changed === "busy") s.busy = "正在保存其他操作";
      if (changed === "superseded") s.plan!.status = "SUPERSEDED";
      if (changed === "revision") s.copy!.currentRevision = "b".repeat(40);
      if (changed === "earlier") s.plan!.steps[0]!.status = "READY";
      if (changed === "removed") s.plan!.steps = s.plan!.steps.slice(0, 2);
      await settle();
      expect(button(dialog, "确认授权").disabled).toBe(true);
      if (changed !== "busy") expect(dialog.querySelector(".step-lock-reason")?.textContent).toBeTruthy();
      // 即使收到迟到的确认事件，原有 confirmSelectedStep 仍重查当前状态。
      button(dialog, "确认授权").dispatchEvent(new MouseEvent("click", { bubbles: true })); await settle();
      expect(confirm).not.toHaveBeenCalled(); expect(start).not.toHaveBeenCalled();
    });

  it("当前顺序变化时编号随实际 Step 位置变化，切换计划关闭旧弹窗", async () => {
    const { s, root, confirm } = setup(); const dialog = await open(root);
    s.plan!.steps.unshift({ ...s.plan!.steps[0]!, id: "new-earlier", status: "SUCCEEDED" }); await settle();
    expect(dialog.querySelector(".step-authorization-identity")?.textContent).toBe("Step 04");
    s.plan = { ...plan(), id: "another-plan" }; await settle();
    expect(dialog.open).toBe(false); expect(confirm).not.toHaveBeenCalled();
  });
});
