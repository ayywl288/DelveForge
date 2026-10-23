import { createApp, h, nextTick, reactive } from "vue";
import { afterEach, expect, it } from "vitest";
import AgentTimeline from "../src/components/AgentTimeline.vue";
import type { AgentEvent, AgentRun } from "../src/api/agentTypes";

const cleanup: (() => void)[] = [];
afterEach(() => cleanup.splice(0).forEach(fn => fn()));
const fact = (sequence: number, kind: string, summary = kind): AgentEvent => ({ sequence, kind, summary, runId: "one", at: "2026-10-23T01:00:00Z" });
async function settle() { await nextTick(); await nextTick(); }
function mount() {
    const props = reactive({ events: [] as AgentEvent[], connection: "已连接", run: {
        id: "one", state: "RUNNING", cancelRequested: false,
    } as AgentRun });
    const root = document.createElement("div"); document.body.append(root);
    const app = createApp({ render: () => h(AgentTimeline, props) }); app.mount(root);
    cleanup.push(() => { app.unmount(); root.remove(); });
    return { props, root };
}
it("follows the latest event until the reader scrolls up, then offers an explicit return", async () => {
    const { root, props } = mount();
    const scroll = root.querySelector<HTMLElement>(".timeline-viewport")!;
    Object.defineProperties(scroll, { scrollHeight: { value: 1200 }, clientHeight: { value: 400 } });
    props.events.push(fact(1, "MODEL_REQUEST")); await settle();
    expect(scroll.scrollTop).toBe(1200);
    scroll.scrollTop = 100; scroll.dispatchEvent(new Event("scroll")); await settle();
    props.events.push(fact(2, "TOOL_STARTED", "tool=read_file; id=tool-one")); await settle();
    expect(scroll.scrollTop).toBe(100);
    const latest = root.querySelector<HTMLButtonElement>(".latest-activity")!;
    expect(latest.textContent).toContain("有新活动");
    latest.click(); await settle();
    expect(scroll.scrollTop).toBe(1200);
    expect(root.querySelector(".latest-activity")).toBeNull();
    expect(root.querySelector(".timeline-viewport")?.getAttribute("tabindex")).toBe("0");
});
it("uses distinct static states, preserves diagnostics, and never invents relationships or unknown descriptions", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "TOOL_FINISHED", '{"tool":"read_file","ok":true,"classification":"RESULT"}'),
        fact(2, "FAILED"), fact(3, "INTERRUPTED"), fact(4, "CLEANUP_BLOCKED"), fact(5, "FUTURE_KIND", "opaque-classification")];
    props.run.state = "FAILED"; await settle();
    expect([...root.querySelectorAll("li")].map(node => node.getAttribute("data-tone")))
        .toEqual(["completed", "failed", "interrupted", "blocked", "recorded"]);
    expect(root.querySelectorAll(".is-active")).toHaveLength(0);
    expect(root.querySelector('[data-sequence="5"] strong')?.textContent).toBe("运行活动");
    expect(root.querySelector('[data-sequence="5"] details')?.textContent).toContain("opaque-classification");
    expect(root.querySelector('[data-sequence="1"] details')?.textContent).toContain("classification: RESULT");
});
it("a terminal Run never animates a trailing start event and cancel intent is not represented as tool progress", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "MODEL_REQUEST")]; await settle();
    expect(root.querySelectorAll(".is-active")).toHaveLength(1);
    props.run.cancelRequested = true; await settle();
    expect(root.querySelectorAll(".is-active")).toHaveLength(0);
    props.run.cancelRequested = false; props.run.state = "INTERRUPTED"; await settle();
    expect(root.querySelectorAll(".is-active")).toHaveLength(0);
});

it("keeps ordered readable event titles and all diagnostic values in initially collapsed technical details", async () => {
    const { root, props } = mount();
    const fullIdentifier = "diagnostic-identifier-".repeat(30);
    props.events = [fact(10, "PREPARING"), fact(11, "TOOL_FINISHED", JSON.stringify({ tool: "run_checks",
        ok: false, classification: "BUILD_FAILED", tests: 0, empty: "", optional: null,
        nested: { fullIdentifier } })), fact(12, "CLEANUP_BLOCKED", "Owned resources require reconciliation: one")];
    props.run.state = "FAILED"; await settle();
    expect([...root.querySelectorAll("li")].map(node => node.getAttribute("data-sequence"))).toEqual(["10", "11", "12"]);
    const event = root.querySelector('[data-sequence="11"]')!;
    expect(event.querySelector("strong")?.textContent).toBe("运行 Maven 检查 · 失败");
    const details = event.querySelector("details")!;
    expect(details.open).toBe(false);
    expect(details.querySelector("summary")?.textContent).toContain("TOOL_FINISHED");
    const technical = details.querySelector("pre")!.textContent!;
    for (const value of ["ok: false", "classification: BUILD_FAILED", "tests: 0", 'empty: ""', "optional: null", fullIdentifier]) {
        expect(technical).toContain(value);
    }
    details.querySelector("summary")!.click(); await settle();
    expect(details.open).toBe(true);
    expect(event.querySelector("time")?.getAttribute("datetime")).toBe(props.events[1]!.at);
    const warning = root.querySelector('[data-sequence="12"] .timeline-warning')!;
    expect(warning.closest("details")).toBeNull();
    expect(warning.textContent).toContain("完成资源协调后再判断能否重试");
    expect(root.querySelectorAll(".is-active")).toHaveLength(0);
});

it("layers real tool results, classification and targets above complete advanced diagnostics", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "TOOL_FINISHED", JSON.stringify({ tool: "run_checks", ok: true,
        classification: "TESTS_PASSED", durationMs: 1240, executedTests: 12, tests: 14, skipped: 2, failures: 0,
        errors: 0, module: "reports", directory: ".", goal: "test", selectedTest: "ScheduleTest", snapshot: "f".repeat(64) }))];
    await settle();
    const highlights = root.querySelector(".event-highlights")!;
    expect(highlights.textContent).toContain("TESTS_PASSED");
    expect(highlights.textContent).toContain("1,240 ms");
    expect(highlights.textContent).toContain("reports");
    const details = root.querySelector<HTMLDetailsElement>(".event-details")!;
    const fields = details.querySelector(".event-fields")!;
    expect(fields.textContent).toContain("工具结果成功");
    expect(fields.textContent).toContain("实际执行测试12");
    expect(fields.textContent).toContain("跳过2");
    expect(fields.textContent).not.toContain("错误0");
    const advanced = details.querySelector<HTMLDetailsElement>(".advanced-event-details")!;
    expect(details.open).toBe(false); expect(advanced.open).toBe(false);
    details.querySelector("summary")!.click(); await settle();
    expect(details.open).toBe(true); expect(advanced.open).toBe(false);
    advanced.querySelector("summary")!.click(); await settle();
    expect(advanced.open).toBe(true);
    expect(advanced.textContent).toContain("errors: 0");
    expect(advanced.textContent).toContain("f".repeat(64));
});

it("organizes saved sandbox constraints while hiding identities in the advanced disclosure", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "SANDBOX_READY", JSON.stringify({ containerId: "container-full-id", imageId: "sha256:" + "a".repeat(64),
        targetJdk: 21, memoryBytes: 2147483648, nanoCpus: 2000000000, pidsLimit: 256, networkMode: "none", readOnlyRoot: true }))];
    await settle();
    const fields = root.querySelector(".event-fields")!;
    for (const value of ["JDK21", "内存2,048 MiB", "CPU2 核", "PID 上限256", "网络none", "只读根文件系统是"]) {
        expect(fields.textContent).toContain(value);
    }
    expect(fields.textContent).not.toContain("container-full-id");
    expect(fields.textContent).not.toContain("sha256:");
    expect(root.querySelector(".advanced-event-details")?.textContent).toContain("container-full-id");
    expect(root.querySelector(".advanced-event-details")?.textContent).toContain("sha256:" + "a".repeat(64));
});

it("does not guess paths or cross-correlate legacy tool starts and finishes", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "TOOL_STARTED", "tool=read_file; id=tool-one"),
        fact(2, "TOOL_FINISHED", '{"tool":"read_file","ok":true,"classification":"RESULT","durationMs":0,"tests":0}'),
        fact(3, "TOOL_FINISHED", '{"tool":"read_file","path":"src/OnlyRecorded.java","durationMs":10}')];
    await settle();
    const first = root.querySelector('[data-sequence="1"]')!;
    const second = root.querySelector('[data-sequence="2"]')!;
    expect(first.querySelector("strong")?.textContent).toContain("读取文件");
    expect(first.querySelector(".event-highlights")).toBeNull();
    expect(second.querySelector(".event-highlights")?.textContent).toContain("0 ms");
    expect(second.textContent).not.toContain("src/OnlyRecorded.java");
    expect(second.querySelector(".event-fields")?.textContent).not.toContain("测试");
    expect(root.querySelector('[data-sequence="3"] .event-highlights')?.textContent).toContain("src/OnlyRecorded.java");
});

it.each(["legacy plain text", '{"tool":', 'null', '["unknown"]', '{"unrecognized":"recorded-value"}'])
    ("preserves malformed, legacy or unknown summaries without manufacturing metadata (%s)", async summary => {
        const { root, props } = mount();
        props.events = [fact(1, "UNKNOWN_KIND", summary)]; await settle();
        expect(root.querySelector(".event-highlights")).toBeNull();
        expect(root.querySelector(".event-fields")).toBeNull();
        expect(root.querySelector("pre")?.textContent).toContain(summary.startsWith('{"unrecognized"') ? "recorded-value" : summary);
        expect(root.querySelector("strong")?.textContent).toBe("运行活动");
    });
