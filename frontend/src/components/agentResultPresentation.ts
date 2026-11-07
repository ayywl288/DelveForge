import type { AgentRun, Delivery } from "../api/agentTypes";

export interface RunPresentation {
    label: string;
    detail: string;
    tone: "completed" | "failed" | "blocked" | "recorded";
}
export function presentRun(run: AgentRun, delivery?: Delivery | null): RunPresentation {
    if (run.state === "FAILED" && run.modelCalls === 0 && run.tokens === 0
        && ["SELECTION_REQUIRED", "ENVIRONMENT_BLOCKED", "SECURITY_BLOCKED"].includes(run.reason ?? "")) {
        return { label: run.reason === "SELECTION_REQUIRED" ? "需要选择检查工程" : "运行前预检受阻",
            detail: "本次尚未调用模型。请查看环境预检诊断后重新开始。", tone: "blocked" };
    }
    if (run.state === "FAILED") return { label: "本次运行失败", detail: "本次尝试没有完成交付。", tone: "failed" };
    if (run.state === "CANCELLED" || run.state === "INTERRUPTED") return {
        label: run.state === "CANCELLED" ? "运行已取消" : "运行已中断", detail: "本次尝试没有完成交付。", tone: "recorded" };
    if (run.state === "SUCCEEDED") {
        if (delivery === undefined) return { label: "候选已冻结，交付状态待核对", detail: "正在读取正式交付状态。", tone: "recorded" };
        const accepted = run.candidateId && delivery?.candidateId === run.candidateId ? delivery : null;
        if (accepted?.state === "COMPLETED" && /^[0-9a-f]{40}$/.test(accepted.revision ?? "")) {
            return { label: "步骤已完成", detail: "本次变更已正式交付。", tone: "completed" };
        }
        if (accepted && ["DELIVERY_BLOCKED", "RECOVERY_REQUIRED"].includes(accepted.state)) {
            return { label: "交付受阻", detail: "编码结果已保留，正式副本需要核对后才能继续。", tone: "blocked" };
        }
        return { label: "候选已冻结，等待交付", detail: "编码已完成，正在等待正式交付确认。", tone: "recorded" };
    }
    return { label: ({ CREATED: "已预约", PREPARING: "准备沙箱", RUNNING: "编码中", FINALIZING: "正在保存编码结果" })[run.state],
        detail: "", tone: "recorded" };
}

export function verificationLabel(code?: string): string {
    return ({ COMMAND_SUCCEEDED: "命令成功", COMMAND_FAILED: "命令失败", COMMAND_TIMED_OUT: "命令超时", COMMAND_CANCELLED: "命令已取消", TESTS_PASSED: "测试通过", TESTS_FAILED: "测试未通过", BUILD_PASSED_NOT_TESTED: "构建通过，未运行测试",
        COMPILATION_FAILED: "编译未通过", DEPENDENCY_MISSING: "依赖尚未准备", BUILD_CONFIGURATION_FAILED: "构建配置需核对",
        JDK_ENVIRONMENT_MISMATCH: "运行环境不匹配", POLICY_REJECTED: "检查被安全规则停止", INVALID_ARGUMENTS: "检查尚未开始",
        TYPECHECK_FAILED: "前端类型检查未通过", FRONTEND_BUILD_FAILED: "前端构建未通过",
        LOCKFILE_INVALID: "前端锁文件不匹配或无效", UNSUPPORTED_FRONTEND_CONFIGURATION: "前端配置暂不支持",
        FRONTEND_ENVIRONMENT_MISMATCH: "前端工具环境不匹配", CHECK_TIMEOUT: "检查超时或输出超限",
        TEST_EXECUTION_UNVERIFIED: "脚本已结束，测试执行未获验证", CHECK_PROCESS_FAILED: "检查进程未成功结束",
        CHECK_NOT_AVAILABLE: "项目未提供此项受支持检查", CHECK_RESULT_UNAVAILABLE: "检查结果待核对" } as Record<string, string>)[code ?? ""] ?? "检查结果待核对";
}
function record(value: unknown): value is Record<string, unknown> {
    return !!value && typeof value === "object" && !Array.isArray(value);
}
function count(value: unknown): number | null {
    return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : null;
}

export function presentSourceView(raw: string): string {
    try {
        const value: unknown = JSON.parse(raw);
        if (!record(value) || !record(value.sourceView)) return "";
        const view = value.sourceView;
        const omitted = count(view.preservedOnlyCount);
        const readOnly = count(view.readOnlyResourceCount);
        if (view.kind === "PROJECTED" && omitted !== null && omitted > 0) {
            return `检查使用执行投影；${omitted} 项资源保留在原仓库，未进入执行环境。`;
        }
        if (view.kind === "FULL" && omitted === 0 && readOnly !== null) {
            return readOnly > 0 ? `检查包含 ${readOnly} 项只读资源；公开资源可读取，未获修改授权。` : "";
        }
        return "执行材料范围待核对。";
    } catch { return ""; }
}
export interface TestPresentation {
    label: string;
    outcome: string;
    scope: string;
    tone: "success" | "danger" | "neutral" | "warning";
    counts: { executed: number; passed: number | null; failed: number | null; errored: number | null; skipped: number | null } | null;
}

function checkScope(check: Record<string, unknown>): string {
    if (check.checkType === "COMMAND") return `${check.directory ?? "."} · ${check.command ?? "命令未记录"}`;
    const type = check.checkType === "FRONTEND" ? "前端" : "Maven";
    const directory = typeof check.directory === "string" && /^[\p{L}\p{N}._/ -]{1,200}$/u.test(check.directory)
        && !check.directory.includes("..") && !check.directory.includes(".env") ? check.directory : "工程目录未记录";
    const goal = ["typecheck", "build", "test", "verify", "compile", "package"].includes(String(check.goal))
        ? String(check.goal) : "检查目标未记录";
    return `${type} · ${directory} · ${goal}`;
}

export function presentCheckRows(raw: string) {
    try {
        const value: unknown = JSON.parse(raw);
        if (!record(value) || !Array.isArray(value.checks)) return [];
        const recorded = value.checks.filter(record);
        const key = (check: Record<string, unknown>) => JSON.stringify([check.command, check.directory]);
        const latest = new Map(recorded.map(check => [key(check), check]));
        const required = new Set(Array.isArray(value.requiredChecks) ? value.requiredChecks.filter(record).map(key) : []);
        return [...recorded].reverse().map(check => ({
            current: value.evidenceVersion === 6 && required.has(key(check)) && latest.get(key(check)) === check
                && check.snapshot === value.finalSnapshot && check.afterSnapshot === value.finalSnapshot,
            scope: checkScope(check), label: verificationLabel(String(check.classification)),
            stale: check.snapshot !== value.finalSnapshot || (check.checkType === "COMMAND"
                ? check.afterSnapshot !== value.finalSnapshot : check.stale !== false),
            executed: (check.checkType === "COMMAND" && check.testDetails === "AVAILABLE") || ["TESTS_PASSED", "TESTS_FAILED"].includes(String(check.classification))
                ? count(check.executedTests) : null,
        }));
    } catch { return []; }
}
export function presentTestEvidence(raw: string): TestPresentation {
    const unknown: TestPresentation = { label: "检查证据待核对", outcome: "没有可确认的当前源码测试结果。",
        scope: "", tone: "warning", counts: null };
    let value: unknown;
    try { value = JSON.parse(raw); } catch { return unknown; }
    if (!record(value) || !["TESTED", "NOT_TESTED"].includes(String(value.status))) return unknown;
    const checks = Array.isArray(value.checks) ? value.checks.filter(record) : [];
    const fresh = checks.filter(check => check.stale === false
        && typeof value.finalSnapshot === "string" && /^[0-9a-f]{64}$/.test(value.finalSnapshot)
        && check.snapshot === value.finalSnapshot);
    if (value.evidenceVersion === 6) {
        if (value.verificationLevel !== "COMMAND_VERIFIED" || !Array.isArray(value.requiredChecks)) return unknown;
        const required = value.requiredChecks.filter(record);
        const latest = new Map(checks.map(check => [JSON.stringify([check.command, check.directory]), check]));
        const valid = required.length > 0 && required.every(requirement => {
            const check = latest.get(JSON.stringify([requirement.command, requirement.directory]));
            return check?.classification === "COMMAND_SUCCEEDED" && check.exitCode === 0
                && check.snapshot === value.finalSnapshot && check.afterSnapshot === value.finalSnapshot
                && check.timedOut === false && check.cancelled === false;
        });
        return valid ? { label: "必要命令已通过", outcome: value.testDetails === "AVAILABLE" ? "当前源码的验证命令执行成功；可用测试明细见命令历史。" : "当前源码的验证命令执行成功；测试数量与明细未提供。",
            scope: `${required.length} 项验证要求；业务验收另行确认`, tone: "success", counts: null } : unknown;
    }
    const meaningful = fresh.filter(check => (count(check.executedTests) ?? 0) > 0
        && ["TESTS_PASSED", "TESTS_FAILED"].includes(String(check.classification))).at(-1);
    if (value.evidenceVersion === 5) {
        if (!["FULLY_TESTED", "BASELINE_DEBT"].includes(String(value.verificationLevel))) return unknown;
        if (value.verificationLevel === "BASELINE_DEBT") {
            if (!Array.isArray(value.baselineDebt) || !value.baselineDebt.length
                || !fresh.some(check => check.classification === "TESTS_FAILED" && (count(check.executedTests) ?? 0) > 0)) return unknown;
            return { label: "保留历史测试失败", outcome: `已记录 ${value.baselineDebt.length} 项历史失败；交付前须审阅确认。`,
                scope: "基线对比结果；各项当前检查见下方", tone: "warning", counts: null };
        }
    }
    if (value.status === "NOT_TESTED") {
        if (meaningful) return unknown;
        return { label: "未运行测试", outcome: fresh.length ? verificationLabel(String(fresh.at(-1)!.classification))
            : "没有记录测试执行。", scope: "", tone: "neutral", counts: null };
    }
    if (!meaningful) return unknown;
    const passed = meaningful.classification === "TESTS_PASSED";
    const executed = count(meaningful.executedTests)!;
    // 旧证据未记录失败计数时，只能由明确 TESTS_PASSED 推断零失败；失败记录保持未知。
    const failed = count(meaningful.failures) ?? (passed ? 0 : null);
    const errored = count(meaningful.errors) ?? (passed ? 0 : null);
    const contradictory = failed !== null && errored !== null && (failed + errored > executed || passed && failed + errored > 0);
    if (contradictory) return unknown;
    return { label: "已测试", outcome: verificationLabel(String(meaningful.classification)),
        scope: meaningful.checkType ? checkScope(meaningful)
            : meaningful.selectedTest || meaningful.module ? "指定范围的测试结果" : "完整测试范围",
        tone: passed ? "success" : "danger", counts: { executed, passed: failed === null || errored === null
            ? null : executed - failed - errored, failed, errored, skipped: count(meaningful.skipped) } };
}

export type PresentedFileChange = { path: string; changeType: "ADDED" | "MODIFIED" | "DELETED" | "UNKNOWN" };
export function candidateFileChanges(paths: readonly string[], diff: string): PresentedFileChange[] {
    const types = new Map<string, PresentedFileChange["changeType"]>();
    for (const chunk of diff.split(/^diff --git /m).slice(1)) {
        const lines = chunk.split("\n");
        const boundary = lines.findIndex(line => line.startsWith("@@"));
        const headers = boundary < 0 ? lines : lines.slice(0, boundary);
        const kind = headers.some(line => line.startsWith("new file mode ")) ? "ADDED"
            : headers.some(line => line.startsWith("deleted file mode ")) ? "DELETED" : "MODIFIED";
        const source = headers.find(line => line.startsWith(kind === "DELETED" ? "--- " : "+++ "))?.slice(4).replace(/\r$/, "");
        let path: string | undefined;
        if (source) {
            try {
                const quoted = source.match(/^"(?:\\.|[^"\\])*"/);
                path = (quoted ? JSON.parse(quoted[0]) as string : source.split("\t")[0]!).replace(/^[ab]\//, "");
            } catch { /* 不支持的 Git 路径编码不猜测类型，完整差异仍可展开。 */ }
        }
        path ??= paths.find(item => lines[0] === `a/${item} b/${item}`
            || lines[0] === `${JSON.stringify("a/" + item)} ${JSON.stringify("b/" + item)}`);
        if (path && paths.includes(path)) types.set(path, kind);
    }
    return paths.map(path => ({ path, changeType: types.get(path) ?? "UNKNOWN" }));
}
