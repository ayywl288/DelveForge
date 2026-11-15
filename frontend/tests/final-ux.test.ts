import { createApp, nextTick, reactive, type Component } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import AssetAuthorizationForm from "../src/components/AssetAuthorizationForm.vue";
import PlanView from "../src/views/PlanView.vue";
import RepositoryView from "../src/views/RepositoryView.vue";
import { api, ApiError } from "../src/api/client";
import { createSession } from "../src/state/session";
import { validLicenseDeclaration } from "../src/state/licenseDeclaration";
import { asset, direction, plan, profile, repository} from "./fixtures";

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
function planning() {
  const s = session(); s.profile = { ...profile(), revision: 2, status: "CONFIRMED" };
  s.directions = [{ ...direction(), status: "SELECTED" }]; s.repositories = [repository()];
  s.assets = [{ ...asset(), licenseInfo: null }];
  vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "E:\\local-storage", existingRoots: [] });
  return s;
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
  it("授权保存失败仍保留编辑器和真实冲突", async () => {
    const s = session(); s.assets = [asset()];
    vi.spyOn(api, "updateAssetAuthorization").mockRejectedValue(new ApiError(409, "ASSET_AUTHORIZATION_CONFLICT"));
    const editor = mount(RepositoryView, { s, authorizationTargetId: "asset-1" });
    await acknowledge(editor.querySelector(".asset-card")!); button(editor, "保存本次授权").click(); await settle();
    expect(button(editor, "返回演化规划")).toBeUndefined(); expect(s.error).toContain("未提交");
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
