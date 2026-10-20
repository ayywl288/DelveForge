import { createApp, nextTick, reactive } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../src/api/client";
import type { EvolutionPlan } from "../src/api/types";
import { createSession, type Session } from "../src/state/session";
import EvolutionView from "../src/views/EvolutionView.vue";
import { copy, successfulPlan } from "./fixtures";

const baseline = "a".repeat(40);
const failureId = "10000000-0000-4000-8000-000000000001";
const sessions: Session[] = [];
const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach(fn => fn());
  sessions.splice(0).forEach(s => s.dispose());
  vi.restoreAllMocks();
});
function failedPlan(): EvolutionPlan {
  const result = successfulPlan();
  result.status = "ACTIVE";
  const step = result.steps[0]!;
  step.status = "FAILED";
  step.baselineRevision = baseline;
  step.verificationResult!.passed = false;
  step.repairAuthorizations = [];
  step.retryAuthorizations = [];
  step.rollbackAuthorization = { id: failureId, candidateRevision: "b".repeat(40), baselineRevision: baseline,
    authorizedAt: new Date().toISOString() };
  step.rollbackResult = { authorizationId: failureId, baselineRevision: baseline, observedRevision: baseline,
    restored: true, diagnostic: "EXACT_BASELINE_CONFIRMED", completedAt: new Date().toISOString() };
  return result;
}
function authorizedPlan(previous: EvolutionPlan, authorization: string): EvolutionPlan {
  const result = structuredClone(previous);
  const old = previous.steps[0]!, step = result.steps[0]!;
  step.retryAuthorizations = [{ id: authorization, failedCheckpointId: failureId, baselineRevision: baseline,
    authorizedAt: new Date().toISOString(), previousExecution: old.executionResult,
    previousVerification: old.verificationResult, previousRepairs: old.repairAuthorizations ?? [],
    previousRollbackAuthorization: old.rollbackAuthorization, previousRollbackResult: old.rollbackResult,
    previousExecutionFailure: null, previousAutomaticRollback: null }];
  step.executionResult = null; step.verificationResult = null;
  step.rollbackAuthorization = null; step.rollbackResult = null; step.status = "READY";
  return result;
}
function setup() {
  let server = failedPlan();
  const s = createSession(); sessions.push(s); s.ids.planId = server.id;
  s.plan.value = server;
  s.copy.value = { ...copy(), status: "EVOLVING", currentRevision: baseline, lastVerifiedRevision: baseline };
  vi.spyOn(api, "plan").mockImplementation(async () => server);
  vi.spyOn(api, "workingCopy").mockImplementation(async () => ({ ...copy(), status: "EVOLVING",
    currentRevision: baseline, lastVerifiedRevision: baseline }));
  return { s, get: () => server, set: (value: EvolutionPlan) => { server = value; } };
}
async function settle() {
  for (let index = 0; index < 35; index++) { await Promise.resolve(); await nextTick(); }
}

describe("M5-E FAILED Retry", () => {
  it("只保存独立授权，READY 不自动执行，后续执行携带该轮身份", async () => {
    const f = setup();
    const execute = vi.spyOn(api, "execute");
    const request = vi.spyOn(api, "retry").mockImplementation(async (_plan, _step, expected, revision, authorization) => {
      expect(expected).toBe(failureId); expect(revision).toBe(baseline);
      f.set(authorizedPlan(f.get(), authorization)); return f.get();
    });
    await f.s.retry("step-1", failureId, baseline);
    expect(request).toHaveBeenCalledOnce();
    expect(request.mock.calls[0]![4]).not.toBe(failureId);
    expect(f.s.plan.value?.steps[0]?.status).toBe("READY");
    expect(f.s.receipt.value).toBeNull();
    expect(execute).not.toHaveBeenCalled();
    const authorization = f.get().steps[0]!.retryAuthorizations![0]!.id;
    await f.s.execute("step-1");
    expect(execute).not.toHaveBeenCalled();
    execute.mockRejectedValue(new ApiError(409, "ASSET_READ_PERMISSION_REQUIRED"));
    await f.s.execute("step-1", authorization);
    expect(execute).toHaveBeenCalledExactlyOnceWith("plan-1", "step-1", authorization);
  });

  it.each(["ASSET_READ_PERMISSION_REQUIRED", "ASSET_USAGE_AUTHORIZATION_REQUIRED", "ASSET_LICENSE_INFORMATION_REQUIRED",
    "ASSET_AUTHORIZATION_CONFLICT", "CONFLICT"])("%s 只在已采纳 GET 证明本次未授权时清除等待", async code => {
    const f = setup();
    const request = vi.spyOn(api, "retry").mockRejectedValue(new ApiError(409, code));
    await f.s.retry("step-1", failureId, baseline);
    expect(request).toHaveBeenCalledOnce();
    expect(f.s.receipt.value).toBeNull();
    expect(f.s.plan.value?.steps[0]?.status).toBe("FAILED");
  });

  it("未知响应与刷新不能自动重发；仅完整保存的授权事实可以结束等待", async () => {
    const f = setup(); let authorization = "";
    const request = vi.spyOn(api, "retry").mockImplementation(async (_plan, _step, _failed, _base, id) => {
      authorization = id; throw new ApiError(0, "CONNECTION_LOST");
    });
    await f.s.retry("step-1", failureId, baseline);
    expect(f.s.receipt.value?.authorizationId).toBe(authorization);
    await f.s.retry("step-1", failureId, baseline);
    expect(request).toHaveBeenCalledOnce();
    const restored = createSession(); sessions.push(restored); restored.ids.planId = "plan-1";
    await restored.refreshPlan();
    expect(restored.receipt.value?.authorizationId).toBe(authorization);
    expect(request).toHaveBeenCalledOnce();
    f.set(authorizedPlan(f.get(), authorization));
    f.get().steps[0]!.retryAuthorizations![0]!.failedCheckpointId = "different";
    await restored.refreshPlan();
    expect(restored.receipt.value).not.toBeNull();
    f.get().steps[0]!.retryAuthorizations![0]!.failedCheckpointId = failureId;
    await restored.refreshPlan();
    expect(restored.receipt.value).toBeNull();
    expect(request).toHaveBeenCalledOnce();
  });

  it.each(["EXECUTING", "ROLLING_BACK"] as const)("已保存授权的 %s 继续保留阻塞", async state => {
    const f = setup();
    const request = vi.spyOn(api, "retry").mockImplementation(async (_plan, _step, _failed, _base, id) => {
      const result = authorizedPlan(f.get(), id); result.steps[0]!.status = state; f.set(result);
      throw new ApiError(409, "ASSET_READ_PERMISSION_REQUIRED");
    });
    await f.s.retry("step-1", failureId, baseline);
    expect(f.s.receipt.value).not.toBeNull();
    await f.s.retry("step-1", failureId, baseline);
    expect(request).toHaveBeenCalledOnce();
  });

  it("被丢弃的旧 GET 不能清除已持久化授权的等待标记", async () => {
    const f = setup(); const failed = f.get();
    let older!: (plan: EvolutionPlan) => void, newer!: (plan: EvolutionPlan) => void;
    let authorization = "";
    const request = vi.spyOn(api, "retry").mockImplementation(async (_plan, _step, _failed, _base, id) => {
      authorization = id;
      vi.mocked(api.plan).mockImplementationOnce(() => new Promise(resolve => { older = resolve; }))
        .mockImplementationOnce(() => new Promise(resolve => { newer = resolve; }));
      throw new ApiError(409, "ASSET_AUTHORIZATION_CONFLICT");
    });
    const pending = f.s.retry("step-1", failureId, baseline);
    await settle(); expect(request).toHaveBeenCalledOnce();
    const fresh = f.s.refreshPlan(); await settle();
    older(failed); await pending;
    expect(f.s.receipt.value?.authorizationId).toBe(authorization);
    const saved = authorizedPlan(failed, authorization); saved.steps[0]!.status = "EXECUTING";
    newer(saved); await fresh;
    expect(f.s.receipt.value?.authorizationId).toBe(authorization);
    expect(f.s.plan.value?.steps[0]?.status).toBe("EXECUTING");
  });

  it("重复点击只发送一次 Retry 授权，不自动执行", async () => {
    const f = setup(); let finish!: () => void;
    const execute = vi.spyOn(api, "execute");
    const request = vi.spyOn(api, "retry").mockImplementation((_plan, _step, _failed, _base, id) => new Promise(resolve => {
      finish = () => { f.set(authorizedPlan(f.get(), id)); resolve(f.get()); };
    }));
    const first = f.s.retry("step-1", failureId, baseline); await settle();
    await f.s.retry("step-1", failureId, baseline);
    expect(request).toHaveBeenCalledOnce();
    finish(); await first;
    expect(execute).not.toHaveBeenCalled(); expect(f.s.receipt.value).toBeNull();
  });

  it("UI 要求独立确认并展示失败历史，确认不会触发执行", async () => {
    const f = setup(); const s = reactive(f.s);
    const retry = vi.spyOn(s, "retry").mockResolvedValue(undefined);
    const execute = vi.spyOn(s, "execute").mockResolvedValue(undefined);
    const root = document.createElement("div"); document.body.append(root);
    const app = createApp(EvolutionView, { s }); app.mount(root);
    cleanup.push(() => { app.unmount(); root.remove(); });
    const button = [...root.querySelectorAll("button")].find(item => item.textContent?.includes("审阅并独立授权完整重试"))!;
    expect(button.disabled).toBe(false); button.click(); await settle();
    const confirm = [...root.querySelectorAll("button")].find(item => item.textContent?.includes("授权完整重试，返回待执行"))!;
    expect(confirm.disabled).toBe(true); expect(root.textContent).toContain("不调用 AI、不修改代码");
    const checkbox = root.querySelector<HTMLInputElement>('dialog[open] input[type="checkbox"]')!;
    expect(checkbox).not.toBeNull();
    checkbox.checked = true; checkbox.dispatchEvent(new Event("change", { bubbles: true })); await nextTick();
    expect(confirm.disabled).toBe(false);
    confirm.click(); await nextTick();
    expect(retry).toHaveBeenCalledExactlyOnceWith("step-1", failureId, baseline);
    expect(execute).not.toHaveBeenCalled();
    const saved = authorizedPlan(f.get(), "retry-history-id"); s.plan = saved; await nextTick();
    root.querySelector<HTMLButtonElement>("#step-tab-history")!.click(); await nextTick();
    expect(root.querySelector("#retry-history")?.textContent).toContain("retry-history-id");
    expect(root.querySelector("#retry-history")?.textContent).toContain(failureId);
    expect(root.querySelector("#retry-history")?.textContent).toContain("EXACT_BASELINE_CONFIRMED");
  });
  it("客户端发送当前失败、baseline 与 Retry 身份，不隐式复用旧执行请求", async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify(failedPlan()), {
      status: 200, headers: { "Content-Type": "application/json" },
    }));
    vi.stubGlobal("fetch", fetcher);
    const authorization = "20000000-0000-4000-8000-000000000002";
    await api.retry("plan-1", "step-1", failureId, baseline, authorization);
    await api.execute("plan-1", "step-1", authorization);
    const calls = fetcher.mock.calls as unknown as [string, RequestInit][];
    expect(calls[0]![0]).toContain("/steps/step-1/retry");
    expect(calls[0]![1].method).toBe("POST");
    expect(JSON.parse(calls[0]![1].body as string)).toEqual({ expectedFailureId: failureId,
      expectedBaselineRevision: baseline, authorizationId: authorization });
    expect(JSON.parse(calls[1]![1].body as string)).toEqual({ retryAuthorizationId: authorization });
  });

});
