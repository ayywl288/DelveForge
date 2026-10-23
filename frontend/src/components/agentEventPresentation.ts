import type { AgentEvent, AgentRun } from "../api/agentTypes";

export type ActivityOutcome = "running" | "completed" | "failed" | "conflict" | "unknown" | "recorded" | "blocked" | "interrupted";
export interface AgentActivity {
    key: string;
    event: AgentEvent;
    finish?: AgentEvent;
    tool?: string;
    target?: string;
    classification?: string;
    duration?: number;
    outcome: ActivityOutcome;
    active: boolean;
}
export const toolNames: Record<string, string> = {
    list_files: "浏览文件", read_file: "读取文件", search_code: "搜索代码", apply_patch: "应用代码修改",
    run_checks: "运行 Maven 检查",
};
const activeKinds: Partial<Record<AgentRun["state"], string[]>> = {
    CREATED: ["CREATED"], PREPARING: ["PREPARING"], RUNNING: ["MODEL_REQUEST", "COMPLETION_FORMAT_RETRY", "RUNNING"],
    FINALIZING: ["FINALIZING"],
};
const credentialShape = /\b(?:(?:AKIA|ASIA)[0-9A-Z]{16}|(?:gh[pousr]|github_pat)_[A-Za-z0-9_]{16,}|sk-[A-Za-z0-9_-]{16,}|xox[baprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{30,}|ya29\.[0-9A-Za-z_-]{20,}|eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,})\b/;

export function summaryFields(event: AgentEvent): Record<string, unknown> {
    try {
        const value: unknown = JSON.parse(event.summary);
        if (value && typeof value === "object" && !Array.isArray(value)) return value as Record<string, unknown>;
    } catch { /* 历史或畸形摘要不作为自由文本显示。 */ }
    return {};
}

export function recordedTool(event: AgentEvent) {
    const data = summaryFields(event);
    const name = typeof data.tool === "string" ? data.tool : event.summary.match(/^tool=([^;]+)/)?.[1];
    return name && Object.hasOwn(toolNames, name) ? name : undefined;
}

export function safePath(value: unknown): string | undefined {
    if (value === ".") return "仓库根目录";
    if (typeof value !== "string" || value.length > 256 || credentialShape.test(value)
        || !/^[\p{L}\p{N}_./-]+$/u.test(value) || value.startsWith("/")) return;
    if (value.split("/").some(part => !part || part.startsWith(".") || part.endsWith(".")
        || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part)
        || /^(id_(rsa|dsa|ecdsa|ed25519)|credentials|service[-_]account)/i.test(part)
        || /\.(pem|key|p12|pfx|jks|keystore|env)$/i.test(part) || /sk-[a-z0-9]{16,}/i.test(part))) return;
    return value;
}

function correlation(event: AgentEvent) {
    const data = summaryFields(event);
    const tool = recordedTool(event);
    return data.metadataVersion === 1 && tool && typeof data.toolCallId === "string"
        && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(data.toolCallId)
        ? `${event.runId}:${tool}:${data.toolCallId}` : undefined;
}

function safeClassification(value: unknown) {
    return typeof value === "string" && /^[A-Z][A-Z0-9_]{0,63}$/.test(value)
        && value !== "RESULT" && !credentialShape.test(value) ? value : undefined;
}
export function classification(event: AgentEvent) {
    return safeClassification(summaryFields(event).classification);
}
export function numeric(data: Record<string, unknown>, key: string) {
    const value = data[key];
    return typeof value === "number" && Number.isFinite(value) && value >= 0 ? value : undefined;
}

function resultOutcome(event: AgentEvent, tool?: string): ActivityOutcome {
    const data = summaryFields(event);
    const code = classification(event);
    if (code === "PATCH_CONFLICT") return "conflict";
    if (data.ok === false || code && /(FAILED|FAILURE|ERROR|LIMIT|UNAVAILABLE|MISSING|REJECTED|MISMATCH|TIMEOUT)/.test(code)) return "failed";
    if (data.ok !== true || !tool) return "unknown";
    if (tool === "apply_patch") return code === "PATCH_APPLIED" ? "completed" : "unknown";
    if (tool === "run_checks") return ["TESTS_PASSED", "BUILD_PASSED_NOT_TESTED"].includes(code ?? "")
        && !(numeric(data, "failures") || numeric(data, "errors")) ? "completed" : "unknown";
    return "completed";
}

export function buildActivities(events: AgentEvent[], run: AgentRun): AgentActivity[] {
    const facts = events.filter(event => event.runId === run.id);
    const starts = new Map<string, AgentEvent[]>();
    const finishes = new Map<string, AgentEvent[]>();
    for (const event of facts) {
        const key = correlation(event);
        const map = event.kind === "TOOL_STARTED" ? starts : event.kind === "TOOL_FINISHED" ? finishes : null;
        if (key && map) map.set(key, [...map.get(key) ?? [], event]);
    }
    const hidden = new Set<number>();
    const activities: AgentActivity[] = [];
    const terminal = ["FAILED", "CANCELLED", "INTERRUPTED", "SUCCEEDED"].includes(run.state);
    for (const event of facts) {
        if (hidden.has(event.sequence)) continue;
        const key = correlation(event);
        const matched = key && starts.get(key)?.length === 1 && finishes.get(key)?.length === 1
            && event.kind === "TOOL_STARTED" ? finishes.get(key)![0] : undefined;
        const finish = matched && matched.sequence > event.sequence ? matched : undefined;
        if (finish) hidden.add(finish.sequence);
        const result = finish ?? event;
        const data = summaryFields(result);
        const startData = summaryFields(event);
        const toolEvent = ["TOOL_STARTED", "TOOL_FINISHED"].includes(event.kind);
        const tool = toolEvent ? recordedTool(event) : undefined;
        const pending = toolEvent && event.kind === "TOOL_STARTED" && !finish;
        const active = !terminal && !run.cancelRequested && result.sequence === facts.at(-1)?.sequence
            && (pending && run.state === "RUNNING" || activeKinds[run.state]?.includes(result.kind));
        let outcome: ActivityOutcome = toolEvent ? pending ? active ? "running" : "unknown" : resultOutcome(result, tool) : "recorded";
        if (!toolEvent) {
            if (event.kind.includes("BLOCKED") || ["DELIVERY_RECOVERY_REQUIRED", "DELIVERY_PENDING", "MATERIAL_RETAINED"].includes(event.kind)) outcome = "blocked";
            else if (event.kind === "FAILED") outcome = "failed";
            else if (["CANCELLED", "INTERRUPTED"].includes(event.kind)) outcome = "interrupted";
            else if (["SANDBOX_READY", "MODEL_COMPLETED", "SUCCEEDED", "DELIVERY_APPLIED", "DELIVERY_COMMIT_READY", "DELIVERY_PUBLISHED", "DELIVERY_COMPLETED"].includes(event.kind)) outcome = "completed";
            else if (active) outcome = "running";
        }
        const target = tool ? safePath(startData.path) ?? safePath(startData.module) ?? safePath(startData.directory)
            ?? safePath(data.path) ?? safePath(data.module) ?? safePath(data.directory) : undefined;
        activities.push({ key: `${event.runId}:${event.sequence}`, event, finish, tool, target,
            classification: tool ? classification(result) : event.summary !== event.kind ? safeClassification(event.summary) : undefined,
            duration: tool && !pending ? numeric(data, "durationMs") : undefined, outcome, active: !!active });
    }
    return activities;
}

export function activityFacts(activity: AgentActivity): { label: string; value: string }[] {
    const event = activity.finish ?? activity.event;
    const data = summaryFields(event);
    const rows: { label: string; value: string }[] = [];
    const addNumber = (key: string, label: string, suffix = "", scale = 1) => {
        const value = numeric(data, key);
        if (value !== undefined) rows.push({ label, value: `${(value / scale).toLocaleString("zh-CN")}${suffix}` });
    };
    if (event.kind === "SANDBOX_READY") {
        addNumber("targetJdk", "JDK"); addNumber("memoryBytes", "内存", " MiB", 1048576);
        addNumber("nanoCpus", "CPU", " 核", 1e9); addNumber("pidsLimit", "PID 上限");
        if (["none", "bridge", "host"].includes(String(data.networkMode))) rows.push({ label: "网络", value: String(data.networkMode) });
        if (typeof data.readOnlyRoot === "boolean") rows.push({ label: "只读根文件系统", value: data.readOnlyRoot ? "是" : "否" });
    } else if (activity.tool === "run_checks" && event.kind === "TOOL_FINISHED") {
        if (numeric(data, "executedTests") || numeric(data, "tests") || numeric(data, "failures") || numeric(data, "errors")) {
            for (const [key, label] of [["executedTests", "已执行测试"], ["failures", "失败"], ["errors", "错误"], ["skipped", "跳过"]] as const) addNumber(key, label);
        }
    } else if (activity.tool === "apply_patch" && activity.outcome === "completed" && Array.isArray(data.paths)) {
        for (const value of data.paths.slice(0, 8)) {
            const path = safePath(value);
            if (path) rows.push({ label: "已应用文件", value: path });
        }
    }
    return rows;
}
