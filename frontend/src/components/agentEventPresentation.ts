import type { AgentEvent } from "../api/agentTypes";

export interface EventField { label: string; value: string }

export function summaryFields(event: AgentEvent): Record<string, unknown> {
    try {
        const value: unknown = JSON.parse(event.summary);
        if (value && typeof value === "object" && !Array.isArray(value)) return value as Record<string, unknown>;
    } catch { /* 旧格式和不可解析摘要只作原始诊断展示。 */ }
    return {};
}

export function recordedTool(event: AgentEvent) {
    const data = summaryFields(event);
    return typeof data.tool === "string" ? data.tool : event.summary.match(/^tool=([^;]+)/)?.[1];
}

export function diagnosticText(event: AgentEvent) {
    const data = summaryFields(event);
    return Object.keys(data).length ? Object.entries(data)
        .map(([key, value]) => `${key}: ${typeof value === "object" || value === "" ? JSON.stringify(value) : value}`).join("\n")
        : event.summary;
}

export function eventMetadata(event: AgentEvent) {
    const data = summaryFields(event);
    const rows: EventField[] = [];
    const primary: EventField[] = [];
    const text = (key: string) => typeof data[key] === "string" && data[key] !== "" ? data[key] as string : null;
    const number = (key: string) => typeof data[key] === "number" && Number.isFinite(data[key]) && data[key] >= 0
        ? data[key] as number : null;
    const add = (label: string, value: string | null, prominent = false) => {
        if (value === null) return;
        const field = { label, value };
        rows.push(field);
        if (prominent) primary.push(field);
    };
    if (["TOOL_STARTED", "TOOL_FINISHED"].includes(event.kind)) {
        if (typeof data.ok === "boolean") add("工具结果", data.ok ? "成功" : "失败");
        const classification = text("classification");
        add("结果分类", classification, classification !== "RESULT");
        const duration = number("durationMs");
        add("耗时", duration === null ? null : `${duration.toLocaleString("zh-CN")} ms`, true);
        // 只使用该条已保存事件的元数据，不从其他工具事件、源码或补丁推导目标。
        const targetKey = text("path") ? "path" : text("module") ? "module" : text("directory") !== "." ? "directory" : null;
        for (const [key, label] of [["path", "文件"], ["module", "模块"], ["directory", "目录"],
            ["goal", "Maven 目标"], ["selectedTest", "测试选择"]] as const) add(label, text(key), key === targetKey);
        if (recordedTool(event) === "run_checks") {
            for (const [key, label] of [["executedTests", "实际执行测试"], ["tests", "报告测试数"],
                ["skipped", "跳过"], ["failures", "失败"], ["errors", "错误"]] as const) {
                const count = number(key);
                if (count !== null && count > 0) add(label, count.toLocaleString("zh-CN"));
            }
        }
    } else if (event.kind === "SANDBOX_READY") {
        const jdk = number("targetJdk");
        add("JDK", jdk === null ? null : String(jdk));
        const memory = number("memoryBytes");
        add("内存", memory === null ? null : `${(memory / 1048576).toLocaleString("zh-CN")} MiB`);
        const cpu = number("nanoCpus");
        add("CPU", cpu === null ? null : `${(cpu / 1e9).toLocaleString("zh-CN")} 核`);
        const pids = number("pidsLimit");
        add("PID 上限", pids === null ? null : pids.toLocaleString("zh-CN"));
        add("网络", text("networkMode"));
        if (typeof data.readOnlyRoot === "boolean") add("只读根文件系统", data.readOnlyRoot ? "是" : "否");
    }
    return { primary, rows, structured: rows.length > 0 };
}
