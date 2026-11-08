import { createApp, h, nextTick, reactive } from "vue";
import { afterEach, expect, it, vi } from "vitest";
import QuickStartView from "../src/views/QuickStartView.vue";
import GuideMarkdown from "../src/components/GuideMarkdown.vue";
import AgentTestEvidence from "../src/components/AgentTestEvidence.vue";
import { api, type QuickStart } from "../src/api/client";
import { executionFacts } from "../src/components/agentResultPresentation";
const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(stop => stop()); vi.restoreAllMocks(); vi.useRealTimers(); });
async function settle() { for (let i = 0; i < 8; i++) await nextTick(); }
function mount(component: Parameters<typeof h>[0], props: object) {
    const root = document.createElement("div"); document.body.append(root);
    const app = createApp({ render: () => h(component, props) }); app.mount(root);
    cleanup.push(() => { app.unmount(); root.remove(); }); return root;
}
const guide: QuickStart = { planId: "plan", candidateId: "candidate", revision: "a".repeat(40), status: "AVAILABLE",
    markdown: "## 启动\n```sh\nnpm run dev\n```", reason: null, updatedAt: null, stale: false };
it("renders Markdown as escaped text and copies the actual command", async () => {
    vi.useFakeTimers();
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", {value: { writeText }, configurable: true});
    const root = mount(GuideMarkdown, { markdown: "## 启动\n**Node 22** 与 `npm`\n<img src=x onerror=alert(1)>\n[bad](javascript:alert(1))\n```sh\nnpm run dev\n```" });
    await settle();
    expect(root.querySelector("h3")?.textContent).toBe("启动");
    expect(root.querySelector("strong")?.textContent).toBe("Node 22");
    expect(root.querySelector("img")).toBeNull();
    expect(root.querySelector("a")).toBeNull();
    root.querySelector<HTMLButtonElement>("button")!.click(); await settle();
    expect(writeText).toHaveBeenCalledWith("npm run dev");
    expect(root.textContent).toContain("已复制");
    expect(root.querySelector("button")?.getAttribute("aria-label")).toBe("Copied");
    await vi.advanceTimersByTimeAsync(1800); await settle();
    expect(root.querySelector("button")?.getAttribute("aria-label")).toBe("Copy");
});
it("shows stale guide identity and explicit generation only after Plan completion", async () => {
    vi.spyOn(api, "quickStart").mockResolvedValue({...guide, stale: true});
    const generate = vi.spyOn(api, "generateQuickStart").mockResolvedValue({...guide, status: "UNAVAILABLE"});
    const props = reactive({planId: "plan", completed: false});
    const root = mount(QuickStartView, props); await settle();
    expect(root.textContent).toContain("此指南已过期");
    expect(root.textContent).toContain(guide.revision);
    expect(root.textContent).toContain("尚未执行验证");
    expect(generate).not.toHaveBeenCalled();
    expect([...root.querySelectorAll("button")].some(button => button.textContent?.includes("重新生成"))).toBe(false);
    props.completed = true; await settle();
    [...root.querySelectorAll<HTMLButtonElement>("button")].find(button => button.textContent?.includes("重新生成"))!.click();
    await settle(); expect(generate).toHaveBeenCalledWith("plan");
    expect(root.textContent).toContain("代码交付已完成");
});
it("counts every receipt, keeps test failures separate from zero Shell exits and omits command history", async () => {
    const receipt = {invocationId: "chosen", snapshot: "a".repeat(64), afterSnapshot: "a".repeat(64), exitCode: 0,
        timedOut: false, cancelled: false, testReport: {availability: "AVAILABLE", executedTests: 2, failures: 1, skipped: 0}};
    const evidence = JSON.stringify({evidenceVersion: 8, status: "TESTED", finalSnapshot: "a".repeat(64),
        checks: [receipt, {...receipt, invocationId: "omitted", exitCode: 2, testReport: {availability: "UNAVAILABLE"}}],
        agentReport: {status: "completed", summary: "已完成修改", executions: ["chosen"], limitations: ["E2E 未执行"]}});
    expect(executionFacts(evidence)).toMatchObject({total: 2, succeeded: 1, failed: 1, failures: 1, unknown: 1});
    const root = mount(AgentTestEvidence, {evidence}); await settle();
    expect(root.textContent).toContain("退出零不证明测试或业务通过");
    expect(root.textContent).toContain("E2E 未执行");
    expect(root.querySelector(".evidence-history")).toBeNull();
});
