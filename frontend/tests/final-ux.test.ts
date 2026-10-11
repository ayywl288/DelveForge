import { createApp, defineComponent, h, KeepAlive, nextTick, reactive, ref, type Component } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "../src/App.vue";
import AssetAuthorizationForm from "../src/components/AssetAuthorizationForm.vue";
import PlanView from "../src/views/PlanView.vue";
import EvolutionView from "../src/views/EvolutionView.vue";
import RepositoryView from "../src/views/RepositoryView.vue";
import { api, ApiError } from "../src/api/client";
import { createSession, executionKey, sessionKey } from "../src/state/session";
import { validLicenseDeclaration } from "../src/state/licenseDeclaration";
import { asset, copy, direction, plan, profile, repository, successfulPlan } from "./fixtures";

const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.restoreAllMocks(); });
const session = () => {
  const s = reactive(createSession());
  cleanup.push(s.dispose);
  return s;
};
function mount(component: Component, props: Record<string, unknown> = {}) {
  const root = document.createElement("div"); document.body.append(root);
  const app = createApp(component, props); app.mount(root);
  cleanup.push(() => { app.unmount(); root.remove(); });
  return root;
}
async function settle() {
  for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick(); }
}
function button(root: HTMLElement, text: string) {
  return [...root.querySelectorAll("button")].find(item => item.textContent?.includes(text))!;
}
async function select(root: HTMLElement, id: string, value: string) {
  const field = root.querySelector<HTMLSelectElement>(`#${id}`)!;
  field.value = value; field.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
}
async function input(field: HTMLTextAreaElement | HTMLInputElement, value: string) {
  field.value = value; field.dispatchEvent(new Event("input", { bubbles: true })); await nextTick();
}
async function acknowledge(root: HTMLElement) {
  const field = root.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')[1]!;
  field.checked = true; field.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
}
function ready() {
  const s = session(); s.plan = plan(); s.copy = copy(); s.ids.planId = "plan-1";
  vi.spyOn(api, "plan").mockResolvedValue(plan());
  vi.spyOn(api, "workingCopy").mockResolvedValue(copy());
  return s;
}
function planning() {
  const s = session(); s.profile = { ...profile(), revision: 2, status: "CONFIRMED" };
  s.directions = [{ ...direction(), status: "SELECTED" }]; s.repositories = [repository()];
  s.assets = [{ ...asset(), licenseInfo: null }];
  vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "E:\\local-storage", existingRoots: [] });
  return s;
}
function restoredPlanning() {
  const target = { ...asset(), licenseInfo: null };
  const duplicate = { ...asset(), id: "historical-same-path", licenseInfo: "MIT" };
  localStorage.setItem(sessionKey, JSON.stringify({ profileId: "profile-1", assetIds: [duplicate.id, target.id],
    repositoryIds: ["repo-1"], directionIds: ["direction-1"], planId: "" }));
  vi.spyOn(api, "connectivity").mockResolvedValue({ service: "delveforge", status: "UP", timestamp: "" });
  vi.spyOn(api, "profile").mockResolvedValue({ ...profile(), revision: 2, status: "CONFIRMED" });
  vi.spyOn(api, "reviewReadiness").mockResolvedValue({ profileId: "profile-1", revision: 2, sufficient: true });
  vi.spyOn(api, "repository").mockResolvedValue(repository());
  vi.spyOn(api, "direction").mockResolvedValue({ ...direction(), status: "SELECTED" });
  vi.spyOn(api, "asset").mockImplementation(async id => id === target.id ? target : duplicate);
  vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "E:\\local-storage", existingRoots: [] });
  return { target, duplicate };
}

describe("E1 许可证声明", () => {
  it("前后端约定是最小文字检查，不把未知或 SPDX 当成授权", () => {
    for (const value of [null, "MIT", "Apache-2.0", "BSD-3-Clause", "GPL-3.0-only", "代码：自有；文档：授权另附"])
      expect(validLicenseDeclaration(value)).toBe(true);
    for (const value of ["", " \n ", "123", "１２３", "---"]) expect(validLicenseDeclaration(value)).toBe(false);
  });
  it("标准许可证仍需显式确认，保留读取拒绝与 DENIED，并提交当前授权版本", async () => {
    const submit = vi.fn();
    const root = mount(AssetAuthorizationForm, { asset: { ...asset(), readPermissionAllowed: false,
      licenseInfo: null, usageAuthorization: "DENIED", authorizationRevision: 4 }, busy: false, onSubmit: submit });
    await select(root, "asset-license-asset-1", "Apache-2.0");
    expect(button(root, "保存本次授权").disabled).toBe(true);
    await acknowledge(root); button(root, "保存本次授权").click();
    expect(submit).toHaveBeenCalledWith({ expectedAuthorizationRevision: 4, readPermissionAllowed: false,
      licenseInfo: "Apache-2.0", usageAuthorization: "DENIED" });
    expect(root.textContent).toContain("未独立验证真实性、权属或法律兼容性");
  });
  it("旧许可忠实显示，新的数字或空白提交拒绝，自定义混合描述按原文保存", async () => {
    const submit = vi.fn();
    const root = mount(AssetAuthorizationForm, { asset: { ...asset(), licenseInfo: "123" }, busy: false, onSubmit: submit });
    const text = root.querySelector<HTMLTextAreaElement>("textarea")!;
    expect(text.value).toBe("123"); await acknowledge(root);
    root.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    expect(submit).not.toHaveBeenCalled();
    await input(text, "  "); expect(button(root, "保存本次授权").disabled).toBe(true);
    const mixed = "代码：Apache-2.0，complete/src；文档：另见教程许可；来源：LICENSE 与 README";
    await input(text, mixed); button(root, "保存本次授权").click();
    expect(submit.mock.calls[0]?.[0].licenseInfo).toBe(mixed);
    expect(root.querySelector<HTMLSelectElement>("select")?.value).toBe("custom");
  });
  it("自定义输入标准名称时不突然移除输入框，切换未知显式返回 null", async () => {
    const submit = vi.fn();
    const root = mount(AssetAuthorizationForm, { asset: { ...asset(), licenseInfo: null }, busy: false, onSubmit: submit });
    await select(root, "asset-license-asset-1", "custom"); await acknowledge(root);
    expect(button(root, "保存本次授权").disabled).toBe(true);
    await input(root.querySelector("textarea")!, "MIT");
    expect(root.querySelector("textarea")).toBeTruthy();
    await select(root, "asset-license-asset-1", "unknown"); button(root, "保存本次授权").click();
    expect(submit.mock.calls[0]?.[0].licenseInfo).toBeNull();
  });
});

describe("E2 精确授权导航", () => {
  it("服务端授权拒绝与版本冲突仍显示修复入口，普通状态冲突不冒充授权问题", async () => {
    const s = planning(); s.assets = [asset()];
    const generate = vi.spyOn(api, "generatePlan").mockRejectedValue(new ApiError(409, "ASSET_USAGE_AUTHORIZATION_REQUIRED"));
    const authorize = vi.fn(); const root = mount(PlanView, { s, onAuthorize: authorize });
    await select(root, "plan-base", "repo-1");
    await s.generatePlan("repo-1"); await nextTick(); button(root, "前往补充").click();
    expect(authorize).toHaveBeenCalledWith("asset-1"); expect(s.error).toContain("使用授权");
    generate.mockRejectedValue(new ApiError(409, "ASSET_AUTHORIZATION_CONFLICT"));
    await s.generatePlan("repo-1"); await nextTick(); expect(button(root, "前往补充")).toBeTruthy();
    expect(s.error).toContain("授权版本已变化");
    generate.mockRejectedValue(new ApiError(409, "CONFLICT"));
    await s.generatePlan("repo-1"); await nextTick(); expect(button(root, "前往补充")).toBeUndefined();
  });
  it("沿用原 RepositoryProfile 和方向，精确打开 Asset ID，成功保存后返回保留的 Planning 选择", async () => {
    const { target, duplicate } = restoredPlanning();
    const save = vi.spyOn(api, "updateAssetAuthorization").mockResolvedValue({ ...target, licenseInfo: "MIT", authorizationRevision: 1 });
    const analyze = vi.spyOn(api, "analyze"); const discover = vi.spyOn(api, "discover"); const generate = vi.spyOn(api, "generatePlan");
    const root = mount(App); await settle(); await select(root, "plan-base", "repo-1");
    button(root, "前往补充").click(); await settle();
    const card = root.querySelector<HTMLElement>(`#asset-${target.id}`)!;
    expect(card.querySelector(".authorization-editor")?.hasAttribute("open")).toBe(true);
    expect(root.querySelector(`#asset-${duplicate.id} .authorization-editor`)?.hasAttribute("open")).toBe(false);
    expect(document.activeElement?.id).toBe("asset-license-asset-1");
    await select(card, "asset-license-asset-1", "MIT"); await acknowledge(card);
    button(card, "保存本次授权").click(); await settle();
    expect(save).toHaveBeenCalledWith(target.id, expect.objectContaining({ expectedAuthorizationRevision: 0, licenseInfo: "MIT" }));
    button(card, "返回演化规划").click(); await settle();
    expect(root.querySelector<HTMLSelectElement>("#plan-base")?.value).toBe("repo-1");
    expect(button(root, "生成演化计划").disabled).toBe(false);
    expect(analyze).not.toHaveBeenCalled(); expect(discover).not.toHaveBeenCalled(); expect(generate).not.toHaveBeenCalled();
  });
  it("目标资源不存在时保留 Planning，不用同路径历史 Asset 替代；失败保存不显示返回按钮", async () => {
    restoredPlanning(); const root = mount(App); await settle(); await select(root, "plan-base", "repo-1");
    vi.mocked(api.asset).mockRejectedValue(new ApiError(404, "NOT_FOUND"));
    button(root, "前往补充").click(); await settle();
    expect(root.querySelector("#plan-base")).toBeTruthy(); expect(root.textContent).toContain("对象不存在");
    const s = session(); s.assets = [asset()];
    vi.spyOn(api, "updateAssetAuthorization").mockRejectedValue(new ApiError(409, "ASSET_AUTHORIZATION_CONFLICT"));
    const editor = mount(RepositoryView, { s, authorizationTargetId: "asset-1" });
    await acknowledge(editor.querySelector(".asset-card")!); button(editor, "保存本次授权").click(); await settle();
    expect(button(editor, "返回演化规划")).toBeUndefined(); expect(s.error).toContain("未提交");
  });
});

describe("E3 真实等待和持久化结果", () => {
  it("702 秒只有一次 execute；READY 与前端等待分开显示，成功突出 revision 和实际检查", async () => {
    vi.useFakeTimers(); const s = ready(); const root = mount(EvolutionView, { s });
    let resolve!: (value: ReturnType<typeof plan>) => void;
    const execute = vi.spyOn(api, "execute").mockImplementation(() => new Promise(r => { resolve = r; }));
    const pending = s.execute("step-1"); await settle();
    expect(root.textContent).toContain("执行请求已提交，正在等待后端结果");
    expect(root.querySelector(".waiting-duration")?.textContent).toContain("0 秒");
    await vi.advanceTimersByTimeAsync(702_000); await s.execute("step-1"); await nextTick();
    expect(root.querySelector(".waiting-duration")?.textContent).toContain("11 分 42 秒");
    expect(root.querySelector(".observed-execution")?.textContent).toContain("READY");
    expect(root.querySelector(".observed-execution")?.textContent).not.toMatch(/Candidate|TEST|BUILD|PREPARING|%/);
    expect(execute).toHaveBeenCalledTimes(1); expect(api.plan).toHaveBeenCalled();
    const success = successfulPlan(); success.steps[0]!.verificationResult!.checks.push({ action: "BUILD", target: "",
      passed: true, criterionIndexes: [0], summary: "合成构建结果" });
    vi.mocked(api.plan).mockResolvedValue(success); vi.mocked(api.workingCopy).mockResolvedValue({ ...copy(),
      currentRevision: "b".repeat(40), lastVerifiedRevision: "b".repeat(40) });
    resolve(success); await pending; await nextTick();
    expect(root.querySelector(".waiting-duration")).toBeNull();
    const result = root.querySelector(".success-note")!.textContent!;
    for (const value of ["SUCCEEDED", "lastVerifiedRevision", "b".repeat(40), "TEST：PASS", "BUILD：PASS"]) expect(result).toContain(value);
    expect(execute).toHaveBeenCalledTimes(1);
  });
  it("暂停、离开缓存页面、卸载停止轮询；返回页面仅恢复 GET，不重发 execute", async () => {
    vi.useFakeTimers(); const s = ready();
    s.receipt = { planId: "plan-1", stepId: "step-1", startedAt: new Date().toISOString() };
    const execute = vi.spyOn(api, "execute"); const visible = ref(true);
    const Other = defineComponent({ render: () => h("p", "其他页面") });
    const root = mount(defineComponent({ render: () => h(KeepAlive, null, { default: () => visible.value
      ? h(EvolutionView, { s }) : h(Other) }) }));
    await vi.advanceTimersByTimeAsync(3_000); const first = vi.mocked(api.plan).mock.calls.length;
    expect(first).toBe(1); button(root, "暂停状态刷新").click(); await nextTick();
    await vi.advanceTimersByTimeAsync(9_000); expect(api.plan).toHaveBeenCalledTimes(first);
    button(root, "恢复只读刷新").click(); await vi.advanceTimersByTimeAsync(3_000);
    visible.value = false; await nextTick(); const beforeLeave = vi.mocked(api.plan).mock.calls.length;
    await vi.advanceTimersByTimeAsync(9_000); expect(api.plan).toHaveBeenCalledTimes(beforeLeave);
    visible.value = true; await nextTick(); await vi.advanceTimersByTimeAsync(3_000);
    expect(api.plan).toHaveBeenCalledTimes(beforeLeave + 1); expect(execute).not.toHaveBeenCalled();
  });
  it("断线与状态未知保留标记，只读刷新失败不伪造 Candidate 或自动重试", async () => {
    vi.useFakeTimers(); const s = ready();
    const execute = vi.spyOn(api, "execute").mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
    await s.execute("step-1"); const root = mount(EvolutionView, { s });
    expect(root.textContent).toContain("执行结果待核对，仅查询状态");
    expect(root.querySelector(".waiting-duration")).toBeNull();
    expect(button(root, "审阅风险并执行").disabled).toBe(true);
    vi.mocked(api.plan).mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
    await vi.advanceTimersByTimeAsync(60_000); await s.execute("step-1"); await nextTick();
    expect(root.textContent).toContain("状态查询失败"); expect(s.receipt).not.toBeNull();
    expect(localStorage.getItem(executionKey)).toContain("step-1"); expect(execute).toHaveBeenCalledTimes(1);
    s.plan = null; await nextTick(); expect(root.textContent).toContain("执行状态尚未读取");
    expect(button(root, "查询当前状态")).toBeTruthy(); expect(button(root, "审阅风险并执行")).toBeUndefined();
  });
  it("只展示 GET 已保存的 VERIFYING、Candidate 和完成检查，不编造其余检查", async () => {
    const s = ready(); s.receipt = { planId: "plan-1", stepId: "step-1", startedAt: new Date().toISOString() };
    const observed = successfulPlan(); observed.status = "ACTIVE"; observed.steps[0]!.status = "VERIFYING";
    observed.steps[0]!.verificationResult!.checks = [{ action: "BUILD", target: "", criterionIndexes: [0], passed: true, summary: "合成已完成检查" }];
    vi.mocked(api.plan).mockResolvedValue(observed); const root = mount(EvolutionView, { s }); await s.refreshPlan(); await nextTick();
    const result = root.querySelector(".observed-execution")!.textContent!;
    expect(result).toContain("VERIFYING"); expect(result).toContain("b".repeat(40)); expect(result).toContain("BUILD：PASS");
    expect(result).not.toContain("TEST"); expect(root.querySelector(".success-note")).toBeNull();
  });
});

it("E4 Prepare 前展示后端配置，浏览器不提交任意目录，也不自动 Prepare", async () => {
  const s = planning(); s.plan = { ...plan(), status: "PROPOSED", workingCopyId: null };
  vi.mocked(api.workspaceConfiguration).mockResolvedValue({ root: "E:\\chosen-storage", existingRoots: ["C:\\old-storage"] });
  const prepare = vi.spyOn(api, "prepare"); const root = mount(PlanView, { s }); await settle();
  expect(root.querySelector(".workspace-configuration")?.textContent).toContain("E:\\chosen-storage");
  expect(root.querySelector(".workspace-configuration")?.textContent).toContain("C:\\old-storage");
  expect(root.querySelector(".workspace-configuration input")).toBeNull(); expect(prepare).not.toHaveBeenCalled();
});
