import type { AgentEvent, AgentRun } from "../api/agentTypes";
import { verificationLabel } from "./agentResultPresentation";

export const isCheckTool = (tool: string | undefined): boolean => tool === "exec_command" || tool === "run_checks" || tool === "run_frontend_checks";

export const isPatchTool = (tool: string | undefined): boolean => tool === "apply_patch" || tool === "edit_file";

export type ActivityOutcome = "running" | "completed" | "failed" | "conflict" | "unknown" | "recorded" | "blocked" | "interrupted";
export interface AgentActivity {
    key: string;
    title?: string;
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
    update_plan: "更新工作笔记", read_log: "读取保留日志", exec_command: "执行命令", run_checks: "运行 Maven 检查", run_frontend_checks: "运行前端检查",
};
const activeKinds: Partial<Record<AgentRun["state"], string[]>> = {
    CREATED: ["CREATED"], PREPARING: ["PREPARING"], RUNNING: ["MODEL_REQUEST", "RUNNING"],
    FINALIZING: ["FINALIZING"],
};
const protectedParts = new Set([".git", ".hg", ".svn", ".ssh", ".aws", ".gnupg", ".m2"]);
const policyRules = new Set(["STORAGE_LOCATION", "PATH_FORMAT", "PROTECTED_CREDENTIAL_PATH", "FORBIDDEN_PATH", "SYMLINK_PATH",
    "AMBIGUOUS_GENERATED_PATH", "SOURCE_ENTRY_TYPE", "SOURCE_PROTECTED_ENTRY", "SOURCE_CASE_CONFLICT", "SOURCE_ENTRY_LIMIT",
    "SOURCE_FILE_LIMIT", "SOURCE_CAPTURE_LIMIT", "PATCH_SIZE", "PATCH_PREIMAGE", "PATCH_TYPE", "PATCH_DIFF_PATHS", "PATCH_FILE_MODE", "PATCH_PATH_FORMAT",
    "GENERATED_PATH", "SOURCE_CREDENTIALS", "PATCH_CREDENTIALS", "COMMAND_BUDGET", "REPORT_COUNT_LIMIT", "UNSAFE_REPORT", "REPORT_COUNTS",
    "MAVEN_OPTIONS", "FRONTEND_ISOLATION", "UNSAFE_REPORT_DIRECTORY", "CHECK_MUTATED_SOURCE", "BASELINE_FILE_LIMIT", "BASELINE_FILE_TYPE", "UNKNOWN_TOOL", "UNCLASSIFIED_POLICY"]);
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

export function routineProviderDiagnostic(activity: AgentActivity): boolean {
    const data = summaryFields(activity.event);
    return activity.event.kind === "MODEL_PROVIDER_DIAGNOSTIC" && data.category === "SUCCESS"
        && data.attempt === 1 && data.responsePresent === true
        && typeof data.httpStatus === "number" && data.httpStatus >= 200 && data.httpStatus < 300;
}

export function providerDiagnosticTitle(event: AgentEvent): string {
    const data = summaryFields(event);
    if (data.category === "TRANSPORT") return "模型请求连接失败";
    if (data.category === "SUCCESS") return typeof data.attempt === "number" && data.attempt > 1
        ? "模型请求重试成功" : "模型请求成功";
    return typeof data.httpStatus === "number" && data.httpStatus >= 400
        ? "模型请求返回 HTTP 错误" : "模型请求诊断";
}

export function commandPresentation(activity: AgentActivity) {
    const data = summaryFields(activity.finish ?? activity.event);
    for (const stream of ["stdout", "stderr"]) {
        if (typeof data[stream] === "string") data[stream] = data[stream].replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, "")
            .replace(/\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)/g, "").replace(/\r\n?/g, "\n");
    }
    const reason = String(data.reason ?? "");
    const explanations: Record<string, string> = {
        DIRECTORY_NOT_FOUND: "工作目录不存在或不是目录。先浏览仓库目录，再使用准确的相对路径；命令内不要重复目录前缀。",
        INVALID_DIRECTORY: "工作目录无效。根目录使用 .，子目录使用仓库相对路径。",
        VERIFICATION_FIELDS_REQUIRED: "两个验证字段需要同时填写或同时省略。依赖安装可省略；检查操作需同时说明验证范围和类型。",
        INVALID_VERIFICATION_SCOPE: "请填写 1 到 1000 字符的验证范围，说明对应的步骤或仓库要求。",
        INVALID_CHECK_KIND: "验证类型只能是 test、build 或 other。准备操作可同时省略两个验证字段。",
        INVALID_TIMEOUT: "单次时限超出允许范围。按工具返回的上限调整，或省略时限使用默认值。",
        INVALID_COMMAND: "命令需为非空文本，不超过 16000 字符且不能包含 NUL。",
        NOT_VERIFICATION_COMMAND: "说明文字或空操作不能作为检查。请运行实际检查，并返回检查本身的退出码。",
    };
    let label: string;
    let explanation = "";
    if (activity.active) label = "命令执行中";
    else if (activity.classification === "POLICY_REJECTED") {
        label = "命令被安全规则停止"; explanation = "查看拒绝原因和授权范围，调整操作后再试。";
    } else if (data.mutationNotStarted === true || activity.classification === "INVALID_ARGUMENTS") {
        label = ["DIRECTORY_NOT_FOUND", "INVALID_DIRECTORY"].includes(reason) ? "工作目录无效，命令未启动"
            : activity.classification === "INVALID_ARGUMENTS" ? "参数无效，命令未启动" : "命令未启动";
        explanation = explanations[reason] ?? "命令未执行，请按反馈调整后重试。旧记录未说明具体参数原因，需核对工具字段与类型。";
    } else if (data.captureStatus === "FAILED") {
        label = "命令已执行，源码捕获失败";
        explanation = `实际退出码：${data.exitCode ?? "未知"}。捕获失败原因：${data.captureFailure ?? "未记录"}。请检查保留现场；不要直接重放已执行的命令。`;
    } else if (data.cancelled === true) {
        label = "命令已取消"; explanation = "命令在取消后停止，本次结果不能作为通过的验证。";
    } else if (data.timedOut === true) {
        label = "命令超时"; explanation = "命令已达到单次时限并停止。查看输出定位阻塞，调整后重新运行必要检查。";
    } else if (typeof data.exitCode === "number" && Number.isInteger(data.exitCode)) {
        label = data.exitCode !== 0 ? "命令返回非零退出码" : numeric(data, "failures") || numeric(data, "errors")
            ? "进程退出成功，测试报告未通过" : "命令退出成功";
        explanation = data.exitCode !== 0 ? "查看输出定位错误；命令里的相对路径以工作目录为起点，修正后重试。"
            : numeric(data, "failures") || numeric(data, "errors")
                ? "进程退出码为 0，但测试报告仍有失败，不能作为通过的验证。核对原检查是否被管道或输出过滤掩盖。"
                : "";
    } else {
        label = "执行结果不可用"; explanation = "未取得完整执行结果，不能确认成功或安全重放；请核对运行诊断和已保留的修改。";
    }
    return { label, explanation, data };
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
        || protectedParts.has(part.toLowerCase())
        || /^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)/i.test(part)
        || /sk-[a-z0-9]{16,}/i.test(part))) return;
    return value;
}


const preflightStages: Record<string, string> = {
    PREFLIGHT: "检查运行环境", MAVEN_CHECK: "后端 Maven 检查",
    FRONTEND_CHECK: "前端检查", DEPENDENCY_PREPARATION: "受控依赖准备",
};
export function preflightProgress(event: AgentEvent) {
    if (!["PREFLIGHT_PROGRESS", "ENVIRONMENT_PREFLIGHT"].includes(event.kind)) return;
    const outcome = event.summary.split("\n")[0];
    const data = event.kind === "ENVIRONMENT_PREFLIGHT" ? {
        stage: "PREFLIGHT", classification: outcome,
        status: ["READY", "READY_BASELINE_FAILURE"].includes(outcome ?? "") ? "COMPLETED"
            : ["ENVIRONMENT_BLOCKED", "SECURITY_BLOCKED", "SELECTION_REQUIRED"].includes(outcome ?? "") ? "FAILED" : "UNKNOWN",
    } as Record<string, unknown> : summaryFields(event);
    if (typeof data.stage !== "string" || !Object.hasOwn(preflightStages, data.stage)) return;
    const goal = ["test", "verify", "build", "typecheck"].includes(String(data.goal)) ? String(data.goal) : "";
    const result = typeof data.classification === "string" && /^[A-Z_]{1,80}$/.test(data.classification)
        ? data.classification : "";
    const rawStatus = String(data.status);
    const status = rawStatus === "IN_PROGRESS" ? "STARTED" : rawStatus === "SUCCESS" ? "COMPLETED"
        : ["STARTED", "COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED"].includes(rawStatus) ? rawStatus : "UNKNOWN";
    return { title: `${preflightStages[data.stage]}${goal ? ` · ${goal}` : ""} · ${
        status === "STARTED" ? "进行中" : status === "FAILED" ? "失败" : status === "COMPLETED" ? "已完成"
            : status === "CANCELLED" ? "已取消" : status === "INTERRUPTED" ? "已中断" : "结果未记录"}`,
        stage: data.stage, goal, directory: safePath(data.directory), result, status,
        duration: typeof data.durationMs === "number" && Number.isFinite(data.durationMs) && data.durationMs >= 0
            && status !== "STARTED" ? data.durationMs : undefined };
}

export function resourcesBlocked(events: AgentEvent[], runId?: string) {
    const last = events.filter(event => (!runId || event.runId === runId)
        && ["CLEANUP_BLOCKED", "RECONCILIATION_BLOCKED", "RESOURCES_RELEASED"].includes(event.kind)).at(-1);
    return !!last && last.kind !== "RESOURCES_RELEASED";
}

export function networkCapability(events: AgentEvent[], runId: string): string | undefined {
    const event = events.filter(item => item.runId === runId && item.kind === "ENVIRONMENT_PREFLIGHT").at(-1);
    if (!event) return;
    try {
        const data = JSON.parse(event.summary.slice(event.summary.indexOf("\n") + 1));
        if (typeof data.network !== "string") return;
        if (data.network.startsWith("OFFLINE:")) return "网络：离线运行，依赖下载不可用；可继续使用仓库和镜像中的已有资源。";
    } catch { /* 历史记录缺少能力说明时，不推断网络可用。 */ }
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

export function modelIncompleteSummary(event: AgentEvent): string | undefined {
    const data = summaryFields(event);
    const summary = data.summary;
    return event.kind === "MODEL_INCOMPLETE" && data.metadataVersion === 1 && typeof summary === "string"
        && summary.trim().length > 0 && summary.length <= 1200 && !credentialShape.test(summary)
        && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(summary) ? summary : undefined;
}

function completionRejection(event: AgentEvent): string | undefined {
    const data = summaryFields(event);
    return event.kind === "COMPLETION_CHECK_REJECTED"
        && ["UNRESOLVED_CHECK_FAILURE", "EVIDENCE_INCOMPARABLE", "EVIDENCE_INVALID"].includes(String(data.classification))
        && typeof data.reason === "string" && data.reason.length <= 2400 && !credentialShape.test(data.reason)
        ? data.reason : undefined;
}

export function noProgressExplanation(events: AgentEvent[], runId: string) {
    const stopped = events.some(event => event.runId === runId && event.kind === "COMPLETION_CHECK_REJECTED"
        && summaryFields(event).progressStatus === "STOPPED");
    return stopped ? "完成报告反复被拒，运行已停止。请查看完成检查诊断和保留的修改记录。"
        : "最近的操作重复且未产生新进展，运行已停止。可在时间线中核对此前的修改和执行记录。";
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
    if (tool === "exec_command" && data.cancelled === true) return "interrupted";
    if (isCheckTool(tool) && (numeric(data, "failures") || numeric(data, "errors"))) return "failed";
    if (isCheckTool(tool) && data.ok === true && (code === "CHECK_NOT_AVAILABLE" || code === "TEST_EXECUTION_UNVERIFIED"
        || data.executionStatus === "NOT_STARTED"
            && ["UNSUPPORTED_FRONTEND_CONFIGURATION", "FRONTEND_ENVIRONMENT_MISMATCH"].includes(code ?? ""))) return "recorded";
    if (data.ok === false || code && /(FAILED|FAILURE|ERROR|LIMIT|UNAVAILABLE|MISSING|REJECTED|MISMATCH|TIMEOUT|UNSUPPORTED|INVALID)/.test(code)) return "failed";
    if (data.ok !== true || !tool) return "unknown";
    if (isPatchTool(tool)) return code === "PATCH_APPLIED" ? "completed" : "unknown";
    if (isCheckTool(tool)) return ["COMMAND_SUCCEEDED", "TESTS_PASSED", "BUILD_PASSED_NOT_TESTED"].includes(code ?? "")
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
    const facts = events.filter(event => event.runId === run.id && event.kind !== "MODEL_USAGE");
    const starts = new Map<string, AgentEvent[]>();
    const finishes = new Map<string, AgentEvent[]>();
    const preflights = new Map<string, number>();
    for (const event of facts) {
        const progress = preflightProgress(event);
        // 同一阶段/工程/目标仅展示最后一个持久事实；原始事件不改写。
        if (progress) preflights.set(JSON.stringify([progress.stage, progress.directory, progress.goal]), event.sequence);
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
        const progress = preflightProgress(event);
        if (progress && preflights.get(JSON.stringify([progress.stage, progress.directory, progress.goal])) !== event.sequence) continue;
        const toolEvent = ["TOOL_STARTED", "TOOL_FINISHED"].includes(event.kind);
        const tool = toolEvent ? recordedTool(event) : undefined;
        const pending = toolEvent && event.kind === "TOOL_STARTED" && !finish;
        const active = !state.terminal && !run.cancelRequested && result.sequence === facts.at(-1)?.sequence
            && (pending && state.state === "RUNNING" || activeKinds[state.state as AgentRun["state"]]?.includes(result.kind));
        let outcome: ActivityOutcome = toolEvent ? pending ? active ? "running" : "unknown" : resultOutcome(result, tool) : "recorded";
        if (!toolEvent) {
            if (event.kind.includes("BLOCKED") || ["DELIVERY_RECOVERY_REQUIRED", "DELIVERY_PENDING", "MATERIAL_RETAINED"].includes(event.kind)) outcome = "blocked";
            else if (["FAILED", "MODEL_FAILED", "MODEL_INCOMPLETE"].includes(event.kind)) outcome = "failed";
            else if (["CANCELLED", "INTERRUPTED"].includes(event.kind)) outcome = "interrupted";
            else if (["SANDBOX_READY", "MODEL_COMPLETED", "SUCCEEDED", "DELIVERY_APPLIED", "DELIVERY_COMMIT_READY", "DELIVERY_PUBLISHED", "DELIVERY_COMPLETED", "PLAN_COMPLETED"].includes(event.kind)) outcome = "completed";
            else if (active) outcome = "running";
        }
        if (event.kind === "MODEL_PROVIDER_DIAGNOSTIC") {
            outcome = data.category === "SUCCESS" ? "completed" : "failed";
        }
        if (progress) outcome = progress.status === "FAILED" ? "failed" : progress.status === "COMPLETED" ? "completed"
            : ["CANCELLED", "INTERRUPTED"].includes(progress.status) ? "interrupted"
            : progress.status === "STARTED" && !state.terminal ? "running" : "unknown";
        const target = progress?.directory ?? (tool ? safePath(startData.path) ?? safePath(startData.module) ?? safePath(startData.directory)
            ?? safePath(data.path) ?? safePath(data.module) ?? safePath(data.directory) : undefined);
        const invocationNs = numeric(data, "invocationDurationNs");
        const internalMs = numeric(data, "durationMs");
        activities.push({ key: `${event.runId}:${event.sequence}`, event, finish, tool, target,
            title: progress && outcome === "unknown" ? progress.title.replace(/ · [^·]+$/, " · 结果未记录") : progress?.title,
            targetWithheld: startData.targetState === "withheld" || data.targetState === "withheld",
            resultUncertain: (data.resultRecorded === false || isPatchTool(tool) && classification(result) === 'PATCH_CONFLICT')
                && !patchNotStarted(data, tool),
            classification: tool || event.kind === "MODEL_FAILED" ? classification(result)
                : event.summary !== event.kind ? safeClassification(event.summary) : undefined,
            duration: progress?.duration ?? (tool && !pending ? invocationNs !== undefined ? invocationNs / 1e6 : internalMs : undefined),
            durationSource: invocationNs !== undefined ? "invocation" : internalMs !== undefined ? "internal" : undefined,
            outcome, active: progress ? outcome === "running" : !!active });
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
        if (terminal === "FAILED" && reason === "MODEL_INCOMPLETE") {
            return { ...staticState, tone: "failed", title: "运行失败 · MODEL_INCOMPLETE", detail: "" };
        }
        return { ...staticState, tone: terminal === "FAILED" ? "failed" : terminal === "SUCCEEDED" ? "completed" : "interrupted",
            title: titles[terminal ?? ""] ?? "运行已结束",
            detail: reason ? reason + (description ? ` · ${description}` : "") : "" };
    }
    if (run.cancelRequested) return { ...staticState, title: "已请求取消，等待运行结束" };
    if (state.state === "PREPARING") {
        const milestones = activities.map(activity => preflightProgress(activity.event))
            .filter((progress): progress is NonNullable<typeof progress> => !!progress);
        const pending = new Map<string, (typeof milestones)[number]>();
        for (const milestone of milestones) {
            const key = JSON.stringify([milestone.stage, milestone.directory, milestone.goal]);
            if (milestone.status === "STARTED") pending.set(key, milestone);
            else pending.delete(key);
            if (milestone.stage === "PREFLIGHT" && milestone.status !== "STARTED") pending.clear();
        }
        const latest = milestones.at(-1);
        const progress = latest?.status === "FAILED" ? latest : [...pending.values()].at(-1) ?? latest;
        if (progress) return { ...staticState, active: progress.status === "STARTED", tone: progress.status === "FAILED" ? "failed"
            : progress.status === "COMPLETED" ? "completed" : progress.status === "STARTED" ? "running" : "recorded",
            title: progress.title, detail: [progress.directory, progress.result].filter(Boolean).join(" · ") };
    }
    if (active?.event.kind === "MODEL_REQUEST") {
        const ordinal = active.event.summary.match(/^request=([1-9]\d{0,8})$/)?.[1];
        return { ...staticState, active: true, waiting: true, tone: "running",
            title: "正在请求模型" + (ordinal ? ` · 第 ${ordinal} 次请求` : ""), detail: "等待模型响应" };
    }
    if (active?.tool) return { ...staticState, active: true, tone: "running",
        title: ({ read_file: "正在读取文件", list_files: "正在浏览目录", search_code: "正在搜索代码", apply_patch: "正在应用代码修改", edit_file: "正在精确编辑代码", exec_command: "正在执行命令", run_checks: "正在执行 Maven 检查", run_frontend_checks: "正在执行前端检查" } as Record<string, string>)[active.tool] ?? "工具操作进行中",
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
    const checks = activities.filter(activity => activity.event.runId === runId && isCheckTool(activity.tool));
    const last = checks.at(-1);
    const source = [...activities].reverse().map(activity => summaryFields(activity.finish ?? activity.event))
        .find(data => typeof data.afterSnapshot === "string" || typeof data.snapshot === "string");
    const snapshot = source?.afterSnapshot ?? source?.snapshot;
    const executing = checks.some(activity => activity.active);
    const nativeReceipts = checks.filter(check => typeof summaryFields(check.finish ?? check.event).runId === "string");
    if (nativeReceipts.length) {
        const latest = last;
        return { key: `checks:${runId}`, activities: [...checks].reverse(), current: [], stale: false,
            count: checks.length, latest, executing, label: latest ? latest.tool === "exec_command" ? commandPresentation(latest).label : verificationLabel(latest.classification) : "尚未执行命令",
            detail: "逐次执行记录；完成范围见 Agent 报告", failed: checks.filter(check => check.outcome === "failed").length,
            sourceBlocked: false, blocked: latest?.outcome === "blocked" };
    }
    const byScope = new Map<string, AgentActivity>();
    for (const check of checks) {
        const result = summaryFields(check.finish ?? check.event);
        if (check.tool === "exec_command" && !result.scope) continue;
        // 先取每个真实命令的最新尝试，再判断新鲜度，不能用旧成功遮盖后来的失败。
        byScope.set(JSON.stringify([check.tool, result.directory, result.command, result.goal, result.module,
            result.testSelectionFingerprint]), check);
    }
    const relevant = [...byScope.values()].reverse();
    const comparable = relevant.filter(check => {
        const result = summaryFields(check.finish ?? check.event);
        return result.snapshot === snapshot && (!result.afterSnapshot || result.afterSnapshot === snapshot);
    });
    const stale = relevant.length > comparable.length;
    const latest = executing ? last : [...comparable].reverse().find(check => ["blocked", "failed", "unknown"].includes(check.outcome))
        ?? [...comparable].reverse().find(check => ["TESTS_PASSED", "TESTS_FAILED"].includes(check.classification ?? "")) ?? comparable[0];
    const data = latest ? summaryFields(latest.finish ?? latest.event) : {};
    const executed = numeric(data, "executedTests");
    const counts = executed ? [`已执行 ${executed} 项`,
        ...([["failures", "失败"], ["errors", "错误"], ["skipped", "跳过"]] as const)
            .flatMap(([key, label]) => numeric(data, key) === undefined ? [] : [`${label} ${numeric(data, key)}`])].join(" · ") : "";
    const scope = [safePath(data.directory), ["test", "verify", "build", "typecheck", "compile", "package"].includes(String(data.goal))
        ? String(data.goal) : undefined].filter(Boolean).join(" · ");
    const detail = [scope, counts].filter(Boolean).join(" · ");
    return { key: `checks:${runId}`, activities: [...checks].reverse(), current: comparable, stale, count: checks.length, latest, executing,
        label: executing ? "正在执行检查" : stale ? "部分检查已过期，需重新验证" : !relevant.length && checks.length ? "尚无当前范围的验证结果" : latest ? latest.tool === "exec_command" ? commandPresentation(latest).label : verificationLabel(latest.classification) : "尚未执行检查",
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
    if (activity.event.kind === "WORKING_NOTES") rows.push({ label: "Agent 工作笔记", value: activity.event.summary });
    if (activity.event.kind === "MODEL_COMPLETED") {
        const report = summaryFields(activity.event);
        if (typeof report.summary === "string") rows.push({ label: "Agent 完成报告", value: report.summary });
        if (Array.isArray(report.limitations)) rows.push({ label: "Agent 报告限制", value: report.limitations.filter(item => typeof item === "string").join("；") });
        if (typeof report.limitations === "string") rows.push({ label: "Agent 报告限制", value: report.limitations });
    }

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
            if (data.alternativeAllowed === true && data.resultRecorded === true) rows.push({ label: "后续处理", value: "可调整方案后继续本次运行" });
        }
    }
    const addNumber = (key: string, label: string, suffix = "", scale = 1) => {
        const value = numeric(data, key);
        if (value !== undefined) rows.push({ label, value: `${(value / scale).toLocaleString("zh-CN")}${suffix}` });
    };
    const progress = preflightProgress(event);
    if (progress) {
        if (progress.result) rows.push({ label: "结果分类", value: progress.result });
        if (progress.directory) rows.push({ label: "检查工程", value: progress.directory });
    }
    const incomplete = modelIncompleteSummary(event);
    if (incomplete) rows.push({ label: "模型说明（未独立验证）", value: incomplete });
    const rejection = completionRejection(event);
    if (rejection) rows.push({ label: "完成检查诊断", value: rejection });
    if (event.kind === "MODEL_PROVIDER_DIAGNOSTIC") {
        const data = summaryFields(event);
        if (typeof data.category === "string") rows.push({ label: "请求结果", value: data.category });
        for (const [key, label] of [["attempt", "尝试"], ["httpStatus", "HTTP 状态"], ["durationMs", "耗时 ms"]]) {
            const value = data[key!];
            if (typeof value === "number") rows.push({ label: label!, value: String(value) });
        }
        rows.push({ label: "收到响应", value: data.responsePresent === true ? "是" : "否" });
    }
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
    } else if (isCheckTool(activity.tool) && event.kind === "TOOL_FINISHED") {
        if (["typecheck", "build", "test", "compile", "package", "verify"].includes(String(data.goal))) {
            rows.push({ label: "检查目标", value: String(data.goal) });
        }
        if (["npm", "pnpm"].includes(String(data.packageManager))) {
            rows.push({ label: "包管理器", value: String(data.packageManager) });
        }
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
