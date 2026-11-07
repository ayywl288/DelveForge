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
const important = (sequence: number) => fact(sequence, "TOOL_FINISHED", '{"tool":"run_checks","ok":true,"classification":"TESTS_PASSED"}');

it("shows a rejected command as not executed without an invented exit code", async () => {
    const { root, props } = mount(false);
    props.events = [tool(1, "TOOL_FINISHED", { tool: "exec_command", command: "mvn test", directory: "missing",
        ok: false, classification: "INVALID_ARGUMENTS", resultRecorded: true, mutationNotStarted: true })];
    await settle();
    (root.querySelector('.check-summary > summary') as HTMLElement).click();
    await settle();
    expect(root.textContent).toContain("命令未执行，请按反馈调整后重试。");
    expect(root.textContent).not.toContain("退出码");
    expect(root.textContent).toContain("mvn test");
});

it("keeps same-snapshot backend test failure visible after a frontend build", async () => {
    const { root, props } = mount(false);
    const check = (sequence: number, fields: Record<string, unknown>) => fact(sequence, "TOOL_FINISHED",
        JSON.stringify({ tool: "run_checks", ok: true, snapshot: "a".repeat(64), goal: "test",
            directory: "backend/bookstore", executedTests: 17, failures: 0, errors: 3, skipped: 0,
            classification: "TESTS_FAILED", ...fields }));
    props.events = [check(1, {}), check(2, { tool: "run_frontend_checks", directory: "frontend",
        classification: "TESTS_PASSED", executedTests: 13, errors: 0 }),
        check(3, { tool: "run_frontend_checks", directory: "frontend", goal: "build",
            classification: "BUILD_PASSED_NOT_TESTED", executedTests: 0, errors: 0 })];
    await settle();
    expect(root.querySelector(".check-latest")?.textContent).toContain("测试未通过");
    expect(root.querySelector(".check-latest")?.getAttribute("data-tone")).toBe("failed");
    expect(root.querySelector(".check-detail")?.textContent).toContain("backend/bookstore");
    expect(root.querySelector(".check-detail")?.textContent).toContain("错误 3");
    props.events.push(check(4, { classification: "TESTS_PASSED", errors: 0, testSelectionFingerprint: "f".repeat(64) }));
    await settle();
    expect(root.querySelector(".check-latest")?.textContent).toContain("测试未通过");
    props.events.push(check(5, { classification: "TESTS_PASSED", errors: 0 }));
    await settle();
    expect(root.querySelector(".check-latest")?.textContent).toContain("测试通过");
    props.events.push(check(6, { tool: "run_frontend_checks", directory: "frontend", goal: "build",
        snapshot: "b".repeat(64), classification: "BUILD_PASSED_NOT_TESTED", executedTests: 0, errors: 0 }));
    await settle();
    expect(root.querySelector(".check-latest")?.textContent).toContain("检查已过期");
    expect(root.querySelector(".check-detail")?.textContent).not.toContain("已执行 17 项");
});

it("shows completion diagnostics as escaped text and ignores other-run model explanations", async () => {
    const { root, props } = mount(false);
    props.run.state = "FAILED"; props.run.reason = "MODEL_INCOMPLETE";
    props.events = [fact(1, "COMPLETION_CHECK_REJECTED", JSON.stringify({ classification: "UNRESOLVED_CHECK_FAILURE",
        reason: "FAILURE_CHANGED; testSource=ContextTest.java" })),
        { ...fact(2, "MODEL_INCOMPLETE", JSON.stringify({ metadataVersion: 1, summary: "other run" })), runId: "other" },
        fact(3, "MODEL_INCOMPLETE", JSON.stringify({ metadataVersion: 1, summary: "<img src=x onerror=alert(1)>" })),
        fact(4, "FAILED", "MODEL_INCOMPLETE")];
    await settle();
    expect(root.textContent).toContain("FAILURE_CHANGED; testSource=ContextTest.java");
    expect(root.textContent).not.toContain("other run");
    expect(root.querySelector("img")).toBeNull();
});

it("shows attributed model incomplete explanation and preserves historical fallback", async () => {
    const { root, props } = mount(false);
    props.run.state = "FAILED"; props.run.reason = "MODEL_INCOMPLETE";
    props.events = [fact(1, "MODEL_INCOMPLETE", JSON.stringify({ metadataVersion: 1,
        summary: "后端检查仍有错误，需要核对。" })), fact(2, "FAILED", "MODEL_INCOMPLETE")];
    await settle();
    expect(root.textContent).toContain("模型说明：后端检查仍有错误，需要核对。");
    expect(root.textContent).toContain("模型报告未完成");
    props.events = [fact(2, "FAILED", "MODEL_INCOMPLETE")]; await settle();
    expect(root.textContent).toContain("未记录具体说明");
});
it("groups frontend checks with Maven checks and displays real failures and counts", async () => {
    const { root, props } = mount();
    props.events = [tool(1, "TOOL_STARTED", { tool: "run_frontend_checks", path: undefined,
        directory: "frontend", goal: "typecheck" }), tool(2, "TOOL_FINISHED", { tool: "run_frontend_checks",
        path: undefined, directory: "frontend", goal: "typecheck", ok: true, classification: "TYPECHECK_FAILED" })];
    await settle(); await expandChecks(root);
    const rows = root.querySelectorAll('[data-kind="run_frontend_checks"]');
    expect(rows).toHaveLength(1);
    expect(rows[0]?.textContent).toContain("运行前端检查");
    expect(rows[0]?.getAttribute("data-tone")).toBe("failed");
    expect(root.querySelector(".check-summary")?.textContent).toContain("前端类型检查未通过");
});
it("distinguishes unavailable and unattested scripts from executed test failures", async () => {
    for (const [classification, executionStatus, label] of [
        ["UNSUPPORTED_FRONTEND_CONFIGURATION", "NOT_STARTED", "前端配置暂不支持"],
        ["TEST_EXECUTION_UNVERIFIED", "EXECUTED", "脚本已结束，测试执行未获验证"],
    ]) {
        const { root, props } = mount();
        props.events = [tool(1, "TOOL_STARTED", { tool: "run_frontend_checks", path: undefined, goal: "test" }),
            tool(2, "TOOL_FINISHED", { tool: "run_frontend_checks", path: undefined, goal: "test", ok: true,
                classification, executionStatus })];
        await settle(); await expandChecks(root);
        expect(root.querySelector('[data-kind="run_frontend_checks"]')?.getAttribute("data-tone")).toBe("recorded");
        expect(root.querySelector(".check-summary")?.textContent).toContain(label);
    }
});
async function settle() { await nextTick(); await nextTick(); }
async function expandChecks(root: HTMLElement) {
    root.querySelector<HTMLElement>(".check-summary summary")!.click(); await settle();
}
function mount(expandFiles = true) {
    const props = reactive({ events: [] as AgentEvent[], connection: "已连接", run: {
        id: "one", state: "RUNNING", cancelRequested: false,
    } as AgentRun });
    const root = document.createElement("div"); document.body.append(root);
    const app = createApp({ render: () => h(AgentTimeline, props) }); app.mount(root);
    if (expandFiles) {
        root.querySelector<HTMLElement>(".exploration-summary summary")!.click();
        root.querySelector<HTMLElement>(".patch-summary summary")!.click();
    }
    cleanup.push(() => { app.unmount(); root.remove(); });
    return { props, root };
}
it("numerical model usage remains diagnostic data without adding timeline actions", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "MODEL_REQUEST"), fact(2, "MODEL_USAGE",
        JSON.stringify({ request: 1, promptTokens: 20, completionTokens: 10, totalTokens: 30 }))];
    await settle();
    expect(root.querySelector('[data-sequence="2"]')).toBeNull();
    expect(root.textContent).not.toContain("promptTokens");
    expect(root.querySelector(".current-activity")?.textContent).toContain("请求模型");
});
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
    expect([...root.querySelectorAll(".agent-timeline li")].map(node => node.getAttribute("data-tone")))
        .toEqual(["failed", "interrupted", "blocked", "recorded"]);
    expect(root.querySelector('.exploration-operations li')?.getAttribute('data-tone')).toBe('completed');
    expect(root.querySelectorAll(".is-active")).toHaveLength(0);
    expect(root.querySelector('[data-sequence="5"] strong')?.textContent).toBe("历史运行活动");
    expect(root.textContent).not.toContain("opaque-classification");
    expect(root.textContent).not.toContain("RESULT");
    expect(root.querySelector(".event-details, .advanced-event-details, pre")).toBeNull();
    expect(props.events[4]!.summary).toBe("opaque-classification");
});
it("a terminal Run never animates a trailing start event and cancellation is not progress", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "MODEL_REQUEST")]; await settle();
    expect(root.querySelector('.current-activity')?.getAttribute('data-active')).toBe('true');
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
    expect([...root.querySelectorAll(".agent-timeline li")].map(node => node.getAttribute("data-sequence"))).toEqual(["10", "12"]);
    await expandChecks(root);
    expect(root.querySelector(".check-history li")?.getAttribute("data-sequence")).toBe("11");
    expect(root.querySelector(".exploration-operations li")).toBeNull();
    const event = root.querySelector('[data-sequence="11"]')!;
    expect(event.querySelector("strong")?.textContent).toBe("运行 Maven 检查");
    expect(event.querySelector(".activity-result")?.textContent).toContain("失败");
    expect(event.querySelector("code")?.textContent).toBe("BUILD_FAILED");
    expect(event.querySelector("time")?.getAttribute("datetime")).toBe(props.events[1]!.at);
    expect(root.textContent).not.toContain(fullIdentifier);
    expect(props.events[1]!.summary).toContain(fullIdentifier);
    const warning = root.querySelector('[data-sequence="12"] .timeline-warning')!;
    expect(warning.closest("details")).toBeNull();
    expect(warning.textContent).toContain("当时的清理失败");
    expect(warning.textContent).toContain("当前是否仍需协调以运行状态提示为准");
    expect(root.querySelectorAll(".is-active")).toHaveLength(0);
});
it("shows actual Maven scope, result, duration once and compact recorded test counts", async () => {
    const { root, props } = mount();
    props.events = [fact(1, "TOOL_FINISHED", JSON.stringify({ tool: "run_checks", ok: true,
        classification: "TESTS_PASSED", durationMs: 1240, executedTests: 12, tests: 14, skipped: 2, failures: 0,
        errors: 0, module: "reports", directory: ".", goal: "test", selectedTest: "ScheduleTest", snapshot: "f".repeat(64) }))];
    await settle();
    expect(root.querySelector<HTMLDetailsElement>(".check-summary")!.open).toBe(false);
    await expandChecks(root);
    expect(root.querySelector(".activity-target")?.textContent).toBe("reports");
    expect(root.querySelector(".activity-result")?.textContent).toContain("TESTS_PASSED");
    expect(root.querySelectorAll(".activity-duration")).toHaveLength(1);
    expect(root.querySelector(".activity-duration")?.textContent).toBe("内部 1,240 ms");
    expect(root.querySelector(".activity-facts")?.textContent).toContain("已执行测试12");
    expect(root.querySelector(".activity-facts")?.textContent).toContain("失败0");
    expect(root.querySelector(".activity-facts")?.textContent).toContain("跳过2");
    expect(root.textContent).not.toContain("f".repeat(64));
    expect(root.querySelector(".event-details, .advanced-event-details, pre")).toBeNull();
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
        expect(root.querySelector(".activity-target, .activity-facts, pre, .event-details, .advanced-event-details")).toBeNull();
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
    expect([...root.querySelectorAll("li")].map(node => node.getAttribute("data-sequence"))).toEqual(["1"]);
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
    props.events = Array.from({ length: 95 }, (_, index) => fact(index + 1, "FINALIZING")); await settle();
    expect(root.querySelectorAll("li")).toHaveLength(40);
    expect(root.querySelector("li")?.getAttribute("data-sequence")).toBe("56");
    root.querySelector<HTMLButtonElement>(".earlier-activity")!.click(); await settle();
    expect(root.querySelectorAll("li")).toHaveLength(80);
    expect(root.querySelector("li")?.getAttribute("data-sequence")).toBe("16");
    props.events.push(fact(96, "FINALIZING")); await settle();
    expect(root.querySelector("li")?.getAttribute("data-sequence")).toBe("16");
    expect(root.querySelector(".latest-activity")?.textContent).toContain("有新活动");
    root.querySelector<HTMLButtonElement>(".earlier-activity")!.click(); await settle();
    expect(root.querySelectorAll("li")).toHaveLength(96);
    expect(root.querySelector(".earlier-activity")).toBeNull();
    root.querySelector<HTMLButtonElement>(".latest-activity")!.click(); await settle();
    expect(root.querySelectorAll("li")).toHaveLength(40);
    props.run.id = "two";
    props.events = Array.from({ length: 50 }, (_, index) => ({ ...fact(index + 1, "FINALIZING"), runId: "two" }));
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
    expect(root.querySelector('.current-activity')?.textContent).toContain("等待模型响应");
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
    expect(root.querySelector('.current-activity[role="status"]')?.textContent).toContain("等待模型响应");
    const rowStyles = readFileSync("src/components/AgentActivityRow.vue", "utf8");
    const timelineStyles = readFileSync("src/components/AgentTimeline.vue", "utf8");
    expect(rowStyles).toMatch(/prefers-reduced-motion: reduce[^}]+animation: none/s);
    expect(timelineStyles).toMatch(/prefers-reduced-motion: reduce[^}]+\.model-waiting i[^}]+animation: none/s);
    expect(timelineStyles).toMatch(/prefers-reduced-motion: reduce.*\.activity-entry-enter-active\s*\{\s*transition: none/s);
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
it("aggregates model requests into statistics and keeps the active request separate from file history", async () => {
    const { root, props } = mount(false);
    props.events = [tool(1, "TOOL_FINISHED", { ok: true, path: "src/A.java" }), fact(2, "MODEL_REQUEST"),
        tool(3, "TOOL_FINISHED", { ok: true, path: "src/B.java" }), fact(4, "MODEL_REQUEST"),
        tool(5, "TOOL_FINISHED", { ok: true, tool: "list_files", path: ".github" }), fact(6, "MODEL_REQUEST")];
    await settle();
    const group = root.querySelector<HTMLDetailsElement>(".exploration-summary")!;
    expect(group.open).toBe(false);
    expect(group.querySelector("summary")?.textContent).toContain("探索仓库文件");
    expect(group.querySelector("summary")?.textContent).toContain("3 次文件操作");
    expect(group.querySelector("summary")?.textContent).toContain("src/A.java · src/B.java · .github");
    expect(group.querySelector("li")).toBeNull();
    expect(root.querySelector('.current-activity .model-waiting')).not.toBeNull();
    group.querySelector("summary")!.click(); await settle();
    expect(group.open).toBe(true);
    expect([...group.querySelectorAll("li[data-sequence]")].map(row => row.getAttribute("data-sequence")))
        .toEqual(["1", "3", "5"]);
    expect([...root.querySelectorAll(".agent-timeline li")].map(row => row.getAttribute("data-sequence")))
        .toEqual([]);
    expect(group.querySelector(".is-active")).toBeNull();
    expect(props.events.map(event => event.sequence)).toEqual([1, 2, 3, 4, 5, 6]);
});
it("keeps patch, checks and security failures in the chronological feed and individual file outcomes in the aggregate", async () => {
    const { root, props } = mount();
    const reads = (from: number) => [0, 1, 2].map(offset => tool(from + offset, "TOOL_FINISHED", { ok: true }));
    props.events = [...reads(1), tool(4, "TOOL_FINISHED", { tool: "apply_patch", ok: true, classification: "PATCH_CONFLICT" }),
        ...reads(5), tool(8, "TOOL_FINISHED", { tool: "run_checks", ok: false, classification: "TESTS_FAILED" }),
        ...reads(9), tool(12, "TOOL_FINISHED", { ok: false, classification: "TOOL_ERROR" }), tool(13, "TOOL_STARTED"),
        tool(14, "TOOL_FINISHED", { ok: false, classification: "POLICY_REJECTED", toolCallId: "different" })];
    await settle();
    expect(root.querySelectorAll(".exploration-summary")).toHaveLength(1);
    for (const sequence of [8, 14]) {
        expect(root.querySelector(`[data-sequence="${sequence}"]`)?.closest(".agent-timeline")).not.toBeNull();
    }
    expect(root.querySelector('[data-sequence="4"]')?.closest('.patch-operations')).not.toBeNull();
    expect(root.querySelector('.patch-count')?.textContent).toContain('冲突 1');
    expect(root.querySelector('[data-sequence="12"]')?.getAttribute("data-tone")).toBe("failed");
    expect(root.querySelector('[data-sequence="13"]')?.getAttribute("data-tone")).toBe("unknown");
    expect(root.querySelector(".exploration-attention")?.textContent).toContain("1 次失败 · 1 次结果待核对");
});
it("represents every loaded file operation without a cap while rendering expanded history incrementally", async () => {
    const { root, props } = mount(false);
    props.events = Array.from({ length: 100 }, (_, index) => tool(index + 1, "TOOL_FINISHED", { ok: true })); await settle();
    expect(root.querySelector(".exploration-count")?.textContent).toContain("100 次文件操作");
    expect(root.querySelector(".exploration-operations")).toBeNull();
    expect(root.querySelector(".agent-timeline li")).toBeNull();
    root.querySelector<HTMLElement>(".exploration-summary summary")!.click(); await settle();
    expect(root.querySelectorAll(".exploration-operations li")).toHaveLength(40);
    root.querySelector<HTMLButtonElement>(".more-exploration")!.click(); await settle();
    expect(root.querySelectorAll(".exploration-operations li")).toHaveLength(80);
    root.querySelector<HTMLButtonElement>(".more-exploration")!.click();
    await settle();
    expect(root.querySelectorAll(".exploration-operations li")).toHaveLength(100);
    expect(root.querySelector('[data-sequence="100"]')).not.toBeNull();
    expect(root.querySelector(".more-exploration")).toBeNull();
});

it("more than thirty mixed operations update the same summary and rows never relocate after completion", async () => {
    const { root, props } = mount(false);
    const summary = root.querySelector<HTMLDetailsElement>(".exploration-summary")!;
    const operations = ["list_files", "read_file", "search_code"];
    for (let index = 0; index < 36; index++) {
        props.events.push(tool(index * 2 + 1, "TOOL_FINISHED", { tool: operations[index % 3], ok: true, path: `src/File${index}.java` }),
            fact(index * 2 + 2, "MODEL_REQUEST"));
        await settle();
        expect(root.querySelector(".exploration-summary")).toBe(summary);
        expect(root.querySelectorAll(".agent-timeline [data-kind='read_file'], .agent-timeline [data-kind='list_files'], .agent-timeline [data-kind='search_code']")).toHaveLength(0);
    }
    expect(root.querySelectorAll(".exploration-summary")).toHaveLength(1);
    expect(summary.textContent).toContain("36 次文件操作 · 读取 12 · 列目录 12 · 搜索 12");
    expect(root.querySelector(".exploration-previews")?.textContent?.split(" · ")).toHaveLength(4);
    summary.querySelector("summary")!.click(); await settle();
    props.events.push(tool(73, "TOOL_STARTED", { toolCallId: "11111111-1111-4111-8111-111111111111", path: "src/Latest.java" })); await settle();
    const row = summary.querySelector('[data-sequence="73"]')!;
    expect(row.classList.contains("is-active")).toBe(true);
    expect(summary.querySelector("summary [role='status']")?.textContent).toContain("读取文件进行中");
    props.events.push(tool(74, "TOOL_FINISHED", { toolCallId: "11111111-1111-4111-8111-111111111111", path: "src/Latest.java", ok: true })); await settle();
    props.events.push(fact(75, "MODEL_REQUEST")); await settle();
    expect(summary.querySelector('[data-sequence="73"]')).toBe(row);
    expect(row.getAttribute("data-tone")).toBe("completed");
    expect(root.querySelector(".exploration-summary")).toBe(summary);
    expect(summary.querySelector(".exploration-count")?.textContent).toContain("37 次文件操作");
    expect(root.querySelectorAll(".agent-timeline li")).toHaveLength(0);
    expect(props.events.at(-1)?.sequence).toBe(75);
});

it("exploration expansion is independent of chronological history and resets when selecting another Run", async () => {
    const { root, props } = mount(false);
    props.events = Array.from({ length: 80 }, (_, index) => fact(index + 1, "FINALIZING"));
    props.events.push(tool(81, "TOOL_FINISHED", { ok: true })); await settle();
    const summary = root.querySelector<HTMLDetailsElement>(".exploration-summary")!;
    summary.querySelector("summary")!.click(); await settle();
    root.querySelector<HTMLButtonElement>(".earlier-activity")!.click(); await settle();
    expect(summary.open).toBe(true);
    expect(root.querySelectorAll(".agent-timeline li")).toHaveLength(80);
    root.querySelector<HTMLButtonElement>(".latest-activity")!.click(); await settle();
    expect(summary.open).toBe(true);
    expect(root.querySelectorAll(".agent-timeline li")).toHaveLength(40);
    props.run.id = "two"; props.events = []; await settle();
    expect(root.querySelector(".exploration-summary")).not.toBe(summary);
    expect(root.querySelector<HTMLDetailsElement>(".exploration-summary")?.open).toBe(false);
    expect(root.querySelector(".exploration-count")?.textContent).toContain("0 次文件操作");
});

it("renders only allowlisted policy rule and stage facts, with legacy diagnostics remaining absent", async () => {
    const { root, props } = mount();
    props.events = [tool(1, "TOOL_FINISHED", { tool: "apply_patch", ok: false, classification: "POLICY_REJECTED",
        policyRule: "SOURCE_CREDENTIALS", policyStage: "PATCH_SOURCE" })]; await settle();
    expect(root.querySelector(".activity-facts")?.textContent).toContain("拒绝规则SOURCE_CREDENTIALS");
    expect(root.querySelector(".activity-facts")?.textContent).toContain("拒绝阶段PATCH_SOURCE");
    props.events = [tool(1, "TOOL_FINISHED", { tool: "apply_patch", ok: false, classification: "POLICY_REJECTED",
        policyRule: "private/protected/path", policyStage: "raw-patch" })]; await settle();
    expect(root.textContent).not.toContain("private/protected/path");
    expect(root.textContent).not.toContain("raw-patch");
    expect(root.querySelector(".activity-facts")).toBeNull();
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

it("updates one persistent current surface through real requests, file work and terminal state", async () => {
    const { root, props } = mount(false);
    const surface = root.querySelector('.current-activity')!;
    props.run.modelCalls = 10;
    props.events = Array.from({ length: 10 }, (_, index) => fact(index + 1, "MODEL_REQUEST", `request=${index + 1}`));
    props.events.push(fact(11, "MODEL_REQUEST", "request=11")); await settle();
    expect(root.querySelector('.current-activity')).toBe(surface);
    expect(surface.textContent).toContain("正在请求模型 · 第 11 次请求");
    expect(root.querySelectorAll('.model-waiting')).toHaveLength(1);
    expect(root.querySelectorAll('.agent-timeline [data-kind="MODEL_REQUEST"]')).toHaveLength(0);
    expect(root.querySelector('.model-statistics')?.textContent).toBe("模型调用 10 次");
    props.events.push(tool(12, "TOOL_STARTED")); await settle();
    expect(root.querySelector('.current-activity')).toBe(surface);
    expect(surface.textContent).toContain("正在读取文件");
    expect(surface.textContent).toContain("src/Counter.java");
    expect(root.querySelector('.model-waiting')).toBeNull();
    expect(root.querySelector('.exploration-current')?.textContent).toContain("读取文件进行中");
    props.events.push(tool(13, "TOOL_FINISHED", { ok: true }), fact(14, "FINALIZING"));
    props.run.state = "FINALIZING"; await settle();
    expect(surface.textContent).toContain("正在冻结 Candidate");
    props.run.state = "SUCCEEDED"; props.events.push(fact(15, "SUCCEEDED")); await settle();
    expect(surface.getAttribute('data-active')).toBe('false');
    expect(surface.textContent).toContain("Candidate 已冻结");
    expect(root.querySelector('.model-waiting, .is-active')).toBeNull();
    expect(props.events).toHaveLength(15);
});

it("keeps important milestones chronological and explains a known pre-mutation refusal without claiming success", async () => {
    const { root, props } = mount(false);
    props.events = [fact(1, "SANDBOX_READY"), fact(2, "MODEL_REQUEST"),
        tool(3, "TOOL_STARTED", { tool: "apply_patch", path: undefined }),
        tool(4, "TOOL_FINISHED", { tool: "apply_patch", path: undefined, ok: false, classification: "POLICY_REJECTED",
            policyRule: "SOURCE_CREDENTIALS", policyStage: "PATCH_SOURCE", resultRecorded: true, mutationNotStarted: true, alternativeAllowed: true })];
    await settle();
    expect(root.querySelector('.current-title')?.textContent).toContain("代码修改被安全策略拒绝");
    expect(root.querySelector('.patch-alert')?.textContent).toContain('1 次安全拒绝');
    root.querySelector<HTMLElement>('.patch-summary summary')!.click(); await settle();
    const refusal = root.querySelector('.patch-operations [data-sequence="3"]')!;
    expect(refusal.getAttribute('data-tone')).toBe('blocked');
    expect(refusal.textContent).toContain("源码修改未开始");
    expect(refusal.textContent).toContain("可调整方案后继续本次运行");
    expect(refusal.querySelector('.activity-uncertain')).toBeNull();
    expect(root.querySelector('.exploration-count')?.textContent).toContain("0 次文件操作");
    props.events.push(fact(5, "MODEL_REQUEST", "request=2"), important(6), fact(7, "FINALIZING"), fact(8, "FAILED", "POLICY_REJECTED"));
    props.run.state = "FAILED"; props.run.reason = "POLICY_REJECTED"; await settle();
    expect([...root.querySelectorAll('.agent-timeline li')].map(row => row.getAttribute('data-sequence'))).toEqual(['1', '7', '8']);
    await expandChecks(root);
    expect(root.querySelector('.check-history [data-sequence="6"]')).not.toBeNull();
    expect(root.querySelector('.current-activity')?.textContent).toContain("运行失败");
    expect(root.querySelector('.model-waiting, .is-active')).toBeNull();
});

it("only labels a known pre-mutation source refusal as unmodified, including terminal budget exhaustion", async () => {
    const { root, props } = mount(false);
    root.querySelector<HTMLElement>('.patch-summary summary')!.click(); await settle();
    props.events = [tool(1, "TOOL_FINISHED", { tool: "apply_patch", ok: false, resultRecorded: false,
        classification: "POLICY_REJECTED", policyRule: "SOURCE_CREDENTIALS", policyStage: "PATCH_SOURCE", mutationNotStarted: true })];
    await settle();
    expect(root.querySelector('.activity-facts')?.textContent).toContain("源码修改未开始");
    expect(root.querySelector('.activity-uncertain')).toBeNull();
    expect(root.textContent).not.toContain("可调整方案后继续本次运行");
    props.events = [tool(1, "TOOL_FINISHED", { tool: "apply_patch", ok: false, resultRecorded: false,
        classification: "POLICY_REJECTED", policyRule: "SOURCE_CAPTURE_LIMIT", policyStage: "SOURCE_CAPTURE", mutationNotStarted: true })];
    await settle();
    expect(root.querySelector('.activity-uncertain')).not.toBeNull();
    expect(root.querySelector('.current-detail')?.textContent).not.toContain("未修改源码");
    expect(root.querySelector('.activity-facts')?.textContent).not.toContain("源码修改未开始");
});

it("updates one patch summary in place with actual outcomes and only applied safe paths", async () => {
    const { root, props } = mount(false);
    const summary = root.querySelector<HTMLDetailsElement>('.patch-summary')!;
    props.events = [tool(1, 'TOOL_STARTED', {tool:'apply_patch',path:undefined})]; await settle();
    expect(summary.open).toBe(false);
    expect(summary.textContent).toContain('1 次尝试');
    expect(summary.textContent).toContain('正在应用代码修改');
    expect(root.querySelector('.agent-timeline [data-kind="apply_patch"]')).toBeNull();
    summary.querySelector('summary')!.click(); await settle();
    const row = summary.querySelector('li')!;
    props.events.push(tool(2,'TOOL_FINISHED',{tool:'apply_patch',path:undefined,ok:true,classification:'PATCH_APPLIED',paths:['src/Actual.java']})); await settle();
    expect(summary.querySelector('li')).toBe(row);
    props.events.push(tool(3,'TOOL_FINISHED',{tool:'apply_patch',toolCallId:'22222222-2222-4222-8222-222222222222',path:undefined,
        ok:false,classification:'PATCH_CONFLICT',mutationNotStarted:true,paths:['src/Unapplied.java'],conflictReason:'CONTEXT_MISMATCH'}),
        tool(4,'TOOL_FINISHED',{tool:'apply_patch',toolCallId:'33333333-3333-4333-8333-333333333333',path:undefined,ok:false,classification:'POLICY_REJECTED'}),
        tool(5,'TOOL_STARTED',{tool:'apply_patch',toolCallId:'44444444-4444-4444-8444-444444444444',path:undefined}), fact(6,'FAILED'));
    props.run.state='FAILED'; await settle();
    expect(root.querySelector('.patch-summary')).toBe(summary);
    expect(summary.querySelector('.patch-count')?.textContent).toContain('4 次尝试 · 已应用 1 · 冲突 1 · 安全拒绝 1 · 结果不明 1');
    expect(summary.querySelector('.patch-paths')).toBeNull();
    expect(summary.querySelector('.patch-operations')?.textContent).toContain('src/Actual.java');
    expect(summary.querySelector('[data-sequence="3"]')?.textContent).toContain('未应用，已有修改保留');
    expect(summary.querySelector('.patch-operations')?.textContent).not.toContain('src/Unapplied.java');
    expect(root.querySelector('.patch-alert')?.textContent).toContain('1 次安全拒绝');
    expect([...summary.querySelectorAll('li')].map(node=>node.getAttribute('data-sequence'))).toEqual(['1','3','4','5']);
    expect(root.querySelector('.activity-bar,.model-waiting,.is-active')).toBeNull();
});

it.each(['apply_patch', 'edit_file'])("keeps long %s histories exclusively in the complete edit summary", async editTool => {
    const {root,props}=mount(false);
    props.events=Array.from({length:100},(_,index)=>tool(index+1,'TOOL_FINISHED',{tool:editTool,ok:true,classification:'PATCH_APPLIED',paths:['src/A.java']}));
    await settle();
    expect(root.querySelectorAll('.agent-timeline li')).toHaveLength(0);
    expect(root.querySelector('.patch-operations')).toBeNull();
    expect(root.querySelector('.patch-count')?.textContent).toContain('100 次尝试');
    root.querySelector<HTMLElement>('.patch-summary summary')!.click(); await settle();
    expect(root.querySelectorAll('.patch-operations li')).toHaveLength(40);
    root.querySelector<HTMLButtonElement>('.more-patches')!.click(); await settle();
    expect(root.querySelectorAll('.patch-operations li')).toHaveLength(80);
    root.querySelector<HTMLButtonElement>('.more-patches')!.click(); await settle();
    expect(root.querySelectorAll('.patch-operations li')).toHaveLength(100);
    expect(root.querySelector<HTMLDetailsElement>('.exploration-summary')!.open).toBe(false);
    props.run.id='two'; props.events=[]; await settle();
    expect(root.querySelector<HTMLDetailsElement>('.patch-summary')!.open).toBe(false);
});

it.each(['apply_patch', 'edit_file'])("preserves live %s status, refusals and lifecycle milestones without duplicate edit rows", async editTool => {
    const { root, props } = mount(false);
    props.events = [fact(1, 'CREATED'), fact(2, 'SANDBOX_READY'),
        tool(3, 'TOOL_STARTED', { tool: editTool })];
    await settle();
    expect(root.querySelector('.current-activity')?.getAttribute('data-active')).toBe('true');
    expect(root.querySelector('.current-title')?.textContent)
        .toContain(editTool === 'edit_file' ? '正在精确编辑代码' : '正在应用代码修改');
    expect(root.querySelector('.activity-bar')).not.toBeNull();
    expect(root.querySelectorAll('.agent-timeline li')).toHaveLength(2);
    props.events.push(tool(4, 'TOOL_FINISHED', { tool: editTool, ok: false,
        classification: 'POLICY_REJECTED', mutationNotStarted: true,
        policyRule: 'SOURCE_CREDENTIALS', policyStage: 'PATCH_SOURCE' }),
        tool(5, 'TOOL_FINISHED', { tool: 'read_file', ok: true }), important(6),
        fact(7, 'FAILED', 'POLICY_REJECTED'));
    props.run.state = 'FAILED'; props.run.reason = 'POLICY_REJECTED';
    await settle();
    expect([...root.querySelectorAll('.agent-timeline li')].map(row => row.getAttribute('data-sequence')))
        .toEqual(['1', '2', '7']);
    await expandChecks(root);
    expect(root.querySelector('.check-history [data-sequence="6"]')).not.toBeNull();
    expect(root.querySelector('.patch-alert')?.textContent).toContain('1 次安全拒绝');
    expect(root.querySelector('.exploration-count')?.textContent).toContain('1 次文件操作');
    expect(root.querySelector('.current-title')?.textContent).toContain('运行失败');
    expect(root.querySelector('.activity-bar')).toBeNull();
    root.querySelector<HTMLElement>('.patch-summary summary')!.click(); await settle();
    expect(root.querySelectorAll('.patch-operations li')).toHaveLength(1);
    expect(root.querySelector('.patch-operations')?.textContent).toContain('源码修改未开始');
    props.events = [...props.events]; await settle();
    expect(root.querySelectorAll('.patch-operations li')).toHaveLength(1);
});

it("animates genuinely active work and supplies static reduced-motion states", async () => {
    const {root,props}=mount(false);
    props.events=[fact(1,'MODEL_REQUEST','request=61')]; await settle();
    expect(root.querySelectorAll('.activity-bar')).toHaveLength(1);
    expect(root.querySelector('.current-title')?.textContent).toContain('第 61 次请求');
    props.run.cancelRequested=true; await settle();
    expect(root.querySelector('.activity-bar,.model-waiting')).toBeNull();
    const css=readFileSync('src/components/AgentTimeline.vue','utf8');
    expect(css).toMatch(/prefers-reduced-motion: reduce[^}]+\.activity-bar::after[^}]+animation: none/s);
    expect(css).toContain('transition: opacity 180ms');
    expect(css).toContain('data-terminal="true"] .counter-values { animation: none');
});

it("keeps legacy conflicts visible without retroactively claiming a pre-mutation result", async () => {
    const {root,props}=mount(false);
    props.events=[tool(1,'TOOL_FINISHED',{tool:'apply_patch',ok:true,classification:'PATCH_CONFLICT'})]; await settle();
    expect(root.querySelector('.patch-count')?.textContent).toContain('冲突 1');
    expect(root.querySelector('.patch-count')?.textContent).toContain('结果不明 1');
    expect(root.querySelector('.patch-alert')?.textContent).toContain('1 次结果不明');
    root.querySelector<HTMLElement>('.patch-summary summary')!.click(); await settle();
    expect(root.querySelector('.patch-operations .activity-uncertain')).not.toBeNull();
    expect(root.querySelector('.patch-operations')?.textContent).not.toContain('未应用，已有修改保留');
});


it("terminal MODEL_PROTOCOL takes precedence over an earlier recoverable source rejection", async () => {
    const { root, props } = mount(false);
    props.events = [tool(1, "TOOL_FINISHED", { tool: "apply_patch", ok: false, classification: "POLICY_REJECTED",
        policyRule: "SOURCE_CREDENTIALS", policyStage: "PATCH_SOURCE", mutationNotStarted: true, alternativeAllowed: true,
        resultRecorded: true }), fact(2, "MODEL_REQUEST", "request=11"), fact(3, "FAILED", "MODEL_PROTOCOL")];
    props.run.state = "FAILED"; props.run.reason = "MODEL_PROTOCOL"; props.run.modelCalls = 10; await settle();
    expect(root.querySelector('.current-title')?.textContent).toContain("运行失败");
    expect(root.querySelector('.current-detail')?.textContent).toBe("MODEL_PROTOCOL");
    expect(root.querySelector('.patch-alert')?.textContent).toContain("1 次安全拒绝");
    root.querySelector<HTMLElement>('.patch-summary summary')!.click(); await settle();
    expect(root.querySelector('.patch-operations')?.textContent).toContain("SOURCE_CREDENTIALS");
    expect(root.querySelector('.model-waiting, .is-active')).toBeNull();
});

it("terminal event and safe model subcategory are visible before the Run snapshot catches up", async () => {
    const { root, props } = mount(false);
    props.run.modelCalls = 10;
    props.events = [fact(1, "MODEL_REQUEST", "request=11"), fact(2, "MODEL_FAILED", JSON.stringify({
        metadataVersion: 1, classification: "MODEL_PROTOCOL", reason: "RESPONSE_TRUNCATED", request: 11,
        stage: "RESPONSE", callReturned: false, responsePresent: true, resultPresent: true, outputPresent: true,
        finishReason: "LENGTH", responseTokenLimit: 8192, toolCallCount: 1, reportedTokens: 8212,
        rawResponse: "private-payload-must-not-render" })), fact(3, "FAILED", "MODEL_PROTOCOL")];
    await settle();
    expect(root.querySelector('.current-title')?.textContent).toContain("运行失败");
    expect(root.querySelector('.current-detail')?.textContent).toContain("MODEL_PROTOCOL");
    expect(root.querySelector('.current-detail')?.textContent).toContain("RESPONSE_TRUNCATED");
    const failed = root.querySelector('[data-kind="MODEL_FAILED"]')!;
    expect(failed.getAttribute('data-tone')).toBe('failed');
    expect(failed.textContent).toContain("模型请求失败");
    expect(failed.textContent).toContain("LENGTH");
    expect(failed.textContent).toContain("8,212");
    expect(root.querySelector('.model-statistics')?.textContent).toContain("10");
    expect(root.textContent).not.toContain("private-payload");
    expect(root.querySelector('.model-waiting, .is-active')).toBeNull();
});

it("unknown model metadata and a mismatched older failure cannot replace the current terminal reason", async () => {
    const { root, props } = mount(false);
    props.events = [fact(1, "MODEL_FAILED", JSON.stringify({ metadataVersion: 1, classification: "MODEL_PROTOCOL",
        reason: "private-reason", finishReason: "private-finish", stage: "private-stage", rawResponse: "private-data" })),
        fact(2, "FAILED", "MODEL_PROTOCOL")];
    props.run.state = "FAILED"; props.run.reason = "MODEL_TRANSPORT"; await settle();
    expect(root.querySelector('.current-detail')?.textContent).toBe("MODEL_TRANSPORT");
    expect(root.textContent).not.toContain("private-");
});

it("shows bounded response recovery without declaring the live Run failed", async () => {
    const { props, root } = mount();
    props.events = [fact(1, "MODEL_FAILED", JSON.stringify({ metadataVersion: 1,
        classification: "MODEL_PROTOCOL", reason: "RESPONSE_TRUNCATED", request: 2,
        finishReason: "LENGTH", reportedTokens: 17 })), fact(2, "MODEL_RESPONSE_RECOVERY")];
    await settle();
    expect(root.textContent).toContain("正在重新请求较小的模型响应");
    expect(root.textContent).toContain("截断内容未执行，保留已完成修改");
    expect(root.textContent).toContain("模型请求失败");
    expect(root.textContent).not.toContain("当前活动 运行失败");
});

it("presents exact source edits as applied patches without exposing replacement text", async () => {
    const { root, props } = mount();
    props.events = [tool(1, "TOOL_STARTED", { tool: "edit_file", path: "src/Example.java" }),
        tool(2, "TOOL_FINISHED", { tool: "edit_file", path: "src/Example.java", ok: true,
            classification: "PATCH_APPLIED", paths: ["src/Example.java"], targetState: "available" })];
    await settle();
    expect(root.querySelector('.patch-summary')?.textContent).toContain("src/Example.java");
    expect(root.querySelector('.patch-summary [data-tone="completed"]')).not.toBeNull();
});

it("shows safe correction after rejected new credential content without claiming a write", async () => {
    const { root, props } = mount();
    props.events = [tool(1, "TOOL_STARTED", { tool: "edit_file", path: "src/ExampleTest.java" }),
        tool(2, "TOOL_FINISHED", { tool: "edit_file", ok: false, classification: "POLICY_REJECTED",
            policyRule: "PATCH_CREDENTIALS", policyStage: "PATCH_CONTENT", mutationNotStarted: true,
            alternativeAllowed: true, resultRecorded: true }), fact(3, "MODEL_REQUEST")];
    await settle();
    expect(root.querySelector('.patch-summary')?.textContent).toContain("PATCH_CREDENTIALS");
    expect(root.querySelector('.patch-summary')?.textContent).toContain("未开始");
    expect(root.querySelector('.current-activity')?.getAttribute('data-active')).toBe('true');
});

it("shows only numeric proposal locations when new content is refused", async () => {
    const { root, props } = mount();
    props.events = [tool(1, "TOOL_FINISHED", { tool: "edit_file", ok: false, classification: "POLICY_REJECTED",
        policyRule: "PATCH_CREDENTIALS", policyStage: "PATCH_CONTENT", mutationNotStarted: true,
        alternativeAllowed: true, resultRecorded: true,
        rejectedInput: { field: "new_text", lines: [2, 4], value: "private-body" } })];
    await settle();
    expect(root.querySelector('.patch-summary')?.textContent).toContain("new_text 第 2、4 行");
    expect(root.textContent).not.toContain("private-body");
});


it("summarizes 100 checks without red intermediate milestones and retains every ordered result", async () => {
    const { root, props } = mount(false);
    props.events = [fact(1, "CREATED"), ...Array.from({length: 100}, (_, index) =>
        fact(index + 2, "TOOL_FINISHED", JSON.stringify({tool: "run_checks", ok: index === 99,
            classification: index === 99 ? "TESTS_PASSED" : "TESTS_FAILED", executedTests: 12,
            failures: index === 99 ? 0 : 1, errors: 0, skipped: 2}))), fact(102, "SUCCEEDED")];
    props.run.state = "SUCCEEDED"; await settle();
    expect(root.querySelectorAll(".agent-timeline li")).toHaveLength(2);
    expect(root.querySelector(".check-history")).toBeNull();
    expect(root.querySelector(".check-latest")?.textContent).toBe("测试通过");
    expect(root.querySelector(".check-count")?.textContent).toContain("100 次尝试 · 历史 99 次未通过");
    expect(root.querySelector(".check-detail")?.textContent).toContain("已执行 12 项 · 失败 0 · 错误 0 · 跳过 2");
    await expandChecks(root);
    expect(root.querySelectorAll(".check-history li")).toHaveLength(40);
    root.querySelector<HTMLButtonElement>(".more-checks")!.click(); await settle();
    root.querySelector<HTMLButtonElement>(".more-checks")!.click(); await settle();
    expect([...root.querySelectorAll(".check-history li")].map(node => node.getAttribute("data-sequence")))
        .toEqual(Array.from({length:100},(_,i)=>String(101-i)));
    expect(root.querySelector('.check-history [data-sequence="2"]')?.getAttribute("data-tone")).toBe("failed");
    props.run.id = "two"; props.events = [{...important(1),runId:"two"}]; await settle();
    expect(root.querySelector<HTMLDetailsElement>(".check-summary")!.open).toBe(false);
    expect(root.querySelector(".check-count")?.textContent).toBe("1 次尝试");
});
it("keeps a source mutation refusal and terminal failure visible outside collapsed check history", async () => {
    const {root,props}=mount(false);
    props.events=[tool(1,"TOOL_FINISHED",{tool:"run_checks",ok:false,classification:"POLICY_REJECTED",
        policyRule:"CHECK_MUTATED_SOURCE",policyStage:"TOOL_EXECUTION",resultRecorded:false}),fact(2,"FAILED","POLICY_REJECTED")];
    props.run.state="FAILED";await settle();
    const warning=root.querySelector('.check-blocker[role="alert"]')!;
    expect(warning.closest("details")).toBeNull();
    expect(warning.textContent).toContain("检测到文件变化");
    expect(root.querySelector('.agent-timeline [data-sequence="2"]')?.getAttribute("data-tone")).toBe("failed");
    expect(root.querySelector<HTMLDetailsElement>(".check-summary")!.open).toBe(false);
    expect(root.querySelector(".check-latest")?.textContent).not.toContain("测试通过");
});

it("renders accepted plan closure once as a completed ordinary activity", async () => {
    const { root, props } = mount();
    props.run.state = "SUCCEEDED";
    props.events = [fact(1, "DELIVERY_COMPLETED"), fact(2, "PLAN_COMPLETED")];
    await settle();
    const closure = root.querySelector('[data-sequence="2"]')!;
    expect(closure.getAttribute("data-tone")).toBe("completed");
    expect(closure.querySelector("strong")?.textContent).toBe("演化计划已完成");
    expect(root.querySelectorAll('[data-sequence="2"]')).toHaveLength(1);
    expect(root.querySelectorAll(".is-active")).toHaveLength(0);
    expect(root.querySelector("pre")).toBeNull();
});

it("restores persisted preflight milestones without inventing agent calls", async () => {
    const { root, props } = mount(); props.run.state = "PREPARING";
    props.events = [fact(1, "SANDBOX_READY"), fact(2, "PREFLIGHT_PROGRESS", JSON.stringify({
        stage: "MAVEN_CHECK", status: "STARTED", directory: "backend/bookstore", goal: "test", durationMs: 0 }))];
    await settle();
    expect(root.querySelector(".current-activity")?.textContent).toContain("后端 Maven 检查");
    expect(root.querySelector(".current-activity")?.textContent).toContain("backend/bookstore");
    expect(root.querySelector('[data-kind="run_checks"]')).toBeNull();
    props.events = [...props.events, fact(3, "PREFLIGHT_PROGRESS", JSON.stringify({ stage: "DEPENDENCY_PREPARATION",
        status: "COMPLETED", directory: "backend/bookstore", goal: "test", classification: "PREPARATION_FINISHED", durationMs: 1234 }))];
    await settle();
    expect(root.textContent).toContain("受控依赖准备");
    expect(root.textContent).toContain("1,234 ms");
    expect(root.querySelector(".current-activity")?.textContent).not.toContain("准备 Docker 沙箱");
    expect(root.querySelector(".current-activity")?.textContent).toContain("后端 Maven 检查 · test · 进行中");
    props.events = [...props.events, fact(4, "PREFLIGHT_PROGRESS", JSON.stringify({ stage: "MAVEN_CHECK",
        status: "COMPLETED", directory: "backend/bookstore", goal: "test", classification: "TESTS_PASSED", durationMs: 2000 }))];
    await settle();
    expect(root.querySelector(".current-activity")?.textContent).toContain("后端 Maven 检查 · test · 已结束");
});

it("only a later confirmed release resolves a cleanup warning", async () => {
    const { resourcesBlocked } = await import("../src/components/agentEventPresentation");
    const blocked = fact(1, "CLEANUP_BLOCKED");
    expect(resourcesBlocked([blocked], "one")).toBe(true);
    expect(resourcesBlocked([blocked, fact(2, "RESOURCES_RELEASED")], "one")).toBe(false);
    expect(resourcesBlocked([blocked, fact(2, "RESOURCES_RELEASED"), fact(3, "RECONCILIATION_BLOCKED")], "one")).toBe(true);
    expect(resourcesBlocked([blocked, { ...fact(2, "RESOURCES_RELEASED"), runId: "different" }], "one")).toBe(true);
});


it("shows current generic checks separately, preserves retries, and invalidates them after shell edits", async () => {
    const { root, props } = mount(false);
    const command = (sequence: number, exitCode: number) => tool(sequence, "TOOL_FINISHED", {
        tool: "exec_command", toolCallId: `call-${sequence}`, invocationId: `invocation-${sequence}`,
        command: "sh check.sh", directory: ".", scope: "README counter regression", kind: "test",
        snapshot: "a".repeat(64), afterSnapshot: "a".repeat(64), exitCode,
        classification: exitCode ? "COMMAND_FAILED" : "COMMAND_SUCCEEDED", ok: !exitCode,
        testDetails: "UNAVAILABLE", stdout: "actual output", timedOut: false, cancelled: false,
    });
    props.events = [command(1, 1), command(2, 0)];
    await settle();
    expect(root.querySelectorAll(".current-check-list li")).toHaveLength(1);
    expect(root.querySelector(".current-check-list")?.textContent).toContain("命令成功");
    expect(root.querySelector(".check-history")).toBeNull();
    await expandChecks(root);
    expect([...root.querySelectorAll(".check-history li")].map(row => row.getAttribute("data-sequence"))).toEqual(["2", "1"]);
    expect(root.querySelector(".check-history")?.textContent).toContain("命令失败");
    expect(root.textContent).not.toContain("已执行 0");
    props.events.push(tool(3, "TOOL_FINISHED", { tool: "exec_command", toolCallId: "write", command: "sh update.sh",
        ok: true, classification: "COMMAND_SUCCEEDED", snapshot: "a".repeat(64), afterSnapshot: "b".repeat(64), sourceChanged: true }));
    await settle();
    expect(root.querySelectorAll(".current-check-list li")).toHaveLength(0);
    expect(root.textContent).toContain("部分必要检查需要重新运行");
});
