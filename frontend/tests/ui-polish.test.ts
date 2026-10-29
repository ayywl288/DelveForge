import { createApp, h, nextTick, reactive } from "vue";
import { afterEach, expect, it, vi } from "vitest";
import EvidenceList from "../src/components/EvidenceList.vue";
import ProfileReview from "../src/components/ProfileReview.vue";
import RepositoryView from "../src/views/RepositoryView.vue";
import DirectionsView from "../src/views/DirectionsView.vue";
import PlanView from "../src/views/PlanView.vue";
import { createSession } from "../src/state/session";
import type { EvidenceBasis } from "../src/api/types";
import { direction, profile } from "./fixtures";
import { fitFinishPlan, longRepository } from "./long-content-fixtures";

const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); vi.restoreAllMocks(); });
function mount(component: Parameters<typeof h>[0], props: Record<string, unknown>) {
    const root = document.createElement("div"); document.body.append(root);
    const app = createApp({ render: () => h(component, props) }); app.mount(root);
    cleanup.push(() => { app.unmount(); root.remove(); }); return root;
}

it("同一依据跨支持类别只展示一次，保留类别、出处差异与原始数据", async () => {
    const basis: EvidenceBasis = { evidence: { sourceType: "USER_INPUT", sourceRef: "每周整理资料", claim: "需要减少整理工作",
        confidence: 0.8, confirmed: false }, origin: { kind: "USER_PROFILE", userProfileId: "profile-1",
        userProfileRevision: 2, repositoryProfileId: null } };
    const saved = JSON.stringify(basis);
    const root = mount(EvidenceList, { expanded: true, groups: [
        { label: "用户需求", bases: [basis, basis] }, { label: "用户匹配", bases: [basis] },
    ] });
    expect(root.querySelectorAll(".evidence-entry")).toHaveLength(1);
    expect(root.querySelectorAll(".evidence-claim")).toHaveLength(1);
    expect([...root.querySelectorAll(".evidence-category")].map(item => item.textContent))
        .toEqual(["用户需求", "用户匹配"]);
    const record = root.querySelector<HTMLDetailsElement>(".evidence-record")!;
    expect(record.open).toBe(false);
    (record.querySelector("summary") as HTMLElement).click(); await nextTick();
    expect(record.open).toBe(true);
    expect(record.querySelector(".source-reference")?.textContent).toBe(basis.evidence.sourceRef);
    expect(root.textContent).not.toMatch(/sourceType|confidence|confirmed|USER_PROFILE/);
    expect(JSON.stringify(basis)).toBe(saved);
    const origins = mount(EvidenceList, { bases: [basis,
        { ...basis, origin: { ...basis.origin, userProfileRevision: 99 } }], expanded: true });
    expect(origins.querySelectorAll(".evidence-entry")).toHaveLength(2);
});

it("审阅卡片保留全部内容、原始描述与编辑事件，图标不占键盘焦点", () => {
    const value = profile(); value.painPoints = [];
    const edit = vi.fn(); const root = mount(ProfileReview, { profile: value, sufficiency: null, busy: false, onEdit: edit });
    expect(root.querySelectorAll(".profile-card")).toHaveLength(6);
    expect(root.querySelectorAll('.profile-card svg[aria-hidden="true"][focusable="false"]')).toHaveLength(6);
    expect(root.querySelector(".profile-card-empty")?.textContent).toContain("暂未记录");
    const button = root.querySelector<HTMLButtonElement>('[aria-label="审阅时纠正兴趣"]')!;
    button.focus(); expect(document.activeElement).toBe(button); button.click();
    expect(edit).toHaveBeenCalledWith("interests");
});

it("仓库分区保留每个分析条目；方向与规划共享精简依据展示", async () => {
    const s = reactive(createSession()); cleanup.push(s.dispose);
    s.profile = profile(); s.repositories = [longRepository()]; s.directions = [direction()];
    s.plan = fitFinishPlan();
    const analyze = vi.spyOn(s, "analyze"); const decide = vi.spyOn(s, "decide");
    const repository = mount(RepositoryView, { s });
    for (const [section, items] of [["capabilities", s.repositories[0]!.capabilities],
        ["reuse", s.repositories[0]!.reusableAssets], ["structure", s.repositories[0]!.modules],
        ["risks", [...s.repositories[0]!.limitations, ...s.repositories[0]!.risks]]] as const) {
        const text = repository.querySelector(`#repository-section-${section}`)!.textContent!;
        for (const item of items) expect(text).toContain(item);
    }
    expect(repository.querySelectorAll(".content-cards").length).toBeGreaterThan(0);
    const directions = mount(DirectionsView, { s });
    expect(directions.querySelectorAll(".direction-evidence .evidence")).toHaveLength(0);
    expect(directions.querySelector(".direction-evidence")).not.toBeNull();
    const plan = mount(PlanView, { s }); await nextTick();
    [...plan.querySelectorAll("button")].find(button => button.textContent?.includes("规划改动、风险、依据"))!.click();
    await nextTick();
    expect(plan.querySelector(".evidence")).not.toBeNull();
    expect(plan.querySelector(".evidence")?.textContent).not.toMatch(/sourceType=|confidence=|confirmed=/);
    expect(analyze).not.toHaveBeenCalled(); expect(decide).not.toHaveBeenCalled();
});
