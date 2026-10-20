import { describe, expect, it } from "vitest";
import type { EvolutionStep } from "../src/api/types";
import { stepEvolutionTrace } from "../src/state/stepEvolutionPresentation";
import { successfulPlan } from "./fixtures";

function failedCandidate(revision: string) {
  const step = structuredClone(successfulPlan().steps[0]!);
  step.executionResult!.candidateRevision = revision;
  step.verificationResult = { ...step.verificationResult!, candidateRevision: revision, passed: false,
    failures: ["DIFF_CHECK failed"], summary: "failed" };
  return step;
}
function repair(id: string, step: EvolutionStep) {
  return { id, candidateRevision: step.executionResult!.candidateRevision, authorizedAt: "2026-10-18T10:02:00Z",
    previousExecution: step.executionResult!, previousVerification: step.verificationResult! };
}
function executionFailure() {
  return { id: "failure", baselineRevision: "a".repeat(40), startingRevision: "a".repeat(40),
    phase: "EXPECTED_TREE", exitCode: 1, diagnostic: "CONFIRMED_GIT_REJECTION", observedRevision: null,
    expectedTree: null, diffComplete: true, diff: [], failedAt: "2026-10-18T10:03:00Z" };
}
export function traceFixture(): EvolutionStep {
  const step = structuredClone(successfulPlan().steps[0]!);
  step.repairAuthorizations = [repair("repair-1", failedCandidate("c".repeat(40))),
    repair("repair-2", failedCandidate("d".repeat(40)))];
  step.retryAuthorizations = [{ id: "retry-1", failedCheckpointId: "failure", baselineRevision: "a".repeat(40),
    authorizedAt: "2026-10-18T10:04:00Z", previousExecution: null, previousVerification: null, previousRepairs: [],
    previousRollbackAuthorization: null, previousRollbackResult: null, previousExecutionFailure: executionFailure(),
    previousAutomaticRollback: { failureId: "failure", baselineRevision: "a".repeat(40), observedRevision: "a".repeat(40),
      restored: true, diagnostic: "EXACT_BASELINE_CONFIRMED", completedAt: "2026-10-18T10:03:30Z" } }];
  return step;
}
describe("Step persisted fact projection", () => {
  it("retains retry ownership and multiple repairs without duplicating snapshots", () => {
    const result = stepEvolutionTrace(traceFixture());
    expect(result[0]!.nodes.map(n => n.label)).toEqual(["Execution", "Automatic Rollback", "Retry"]);
    expect(result[1]!.nodes.map(n => n.label)).toEqual(["Candidate", "Verification", "Repair", "Repair Candidate",
      "Verification", "Repair", "Repair Candidate", "Verification", "当前状态"]);
    expect(result[1]!.nodes.filter(n => n.label === "Verification").map(n => n.result)).toEqual(["FAIL", "FAIL", "PASS"]);
    expect(result.at(-1)!.nodes.at(-1)!.time).toBeNull();
  });
  it("does not sort facts by potentially skewed timestamps", () => {
    const step = traceFixture();
    step.repairAuthorizations![0]!.authorizedAt = "2000-01-01T00:00:00Z";
    const nodes = stepEvolutionTrace(step)[1]!.nodes;
    expect(nodes[0]!.label).toBe("Candidate");
    expect(nodes[2]!.label).toBe("Repair");
  });
  it("READY after retry does not fabricate another Execute or Candidate", () => {
    const step = traceFixture();
    Object.assign(step, { status: "READY", executionResult: null, verificationResult: null, repairAuthorizations: [] });
    expect(stepEvolutionTrace(step).at(-1)!.nodes.map(n => n.label)).toEqual(["当前状态"]);
  });
  it("retains failed Repair facts before an automatic rollback result", () => {
    const step = traceFixture();
    Object.assign(step, { status: "RECOVERY_REQUIRED", executionResult: null, verificationResult: null,
      executionFailureResult: executionFailure(), automaticRollbackResult: {
        ...step.retryAuthorizations![0]!.previousAutomaticRollback!, restored: false } });
    const nodes = stepEvolutionTrace(step).at(-1)!.nodes;
    expect(nodes.some(n => n.label === "Repair")).toBe(true);
    expect(nodes.at(-2)!.result).toContain("恢复未确认");
    expect(nodes.at(-1)!.result).toBe("RECOVERY_REQUIRED");
  });
  it("distinguishes user authorization from user rollback completion", () => {
    const step = failedCandidate("c".repeat(40));
    step.status = "ROLLING_BACK";
    step.rollbackAuthorization = { id: "rollback", baselineRevision: "a".repeat(40),
      candidateRevision: "c".repeat(40), authorizedAt: "2026-10-18T10:01:00Z" };
    expect(stepEvolutionTrace(step)[0]!.nodes.filter(n => n.label === "User Rollback")).toHaveLength(1);
    step.rollbackResult = { authorizationId: "rollback", baselineRevision: "a".repeat(40), observedRevision: "a".repeat(40),
      restored: true, diagnostic: "restored", completedAt: "2026-10-18T10:02:00Z" };
    expect(stepEvolutionTrace(step)[0]!.nodes.filter(n => n.label === "User Rollback")).toHaveLength(2);
  });
});
