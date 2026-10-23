import { createApp, h, nextTick, reactive } from "vue";
import { afterEach, expect, it } from "vitest";
import AgentTimeline from "../src/components/AgentTimeline.vue";
import type { AgentEvent, AgentRun } from "../src/api/agentTypes";
import { readFileSync } from "node:fs";

const cleanup: (() => void)[] = [];
afterEach(() => cleanup.splice(0).forEach(fn => fn()));
const fact = (sequence: number, kind: string, summary = kind): AgentEvent => ({ sequence, kind, summary, runId: "one", at: "2026-10-23T01:00:00Z" });
const identity = "9d52e412-e03d-4419-a2fb-56322848b598";
const tool = (sequence: number, kind: string, fields: Record<string, unknown> = {}) => fact(sequence, kind,
    JSON.stringify({ metadataVersion: 1, toolCallId: identity, tool: "read_file", path: "src/Counter.java", ...fields }));
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
    props.events.push(tool(2, "TOOL_STARTED")); await settle();
    expect(scroll.scrollTop).toBe(100);
    const latest = root.querySelector<HTMLButtonElement>(".latest-activity")!;
    expect(latest.textContent).toContain("有新活动");
    latest.click(); await settle();
    expect(scroll.scrollTop).toBe(1200);
    expect(root.querySelector(".latest-activity")).toBeNull();
    expect(scroll.getAttribute("tabindex")).toBe("0");
    expect(scroll.getAttribute("aria-label")).toContain("可滚动查看历史");
});
it("uses distinct static states without exposing unknown or generic raw diagnostics", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "TOOL_FINISHED", '{"tool":"read_file","ok":true,"classification":"RESULT"}'),
        fact(2, "FAILED"), fact(3, "INTERRUPTED"), fact(4, "CLEANUP_BLOCKED"), fact(5, "FUTURE_KIND", "opaque-classification")];
    props.run.state = "FAILED"; await settle();
    expect([...root.querySelectorAll("li")].map(node => node.getAttribute("data-tone")))
        .toEqual(["completed", "failed", "interrupted", "blocked", "recorded"]);
    expect(root.querySelectorAll(".is-active")).toHaveLength(0);
    expect(root.querySelector('[data-sequence="5"] strong')?.textContent).toBe("历史运行活动");
    expect(root.textContent).not.toContain("opaque-classification");
    expect(root.textContent).not.toContain("RESULT");
    expect(root.querySelector("details, pre")).toBeNull();
    expect(props.events[4]!.summary).toBe("opaque-classification");
});
it("a terminal Run never animates a trailing start event and cancellation is not progress", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "MODEL_REQUEST")]; await settle();
    expect(root.querySelectorAll(".is-active")).toHaveLength(1);
    props.run.cancelRequested = true; await settle();
    expect(root.querySelectorAll(".is-active")).toHaveLength(0);
    props.run.cancelRequested = false; props.run.state = "INTERRUPTED";
    props.events.push(tool(2, "TOOL_STARTED")); await settle();
    expect(root.querySelectorAll(".is-active")).toHaveLength(0);
    expect(root.querySelector('[data-sequence="2"]')?.getAttribute("data-tone")).toBe("unknown");
    expect(root.textContent).toContain("结果未记录或待核对");
});
it("keeps ordered readable failure classifications and visible cleanup warnings while retaining source records", async () => {
    const { root, props } = mount();
    const fullIdentifier = "diagnostic-identifier-".repeat(30);
    props.events = [fact(10, "PREPARING"), fact(11, "TOOL_FINISHED", JSON.stringify({ tool: "run_checks",
        ok: false, classification: "BUILD_FAILED", tests: 0, nested: { fullIdentifier } })),
        fact(12, "CLEANUP_BLOCKED", "Owned resources require reconciliation: one")];
    props.run.state = "FAILED"; await settle();
    expect([...root.querySelectorAll("li")].map(node => node.getAttribute("data-sequence"))).toEqual(["10", "11", "12"]);
    const event = root.querySelector('[data-sequence="11"]')!;
    expect(event.querySelector("strong")?.textContent).toBe("运行 Maven 检查");
    expect(event.querySelector(".activity-result")?.textContent).toContain("失败");
    expect(event.querySelector("code")?.textContent).toBe("BUILD_FAILED");
    expect(event.querySelector("time")?.getAttribute("datetime")).toBe(props.events[1]!.at);
    expect(root.textContent).not.toContain(fullIdentifier);
    expect(props.events[1]!.summary).toContain(fullIdentifier);
    const warning = root.querySelector('[data-sequence="12"] .timeline-warning')!;
    expect(warning.closest("details")).toBeNull();
    expect(warning.textContent).toContain("完成资源协调后再判断能否重试");
    expect(root.querySelectorAll(".is-active")).toHaveLength(0);
});
it("shows actual Maven scope, result, duration once and compact recorded test counts", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "TOOL_FINISHED", JSON.stringify({ tool: "run_checks", ok: true,
        classification: "TESTS_PASSED", durationMs: 1240, executedTests: 12, tests: 14, skipped: 2, failures: 0,
        errors: 0, module: "reports", directory: ".", goal: "test", selectedTest: "ScheduleTest", snapshot: "f".repeat(64) }))];
    await settle();
    expect(root.querySelector(".activity-target")?.textContent).toBe("reports");
    expect(root.querySelector(".activity-result")?.textContent).toContain("TESTS_PASSED");
    expect(root.querySelectorAll(".activity-duration")).toHaveLength(1);
    expect(root.querySelector(".activity-duration")?.textContent).toBe("内部 1,240 ms");
    expect(root.querySelector(".activity-facts")?.textContent).toContain("已执行测试12");
    expect(root.querySelector(".activity-facts")?.textContent).toContain("失败0");
    expect(root.querySelector(".activity-facts")?.textContent).toContain("跳过2");
    expect(root.textContent).not.toContain("f".repeat(64));
    expect(root.querySelector("details, pre")).toBeNull();
    expect(props.events[0]!.summary).toContain("ScheduleTest");
});
it("shows saved sandbox constraints without container identities or raw JSON", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "SANDBOX_READY", JSON.stringify({ containerId: "container-full-id", imageId: "sha256:" + "a".repeat(64),
        targetJdk: 21, memoryBytes: 2147483648, nanoCpus: 2000000000, pidsLimit: 256, networkMode: "none", readOnlyRoot: true }))];
    await settle();
    const fields = root.querySelector(".activity-facts")!;
    for (const value of ["JDK21", "内存2,048 MiB", "CPU2 核", "PID 上限256", "网络none", "只读根文件系统是"]) {
        expect(fields.textContent).toContain(value);
    }
    expect(root.textContent).not.toContain("container-full-id");
    expect(root.textContent).not.toContain("sha256:");
    expect(props.events[0]!.summary).toContain("container-full-id");
});
it("does not guess paths or correlate legacy tool events by proximity", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "TOOL_STARTED", "tool=read_file; id=tool-one"),
        fact(2, "TOOL_FINISHED", '{"tool":"read_file","ok":true,"classification":"RESULT","durationMs":0,"tests":0}'),
        fact(3, "TOOL_FINISHED", '{"tool":"read_file","path":"src/OnlyRecorded.java","durationMs":10}')];
    await settle();
    expect(root.querySelectorAll("li")).toHaveLength(3);
    expect(root.querySelector('[data-sequence="1"] strong')?.textContent).toBe("读取文件");
    expect(root.querySelector('[data-sequence="1"] .activity-target')).toBeNull();
    const second = root.querySelector('[data-sequence="2"]')!;
    expect(second.querySelector(".activity-duration")?.textContent).toBe("内部 <1 ms");
    expect(second.textContent).not.toContain("src/OnlyRecorded.java");
    expect(second.querySelector(".activity-facts")).toBeNull();
    expect(root.querySelector('[data-sequence="3"] .activity-target')?.textContent).toBe("src/OnlyRecorded.java");
});
it.each(["legacy plain text", '{"tool":', 'null', '["unknown"]', '{"unrecognized":"recorded-value"}'])
    ("leaves unknown persisted summaries intact without rendering their raw content (%s)", async summary => {
        const { root, props } = mount();
        props.events = [fact(1, "UNKNOWN_KIND", summary)]; await settle();
        expect(root.querySelector(".activity-target, .activity-facts, pre, details")).toBeNull();
        expect(root.textContent).not.toContain(summary.startsWith('{"unrecognized"') ? "recorded-value" : summary);
        expect(root.querySelector("strong")?.textContent).toBe("历史运行活动");
        expect(props.events[0]!.summary).toBe(summary);
    });
it("transitions a reliably correlated operation in place while preserving standalone event order", async () => {
    const { root, props } = mount();
    props.events = [tool(1, "TOOL_STARTED")]; await settle();
    const row = root.querySelector("li")!;
    expect(row.classList.contains("is-active")).toBe(true);
    expect(row.querySelector('[role="status"]')?.textContent).toBe("进行中");
    props.events.push(fact(2, "MODEL_REQUEST"), tool(3, "TOOL_FINISHED", { ok: true, durationMs: 12, classification: "RESULT" }));
    await settle();
    expect(root.querySelector("li")).toBe(row);
    expect([...root.querySelectorAll("li")].map(node => node.getAttribute("data-sequence"))).toEqual(["1", "2"]);
    expect(row.getAttribute("data-tone")).toBe("completed");
    expect(row.querySelector(".activity-target")?.textContent).toBe("src/Counter.java");
    expect(row.querySelectorAll(".activity-duration")).toHaveLength(1);
    expect(row.querySelector(".activity-duration")?.textContent).toBe("内部 12 ms");
    expect(row.textContent).not.toContain("RESULT");
    expect(root.querySelectorAll(".is-active")).toHaveLength(0);
    expect(props.events.map(event => event.sequence)).toEqual([1, 2, 3]);
});
it.each([
    [{ ok: true, classification: "PATCH_CONFLICT" }, "conflict"],
    [{ ok: true, classification: "PATCH_APPLIED", paths: ["src/Counter.java"] }, "completed"],
    [{ ok: false, classification: "TOOL_ERROR" }, "failed"],
    [{ ok: true, classification: "RESULT" }, "unknown"],
] as const)("uses actual patch classification rather than ok alone (%j)", async (result, outcome) => {
    const { root, props } = mount();
    props.events = [tool(1, "TOOL_STARTED", { tool: "apply_patch", path: undefined }),
        tool(2, "TOOL_FINISHED", { tool: "apply_patch", path: undefined, ...result })];
    await settle();
    expect(root.querySelectorAll("li")).toHaveLength(1);
    expect(root.querySelector("li")?.getAttribute("data-tone")).toBe(outcome);
    expect(root.querySelector(".activity-target")).toBeNull();
    expect(root.querySelector(".activity-facts")?.textContent ?? "").toBe(outcome === "completed" ? "已应用文件src/Counter.java" : "");
});
it.each(["missing-version", "wrong-tool", "wrong-run", "duplicate-start", "finish-before-start"])
    ("does not merge unreliable tool identities (%s)", async variant => {
        const { root, props } = mount();
        const fields = variant === "missing-version" ? { metadataVersion: undefined }
            : variant === "wrong-tool" ? { tool: "search_code" } : {};
        const finish = tool(2, "TOOL_FINISHED", { ok: true, ...fields });
        if (variant === "wrong-run") finish.runId = "other";
        props.events = variant === "finish-before-start" ? [tool(1, "TOOL_FINISHED", { ok: true }), tool(2, "TOOL_STARTED")]
            : [tool(1, "TOOL_STARTED"), finish];
        if (variant === "duplicate-start") props.events.push(tool(3, "TOOL_STARTED"));
        await settle();
        expect(root.querySelectorAll("li")).toHaveLength(variant === "wrong-run" ? 1 : variant === "duplicate-start" ? 3 : 2);
        expect(root.querySelector('[data-sequence="1"]')?.getAttribute("data-tone"))
            .toBe(variant === "finish-before-start" ? "completed" : variant === "wrong-run" ? "running" : "unknown");
    });
it.each(["../secret.java", "E:\\private\\host.java", "/etc/passwd", ".env", "keys/id_rsa", "a".repeat(257),
    "src/github_pat_" + "a".repeat(30) + ".java", "src/AKIA" + "A".repeat(16) + ".java"])
    ("does not render unsafe legacy paths (%s)", async path => {
        const { root, props } = mount();
        props.events = [tool(1, "TOOL_FINISHED", { path, ok: true })]; await settle();
        expect(root.querySelector(".activity-target")).toBeNull();
        expect(root.textContent).not.toContain(path);
        expect(props.events[0]!.summary).toContain(JSON.stringify(path).slice(1, -1));
    });
it("does not expose credential-shaped legacy classification strings", async () => {
    const { root, props } = mount();
    const credential = "AKIA" + "A".repeat(16);
    props.events = [fact(1, "FAILED", credential), tool(2, "TOOL_FINISHED", { ok: false, classification: credential })];
    await settle();
    expect(root.textContent).not.toContain(credential);
    expect(root.querySelectorAll("li")).toHaveLength(2);
    expect(props.events[0]!.summary).toBe(credential);
});
it("renders forty recent activities, reveals older loaded history, preserves reading and resets on Run switch", async () => {
    const { root, props } = mount();
    props.events = Array.from({ length: 95 }, (_, index) => fact(index + 1, "MODEL_REQUEST")); await settle();
    expect(root.querySelectorAll("li")).toHaveLength(40);
    expect(root.querySelector("li")?.getAttribute("data-sequence")).toBe("56");
    root.querySelector<HTMLButtonElement>(".earlier-activity")!.click(); await settle();
    expect(root.querySelectorAll("li")).toHaveLength(80);
    expect(root.querySelector("li")?.getAttribute("data-sequence")).toBe("16");
    props.events.push(fact(96, "MODEL_REQUEST")); await settle();
    expect(root.querySelector("li")?.getAttribute("data-sequence")).toBe("16");
    expect(root.querySelector(".latest-activity")?.textContent).toContain("有新活动");
    root.querySelector<HTMLButtonElement>(".earlier-activity")!.click(); await settle();
    expect(root.querySelectorAll("li")).toHaveLength(96);
    expect(root.querySelector(".earlier-activity")).toBeNull();
    root.querySelector<HTMLButtonElement>(".latest-activity")!.click(); await settle();
    expect(root.querySelectorAll("li")).toHaveLength(40);
    props.run.id = "two";
    props.events = Array.from({ length: 50 }, (_, index) => ({ ...fact(index + 1, "MODEL_REQUEST"), runId: "two" }));
    await settle();
    expect(root.querySelectorAll("li")).toHaveLength(40);
    expect(root.querySelector("li")?.getAttribute("data-sequence")).toBe("11");
    expect(root.querySelector(".latest-activity")).toBeNull();
});

it("uses persisted request activity when the Run snapshot is behind, independently of SSE connection", async () => {
    const { root, props } = mount();
    props.run.state = "PREPARING";
    props.connection = "连接中断，正在重连";
    props.events = [fact(1, "PREPARING"), fact(2, "MODEL_REQUEST")]; await settle();
    expect(root.querySelector('.model-waiting[aria-hidden="true"]')?.children).toHaveLength(3);
    expect(root.querySelector('[data-sequence="2"] [role="status"]')?.textContent).toBe("等待模型响应");
    expect(root.querySelector(".snapshot-note")?.textContent).toContain("运行概览待同步");
    props.events.push(fact(3, "FAILED", "POLICY_REJECTED")); await settle();
    expect(root.querySelector(".model-waiting, .is-active")).toBeNull();
    expect(root.querySelector('[data-sequence="3"] code')?.textContent).toBe("POLICY_REJECTED");
    props.run.state = "FAILED"; await settle();
    expect(root.querySelector(".snapshot-note")).toBeNull();
});
it("does not animate historical requests when a snapshot already reached finalization or cancellation", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "MODEL_REQUEST")]; props.run.state = "FINALIZING"; await settle();
    expect(root.querySelector(".model-waiting, .is-active")).toBeNull();
    props.run.state = "RUNNING"; props.run.cancelRequested = true; await settle();
    expect(root.querySelector(".model-waiting, .is-active")).toBeNull();
});
it("keeps readable status and supplies reduced-motion overrides for every active and entry animation", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "MODEL_REQUEST")]; await settle();
    expect(root.querySelector('[role="status"]')?.textContent).toContain("事件连接");
    expect(root.querySelector('.activity-result [role="status"]')?.textContent).toBe("等待模型响应");
    const rowStyles = readFileSync("src/components/AgentActivityRow.vue", "utf8");
    const timelineStyles = readFileSync("src/components/AgentTimeline.vue", "utf8");
    expect(rowStyles).toMatch(/prefers-reduced-motion: reduce[^}]+\.model-waiting i[^}]+animation: none/s);
    expect(timelineStyles).toMatch(/prefers-reduced-motion: reduce[^}]+transition: none/s);
    expect(timelineStyles).toContain("transform 220ms");
});
it.each([[0, "<1 ms"], [500000, "<1 ms"], [1250000, "1.25 ms"], [12000000, "12 ms"]] as const)
    ("prefers actual invocation timing and distinguishes internal duration (%s)", async (invocationDurationNs, text) => {
        const { root, props } = mount();
        props.events = [tool(1, "TOOL_FINISHED", { ok: true, invocationDurationNs, durationMs: 0 })]; await settle();
        const duration = root.querySelector(".activity-duration")!;
        expect(duration.textContent).toBe(text);
        expect(duration.getAttribute("data-source")).toBe("invocation");
        expect(duration.getAttribute("title")).toContain("调用总耗时");
        props.events = [tool(1, "TOOL_FINISHED", { ok: true })]; await settle();
        expect(root.querySelector(".activity-duration")).toBeNull();
    });
it("a thrown policy rejection closes its correlated activity without claiming no mutation", async () => {
    const { root, props } = mount();
    props.events = [tool(1, "TOOL_STARTED", { tool: "apply_patch", path: undefined }),
        tool(2, "TOOL_FINISHED", { tool: "apply_patch", path: undefined, ok: false, resultRecorded: false,
            classification: "POLICY_REJECTED", invocationDurationNs: 1250000 }), fact(3, "FAILED", "POLICY_REJECTED")];
    await settle();
    expect(root.querySelectorAll("li")).toHaveLength(2);
    expect(root.querySelector('[data-sequence="1"]')?.getAttribute("data-tone")).toBe("blocked");
    expect(root.querySelector('[data-sequence="1"] .activity-uncertain')?.textContent).toContain("修改情况需核对");
    expect(root.querySelector('[data-sequence="1"] .activity-duration')?.textContent).toBe("1.25 ms");
    expect(root.querySelector(".is-active")).toBeNull();
});
it("groups bounded successful exploration with interleaved model events in their original order", async () => {
    const { root, props } = mount();
    props.events = [tool(1, "TOOL_FINISHED", { ok: true, path: "src/A.java" }), fact(2, "MODEL_REQUEST"),
        tool(3, "TOOL_FINISHED", { ok: true, path: "src/B.java" }), fact(4, "MODEL_REQUEST"),
        tool(5, "TOOL_FINISHED", { ok: true, tool: "list_files", path: ".github" }), fact(6, "MODEL_REQUEST")];
    await settle();
    const group = root.querySelector<HTMLDetailsElement>(".exploration-group details")!;
    expect(group.open).toBe(false);
    expect(group.querySelector("summary")?.textContent).toContain("探索仓库文件");
    expect(group.querySelector("summary")?.textContent).toContain("3 次文件操作");
    expect(group.querySelector("summary")?.textContent).toContain("src/A.java · src/B.java");
    expect(group.querySelector("li")).toBeNull();
    expect(root.querySelector('[data-sequence="6"] .model-waiting')).not.toBeNull();
    group.querySelector("summary")!.click(); await settle();
    expect(group.open).toBe(true);
    expect([...root.querySelectorAll("li[data-sequence]")].map(row => row.getAttribute("data-sequence")))
        .toEqual(["1", "2", "3", "4", "5", "6"]);
    expect(group.querySelector(".is-active")).toBeNull();
    expect(props.events.map(event => event.sequence)).toEqual([1, 2, 3, 4, 5, 6]);
});
it("keeps patch, checks, failed and incomplete calls outside ordinary exploration groups", async () => {
    const { root, props } = mount();
    const reads = (from: number) => [0, 1, 2].map(offset => tool(from + offset, "TOOL_FINISHED", { ok: true }));
    props.events = [...reads(1), tool(4, "TOOL_FINISHED", { tool: "apply_patch", ok: true, classification: "PATCH_CONFLICT" }),
        ...reads(5), tool(8, "TOOL_FINISHED", { tool: "run_checks", ok: false, classification: "TESTS_FAILED" }),
        ...reads(9), tool(12, "TOOL_FINISHED", { ok: false, classification: "TOOL_ERROR" }), tool(13, "TOOL_STARTED")];
    await settle();
    expect(root.querySelectorAll(".exploration-group")).toHaveLength(3);
    for (const sequence of [4, 8, 12, 13]) {
        expect(root.querySelector(`[data-sequence="${sequence}"]`)?.closest(".exploration-group")).toBeNull();
    }
    expect(root.querySelector('[data-sequence="13"]')?.classList.contains("is-active")).toBe(true);
});
it("caps each exploration group and keeps the newest completed row independent", async () => {
    const { root, props } = mount();
    props.events = Array.from({ length: 100 }, (_, index) => tool(index + 1, "TOOL_FINISHED", { ok: true })); await settle();
    expect(root.querySelectorAll(".agent-timeline > li").length).toBeLessThan(12);
    expect(root.querySelector('[data-sequence="100"]')).not.toBeNull();
    for (const summary of root.querySelectorAll(".exploration-group summary")) {
        expect(summary.textContent).toMatch(/[3-8] 次文件操作/);
    }
    for (const summary of root.querySelectorAll<HTMLElement>(".exploration-group summary")) summary.click();
    await settle();
    expect(root.querySelectorAll("li[data-sequence]")).toHaveLength(40);
});
it.each([".github/workflows/build.yml", ".gitignore", "src/Order Service.java"])
    ("shows permitted relative targets instead of hiding every dot path (%s)", async path => {
        const { root, props } = mount(); props.events = [tool(1, "TOOL_STARTED", { path })]; await settle();
        expect(root.querySelector(".activity-target")?.textContent).toBe(path);
    });
it("distinguishes withheld targets from absent legacy metadata without exposing protected names", async () => {
    const { root, props } = mount();
    props.events = [tool(1, "TOOL_FINISHED", { path: undefined, ok: true, targetState: "withheld" }),
        fact(2, "TOOL_FINISHED", '{"tool":"read_file","ok":true}')]; await settle();
    expect(root.querySelector('[data-sequence="1"] .target-note')?.textContent).toBe("目标已隐藏");
    expect(root.querySelector('[data-sequence="2"] .target-note')?.textContent).toBe("目标未记录");
    expect(root.textContent).not.toContain(".env");
});
