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
it("keeps the generating preview mounted between polls and replaces it with the finished guide", async () => {
    vi.useFakeTimers();
    const inProgress = { ...guide, status: "GENERATING" as const, markdown: "" };
    const read = vi.spyOn(api, "quickStart").mockResolvedValueOnce(inProgress).mockResolvedValueOnce(inProgress)
        .mockResolvedValue(guide);
    const generate = vi.spyOn(api, "generateQuickStart");
    const root = mount(QuickStartView, { planId: "plan", completed: true }); await settle();
    const preview = root.querySelector('.guide-generating[role="status"]');
    expect(preview?.textContent).toContain("正在生成启动指南");
    expect(preview?.textContent).not.toContain(guide.revision!.slice(0, 12));
    expect(preview?.querySelector('[aria-hidden="true"] .draft-line')).not.toBeNull();
    expect(root.textContent).not.toContain("暂不可用");
    expect(root.querySelector("button")).toBeNull();
    await vi.advanceTimersByTimeAsync(2000); await settle();
    expect(root.querySelector(".guide-generating")).toBe(preview);
    await vi.advanceTimersByTimeAsync(2000); await settle();
    expect(root.querySelector(".guide-generating")).toBeNull();
    expect(root.querySelector(".guide-markdown")?.textContent).toContain("npm run dev");
    expect(read).toHaveBeenCalledTimes(3);
    expect(generate).not.toHaveBeenCalled();
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

it("separates project identity, prerequisites and collapsed source provenance", async () => {
    vi.spyOn(api, "quickStart").mockResolvedValue({...guide, markdown: "# 纸上书店\n\n## 前置条件\n- Java 17\n- Docker\n## 1. 必要配置\n复制环境示例\n## 5. 访问地址\n[打开应用](http://localhost:5173)\n## 生成依据\n- `README.md`\n- `document/开发指南.md`"});
    const root = mount(QuickStartView, {planId: "plan", completed: true}); await settle();
    expect(root.querySelector("h2")?.textContent).toContain("纸上书店");
    expect(root.querySelectorAll(".prerequisites li")).toHaveLength(2);
    const sources = root.querySelector<HTMLDetailsElement>(".guide-sources")!;
    expect(sources.open).toBe(false);
    expect(sources.textContent).toContain("document/开发指南.md");
    expect(root.querySelector(".guide-markdown")?.textContent).not.toContain("README.md");
    expect(root.querySelector("a")?.getAttribute("href")).toBe("http://localhost:5173");
    expect(root.querySelectorAll(".guide-verification")).toHaveLength(1);
});
it("renders hostile source paths as literal text without links or injected elements", async () => {
    const paths = ["docs/`[external](https://evil.example)/setup.md", "docs/``[second](https://evil.example)/开发指南.md",
        "docs/**bold**_[label](https://evil.example)/install.md", "docs/<img src=x onerror=alert(1)>/setup.md"];
    vi.spyOn(api, "quickStart").mockResolvedValue({...guide, markdown: "# 项目\n## 访问地址\n[应用](http://localhost:5173)\n## 生成依据\n"
        + paths.map(path => "- `" + path + "`").join("\n")});
    const root = mount(QuickStartView, {planId: "plan", completed: true}); await settle();
    const sources = root.querySelector<HTMLDetailsElement>(".guide-sources")!;
    sources.open = true;
    expect(sources.querySelectorAll("a,img,strong,script")).toHaveLength(0);
    for (const path of paths) expect(sources.textContent).toContain(path);
    expect(root.querySelectorAll("a")).toHaveLength(1);
    expect(root.querySelector("a")?.getAttribute("href")).toBe("http://localhost:5173");
});
it("separates final-source validation from repeated historical test failures", async () => {
    const finalSnapshot = "a".repeat(64), old = "b".repeat(64);
    const sourceView = { kind: "FULL", preservedOnlyCount: 0, readOnlyResourceCount: 0, withheldSourceCount: 0 };
    const receipt = { snapshot: old, afterSnapshot: old, sourceView, exitCode: 1, timedOut: false, cancelled: false,
        testReport: {availability: "AVAILABLE", executedTests: 10, failures: 2, errors: 0, skipped: 1} };
    const evidence = JSON.stringify({evidenceVersion: 8, status: "TESTED", finalSnapshot, sourceView,
        checks: [receipt, {...receipt, snapshot: finalSnapshot, afterSnapshot: finalSnapshot,
            sourceView: {...sourceView, readOnlyResourceCount: 1}}, {...receipt, snapshot: finalSnapshot, afterSnapshot: finalSnapshot, exitCode: 0,
            testReport: {availability: "AVAILABLE", executedTests: 11, failures: 0, errors: 0, skipped: 1}}],
        agentReport: {summary: "新增取货点查询。完善司机入口。", limitations: ["浏览器 E2E 未执行"]}});
    const root = mount(AgentTestEvidence, {evidence}); await settle();
    expect(root.querySelectorAll(".implementation-summary li")).toHaveLength(2);
    expect(root.textContent).toContain("最终源码上的 1 次命令：成功 1 · 失败 0");
    expect(root.textContent).toContain("另有 2 次执行的源码或视图与最终状态不匹配");
    expect(root.textContent).not.toContain("失败 / 错误 4");
    expect(root.querySelector(".result-limitations")?.textContent).toContain("E2E 未执行");
    expect(root.querySelector("details, pre")).toBeNull();
});
