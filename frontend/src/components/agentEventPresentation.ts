import type { AgentEvent, AgentRun } from "../api/agentTypes";
import { verificationLabel } from "./agentResultPresentation";

export const isPatchTool = (tool: string | undefined): boolean => tool === "apply_patch" || tool === "edit_file";

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
    list_files: "浏览文件", read_file: "读取文件", search_code: "搜索代码", apply_patch: "应用代码修改", edit_file: "精确编辑代码",
    run_checks: "运行 Maven 检查",
};
const activeKinds: Partial<Record<AgentRun["state"], string[]>> = {
    CREATED: ["CREATED"], PREPARING: ["PREPARING"], RUNNING: ["MODEL_REQUEST", "RUNNING"],
    FINALIZING: ["FINALIZING"],
};
const protectedParts = new Set([".git", ".hg", ".svn", ".ssh", ".aws", ".gnupg", ".m2", ".npmrc", ".netrc", ".pypirc"]);
const policyRules = new Set(["STORAGE_LOCATION", "PATH_FORMAT", "PROTECTED_CREDENTIAL_PATH", "FORBIDDEN_PATH", "SYMLINK_PATH",
    "AMBIGUOUS_GENERATED_PATH", "SOURCE_ENTRY_TYPE", "SOURCE_PROTECTED_ENTRY", "SOURCE_CASE_CONFLICT", "SOURCE_ENTRY_LIMIT",
    "SOURCE_FILE_LIMIT", "SOURCE_CAPTURE_LIMIT", "PATCH_SIZE", "PATCH_PREIMAGE", "PATCH_TYPE", "PATCH_DIFF_PATHS", "PATCH_FILE_MODE", "PATCH_PATH_FORMAT",
    "GENERATED_PATH", "SOURCE_CREDENTIALS", "PATCH_CREDENTIALS", "COMMAND_BUDGET", "REPORT_COUNT_LIMIT", "UNSAFE_REPORT", "REPORT_COUNTS",
    "MAVEN_OPTIONS", "UNSAFE_REPORT_DIRECTORY", "CHECK_MUTATED_SOURCE", "BASELINE_FILE_LIMIT", "BASELINE_FILE_TYPE", "UNKNOWN_TOOL", "UNCLASSIFIED_POLICY"]);
const policyStages = new Set(["ARGUMENTS", "STORAGE", "PATH_VALIDATION", "PATCH_INSPECTION", "PATCH_SOURCE", "PATCH_CONTENT",
    "PATCH_APPLICATION", "SOURCE_CAPTURE", "TOOL_EXECUTION"]);
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

const modelFailureReasons: Record<string, string> = {
    RESPONSE_MISSING: "模型响应缺失", RESULT_MISSING: "模型响应没有候选结果", OUTPUT_MISSING: "模型输出缺失",
    RESPONSE_TRUNCATED: "模型输出被截断", TOOL_ID_INVALID: "工具调用身份无效", TOOL_NAME_INVALID: "工具名称无效",
    TOOL_ARGUMENTS_INVALID: "工具参数缺失", TOOL_ARGUMENTS_LIMIT: "工具参数超过限制",
    TOOL_CALL_TYPE_INVALID: "工具调用类型无效", TOOL_CALL_STRUCTURE_INVALID: "工具调用结构无效",
    CONVERSATION_ROLE_UNSUPPORTED: "对话角色不受支持", COMPLETION_FORMAT_INVALID: "完成状态格式无效",
    PROVIDER_CALL_FAILED: "提供者调用失败",
};
const modelFailureStages: Record<string, string> = {
    CONVERSATION: "对话转换", RESPONSE: "响应检查", TOOL_CALL: "工具协议检查", COMPLETION: "完成状态检查", PROVIDER_CALL: "提供者调用",
};
function modelFailureFields(event: AgentEvent) {
    const data = summaryFields(event);
    return event.kind === "MODEL_FAILED" && data.metadataVersion === 1
        && ["MODEL_PROTOCOL", "MODEL_TRANSPORT"].includes(String(data.classification)) ? data : {};
}
function modelFailureReason(event: AgentEvent) {
    const reason = modelFailureFields(event).reason;
    return typeof reason === "string" && Object.hasOwn(modelFailureReasons, reason) ? reason : undefined;
}
function modelFailureDescription(event: AgentEvent) {
    const reason = modelFailureReason(event);
    return reason ? `${reason}（${modelFailureReasons[reason]}）` : undefined;
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

function sourceEditNotStarted(data: Record<string, unknown>, tool?: string) {
    return isPatchTool(tool) && data.classification === "POLICY_REJECTED" && data.mutationNotStarted === true
        && (data.policyRule === "SOURCE_CREDENTIALS" && data.policyStage === "PATCH_SOURCE"
            || data.policyRule === "PATCH_CREDENTIALS" && data.policyStage === "PATCH_CONTENT");
}
function patchNotStarted(data: Record<string, unknown>, tool?: string) {
    return sourceEditNotStarted(data, tool) || isPatchTool(tool) && data.mutationNotStarted === true
        && ['PATCH_CONFLICT', 'PATCH_REPEATED_CONFLICT', 'PATCH_CORRECTION_LIMIT'].includes(String(data.classification));
}

function resultOutcome(event: AgentEvent, tool?: string): ActivityOutcome {
    const data = summaryFields(event);
    const code = classification(event);
    if (code === "POLICY_REJECTED") return "blocked";
    if (['PATCH_REPEATED_CONFLICT', 'PATCH_CORRECTION_LIMIT'].includes(code ?? '')) return 'conflict';
    if (data.resultRecorded === false) return code === "POLICY_REJECTED" ? "blocked" : "unknown";
    if (code === "PATCH_CONFLICT") return "conflict";
    if (data.ok === false || code && /(FAILED|FAILURE|ERROR|LIMIT|UNAVAILABLE|MISSING|REJECTED|MISMATCH|TIMEOUT)/.test(code)) return "failed";
    if (data.ok !== true || !tool) return "unknown";
    if (isPatchTool(tool)) return code === "PATCH_APPLIED" ? "completed" : "unknown";
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
        else if (["MODEL_REQUEST", "MODEL_RESPONSE_RECOVERY", "TOOL_STARTED", "TOOL_FINISHED", "MODEL_COMPLETED"].includes(event.kind)) eventState = "RUNNING";
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
            else if (["FAILED", "MODEL_FAILED"].includes(event.kind)) outcome = "failed";
            else if (["CANCELLED", "INTERRUPTED"].includes(event.kind)) outcome = "interrupted";
            else if (["SANDBOX_READY", "MODEL_COMPLETED", "SUCCEEDED", "DELIVERY_APPLIED", "DELIVERY_COMMIT_READY", "DELIVERY_PUBLISHED", "DELIVERY_COMPLETED", "PLAN_COMPLETED"].includes(event.kind)) outcome = "completed";
            else if (active) outcome = "running";
        }
        const target = tool ? safePath(startData.path) ?? safePath(startData.module) ?? safePath(startData.directory)
            ?? safePath(data.path) ?? safePath(data.module) ?? safePath(data.directory) : undefined;
        const invocationNs = numeric(data, "invocationDurationNs");
        const internalMs = numeric(data, "durationMs");
        activities.push({ key: `${event.runId}:${event.sequence}`, event, finish, tool, target,
            targetWithheld: startData.targetState === "withheld" || data.targetState === "withheld",
            resultUncertain: (data.resultRecorded === false || isPatchTool(tool) && classification(result) === 'PATCH_CONFLICT')
                && !patchNotStarted(data, tool),
            classification: tool || event.kind === "MODEL_FAILED" ? classification(result)
                : event.summary !== event.kind ? safeClassification(event.summary) : undefined,
            duration: tool && !pending ? invocationNs !== undefined ? invocationNs / 1e6 : internalMs : undefined,
            durationSource: invocationNs !== undefined ? "invocation" : internalMs !== undefined ? "internal" : undefined,
            outcome, active: !!active });
    }
    return activities;
}

export function currentActivity(activities: AgentActivity[], events: AgentEvent[], run: AgentRun) {
    const state = activityState(events, run);
    const last = activities.at(-1);
    const active = activities.find(activity => activity.active);
    const staticState = { active: false, waiting: false, tone: "recorded", title: "等待运行活动", detail: "" };
    if (state.terminal) {
        const terminalKinds = ["FAILED", "CANCELLED", "INTERRUPTED", "SUCCEEDED"];
        const terminal = terminalKinds.includes(run.state) ? run.state
            : [...activities].reverse().find(activity => terminalKinds.includes(activity.event.kind))?.event.kind;
        const titles: Record<string, string> = { FAILED: "运行失败", CANCELLED: "运行已取消", INTERRUPTED: "运行已中断", SUCCEEDED: "Candidate 已冻结" };
        const terminalEvent = [...events].reverse().find(event => event.runId === run.id && event.kind === terminal);
        const reason = safeClassification(terminalKinds.includes(run.state) ? run.reason : terminalEvent?.summary)
            ?? safeClassification(terminalEvent?.summary);
        const diagnostic = [...events].reverse().find(event => event.runId === run.id
            && event.kind === "MODEL_FAILED" && modelFailureFields(event).classification === reason);
        const description = diagnostic ? modelFailureDescription(diagnostic) : undefined;
        return { ...staticState, tone: terminal === "FAILED" ? "failed" : terminal === "SUCCEEDED" ? "completed" : "interrupted",
            title: titles[terminal ?? ""] ?? "运行已结束",
            detail: reason ? reason + (description ? ` · ${description}` : "") : "" };
    }
    if (run.cancelRequested) return { ...staticState, title: "已请求取消，等待运行结束" };
    if (active?.event.kind === "MODEL_REQUEST") {
        const ordinal = active.event.summary.match(/^request=([1-9]\d{0,8})$/)?.[1];
        return { ...staticState, active: true, waiting: true, tone: "running",
            title: "正在请求模型" + (ordinal ? ` · 第 ${ordinal} 次请求` : ""), detail: "等待模型响应" };
    }
    if (active?.tool) return { ...staticState, active: true, tone: "running",
        title: ({ read_file: "正在读取文件", list_files: "正在浏览目录", search_code: "正在搜索代码", apply_patch: "正在应用代码修改", edit_file: "正在精确编辑代码", run_checks: "正在执行 Maven 检查" } as Record<string, string>)[active.tool] ?? "工具操作进行中",
        detail: active.target ?? (active.targetWithheld ? "目标已隐藏" : "目标未记录") };
    if (active) return { ...staticState, active: true, tone: "running",
        title: ({ CREATED: "运行已预约", PREPARING: "正在准备 Docker 沙箱", RUNNING: "开始编码", FINALIZING: "正在冻结 Candidate" } as Record<string, string>)[active.event.kind] ?? "运行进行中" };
    if (last?.event.kind === "MODEL_RESPONSE_RECOVERY") return { ...staticState, tone: "running",
        title: "正在重新请求较小的模型响应", detail: "截断内容未执行，保留已完成修改" };
    if (last?.event.kind === "MODEL_FAILED") return { ...staticState, tone: "failed", title: "模型请求失败",
        detail: [classification(last.event), modelFailureDescription(last.event)].filter(Boolean).join(" · ") };
    if (last?.classification === "POLICY_REJECTED") return { ...staticState, tone: "blocked",
        title: isPatchTool(last.tool) ? "代码修改被安全策略拒绝" : "工具操作被安全策略拒绝",
        detail: sourceEditNotStarted(summaryFields(last.finish ?? last.event), last.tool) ? "本次未修改源码" : "请核对运行诊断" };
    if (last?.event.kind === "MODEL_COMPLETED") return { ...staticState, title: "模型已结束，等待冻结结果" };
    return { ...staticState, title: ({ CREATED: "运行已预约", PREPARING: "正在准备 Docker 沙箱", RUNNING: "等待下一项运行活动", FINALIZING: "正在冻结 Candidate" } as Record<string, string>)[state.state] ?? "等待运行活动" };
}

export interface ExplorationGroup {
    key: string;
    activities: AgentActivity[];
    count: number;
    previews: string[];
    counts: { read_file: number; list_files: number; search_code: number };
    failed: number;
    uncertain: number;
}

export function patchSummary(activities: AgentActivity[], runId: string) {
    const patches = activities.filter(activity => isPatchTool(activity.tool));
    const applied = patches.filter(activity => activity.classification === 'PATCH_APPLIED' && activity.outcome === 'completed');
    return { key: `patches:${runId}`, activities: patches, count: patches.length, applied: applied.length,
        conflicts: patches.filter(activity => activity.outcome === 'conflict').length,
        blocked: patches.filter(activity => activity.outcome === 'blocked').length,
        uncertain: patches.filter(activity => activity.resultUncertain || activity.outcome === 'unknown').length,
        failed: patches.filter(activity => activity.outcome === 'failed').length,
        active: patches.some(activity => activity.active),
        paths: [...new Set(applied.flatMap(activity => {
            const data = summaryFields(activity.finish ?? activity.event);
            return Array.isArray(data.paths) ? data.paths.slice(0, 8).flatMap(path => safePath(path) ? [safePath(path)!] : []) : [];
        }))].slice(-4) };
}
export function checkSummary(activities: AgentActivity[], runId: string) {
    const checks = activities.filter(activity => activity.tool === "run_checks");
    const latest = checks.at(-1);
    const data = latest ? summaryFields(latest.finish ?? latest.event) : {};
    const executing = checks.some(activity => activity.active);
    const executed = numeric(data, "executedTests");
    const detail = executed ? [`已执行 ${executed} 项`,
        ...([["failures", "失败"], ["errors", "错误"], ["skipped", "跳过"]] as const)
            .flatMap(([key, label]) => numeric(data, key) === undefined ? [] : [`${label} ${numeric(data, key)}`])].join(" · ") : "";
    return { key: `checks:${runId}`, activities: checks, count: checks.length, latest, executing,
        label: executing ? "正在执行检查" : latest ? verificationLabel(latest.classification) : "尚未执行检查",
        detail, failed: checks.filter(activity => ["failed", "blocked"].includes(activity.outcome)).length,
        sourceBlocked: data.policyRule === "CHECK_MUTATED_SOURCE",
        blocked: latest?.outcome === "blocked" };
}

export function isExploration(activity: AgentActivity) {
    return !!activity.tool && ["read_file", "list_files", "search_code"].includes(activity.tool)
        && activity.classification !== "POLICY_REJECTED" && activity.outcome !== "blocked";
}
export function explorationSummary(activities: AgentActivity[], runId: string): ExplorationGroup {
    const files = activities.filter(isExploration);
    const counts = { read_file: 0, list_files: 0, search_code: 0 };
    for (const activity of files) counts[activity.tool as keyof typeof counts]++;
    return { key: `exploration:${runId}`, activities: files, count: files.length, counts,
        failed: files.filter(activity => activity.outcome === "failed").length,
        uncertain: files.filter(activity => activity.outcome === "unknown").length,
        previews: [...new Set([...files].reverse().flatMap(activity => activity.target ? [activity.target] : []))].slice(0, 4).reverse() };
}

export function activityFacts(activity: AgentActivity): { label: string; value: string }[] {
    const event = activity.finish ?? activity.event;
    const data = summaryFields(event);
    const rows: { label: string; value: string }[] = [];
    if (isPatchTool(activity.tool) && patchNotStarted(data, activity.tool) && activity.classification !== 'POLICY_REJECTED') {
        rows.push({ label: '本次补丁', value: '未应用，已有修改保留' });
        const reasons: Record<string, string> = { CONTEXT_MISMATCH: '当前源码上下文不匹配', CHANGE_ALREADY_PRESENT: '变更已存在',
            INVALID_DIFF: '补丁格式无效', PATH_STATE_MISMATCH: '目标文件状态不匹配', CHECK_FAILED: '预检查未通过' };
        if (typeof data.conflictReason === 'string' && reasons[data.conflictReason]) rows.push({ label: '冲突原因', value: reasons[data.conflictReason]! });
    }
    if (activity.classification === "POLICY_REJECTED") {
        if (typeof data.policyRule === "string" && policyRules.has(data.policyRule)) rows.push({ label: "拒绝规则", value: data.policyRule });
        if (typeof data.policyStage === "string" && policyStages.has(data.policyStage)) rows.push({ label: "拒绝阶段", value: data.policyStage });
        const location = data.rejectedInput as { field?: unknown; lines?: unknown } | undefined;
        if (location && ['patch', 'new_text'].includes(String(location.field)) && Array.isArray(location.lines)
            && location.lines.length > 0 && location.lines.length <= 8
            && location.lines.every(line => typeof line === 'number' && Number.isInteger(line) && line >= 1 && line <= 1000000)) {
            rows.push({ label: "提案位置", value: `${location.field} 第 ${location.lines.join('、')} 行` });
        }
        if (sourceEditNotStarted(data, activity.tool)) {
            rows.push({ label: "源码修改", value: "未开始" });
            if (data.alternativeAllowed === true && data.resultRecorded === true) rows.push({ label: "后续处理", value: "允许一次另选方案" });
        }
    }
    const addNumber = (key: string, label: string, suffix = "", scale = 1) => {
        const value = numeric(data, key);
        if (value !== undefined) rows.push({ label, value: `${(value / scale).toLocaleString("zh-CN")}${suffix}` });
    };
    if (event.kind === "MODEL_FAILED") {
        const data = modelFailureFields(event);
        const description = modelFailureDescription(event);
        if (description) rows.push({ label: "诊断", value: description });
        if (typeof data.stage === "string" && Object.hasOwn(modelFailureStages, data.stage)) {
            rows.push({ label: "阶段", value: modelFailureStages[data.stage]! });
        }
        if (data.metadataVersion === 1) {
            addNumber("request", "请求尝试"); addNumber("toolCallCount", "响应内工具调用");
            addNumber("responseTokenLimit", "响应 token 上限"); addNumber("reportedTokens", "该响应报告 tokens");
            for (const [key, label] of [["callReturned", "适配器已返回"], ["responsePresent", "响应存在"],
                ["resultPresent", "候选结果存在"], ["outputPresent", "输出存在"]] as const) {
                if (typeof data[key] === "boolean") rows.push({ label, value: data[key] ? "是" : "否" });
            }
            if (["STOP", "TOOL_CALLS", "LENGTH", "CONTENT_FILTER", "MISSING", "OTHER"].includes(String(data.finishReason))) {
                rows.push({ label: "结束原因", value: String(data.finishReason) });
            }
        }
    }
    if (event.kind === "SANDBOX_READY") {
        addNumber("targetJdk", "JDK"); addNumber("memoryBytes", "内存", " MiB", 1048576);
        addNumber("nanoCpus", "CPU", " 核", 1e9); addNumber("pidsLimit", "PID 上限");
        if (["none", "bridge", "host"].includes(String(data.networkMode))) rows.push({ label: "网络", value: String(data.networkMode) });
        if (typeof data.readOnlyRoot === "boolean") rows.push({ label: "只读根文件系统", value: data.readOnlyRoot ? "是" : "否" });
    } else if (activity.tool === "run_checks" && event.kind === "TOOL_FINISHED") {
        if (numeric(data, "executedTests") || numeric(data, "tests") || numeric(data, "failures") || numeric(data, "errors")) {
            for (const [key, label] of [["executedTests", "已执行测试"], ["failures", "失败"], ["errors", "错误"], ["skipped", "跳过"]] as const) addNumber(key, label);
        }
    } else if (isPatchTool(activity.tool) && activity.outcome === "completed" && Array.isArray(data.paths)) {
        for (const value of data.paths.slice(0, 8)) {
            const path = safePath(value);
            if (path) rows.push({ label: "已应用文件", value: path });
        }
    }
    return rows;
}
