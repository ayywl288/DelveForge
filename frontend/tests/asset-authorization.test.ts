import { createApp, nextTick, reactive, type Component } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import EvidenceList from "../src/components/EvidenceList.vue";
import RepositoryView from "../src/views/RepositoryView.vue";
import PlanView from "../src/views/PlanView.vue";
import { api, ApiError } from "../src/api/client";
import { createSession } from "../src/state/session";
import { repositoryLocationKey } from "../src/state/repositoryLocation";
import { asset, direction, profile, repository } from "./fixtures";

const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach(fn => fn());
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});
const session = () => {
  const s = reactive(createSession());
  cleanup.push(() => s.dispose());
  return s;
};
function mount(component: Component, props: Record<string, unknown>) {
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(component, props);
  app.mount(root);
  cleanup.push(() => { app.unmount(); root.remove(); });
  return root;
}
const settle = async () => { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick(); };
const input = async (element: HTMLInputElement | HTMLTextAreaElement, value: string) => {
  element.value = value;
  element.dispatchEvent(new Event("input", { bubbles: true }));
  await nextTick();
};

describe("资产授权和展示", () => {
  it("Evidence 默认只描述来源，原始确认字段在技术详情中且不修改数据", () => {
    const items = [{ sourceType: "USER_INPUT", sourceRef: "原始描述", claim: "用户想做Java项目",
      confidence: 0.8, confirmed: false }, { sourceType: "REPOSITORY", sourceRef: "pom.xml", claim: "使用Maven",
      confidence: null, confirmed: false }];
    const root = mount(EvidenceList, { items, expanded: true });
    const meta = root.querySelector("article > .meta")!;
    expect(meta.textContent).toContain("来自用户描述");
    expect(meta.textContent).not.toMatch(/confirmed|未确认|待核对|USER_INPUT/);
    const details = root.querySelector("article > details")!;
    expect(details.hasAttribute("open")).toBe(false);
    expect(details.textContent).toContain("confirmed=false");
    expect(details.textContent).toContain("与整体画像确认及方向选择不同");
    expect(root.querySelectorAll("article > .meta")[1]?.textContent).toContain("来自仓库文件");
    expect(items[0]?.confirmed).toBe(false);
  });

  it("同路径已注册提示阻止重复按钮，Windows 词法别名一致", async () => {
    expect(repositoryLocationKey("c:\\PROJECTS\\child\\..\\sample\\")).toBe(repositoryLocationKey(asset().location));
    const s = session(); s.assets = [asset()];
    const register = vi.spyOn(s, "register");
    const root = mount(RepositoryView, { s });
    await input(root.querySelector("#repo-path")!, "c:/PROJECTS/sample/./");
    const button = [...root.querySelectorAll("button")].find(item => item.textContent?.trim() === "已注册")!;
    expect(button.disabled).toBe(true);
    expect(root.textContent).toContain("在已有记录上补齐");
    button.click();
    expect(register).not.toHaveBeenCalled();
  });

  it("授权编辑必须显式核对后提交完整字段和当前版本", async () => {
    const s = session(); s.assets = [{ ...asset(), licenseInfo: null, usageAuthorization: "UNCLEAR", authorizationRevision: 3 }];
    const save = vi.spyOn(s, "updateAssetAuthorization").mockResolvedValue(true);
    const root = mount(RepositoryView, { s });
    const form = root.querySelector(".authorization-editor form")!;
    const button = form.querySelector("button")!;
    expect(button.disabled).toBe(true);
    const license = form.querySelector(".license-declaration select") as HTMLSelectElement;
    license.value = "custom"; license.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
    await input(form.querySelector("textarea")!, "Code Apache-2.0; prose BY-ND-3.0");
    const select = form.querySelector("#asset-usage-asset-1") as HTMLSelectElement;
    select.value = "ALLOWED"; select.dispatchEvent(new Event("change", { bubbles: true }));
    const check = form.querySelectorAll("input[type=checkbox]")[1] as HTMLInputElement;
    check.checked = true; check.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
    button.click(); await settle();
    expect(save).toHaveBeenCalledWith("asset-1", { expectedAuthorizationRevision: 3, readPermissionAllowed: true,
      licenseInfo: "Code Apache-2.0; prose BY-ND-3.0", usageAuthorization: "ALLOWED" });
  });

  it("缺少许可证的已有分析仍可选择，但需显式补齐授权才允许发起规划", async () => {
    const s = session(); s.profile = { ...profile(), revision: 2, status: "CONFIRMED" }; s.directions = [{ ...direction(), status: "SELECTED" }];
    s.repositories = [repository()]; s.assets = [{ ...asset(), licenseInfo: null }];
    const generate = vi.spyOn(s, "generatePlan").mockResolvedValue();
    const root = mount(PlanView, { s });
    const select = root.querySelector("select")!;
    select.value = "repo-1"; select.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
    const generateButton = () => [...root.querySelectorAll("button")].find(item => item.textContent?.includes("生成演化计划"))!;
    expect(generateButton().disabled).toBe(true);
    expect(root.textContent).toContain("无需重新分析或重新生成方向");
    s.assets = [{ ...asset(), authorizationRevision: 1 }]; await nextTick();
    expect(generateButton().disabled).toBe(false);
    expect(generate).not.toHaveBeenCalled();
  });

  it("服务端已登记的拒绝授权不会被新输入 ALLOWED 覆盖，也不自动分析", async () => {
    const s = session();
    const existing = { ...asset(), readPermissionAllowed: false, usageAuthorization: "DENIED" as const };
    const lookup = vi.spyOn(api, "assetsAtLocation").mockResolvedValue([existing]);
    const register = vi.spyOn(api, "registerAsset");
    const analyze = vi.spyOn(api, "analyze");
    await s.register({ ...asset(), usageAuthorization: "ALLOWED" });
    expect(lookup).toHaveBeenCalledOnce(); expect(register).not.toHaveBeenCalled();
    expect(analyze).not.toHaveBeenCalled(); expect(s.assets[0]?.usageAuthorization).toBe("DENIED");
    await s.register(asset());
    expect(lookup).toHaveBeenCalledOnce(); expect(s.ids.assetIds).toEqual(["asset-1"]);
  });

  it("请求处理中阻止重复登记，并发冲突只查询原记录，不自动重发", async () => {
    const s = session();
    vi.spyOn(api, "assetsAtLocation").mockResolvedValueOnce([]).mockResolvedValueOnce([asset()]);
    const register = vi.spyOn(api, "registerAsset").mockRejectedValue(new ApiError(409, "ASSET_ALREADY_REGISTERED"));
    await Promise.all([s.register(asset()), s.register(asset())]);
    expect(register).toHaveBeenCalledOnce(); expect(s.assets).toHaveLength(1);
    expect(s.error).toContain("原记录");
  });

  it("授权更新保留分析和方向绑定；旧版本失败读取当前授权但不自动重试", async () => {
    const s = session(); s.assets = [asset()]; s.repositories = [repository()]; s.directions = [{ ...direction(), status: "SELECTED" }];
    const originalRepository = s.repositories[0]; const originalDirection = s.directions[0];
    const save = vi.spyOn(api, "updateAssetAuthorization").mockResolvedValue({ ...asset(), authorizationRevision: 1 });
    const update = { expectedAuthorizationRevision: 0, readPermissionAllowed: true, licenseInfo: "MIT", usageAuthorization: "ALLOWED" as const };
    await s.updateAssetAuthorization("asset-1", update);
    expect(s.repositories[0]).toBe(originalRepository); expect(s.directions[0]).toBe(originalDirection);
    expect(s.assets[0]?.authorizationRevision).toBe(1);
    save.mockRejectedValue(new ApiError(409, "ASSET_AUTHORIZATION_CONFLICT"));
    vi.spyOn(api, "asset").mockResolvedValue({ ...asset(), authorizationRevision: 2, usageAuthorization: "DENIED" });
    await s.updateAssetAuthorization("asset-1", update);
    expect(save).toHaveBeenCalledTimes(2); expect(s.error).toContain("未提交");
    expect(s.assets[0]?.usageAuthorization).toBe("DENIED");
    expect(s.directions[0]?.status).toBe("SELECTED");
  });

  it("API 按真实字段发出授权请求，错误只展示安全固定文案", async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify(asset())));
    vi.stubGlobal("fetch", fetcher);
    await api.updateAssetAuthorization("asset-1", { expectedAuthorizationRevision: 4, readPermissionAllowed: true,
      licenseInfo: "Code MIT", usageAuthorization: "ALLOWED" });
    expect(fetcher).toHaveBeenCalledWith("/api/software-assets/asset-1/authorization", expect.objectContaining({
      method: "PUT", body: JSON.stringify({ expectedAuthorizationRevision: 4, readPermissionAllowed: true,
        licenseInfo: "Code MIT", usageAuthorization: "ALLOWED" }),
    }));
    fetcher.mockImplementation(async () => new Response(JSON.stringify({ code: "ASSET_LICENSE_INFORMATION_REQUIRED",
      message: "private server content" }), { status: 409 }));
    await expect(api.generatePlan("direction", "asset", "profile")).rejects.toThrow("补齐许可证");
  });
});
