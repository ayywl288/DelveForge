import { describe, expect, it } from "vitest";
import { candidateFileChanges, presentRun, presentTestEvidence } from "../src/components/agentResultPresentation";
import type { AgentRun, Delivery } from "../src/api/agentTypes";

const run: AgentRun = { id: "r", planId: "p", stepId: "s", workingCopyId: "w", baseline: "a".repeat(40),
    state: "SUCCEEDED", startedAt: "2026-10-27T01:00:00Z", endedAt: "2026-10-27T01:01:00Z", reason: "CANDIDATE_FROZEN",
    candidateId: "c", cancelRequested: false, retained: false, modelCalls: 1, toolCalls: 2, tokens: 3 };
const delivery: Delivery = { candidateId: "c", state: "COMPLETED", revision: "b".repeat(40), reason: null };
const check = { classification: "TESTS_PASSED", snapshot: "a".repeat(64), stale: false, goal: "test",
    module: "", selectedTest: "", tests: 10, executedTests: 8, skipped: 2 };
function evidence(status: string, checks: unknown[]) { return JSON.stringify({ status, finalSnapshot: "a".repeat(64), checks }); }

describe("Run result presentation", () => {
    it("keeps model incomplete distinct from cancellation and platform failure", () => {
        const failed = { ...run, state: "FAILED" as const, candidateId: null, reason: "MODEL_INCOMPLETE" };
        expect(presentRun(failed, null).label).toBe("模型报告未完成");
        expect(presentRun(failed, null).detail).toContain("执行活动");
        expect(presentRun({ ...failed, state: "CANCELLED", reason: "USER_CANCELLED" }, null).label).toBe("运行已取消");
        expect(presentRun({ ...failed, reason: "SANDBOX_FAILED" }, null).label).toBe("本次运行失败");
    });
    it("requires matching confirmed Delivery and preserves historical failures", () => {
        expect(presentRun(run, delivery).label).toBe("步骤已完成");
        expect(presentRun(run, null).label).toBe("候选已冻结，等待交付");
        expect(presentRun(run, { ...delivery, candidateId: "other" }).label).toBe("候选已冻结，等待交付");
        expect(presentRun(run, { ...delivery, state: "DELIVERY_BLOCKED" }).label).toBe("交付受阻");
        expect(presentRun({ ...run, state: "FAILED", candidateId: null }, delivery).label).toBe("本次运行失败");
    });
});

describe("structured test evidence", () => {
    it("keeps baseline debt visible even after a passing frontend or selected check", () => {
        const failed = { ...check, checkType: "MAVEN", directory: "backend", classification: "TESTS_FAILED", failures: 1, errors: 2 };
        for (const status of ["TESTED", "NOT_TESTED"]) {
            const raw = JSON.stringify({ evidenceVersion: 5, verificationLevel: "BASELINE_DEBT", status,
                finalSnapshot: "a".repeat(64), baselineDebt: [{ testId: "old" }],
                checks: [failed, { ...check, checkType: "FRONTEND", directory: "frontend" }] });
            const result = presentTestEvidence(raw);
            expect(result.label).toBe("保留历史测试失败");
            expect(result.outcome).toContain("交付前须审阅确认");
            expect(result.tone).toBe("warning");
            expect(result.counts).toBeNull();
        }
    });
    it("does not invent a debt result from missing or stale current evidence", () => {
        for (const debt of [[], [{ testId: "old" }]]) {
            const raw = JSON.stringify({ evidenceVersion: 5, verificationLevel: "BASELINE_DEBT", status: "TESTED",
                finalSnapshot: "a".repeat(64), baselineDebt: debt, checks: [{ ...check, stale: true }] });
            expect(presentTestEvidence(raw).label).toBe("检查证据待核对");
        }
    });
    it("shows actual counts and retains a test pass when the last action only compiled", () => {
        const result = presentTestEvidence(evidence("TESTED", [check,
            { ...check, classification: "BUILD_PASSED_NOT_TESTED", goal: "compile", tests: 0, executedTests: 0, skipped: 0 }]));
        expect(result.label).toBe("已测试");
        expect(result.outcome).toBe("测试通过");
        expect(result.counts).toEqual({ executed: 8, passed: 8, failed: 0, errored: 0, skipped: 2 });
    });
    it("never turns missing legacy failure counts, stale passes or NOT_TESTED into success", () => {
        const failed = presentTestEvidence(evidence("TESTED", [{ ...check, classification: "TESTS_FAILED" }]));
        expect(failed.outcome).toBe("测试未通过");
        expect(failed.counts?.failed).toBeNull();
        expect(failed.counts?.passed).toBeNull();
        expect(presentTestEvidence(evidence("TESTED", [{ ...check, stale: true }])).label).toBe("检查证据待核对");
        expect(presentTestEvidence(evidence("NOT_TESTED", [{ ...check, classification: "BUILD_PASSED_NOT_TESTED", executedTests: 0, tests: 0 }])).label).toBe("未运行测试");
        expect(presentTestEvidence("{broken").label).toBe("检查证据待核对");
    });
    it("reads failure and error counts when recorded without inventing passing tests", () => {
        const result = presentTestEvidence(evidence("TESTED", [{ ...check, classification: "TESTS_FAILED", failures: 1, errors: 2 }]));
        expect(result.counts).toEqual({ executed: 8, passed: 5, failed: 1, errored: 2, skipped: 2 });
    });
});

it("classifies frozen added/modified/deleted files, quoted paths and unparsed paths honestly", () => {
    const diff = ['diff --git a/src/New.java b/src/New.java', 'new file mode 100644', '--- /dev/null', '+++ b/src/New.java',
        '@@ -0,0 +1 @@', '+new', 'diff --git a/src/Old.java b/src/Old.java', 'deleted file mode 100644',
        '--- a/src/Old.java', '+++ /dev/null', '@@ -1 +0,0 @@', '-old',
        'diff --git "a/src/with space.java" "b/src/with space.java"', '--- "a/src/with space.java"', '+++ "b/src/with space.java"', '@@ -1 +1 @@', '-before', '+after'].join("\n");
    expect(candidateFileChanges(["src/New.java", "src/Old.java", "src/with space.java", "unknown.java"], diff)).toEqual([
        { path: "src/New.java", changeType: "ADDED" }, { path: "src/Old.java", changeType: "DELETED" },
        { path: "src/with space.java", changeType: "MODIFIED" }, { path: "unknown.java", changeType: "UNKNOWN" }]);
});


it("requires an explicit valid final snapshot binding before presenting a test pass", () => {
    const missing = JSON.stringify({status:"TESTED",checks:[check]});
    const mismatched = JSON.stringify({status:"TESTED",finalSnapshot:"b".repeat(64),checks:[check]});
    const invalid = JSON.stringify({status:"TESTED",finalSnapshot:"invalid",checks:[{...check,snapshot:"invalid"}]});
    for (const raw of [missing,mismatched,invalid]) {
        expect(presentTestEvidence(raw).label).toBe("检查证据待核对");
        expect(presentTestEvidence(raw).counts).toBeNull();
    }
});


it("v7 shows only actual referenced current executions and refuses an explicitly unresolved report", () => {
    const snapshot = "a".repeat(64);
    const evidence = { evidenceVersion: 7, status: "NOT_TESTED", runId: "run", finalSnapshot: snapshot,
        agentReport: { status: "completed", validationComplete: true, executions: ["pass"], limitations: "projected view" },
        checks: [{ invocationId: "old", runId: "run", classification: "COMMAND_FAILED", exitCode: 1, snapshot },
            { invocationId: "pass", runId: "run", classification: "COMMAND_SUCCEEDED", exitCode: 0,
                snapshot, afterSnapshot: snapshot, timedOut: false, cancelled: false }] };
    expect(presentTestEvidence(JSON.stringify(evidence)).label).toBe("引用执行已核验");
    evidence.agentReport.validationComplete = false;
    expect(presentTestEvidence(JSON.stringify(evidence)).tone).toBe("warning");
});
