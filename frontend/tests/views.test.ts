import { createApp, nextTick, reactive, type Component } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import DiscoveryView from "../src/views/DiscoveryView.vue";
import DirectionsView from "../src/views/DirectionsView.vue";
import PlanView from "../src/views/PlanView.vue";
import RepositoryView from "../src/views/RepositoryView.vue";
import EvolutionView from "../src/views/EvolutionView.vue";
import { createSession, type WorkspaceSession } from "../src/state/session";
import {
  copy,
  asset,
  direction,
  plan,
  profile,
  repository,
  successfulPlan,
} from "./fixtures";
const cleanup: (() => void)[] = [];
function mount(component: Component, s: WorkspaceSession) {
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(component, { s });
  app.mount(root);
  cleanup.push(() => {
    app.unmount();
    s.dispose();
  });
  return root;
}
const session = () => reactive(createSession());
const button = (root: HTMLElement, text: string) =>
  [...root.querySelectorAll("button")].find((item) =>
    item.textContent?.includes(text),
  )!;
const settle = async () => {
  for (let i = 0; i < 20; i++) {
    await Promise.resolve();
    await nextTick();
  }
};
afterEach(() => cleanup.splice(0).forEach((fn) => fn()));
describe("五阶段 Vue 交互（合成 API 夹具）", () => {
  it("审阅主体显示六区实际内容、当前版本、全部依据和待核实状态，纠正使勾选失效", async () => {
    const s = session();
    s.profile = { ...profile(), status: "REVIEWING", revision: 7, interests: ["战绩趋势"],
      behaviors: ["每周战队练习"], painPoints: ["手工统计耗时"], technicalCapabilities: ["完成过Java接口"],
      projectGoals: ["六周完成可展示作品"], constraints: ["每周五小时"], evidence: [
        { sourceType: "USER_INPUT", sourceRef: "每周五小时做Java作品", claim: "可投入五小时", confidence: null, confirmed: false },
      ] };
    s.sufficiency = { sufficient: true, missingAreas: [], nextQuestion: null };
    const root = mount(DiscoveryView, s);
    const review = root.querySelector(".profile-review")!;
    for (const text of ["画像版本 7", "战绩趋势", "每周战队练习", "手工统计耗时", "完成过Java接口", "六周完成可展示作品", "每周五小时", "可投入五小时", "每周五小时做Java作品", "提取结论待核对"]) {
      expect(review.textContent).toContain(text);
    }
    expect(review.querySelector(".evidence")?.hasAttribute("open")).toBe(true);
    expect(root.querySelector("form")?.style.display).toBe("none");
    expect(button(root, "返回探索并提问")).toBeTruthy();
    const check = root.querySelector(".review-box input") as HTMLInputElement;
    check.checked = true; check.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
    expect(button(root, "确认当前画像").disabled).toBe(false);
    s.profile = { ...s.profile, revision: 8, constraints: ["改为两小时"] }; s.sufficiency = null; await nextTick();
    expect(button(root, "确认当前画像").disabled).toBe(true);
    expect(review.textContent).toContain("画像版本 8"); expect(review.textContent).toContain("改为两小时");
    expect(review.textContent).toContain("不会重构评估结果");
  });
  it("充分后的两个选择明确，继续探索显示实际问题且不重复请求", async () => {
    const s = session(); s.profile = profile(); s.ids.profileId = "profile-1";
    s.sufficiency = { sufficient: true, missingAreas: [], nextQuestion: null };
    const fetcher = vi.fn(async (_url: string, _options?: RequestInit) => new Response(JSON.stringify({
      revision: 1, sufficient: true, missingAreas: [], nextQuestion: "你希望先让谁使用这个项目？",
    })));
    vi.stubGlobal("fetch", fetcher); const root = mount(DiscoveryView, s);
    expect(button(root, "进入画像审阅")).toBeTruthy();
    button(root, "继续深入探索").click(); await settle();
    expect(root.querySelector(".chat-log")?.textContent).toContain("你希望先让谁使用这个项目？");
    expect(root.querySelector(".review-box")?.textContent).not.toContain("你希望先让谁使用这个项目？");
    button(root, "继续深入探索").click(); await settle();
    expect(fetcher).toHaveBeenCalledTimes(1); expect(root.querySelectorAll(".message.agent")).toHaveLength(1);
  });
  it("单独评估的 nextQuestion 进入左侧聊天，右侧仅展示缺失信息与审阅操作", async () => {
    const s = session(); s.profile = profile(); s.ids.profileId = "profile-1";
    const fetcher = vi.fn(async (url: string) => new Response(JSON.stringify(
      url.endsWith("sufficiency-assessment")
        ? { sufficient: false, missingAreas: ["尚不明确具体操作环节"], nextQuestion: "最近一次集卡哪里麻烦？", profileStatus: "EXPLORING" }
        : profile(),
    )));
    vi.stubGlobal("fetch", fetcher); const root = mount(DiscoveryView, s);
    button(root, "进入画像审阅").click(); await settle();
    button(root, "进入画像审阅").click(); await settle();
    expect(root.querySelectorAll(".message.agent")).toHaveLength(1);
    expect(root.querySelector(".chat-log")?.textContent).toContain("最近一次集卡哪里麻烦？");
    expect(root.querySelector(".review-box")?.textContent).not.toContain("最近一次集卡哪里麻烦？");
    expect(root.querySelector(".review-box")?.textContent).toContain("尚不明确具体操作环节");
    expect(root.querySelector("textarea")?.disabled).toBe(false);
    expect(root.textContent).toContain("画像版本 1");
  });
  it("仓库分析结果按业务字段展示，保留 analyzedRevision 与 Evidence", async () => {
    const s = session(); s.assets = [asset()]; s.ids.assetIds = ["asset-1"];
    const fetcher = vi.fn(async (_url: string, _options?: RequestInit) => new Response(JSON.stringify(repository())));
    vi.stubGlobal("fetch", fetcher); const root = mount(RepositoryView, s);
    button(root, "分析仓库（调用 AI）").click(); await settle();
    expect(fetcher.mock.calls[0]?.[0]).toBe("/api/software-assets/asset-1/analysis");
    expect(fetcher.mock.calls[0]?.[1]?.body).toBeUndefined();
    expect(root.textContent).toContain("Java 报表工具"); expect(root.textContent).toContain("ReportService");
    expect(root.textContent).toContain("只能手动执行"); expect(root.textContent).toContain("a".repeat(40));
    expect(root.textContent).toContain("源码与分析依据");
  });
  it("对话只显示实际 nextQuestion，Profile Confirm 必须审阅且使用当前 revision", async () => {
    const s = session();
    s.profile = profile();
    s.ids.profileId = "profile-1";
    const reviewed = {
      ...profile(),
      revision: 4,
      status: "EXPLORING",
      interests: ["数据可视化"],
      painPoints: ["重复导出"],
    };
    const fetcher = vi.fn(
      async (url: string, _options?: RequestInit) =>
        new Response(
          JSON.stringify(
            url.endsWith("discovery-turn")
              ? {
                  profile: reviewed,
                  sufficient: true,
                  missingAreas: [],
                  nextQuestion: null,
                }
              : url.endsWith("sufficiency-assessment")
                ? { sufficient: true, missingAreas: [], nextQuestion: null, profileStatus: "REVIEWING" }
                : url.includes("/confirm")
                  ? { ...reviewed, status: "CONFIRMED" }
                  : { ...reviewed, status: "REVIEWING" },
          ),
        ),
    );
    vi.stubGlobal("fetch", fetcher);
    const root = mount(DiscoveryView, s);
    const input = root.querySelector("textarea")!;
    input.value = "我经常重复导出报表";
    input.dispatchEvent(new Event("input", { bubbles: true }));
    root
      .querySelector("form")!
      .dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
    await settle();
    expect(root.textContent).toContain("重复导出");
    expect(root.querySelectorAll(".message.agent")).toHaveLength(0);
    expect(root.textContent).toContain("本页没有可继续回答的下一问");
    expect(root.querySelector("textarea")?.disabled).toBe(false);
    button(root, "进入画像审阅").click(); await settle();
    const confirm = button(root, "确认当前画像");
    expect(confirm.disabled).toBe(true);
    const check = root.querySelector(".review-box input") as HTMLInputElement;
    check.checked = true;
    check.dispatchEvent(new Event("change", { bubbles: true }));
    await nextTick();
    confirm.click();
    await settle();
    expect(
      fetcher.mock.calls.some(
        ([url, options]) =>
          url.endsWith("/confirm") && options?.body === '{"revision":4}',
      ),
    ).toBe(true);
    expect(root.querySelector(".badge")?.textContent?.trim()).toBe("已确认");
    expect(root.querySelector("textarea")?.disabled).toBe(true);
    expect(root.querySelectorAll(".review-box input")).toHaveLength(0);
    expect(button(root, "纠正").disabled).toBe(true);
  });
  it("继续探索并重新进入审阅后，即使版本没变也要重新勾选审阅", async () => {
    const s = session(); s.profile = { ...profile(), status: "REVIEWING" };
    const root = mount(DiscoveryView, s);
    const check = root.querySelector(".review-box input") as HTMLInputElement;
    check.checked = true; check.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
    expect(button(root, "确认当前画像").disabled).toBe(false);
    s.profile = profile(); await nextTick();
    expect(root.querySelector("textarea")?.disabled).toBe(false);
    s.profile = { ...profile(), status: "REVIEWING" }; await nextTick();
    expect(button(root, "确认当前画像").disabled).toBe(true);
    s.profile = { ...profile(), status: "CONFIRMED" }; await nextTick();
    expect(root.querySelector("textarea")?.disabled).toBe(true);
  });
  it("仓库授权没有预选，绝对路径及权限是明确表单输入", async () => {
    const s = session();
    const register = vi.spyOn(s, "register").mockResolvedValue();
    const root = mount(RepositoryView, s);
    expect(button(root, "注册此仓库").disabled).toBe(true);
    expect(
      [...root.querySelectorAll("select")].every(
        (select) => select.value === "",
      ),
    ).toBe(true);
    const path = root.querySelector("#repo-path") as HTMLInputElement;
    path.value = "C:\\projects\\authorized";
    path.dispatchEvent(new Event("input", { bubbles: true }));
    const permission = root.querySelector(
      "#read-permission",
    ) as HTMLSelectElement;
    permission.value = "true";
    permission.dispatchEvent(new Event("change", { bubbles: true }));
    const auth = root.querySelector("#usage-auth") as HTMLSelectElement;
    auth.value = "UNCLEAR";
    auth.dispatchEvent(new Event("change", { bubbles: true }));
    await nextTick();
    button(root, "注册此仓库").click();
    await settle();
    expect(register).toHaveBeenCalledWith({
      location: "C:\\projects\\authorized",
      readPermissionAllowed: true,
      licenseInfo: null,
      usageAuthorization: "UNCLEAR",
    });
    expect(
      [...root.querySelectorAll("input,select")].every(
        (input) => !!root.querySelector(`label[for="${input.id}"]`),
      ),
    ).toBe(true);
  });
  it("候选不会自动选择，Select / Reject 要经过独立确认", async () => {
    const s = session();
    s.profile = { ...profile(), revision: 2, status: "CONFIRMED" };
    s.directions = [direction()];
    const decide = vi.spyOn(s, "decide").mockResolvedValue();
    const root = mount(DirectionsView, s);
    await settle();
    expect(decide).not.toHaveBeenCalled();
    expect(root.textContent).toContain("可配置的定时报表工具");
    button(root, "选择此方向").click();
    await nextTick();
    expect(decide).not.toHaveBeenCalled();
    expect(root.querySelector("dialog")?.hasAttribute("open")).toBe(true);
    button(root, "明确选择").click();
    await settle();
    expect(decide).toHaveBeenCalledWith("direction-1", "select");
    button(root, "拒绝").click();
    await nextTick();
    button(root, "明确拒绝").click();
    await settle();
    expect(decide).toHaveBeenCalledWith("direction-1", "reject");
  });
  it("Plan Prepare 不等于 Step 授权，前一步未完成时禁止后续授权", async () => {
    const s = session();
    s.plan = plan();
    s.plan.status = "PROPOSED";
    s.plan.workingCopyId = null;
    s.plan.steps[0]!.status = "PENDING_CONFIRMATION";
    const prepare = vi.spyOn(s, "prepare").mockResolvedValue();
    const confirm = vi.spyOn(s, "confirmStep").mockResolvedValue();
    const root = mount(PlanView, s);
    expect(root.textContent).toContain("新增调度配置");
    expect(root.textContent).toContain("调度测试通过");
    expect(button(root, "Prepare WorkingCopy").disabled).toBe(true);
    expect(button(root, "审阅并授权此 Step").disabled).toBe(true);
    const review = root.querySelector(".prepare-box input") as HTMLInputElement;
    review.checked = true;
    review.dispatchEvent(new Event("change", { bubbles: true }));
    await nextTick();
    button(root, "Prepare WorkingCopy").click();
    await nextTick();
    button(root, "准备 WorkingCopy").click();
    await settle();
    expect(prepare).toHaveBeenCalledOnce();
    expect(confirm).not.toHaveBeenCalled();
    s.plan.status = "ACTIVE";
    s.copy = copy();
    await nextTick();
    button(root, "审阅并授权此 Step").click();
    await nextTick();
    button(root, "明确授权 Step").click();
    await settle();
    expect(confirm).toHaveBeenCalledWith("step-1");
    s.plan.steps.push({ ...s.plan.steps[0]!, id: "step-2" });
    await nextTick();
    expect(
      [...root.querySelectorAll("button")]
        .filter((item) => item.textContent?.includes("审阅并授权此 Step"))
        .at(-1)?.disabled,
    ).toBe(true);
  });
  it("风险确认前不会 execute，断线 READY 展示待核对并保持禁用", async () => {
    const s = session();
    s.plan = plan();
    s.copy = copy();
    const execute = vi.spyOn(s, "execute").mockResolvedValue();
    const root = mount(EvolutionView, s);
    button(root, "审阅风险并执行此 Step").click();
    await nextTick();
    const confirm = button(root, "执行当前 Step");
    expect(confirm.disabled).toBe(true);
    expect(execute).not.toHaveBeenCalled();
    const check = root.querySelector("dialog input") as HTMLInputElement;
    check.checked = true;
    check.dispatchEvent(new Event("change", { bubbles: true }));
    await nextTick();
    confirm.click();
    await settle();
    expect(execute).toHaveBeenCalledWith("step-1");
    s.receipt = {
      planId: "plan-1",
      stepId: "step-1",
      startedAt: "2026-10-10T00:00:00Z",
    };
    await nextTick();
    expect(root.textContent).toContain("执行结果待核对");
    expect(root.textContent).toContain("READY");
    expect(root.textContent).not.toContain("PREPARING");
    expect(button(root, "审阅风险并执行此 Step").disabled).toBe(true);
  });
  it("显示保存的 Verification 动作与 Candidate，不执行下一 Step，不解析 HTML", async () => {
    const s = session();
    s.plan = successfulPlan();
    s.plan.steps[0]!.executionResult!.changes = [
      "<img src=x onerror=alert(1)>",
    ];
    s.copy = {
      ...copy(),
      currentRevision: "b".repeat(40),
      lastVerifiedRevision: "b".repeat(40),
    };
    const execute = vi.spyOn(s, "execute");
    const root = mount(EvolutionView, s);
    expect(root.textContent).toContain("TEST");
    expect(root.textContent).toContain("src/ReportService.java");
    expect(root.textContent).toContain(
      "lastVerifiedRevision 与该 Candidate 一致",
    );
    expect(root.querySelector("img")).toBeNull();
    expect(execute).not.toHaveBeenCalled();
    expect(button(root, "审阅风险并执行此 Step").disabled).toBe(true);
  });
});
