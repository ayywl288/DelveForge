import type { AgentEvent, AgentRun } from "../api/agentTypes";

export type ActivityOutcome = "running" | "completed" | "failed" | "conflict" | "unknown" | "recorded" | "blocked" | "interrupted";
export interface AgentActivity {
    key: string;
    event: AgentEvent;
    finish?: AgentEvent;
    tool?: string;
    target?: string;
    targetWithheld: boolean;
    classification?: string;
    duration?: number;
    durationSource?: "invocation" | "internal";
    resultUncertain: boolean;
    outcome: ActivityOutcome;
    active: boolean;
}
export const toolNames: Record<string, string> = {
    list_files: "浏览文件", read_file: "读取文件", search_code: "搜索代码", apply_patch: "应用代码修改",
    run_checks: "运行 Maven 检查",
};
const activeKinds: Partial<Record<AgentRun["state"], string[]>> = {
    CREATED: ["CREATED"], PREPARING: ["PREPARING"], RUNNING: ["MODEL_REQUEST", "RUNNING"],
    FINALIZING: ["FINALIZING"],
};
const protectedParts = new Set([".git", ".hg", ".svn", ".ssh", ".aws", ".gnupg", ".m2", ".npmrc", ".netrc", ".pypirc"]);
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
        || !/^[\p{L}\p{N}_./ -]+$/u.test(value) || value.startsWith("/")) return;
    if (value.split("/").some(part => !part || part === "." || part === ".." || part.endsWith(".") || part.endsWith(" ")
        || protectedParts.has(part.toLowerCase()) || part.toLowerCase().startsWith(".env")
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
    if (data.resultRecorded === false) return code === "POLICY_REJECTED" ? "blocked" : "unknown";
    if (code === "PATCH_CONFLICT") return "conflict";
    if (data.ok === false || code && /(FAILED|FAILURE|ERROR|LIMIT|UNAVAILABLE|MISSING|REJECTED|MISMATCH|TIMEOUT)/.test(code)) return "failed";
    if (data.ok !== true || !tool) return "unknown";
    if (tool === "apply_patch") return code === "PATCH_APPLIED" ? "completed" : "unknown";
    if (tool === "run_checks") return ["TESTS_PASSED", "BUILD_PASSED_NOT_TESTED"].includes(code ?? "")
        && !(numeric(data, "failures") || numeric(data, "errors")) ? "completed" : "unknown";
    return "completed";
}

export function activityState(events: AgentEvent[], run: AgentRun) {
    const ranks: Record<string, number> = { CREATED: 0, PREPARING: 1, RUNNING: 2, FINALIZING: 3 };
    const terminals = ["FAILED", "CANCELLED", "INTERRUPTED", "SUCCEEDED"];
    let eventState: string | undefined;
    let eventTerminal = false;
    for (const event of events) {
        if (event.runId !== run.id) continue;
        if (terminals.includes(event.kind)) eventTerminal = true;
        if (Object.hasOwn(ranks, event.kind)) eventState = event.kind;
        else if (["MODEL_REQUEST", "TOOL_STARTED", "TOOL_FINISHED", "MODEL_COMPLETED"].includes(event.kind)) eventState = "RUNNING";
    }
    const terminal = terminals.includes(run.state) || eventTerminal;
    const snapshotBehind = !terminals.includes(run.state) && (eventTerminal
        || eventState !== undefined && (ranks[eventState] ?? -1) > (ranks[run.state] ?? -1));
    return { terminal, snapshotBehind, state: snapshotBehind && eventState ? eventState : run.state };
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
    const state = activityState(facts, run);
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
        const active = !state.terminal && !run.cancelRequested && result.sequence === facts.at(-1)?.sequence
            && (pending && state.state === "RUNNING" || activeKinds[state.state as AgentRun["state"]]?.includes(result.kind));
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
        const invocationNs = numeric(data, "invocationDurationNs");
        const internalMs = numeric(data, "durationMs");
        activities.push({ key: `${event.runId}:${event.sequence}`, event, finish, tool, target,
            targetWithheld: startData.targetState === "withheld" || data.targetState === "withheld",
            resultUncertain: data.resultRecorded === false,
            classification: tool ? classification(result) : event.summary !== event.kind ? safeClassification(event.summary) : undefined,
            duration: tool && !pending ? invocationNs !== undefined ? invocationNs / 1e6 : internalMs : undefined,
            durationSource: invocationNs !== undefined ? "invocation" : internalMs !== undefined ? "internal" : undefined,
            outcome, active: !!active });
    }
    return activities;
}

export interface ExplorationGroup {
    key: string;
    activities: AgentActivity[];
    count: number;
    previews: string[];
}
export function groupExploration(activities: AgentActivity[]): (AgentActivity | ExplorationGroup)[] {
    const rows: (AgentActivity | ExplorationGroup)[] = [];
    const exploration = (activity: AgentActivity) => !!activity.tool
        && ["read_file", "list_files", "search_code"].includes(activity.tool) && activity.outcome === "completed";
    for (let index = 0; index < activities.length;) {
        const batch: AgentActivity[] = [];
        let count = 0;
        let cursor = index;
        // 最后一项保持独立，刚完成的活动不会在原位转换时被立即收进分组。
        while (cursor < activities.length - 1 && batch.length < 16 && count < 8) {
            const activity = activities[cursor]!;
            const modelBetween = activity.event.kind === "MODEL_REQUEST" && !activity.active
                && cursor + 1 < activities.length - 1 && exploration(activities[cursor + 1]!);
            if (activity.active || !(exploration(activity) || count > 0 && modelBetween)) break;
            batch.push(activity);
            if (exploration(activity)) count++;
            cursor++;
        }
        if (count >= 3) {
            rows.push({ key: `exploration:${batch[0]!.key}`, activities: batch, count,
                previews: [...new Set(batch.flatMap(activity => activity.target ? [activity.target] : []))].slice(0, 2) });
            index = cursor;
        } else rows.push(activities[index++]!);
    }
    return rows;
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
