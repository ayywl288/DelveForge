import { createApp, nextTick, reactive, type Component } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import DiscoveryView from "../src/views/DiscoveryView.vue";
import RepositoryView from "../src/views/RepositoryView.vue";
import DirectionsView from "../src/views/DirectionsView.vue";
import PlanView from "../src/views/PlanView.vue";
import EvolutionView from "../src/views/EvolutionView.vue";
import { api } from "../src/api/client";
import { createSession, type WorkspaceSession } from "../src/state/session";
import type { EvolutionStep, ProjectSummary } from "../src/api/types";
import { asset, copy, direction, plan, profile, repository, successfulPlan } from "./fixtures";

const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.restoreAllMocks(); });
const session = () => reactive(createSession());
function mount(component: Component, s: WorkspaceSession, extra: Record<string, unknown> = {}) {
    const root = document.createElement("div");
    document.body.append(root);
    const app = createApp(component, { s, ...extra });
    app.mount(root);
    cleanup.push(() => { app.unmount(); root.remove(); s.dispose(); });
    return root;
}
function button(root: Element, text: string) {
    return [...root.querySelectorAll("button")].find(item => item.textContent?.includes(text))!;
}
async function settle() {
    for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick(); }
}

describe("G2 画像工作区", () => {
    it("六维 Inspector 默认折叠条目，整体收起仍保留审阅入口，不发请求", async () => {
        const s = session(); s.profile = { ...profile(), interests: ["原始兴趣内容"] };
        const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
        const root = mount(DiscoveryView, s);
        const dimensions = root.querySelectorAll<HTMLDetailsElement>(".profile-panel .profile-section");
        expect(dimensions).toHaveLength(6);
        expect([...dimensions].every(item => !item.open)).toBe(true);
        expect(dimensions[0]?.textContent).toContain("原始兴趣内容");
        button(root, "收起画像").click(); await nextTick();
        expect(button(root, "展开画像").getAttribute("aria-expanded")).toBe("false");
        expect((root.querySelector("#profile-inspector") as HTMLElement).style.display).toBe("none");
        expect(button(root, "进入画像审阅")).toBeTruthy();
        expect(fetcher).not.toHaveBeenCalled();
    });

    it("独立审阅只展示一份六维内容，切回对话不能确认，纠正后必须重勾当前版本", async () => {
        const s = session(); s.profile = { ...profile(), status: "REVIEWING", revision: 3, interests: ["唯一审阅条目"] };
        const confirm = vi.spyOn(s, "confirmProfile").mockResolvedValue();
        const root = mount(DiscoveryView, s);
        expect(root.textContent?.match(/唯一审阅条目/g)).toHaveLength(1);
        expect(root.querySelectorAll(".inspector-index a")).toHaveLength(6);
        const check = root.querySelector<HTMLInputElement>(".review-box input")!;
        check.checked = true; check.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
        button(root, "本次对话").click(); await nextTick();
        expect(button(root, "确认当前画像").disabled).toBe(true);
        button(root, "画像审阅").click(); await nextTick();
        button(root, "确认当前画像").click(); await settle();
        expect(confirm).toHaveBeenCalledWith(3);
        s.profile = { ...s.profile, revision: 4 }; await nextTick();
        expect(button(root, "确认当前画像").disabled).toBe(true);
    });

    it("恢复 CONFIRMED 优先确认信息与下一阶段，不显示空会话或自动提问", async () => {
        const s = session(); s.profile = { ...profile(), status: "CONFIRMED", revision: 9 };
        const next = vi.fn(); const question = vi.spyOn(api, "discoveryQuestion");
        const root = mount(DiscoveryView, s, { onNext: next });
        expect(root.querySelector(".confirmed-profile")?.textContent).toContain("画像版本 9");
        expect((root.querySelector(".chat-log") as HTMLElement).style.display).toBe("none");
        expect(button(root, "本次对话")).toBeUndefined();
        expect(button(root, "纠正").disabled).toBe(true);
        button(root, "理解我的仓库").click(); await nextTick();
        expect(next).toHaveBeenCalledOnce(); expect(question).not.toHaveBeenCalled();
    });
});

describe("G2 仓库 Explorer", () => {
    it("保存分析优先分区阅读，注册默认收起；快照切换和 Evidence 阅读不调用分析", async () => {
        const s = session(); s.assets = [asset()];
        s.repositories = [repository(), { ...repository(), id: "repo-2", purpose: "另一份原始分析", techStack: ["原始技术栈"],
            analyzedRevision: "c".repeat(40), evidence: [{ sourceType: "REPOSITORY", sourceRef: "src/" + "long-path/".repeat(25),
                claim: "原始证据说明", confidence: 0.8, confirmed: false }] }];
        const analyze = vi.spyOn(s, "analyze");
        const root = mount(RepositoryView, s);
        expect(root.querySelector<HTMLDetailsElement>(".repository-setup")?.open).toBe(false);
        expect(root.querySelector(".repository-explorer")?.textContent).toContain(asset().location);
        const field = root.querySelector<HTMLSelectElement>("#repository-snapshot")!;
        field.value = "repo-2"; field.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
        expect(root.querySelector(".explorer-heading")?.textContent).toContain("另一份原始分析");
        button(root.querySelector(".local-navigation")!, "结构与技术").click(); await nextTick();
        expect((root.querySelector("#repository-section-overview") as HTMLElement).style.display).toBe("none");
        expect((root.querySelector("#repository-section-structure") as HTMLElement).style.display).toBe("");
        expect(root.querySelector("#repository-section-structure")?.textContent).toContain("原始技术栈");
        button(root.querySelector(".local-navigation")!, "分析依据").click(); await nextTick();
        expect(root.querySelector<HTMLDetailsElement>("#repository-section-evidence .evidence")?.open).toBe(true);
        expect(root.querySelector("#repository-section-evidence")?.textContent).toContain("原始证据说明");
        expect(root.querySelector("#repository-section-evidence")?.textContent).toContain("long-path/".repeat(25));
        expect(analyze).not.toHaveBeenCalled();
    });

    it("无分析时展开明确授权，已分析时按目标 Asset ID 展开授权", () => {
        const s = session(); s.assets = [asset(), { ...asset(), id: "asset-other" }];
        const root = mount(RepositoryView, s);
        expect(root.querySelector<HTMLDetailsElement>(".repository-setup")?.open).toBe(true);
        const other = session(); other.assets = [...s.assets]; other.repositories = [repository()];
        const target = mount(RepositoryView, other, { authorizationTargetId: "asset-1" });
        expect(target.querySelector<HTMLDetailsElement>(".repository-setup")?.open).toBe(true);
        expect(target.querySelector<HTMLDetailsElement>("#asset-asset-1 .authorization-editor")?.open).toBe(true);
        expect(target.querySelector<HTMLDetailsElement>("#asset-asset-other .authorization-editor")?.open).toBe(false);
    });
});

describe("G2 方向主从视图", () => {
    it("窄屏查看方向后焦点到达详情，仍不触发 Domain 选择", async () => {
        vi.stubGlobal("innerWidth", 390);
        const s = session(); s.directions = [direction(), { ...direction(), id: "direction-mobile", title: "窄屏候选" }];
        const decide = vi.spyOn(s, "decide"); const root = mount(DirectionsView, s);
        button(root.querySelector(".direction-list")!, "窄屏候选").click(); await settle();
        expect(document.activeElement?.id).toBe("direction-detail");
        expect(root.querySelector(".direction-detail")?.textContent).toContain("窄屏候选");
        expect(decide).not.toHaveBeenCalled();
    });

    it("点击不同候选与历史只改变查看对象，Select 仍需独立确认且针对被查看的 ID", async () => {
        const s = session(); s.profile = { ...profile(), revision: 2, status: "CONFIRMED" };
        s.directions = [direction(), { ...direction(), id: "direction-2", title: "另一个候选", technicalValue: "完整技术价值" },
            { ...direction(), id: "old-direction", status: "SUPERSEDED", title: "原历史方向" }];
        const decide = vi.spyOn(s, "decide").mockResolvedValue();
        const root = mount(DirectionsView, s);
        expect(root.querySelector<HTMLDetailsElement>(".direction-history")?.open).toBe(false);
        button(root.querySelector(".direction-list")!, "另一个候选").click(); await nextTick();
        expect(root.querySelector(".direction-detail")?.textContent).toContain("完整技术价值");
        expect(s.selected).toBeNull(); expect(decide).not.toHaveBeenCalled();
        button(root, "选择此方向").click(); await nextTick();
        expect(decide).not.toHaveBeenCalled();
        button(root, "明确选择").click(); await settle();
        expect(decide).toHaveBeenCalledExactlyOnceWith("direction-2", "select");
        button(root.querySelector(".direction-history")!, "原历史方向").click(); await nextTick();
        expect(root.querySelector(".direction-detail")?.textContent).toContain("SUPERSEDED");
        expect(button(root, "选择此方向")).toBeUndefined();
        expect(button(root.querySelector(".direction-detail")!, "拒绝")).toBeUndefined();
        expect(decide).toHaveBeenCalledTimes(1);
    });

    it("过期依据的查看不改变 Domain 状态，选择被禁用但仍可显式拒绝", () => {
        const s = session(); s.profile = { ...profile(), revision: 8, status: "CONFIRMED" }; s.directions = [direction()];
        const root = mount(DirectionsView, s);
        expect(button(root, "选择此方向").disabled).toBe(true);
        expect(button(root, "拒绝").disabled).toBe(false);
        expect(root.querySelector(".direction-detail")?.textContent).toContain("画像与此候选的依据版本不同");
        expect(s.directions[0]?.status).toBe("CANDIDATE");
    });
});

describe("G2 路线与执行分区", () => {
    it("规划历史选择只打开对应轮次，不生成或授权计划", async () => {
        const s = session(); s.plan = plan();
        s.projectContext = { id: "current", name: "合成项目", userProfileId: "profile-1", assetIds: ["asset-1"],
            repositoryProfileIds: ["repo-1"], directionIds: ["direction-1"], planId: "plan-1", version: 0,
            flowId: "flow-1", previousContextId: "previous", createdAt: "", updatedAt: "", deletedAt: null };
        s.planningRounds = [{ key: "project:previous", planId: "historical-plan", planStatus: "SUPERSEDED",
            totalSteps: 3, succeededSteps: 1, deletedAt: null } as ProjectSummary];
        vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: null, existingRoots: [] });
        const open = vi.spyOn(s, "openProject").mockResolvedValue(null);
        const generate = vi.spyOn(s, "generatePlan"); const confirm = vi.spyOn(s, "confirmStep");
        const root = mount(PlanView, s);
        button(root.querySelector(".planning-history")!, "计划 historic").click(); await settle();
        expect(open).toHaveBeenCalledExactlyOnceWith("project:previous", false);
        expect(generate).not.toHaveBeenCalled(); expect(confirm).not.toHaveBeenCalled();
    });

    it("Step 内联展开只打开一项，可折叠；历史 Plan 切换保留基线但不授权", async () => {
        const s = session(); s.plan = plan(); s.copy = copy();
        s.plan.steps.push({ ...s.plan.steps[0]!, id: "step-2", goal: "第二个原始目标", status: "PENDING_CONFIRMATION" });
        vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "E:\\synthetic", existingRoots: [] });
        const confirm = vi.spyOn(s, "confirmStep"); const prepare = vi.spyOn(s, "prepare");
        const root = mount(PlanView, s);
        expect(root.querySelectorAll(".step-content")).toHaveLength(1);
        const toggles = root.querySelectorAll<HTMLButtonElement>(".roadmap-toggle");
        toggles[1]!.click(); await nextTick();
        expect(toggles[0]?.getAttribute("aria-expanded")).toBe("false");
        expect(root.querySelector(".step-content")?.id).toBe("plan-step-step-2");
        expect(button(root, "审阅并授权此 Step").disabled).toBe(true);
        toggles[1]!.click(); await nextTick();
        expect(root.querySelectorAll(".step-content")).toHaveLength(0);
        s.plan = { ...successfulPlan(), id: "historical-plan", status: "SUPERSEDED" }; await nextTick();
        expect(root.querySelector(".step-content")?.textContent).toContain("a".repeat(40));
        expect(root.textContent).toContain("保留为历史记录");
        expect(confirm).not.toHaveBeenCalled(); expect(prepare).not.toHaveBeenCalled();
    });

    it("待授权显示真实前序前提与规划入口，导航不执行或自动授权", async () => {
        const s = session(); s.plan = plan(); s.copy = copy(); s.plan.steps[0]!.status = "PENDING_CONFIRMATION";
        const planning = vi.fn(); const execute = vi.spyOn(s, "execute"); const confirm = vi.spyOn(s, "confirmStep");
        const root = mount(EvolutionView, s, { onPlanning: planning });
        expect(root.querySelector("#execution-goal")?.textContent).toContain("尚未授权");
        expect(root.querySelector(".step-prerequisites")?.textContent).toContain("当前是第一步");
        expect(button(root, "审阅风险并执行")).toBeUndefined();
        button(root, "前往规划页").click(); await nextTick();
        expect(planning).toHaveBeenCalledOnce(); expect(confirm).not.toHaveBeenCalled(); expect(execute).not.toHaveBeenCalled();
    });

    it("Candidate、验证与可信 revision 分开显示，VERIFYING 不呈现成功，异常副本阻止执行", async () => {
        const s = session(); s.plan = { ...successfulPlan(), status: "ACTIVE" };
        s.plan.steps[0]!.status = "VERIFYING"; s.plan.steps[0]!.verificationResult = null;
        s.copy = { ...copy(), currentRevision: "b".repeat(40) };
        const root = mount(EvolutionView, s);
        expect(root.querySelector("#execution-result")?.textContent).toContain("b".repeat(40));
        expect(root.querySelector("#verification-result")?.textContent).toContain("a".repeat(40));
        expect(root.querySelector("#verification-result")?.textContent).toContain("尚无 VerificationResult");
        expect(root.querySelector(".success-note")).toBeNull();
        expect(root.querySelector(".execution-action")?.textContent).toContain("Candidate 尚不能视为验证通过");
        expect(button(root, "审阅风险并执行")).toBeUndefined();
        s.plan.steps[0]!.status = "READY"; await nextTick();
        expect(root.querySelector(".execution-action")?.textContent).toContain("revision 不一致");
        s.copy.status = "RECOVERY_REQUIRED"; await nextTick();
        expect(root.querySelector(".execution-action.inline-note")?.textContent).toContain("没有恢复操作");
        expect(button(root, "审阅风险并执行").disabled).toBe(true);
    });

    it("Step Navigator 切换历史成功项与未授权项仅改变阅读，结果和下一步授权保持独立", async () => {
        const s = session(); s.plan = { ...successfulPlan(), status: "ACTIVE" }; s.copy = copy();
        s.plan.steps.push({ ...plan().steps[0]!, id: "step-next", goal: "下一个步骤", status: "PENDING_CONFIRMATION" });
        const execute = vi.spyOn(s, "execute"); const root = mount(EvolutionView, s);
        expect(root.querySelector(".execution-detail > .panel-header")?.textContent).toContain("下一个步骤");
        button(root.querySelector(".execution-steps")!, "支持周期调度").click(); await nextTick();
        expect(root.querySelector(".execution-detail > .panel-header")?.textContent).toContain("SUCCEEDED");
        expect(root.querySelector("#verification-result")?.textContent).toContain("合成测试验证结果");
        expect(root.querySelector(".execution-action")?.textContent).toContain("不再执行");
        expect(execute).not.toHaveBeenCalled(); expect(s.plan.steps[1]?.status).toBe("PENDING_CONFIRMATION");
    });

    it.each(["EXECUTING", "VERIFYING", "FAILED", "VERIFICATION_FAILED", "ROLLING_BACK", "RECOVERY_REQUIRED", "CANCELLED"] as EvolutionStep["status"][])(
        "后端状态 %s 保留原始代码、禁用执行且不生成成功信号", async status => {
            const s = session(); s.plan = plan(); s.copy = copy(); s.plan.steps[0]!.status = status;
            const root = mount(EvolutionView, s);
            expect(root.querySelector(".execution-detail > .panel-header .badge")?.getAttribute("data-status")).toBe(status);
            expect(button(root, "审阅风险并执行")).toBeUndefined();
            expect(root.querySelector(".success-note")).toBeNull();
            expect(root.textContent).not.toMatch(/PREPARING|\d+%/);
            if (["FAILED", "VERIFICATION_FAILED", "ROLLING_BACK", "RECOVERY_REQUIRED"].includes(status)) {
                expect(root.querySelector(".execution-action.inline-note")).not.toBeNull();
            }
            if (status === "CANCELLED") expect(root.querySelector(".execution-action")?.textContent).toContain("已取消");
        },
    );
});
