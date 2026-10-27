import type { AgentRun, Delivery } from "../api/agentTypes";

export interface RunPresentation {
    label: string;
    detail: string;
    tone: "completed" | "failed" | "blocked" | "recorded";
}
export function presentRun(run: AgentRun, delivery?: Delivery | null): RunPresentation {
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
    return ({ TESTS_PASSED: "测试通过", TESTS_FAILED: "测试未通过", BUILD_PASSED_NOT_TESTED: "构建通过，未运行测试",
        COMPILATION_FAILED: "编译未通过", DEPENDENCY_MISSING: "依赖尚未准备", BUILD_CONFIGURATION_FAILED: "构建配置需核对",
        JDK_ENVIRONMENT_MISMATCH: "运行环境不匹配", POLICY_REJECTED: "检查被安全规则停止", INVALID_ARGUMENTS: "检查尚未开始",
        CHECK_RESULT_UNAVAILABLE: "检查结果待核对" } as Record<string, string>)[code ?? ""] ?? "检查结果待核对";
}
function record(value: unknown): value is Record<string, unknown> {
    return !!value && typeof value === "object" && !Array.isArray(value);
}
function count(value: unknown): number | null {
    return typeof value === "number" && Number.isSafeInteger(value) && value >= 0 ? value : null;
}
export interface TestPresentation {
    label: string;
    outcome: string;
    scope: string;
    tone: "success" | "danger" | "neutral" | "warning";
    counts: { executed: number; passed: number | null; failed: number | null; errored: number | null; skipped: number | null } | null;
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
    const meaningful = fresh.filter(check => (count(check.executedTests) ?? 0) > 0
        && ["TESTS_PASSED", "TESTS_FAILED"].includes(String(check.classification))).at(-1);
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
        scope: meaningful.selectedTest || meaningful.module ? "指定范围的测试结果" : "完整测试范围",
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
