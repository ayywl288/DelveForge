import { createApp, h, KeepAlive, nextTick, reactive, type Component } from "vue";
import { afterEach, expect, it, vi } from "vitest";
import DetailPanel from "../src/components/DetailPanel.vue";
import PlanView from "../src/views/PlanView.vue";
import ProjectsView from "../src/views/ProjectsView.vue";
import RepositoryView from "../src/views/RepositoryView.vue";
import { api } from "../src/api/client";
import { createSession } from "../src/state/session";
import { fitFinishPlan, longProjectSummary, longRepository } from "./long-content-fixtures";

const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.restoreAllMocks(); });
function mount(component: Component, props: Record<string, unknown>) {
    const root = document.createElement("div"); document.body.append(root);
    const app = createApp({ render: () => h(component, props) }); app.mount(root);
    cleanup.push(() => { app.unmount(); root.remove(); });
    return root;
}
function session() {
    const s = reactive(createSession()); cleanup.push(s.dispose); return s;
}
function button(root: Element, text: string) {
    return [...root.querySelectorAll("button")].find(item => item.textContent?.includes(text))!;
}
async function settle() { await new Promise(resolve => setTimeout(resolve, 0)); await nextTick(); }
function projects() {
    const saved = longProjectSummary();
    vi.spyOn(api, "projects").mockResolvedValue([saved]); vi.spyOn(api, "resources").mockResolvedValue([]);
    return saved;
}

it("Inspector 完整展示警告和原值，Escape 关闭后回到项目详情入口，不触发业务请求", async () => {
    const saved = projects(), s = session();
    const execute = vi.spyOn(api, "execute"), prepare = vi.spyOn(api, "prepare"), remove = vi.spyOn(s, "deleteProject");
    const root = mount(ProjectsView, { s }); await settle();
    const trigger = button(root, "项目详情"); trigger.focus(); trigger.click(); await settle();
    const panel = root.querySelector<HTMLDialogElement>(".detail-panel")!;
    expect(panel.open).toBe(true);
    for (const value of [saved.key, saved.planId, saved.workingCopyId, saved.currentRevision, saved.lastVerifiedRevision]) {
        expect(panel.textContent).toContain(value);
    }
    for (const repo of saved.repositories) {
        expect(panel.textContent).toContain(repo.assetId); expect(panel.textContent).toContain(repo.location);
    }
    expect(panel.querySelector(".inspector-warnings")?.textContent).toContain("PROFILE_BASIS_CHANGED");
    expect(panel.querySelector(".project-management")).toBeTruthy();
    expect(root.querySelector(".project-row .project-metadata")).toBeNull();
    panel.dispatchEvent(new Event("cancel", { cancelable: true })); await settle();
    expect(panel.open).toBe(false); expect(document.activeElement).toBe(trigger);
    for (const spy of [execute, prepare, remove]) expect(spy).not.toHaveBeenCalled();
});

it("Inspector 删除先退出阅读面板，仅单独确认后提交；取消回到项目入口", async () => {
    projects(); const s = session(), remove = vi.spyOn(s, "deleteProject").mockResolvedValue(false);
    const root = mount(ProjectsView, { s }); await settle();
    const trigger = button(root, "项目详情"); trigger.focus(); trigger.click(); await settle();
    button(root.querySelector(".detail-panel")!, "删除项目").click(); await settle();
    const panels = [...root.querySelectorAll<HTMLDialogElement>("dialog")];
    expect(panels.filter(panel => panel.open)).toHaveLength(1);
    expect(root.querySelector<HTMLDialogElement>(".detail-panel")?.open).toBe(false);
    expect(root.querySelector("dialog[open]")?.textContent).toContain("从工作台删除项目？");
    expect(remove).not.toHaveBeenCalled();
    button(root.querySelector("dialog[open]")!, "保留项目").click(); await settle();
    expect(document.activeElement).toBe(trigger); expect(remove).not.toHaveBeenCalled();
    trigger.click(); await settle(); button(root.querySelector(".detail-panel")!, "删除项目").click(); await settle();
    button(root.querySelector("dialog[open]")!, "确认删除项目").click(); await settle();
    expect(remove).toHaveBeenCalledOnce(); expect(remove.mock.calls[0]?.[0].key).toBe("project:long-content");
});

it("READY 或未验证版本阻止 Inspector 删除，必需提示在列表和面板均可读", async () => {
    const saved = projects(); saved.currentRevision = "c".repeat(40);
    saved.stepCounts = { SUCCEEDED: 1, READY: 1, PENDING_CONFIRMATION: 1 };
    const s = session(), root = mount(ProjectsView, { s }); await settle();
    expect(root.querySelector(".project-row .deletion-blocked")?.textContent).toContain("不要重复 execute");
    button(root, "项目详情").click(); await settle();
    const panel = root.querySelector(".detail-panel")!;
    expect(button(panel, "删除项目").disabled).toBe(true);
    expect(panel.querySelector(".project-management .deletion-blocked")?.textContent).toContain("未决执行");
    expect(panel.querySelector(".project-meta-grid")?.textContent).toContain("c".repeat(40));
});

it("刷新失去项目时关闭 Inspector；保留同身份时显示更新数据，不复用旧元数据", async () => {
    projects(); const s = session(), root = mount(ProjectsView, { s }); await settle();
    const trigger = button(root, "项目详情"); trigger.focus(); trigger.click(); await settle();
    s.projects = [{ ...s.projects[0]!, name: "更新后的同身份", version: 2 }]; await settle();
    expect(root.querySelector(".detail-panel h2")?.textContent).toBe("更新后的同身份");
    s.projects = []; await settle();
    expect(root.querySelector<HTMLDialogElement>(".detail-panel")?.open).toBe(false);
    expect(root.querySelector(".detail-panel .project-metadata")).toBeNull();
    expect(document.activeElement?.id).toBe("history-heading");
});

it("长 Current/Target State 原文仅进入阅读面板，路线、审阅与准备仍在原位置且不会授权", async () => {
    vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "C:/synthetic/workspaces", existingRoots: [] });
    const s = session(); s.plan = fitFinishPlan();
    const prepare = vi.spyOn(s, "prepare"), confirm = vi.spyOn(s, "confirmStep"), root = mount(PlanView, { s });
    await settle();
    expect(root.querySelector(".state-comparison .content-list")).toBeNull();
    const prepareButton = button(root.querySelector(".prepare-box")!, "准备隔离副本"); expect(prepareButton.disabled).toBe(true);
    const current = button(root, "查看完整现状"); current.focus(); current.click(); await settle();
    let panel = root.querySelector<HTMLDialogElement>(".detail-panel")!;
    expect(panel.querySelector(".reading-text")?.textContent).toBe(s.plan.currentState.summary);
    expect(panel.querySelectorAll(".content-rows li")).toHaveLength(29);
    panel.dispatchEvent(new Event("cancel", { cancelable: true })); await settle();
    expect(document.activeElement).toBe(current);
    button(root, "查看完整目标").click(); await settle();
    panel = root.querySelector<HTMLDialogElement>(".detail-panel")!;
    for (const value of Object.values(s.plan.targetState)) expect(panel.textContent).toContain(value);
    expect(panel.querySelectorAll(".content-rows li")).toHaveLength(15);
    expect(prepareButton.disabled).toBe(true); expect(root.querySelectorAll(".roadmap-step")).toHaveLength(1);
    expect(prepare).not.toHaveBeenCalled(); expect(confirm).not.toHaveBeenCalled();
});

it("规划依据面板保留分页及绑定，切换 Plan 身份清除旧阅读状态", async () => {
    vi.spyOn(api, "workspaceConfiguration").mockResolvedValue({ root: "C:/synthetic", existingRoots: [] });
    const s = session(); s.plan = fitFinishPlan(); const root = mount(PlanView, { s }); await settle();
    button(root, "规划改动、风险").click(); await settle();
    const panel = root.querySelector<HTMLDialogElement>(".detail-panel")!;
    expect(panel.querySelectorAll(".evidence-entry")).toHaveLength(6);
    expect(panel.querySelector(".count-pill")?.textContent).toBe("27");
    expect(panel.querySelector(".project-meta-grid")?.textContent).toContain(s.plan.baseRepositoryProfileId);
    s.plan = { ...fitFinishPlan(), id: "another-plan" }; await settle();
    expect(panel.open).toBe(false); expect(panel.textContent).not.toContain("plan-1");
});

it("仓库分区切换定位到阅读起点，保留导航焦点与正确分区，不触发分析", async () => {
    const s = session(); s.repositories = [longRepository()]; const analyze = vi.spyOn(s, "analyze");
    const root = mount(RepositoryView, { s }), start = root.querySelector<HTMLElement>(".reading-start")!;
    start.scrollIntoView = vi.fn();
    const control = button(root.querySelector(".local-navigation")!, "已有能力"); control.focus(); control.click(); await settle();
    expect(control.getAttribute("aria-pressed")).toBe("true");
    expect(root.querySelector<HTMLElement>("#repository-section-capabilities")?.style.display).not.toBe("none");
    expect(root.querySelector<HTMLElement>("#repository-section-overview")?.style.display).toBe("none");
    expect(start.scrollIntoView).toHaveBeenCalledWith({ block: "start" });
    expect(document.activeElement).toBe(control); expect(analyze).not.toHaveBeenCalled();
});

it("KeepAlive 离开视图关闭辅助面板，返回不会重新弹出或留下模态遮罩", async () => {
    const state = reactive({ visible: true, open: true });
    const host = { render: () => h(KeepAlive, null, { default: () => state.visible
        ? h(DetailPanel, { open: state.open, title: "辅助详情", onClose: () => { state.open = false; } })
        : h("div", "另一个视图") }) };
    const root = mount(host, {}); await settle();
    expect(root.querySelector<HTMLDialogElement>(".detail-panel")?.open).toBe(true);
    state.visible = false; await settle(); expect(state.open).toBe(false);
    state.visible = true; await settle(); expect(root.querySelector<HTMLDialogElement>(".detail-panel")?.open).toBe(false);
});
