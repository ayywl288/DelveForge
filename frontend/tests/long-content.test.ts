import { createApp, h, nextTick, reactive, type Component } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import EvidenceList from "../src/components/EvidenceList.vue";
import DirectionsView from "../src/views/DirectionsView.vue";
import DiscoveryView from "../src/views/DiscoveryView.vue";
import RepositoryView from "../src/views/RepositoryView.vue";
import ProjectsView from "../src/views/ProjectsView.vue";
import { api } from "../src/api/client";
import type { Evidence, EvidenceBasis } from "../src/api/types";
import { createSession } from "../src/state/session";
import { longDirections, longProfile, longProjectSummary, longRepository, userEvidence } from "./long-content-fixtures";

const cleanup: (() => void)[] = [];
afterEach(() => {
    cleanup.splice(0).forEach(fn => fn());
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
});
function mount(component: Component, props: Record<string, unknown>) {
    const root = document.createElement("div");
    document.body.append(root);
    const app = createApp({ render: () => h(component, props) });
    app.mount(root);
    cleanup.push(() => { app.unmount(); root.remove(); });
    return root;
}
function session() {
    const s = reactive(createSession());
    cleanup.push(s.dispose);
    return s;
}
async function settle() {
    await new Promise(resolve => setTimeout(resolve, 0));
    await nextTick();
}
function button(root: Element, text: string) {
    return [...root.querySelectorAll("button")].find(item => item.textContent?.includes(text))!;
}
function indices(root: Element) {
    return [...root.querySelectorAll(".evidence-entry")].map(item => Number(item.getAttribute("data-record-index")));
}

describe("长 Evidence 的本地分页", () => {
    it("逐页保留全部 23 条的顺序、原文与元数据，不发请求或改变原集合", async () => {
        const items = userEvidence(), original = structuredClone(items), seen: number[] = [];
        const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
        const root = mount(EvidenceList, { items, title: "画像依据", expanded: true });
        expect(button(root, "上一页").disabled).toBe(true);
        for (let page = 1; page <= 4; page++) {
            expect(root.querySelector(".evidence-page-status")?.textContent).toContain(`第 ${page} / 4 页`);
            const current = indices(root); seen.push(...current);
            expect(current.length).toBe(page === 4 ? 5 : 6);
            for (const entry of root.querySelectorAll<HTMLElement>(".evidence-entry")) {
                const item = items[Number(entry.dataset.recordIndex) - 1]!;
                expect(entry.querySelector(".evidence-claim")?.textContent).toBe(item.claim);
                expect(entry.querySelector(".source-reference")?.textContent).toBe(item.sourceRef);
                expect(entry.textContent).toContain(`confirmed=${item.confirmed}`);
                expect(entry.querySelector(".evidence-record")?.textContent).toContain(`confidence=${item.confidence ?? "null"}`);
            }
            if (page < 4) {
                button(root, "下一页").click(); await settle();
                expect(document.activeElement).toBe(root.querySelector(".evidence-page-status"));
                expect([...root.querySelectorAll<HTMLDetailsElement>(".evidence-record")].every(item => !item.open)).toBe(true);
            }
        }
        expect(seen).toEqual(Array.from({ length: 23 }, (_, index) => index + 1));
        expect(button(root, "下一页").disabled).toBe(true);
        button(root, "上一页").click(); await settle();
        expect(indices(root)).toEqual([13, 14, 15, 16, 17, 18]);
        expect(items).toEqual(original); expect(fetcher).not.toHaveBeenCalled();
    });

    it("跳转末页与展开记录可检查完整出处，零值元数据和重复依据不会合并", async () => {
        const evidence: Evidence = { sourceType: "USER_INPUT", sourceRef: "完整引用\n" + "原始输入".repeat(80),
            claim: "保留完整判断".repeat(20), confidence: 0, confirmed: true };
        const bases: EvidenceBasis[] = Array.from({ length: 25 }, () => ({ evidence: { ...evidence },
            origin: { kind: "USER_PROFILE", userProfileId: "profile-origin", userProfileRevision: 0, repositoryProfileId: null } }));
        const root = mount(EvidenceList, { bases, items: userEvidence(1), expanded: true });
        const select = root.querySelector("select")!;
        select.value = "5"; select.dispatchEvent(new Event("change", { bubbles: true })); await settle();
        expect(indices(root)).toEqual([25]);
        (root.querySelector(".evidence-record > summary") as HTMLElement).click(); await settle();
        expect(root.querySelector<HTMLDetailsElement>(".evidence-record")?.open).toBe(true);
        expect(root.querySelector(".evidence-claim")?.textContent).toBe(evidence.claim);
        expect(root.querySelector(".source-reference")?.textContent).toBe(evidence.sourceRef);
        expect(root.textContent).toContain("confidence 0");
        expect(root.textContent).toContain("profile-origin"); expect(root.textContent).toContain("revision 0");
        expect(root.textContent).toContain("confirmed=true");
        expect(root.querySelector(".count-pill")?.textContent).toBe("25");
    });

    it("替换或缩短依据集合时重置页码和查看状态，不复用旧记录", async () => {
        const props = reactive({ items: userEvidence(), expanded: true });
        const root = mount(EvidenceList, props);
        const select = root.querySelector("select")!;
        select.value = "4"; select.dispatchEvent(new Event("change", { bubbles: true })); await settle();
        (root.querySelector(".evidence-record > summary") as HTMLElement).click(); await settle();
        props.items = [{ ...props.items[0]!, claim: "另一份画像的新依据", sourceRef: "新引用" }]; await settle();
        expect(indices(root)).toEqual([1]); expect(root.querySelector(".evidence-pagination")).toBeNull();
        expect(root.querySelector<HTMLDetailsElement>(".evidence-record")?.open).toBe(false);
        expect(root.querySelector(".source-reference")?.textContent).toBe("新引用");
        props.items.splice(0); await settle();
        expect(root.querySelectorAll(".evidence-entry")).toHaveLength(0);
        expect(root.textContent).toContain("没有可追溯的依据");
    });

    it("短集合不分页，逐条展开限制在当前记录，不推断判断关系", async () => {
        const root = mount(EvidenceList, { items: userEvidence(2), expanded: true });
        expect(root.querySelector(".evidence-pagination")).toBeNull();
        const summaries = root.querySelectorAll<HTMLElement>(".evidence-record > summary");
        summaries[0]!.click(); await settle(); summaries[1]!.click(); await settle();
        const records = root.querySelectorAll<HTMLDetailsElement>(".evidence-record");
        expect(records[0]!.open).toBe(false); expect(records[1]!.open).toBe(true);
        expect(root.textContent).not.toContain("USER_PROFILE");
    });
});

it("已确认画像和仓库真实使用共享分页，切换分析后不会保留另一份快照页码", async () => {
    const s = session(); s.profile = longProfile(); s.repositories = [longRepository()];
    const decide = vi.spyOn(s, "decide"), analyze = vi.spyOn(s, "analyze");
    const discovery = mount(DiscoveryView, { s });
    expect(discovery.querySelectorAll(".evidence-entry")).toHaveLength(6);
    expect(discovery.querySelector(".evidence .count-pill")?.textContent).toBe("23");
    const repo = mount(RepositoryView, { s });
    expect(repo.querySelector(".evidence .count-pill")?.textContent).toBe("27");
    const evidence = repo.querySelector(".evidence")!;
    button(evidence, "下一页").click(); await settle();
    expect(indices(evidence)).toEqual([7, 8, 9, 10, 11, 12]);
    s.repositories = [{ ...longRepository(), id: "another-repository", evidence: userEvidence(1) }]; await settle();
    expect(indices(evidence)).toEqual([1]); expect(evidence.querySelector(".evidence-pagination")).toBeNull();
    expect(decide).not.toHaveBeenCalled(); expect(analyze).not.toHaveBeenCalled();
});

it("十二个方向与历史全部可查看，动作与原文分区保持独立且不自动选择", async () => {
    vi.stubGlobal("innerWidth", 1440);
    const s = session(); s.profile = longProfile(); s.directions = longDirections();
    const original = structuredClone(s.directions.map(item => ({ id: item.id, status: item.status })));
    const decide = vi.spyOn(s, "decide"), root = mount(DirectionsView, { s });
    expect(root.querySelector(".direction-list-scroll")?.getAttribute("tabindex")).toBe("0");
    expect(root.querySelector(".direction-detail-body")?.getAttribute("tabindex")).toBe("0");
    for (const entry of s.directions) {
        button(root.querySelector(".direction-list")!, entry.title).click(); await settle();
        expect(root.querySelector(".direction-detail h2")?.textContent).toBe(entry.title);
        expect(root.querySelector(".direction-detail-body")?.textContent).toContain(entry.targetProduct);
        expect(root.querySelector(".direction-detail-body")?.textContent).toContain(entry.id);
        if (entry.status === "CANDIDATE") expect(button(root.querySelector(".direction-detail-controls")!, "选择此方向")).toBeTruthy();
    }
    expect(s.directions.map(item => ({ id: item.id, status: item.status }))).toEqual(original);
    expect(decide).not.toHaveBeenCalled();
});

it("窄屏长方向列表查看末项后仍聚焦详情，选择需要单独确认", async () => {
    vi.stubGlobal("innerWidth", 320);
    const s = session(); s.profile = longProfile(); s.directions = longDirections();
    const decide = vi.spyOn(s, "decide"), root = mount(DirectionsView, { s });
    button(root.querySelector(".direction-list")!, s.directions[11]!.title).click(); await settle();
    expect(document.activeElement?.id).toBe("direction-detail");
    button(root, "选择此方向").click(); await settle();
    expect(root.querySelector<HTMLDialogElement>("dialog")?.open).toBe(true);
    expect(decide).not.toHaveBeenCalled();
});

it("长项目元数据完整保留在 Inspector，受控删除与技术信息分开", async () => {
    const saved = longProjectSummary(); saved.stepCounts = { SUCCEEDED: 1, READY: 1, PENDING_CONFIRMATION: 1 };
    vi.spyOn(api, "projects").mockResolvedValue([saved]); vi.spyOn(api, "resources").mockResolvedValue([]);
    const s = session(), remove = vi.spyOn(s, "deleteProject"), root = mount(ProjectsView, { s }); await settle();
    expect(root.querySelector(".project-row .project-metadata")).toBeNull();
    button(root, "项目详情").click(); await settle();
    expect(root.querySelector<HTMLDialogElement>(".detail-panel")?.open).toBe(true);
    expect(root.querySelectorAll(".project-repository-list .project-path")).toHaveLength(9);
    for (const repo of saved.repositories) {
        expect(root.querySelector(".project-metadata")?.textContent).toContain(repo.location);
        expect(root.querySelector(".project-metadata")?.textContent).toContain(repo.assetId);
    }
    expect(root.querySelector(".project-meta-grid")?.textContent).toContain(saved.currentRevision);
    expect(root.querySelector(".project-meta-grid")?.textContent).toContain(saved.workingCopyId);
    expect(root.querySelector(".project-metadata .text-button")).toBeNull();
    expect(button(root.querySelector(".project-management")!, "删除项目").disabled).toBe(true);
    expect(root.querySelector(".deletion-blocked")?.textContent).toContain("未决执行期间不能删除");
    expect(root.querySelector(".project-row-issues")?.textContent).toBeTruthy();
    expect(remove).not.toHaveBeenCalled();
});
