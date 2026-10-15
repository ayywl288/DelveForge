import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../src/api/client";
import type { EvolutionPlan } from "../src/api/types";
import { createSession, executionKey, type Session, type ExecutionReceipt } from "../src/state/session";
import { copy, successfulPlan } from "./fixtures";
import { installWebLocks } from "./webLocks";

const baseline = "a".repeat(40);
const authorization = "20000000-0000-4000-8000-000000000002";
const failure = "10000000-0000-4000-8000-000000000001";
const sessions: Session[] = [];
afterEach(() => {
  sessions.splice(0).forEach(session => session.dispose());
  vi.restoreAllMocks();
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function settle() {
  for (let index = 0; index < 50; index++) await Promise.resolve();
}
function readyPlan(): EvolutionPlan {
  const result = successfulPlan(); result.status = "ACTIVE";
  const step = result.steps[0]!;
  const previous = structuredClone(step);
  step.retryAuthorizations = [{ id: authorization, failedCheckpointId: failure, baselineRevision: baseline,
    authorizedAt: "2026-10-16T00:00:00Z", previousExecution: previous.executionResult,
    previousVerification: { ...previous.verificationResult!, passed: false }, previousRepairs: [],
    previousRollbackAuthorization: { id: failure, candidateRevision: "b".repeat(40), baselineRevision: baseline,
      authorizedAt: "2026-10-15T23:59:00Z" },
    previousRollbackResult: { authorizationId: failure, baselineRevision: baseline, observedRevision: baseline,
      restored: true, diagnostic: "EXACT_BASELINE_CONFIRMED", completedAt: "2026-10-15T23:59:01Z" },
    previousExecutionFailure: null, previousAutomaticRollback: null }];
  step.status = "READY"; step.baselineRevision = baseline;
  step.executionResult = null; step.verificationResult = null;
  step.repairAuthorizations = []; step.rollbackAuthorization = null; step.rollbackResult = null;
  return result;
}
function oldRetryReceipt() {
  return { requestId: "old-retry-request", planId: "plan-1", stepId: "step-1", operation: "RETRY",
    startedAt: "2026-10-16T00:00:00Z", authorizationId: authorization,
    failedCheckpointId: failure, baselineRevision: baseline };
}
function tab() {
  const session = createSession(); sessions.push(session); session.ids.planId = "plan-1";
  return session;
}
function storageEvent(newValue = localStorage.getItem(executionKey)) {
  window.dispatchEvent(new StorageEvent("storage", { key: executionKey, newValue }));
}

describe("M5-010 cross-tab receipt ownership", () => {
  it.each(["delayed-storage-ready", "delivered-storage-terminal"])(
    "%s：旧 Retry GET 不能清除另一 Session 的新 Execute", async scenario => {
      installWebLocks();
      let server = readyPlan();
      vi.spyOn(api, "plan").mockImplementation(async () => structuredClone(server));
      vi.spyOn(api, "workingCopy").mockResolvedValue({ ...copy(), status: "EVOLVING" });
      localStorage.setItem(executionKey, JSON.stringify(oldRetryReceipt()));
      const older = tab(), newer = tab();
      const oldGet = deferred<EvolutionPlan>();
      vi.mocked(api.plan).mockImplementationOnce(() => oldGet.promise);
      const pendingGet = older.refreshPlan();
      await newer.refreshPlan();
      expect(newer.receipt.value).toBeNull();
      const response = deferred<EvolutionPlan>();
      const execute = vi.spyOn(api, "execute").mockImplementation(() => response.promise);
      const pendingExecute = newer.execute("step-1", authorization);
      await settle();
      expect(execute).toHaveBeenCalledExactlyOnceWith("plan-1", "step-1", authorization);
      const stored = localStorage.getItem(executionKey);
      expect(stored).not.toBeNull();
      try {
        let snapshot = readyPlan();
        if (scenario === "delivered-storage-terminal") {
          storageEvent();
          snapshot = successfulPlan(); snapshot.steps[0]!.retryAuthorizations = readyPlan().steps[0]!.retryAuthorizations;
        }
        oldGet.resolve(snapshot); await pendingGet;
        expect(localStorage.getItem(executionKey)).toBe(stored);
        const reloaded = tab();
        expect(JSON.stringify(reloaded.receipt.value)).toBe(stored);
        expect(reloaded.receipt.value?.operation).toBeUndefined();
        expect(execute).toHaveBeenCalledOnce();
      } finally {
        server = readyPlan(); server.steps[0]!.status = "EXECUTING";
        response.reject(new ApiError(0, "CONNECTION_LOST"));
        await pendingExecute;
      }
    });
});

const receiptLock = "delveforge.execution-receipt";
const operations = ["EXECUTE", "REPAIR", "ROLLBACK", "RETRY"] as const;
type Operation = typeof operations[number];
const nextFailure = "30000000-0000-4000-8000-000000000003";
function candidatePlan(): EvolutionPlan {
  const result = successfulPlan(); result.status = "ACTIVE";
  const step = result.steps[0]!;
  step.status = "VERIFICATION_FAILED"; step.verificationResult!.passed = false;
  step.retryAuthorizations = readyPlan().steps[0]!.retryAuthorizations;
  step.repairAuthorizations = []; step.rollbackAuthorization = null; step.rollbackResult = null;
  return result;
}
function restoredPlan(): EvolutionPlan {
  const result = candidatePlan(), step = result.steps[0]!;
  step.status = "FAILED";
  step.rollbackAuthorization = { id: nextFailure, candidateRevision: "b".repeat(40), baselineRevision: baseline,
    authorizedAt: "2026-10-16T00:01:00Z" };
  step.rollbackResult = { authorizationId: nextFailure, baselineRevision: baseline, observedRevision: baseline,
    restored: true, diagnostic: "EXACT_BASELINE_CONFIRMED", completedAt: "2026-10-16T00:01:01Z" };
  return result;
}
function beforeOperation(operation: Operation) {
  return operation === "EXECUTE" ? readyPlan() : operation === "RETRY" ? restoredPlan() : candidatePlan();
}
function serverFixture(initial = readyPlan()) {
  let server = initial;
  vi.spyOn(api, "plan").mockImplementation(async () => structuredClone(server));
  vi.spyOn(api, "workingCopy").mockImplementation(async () => {
    const step = server.steps[0]!;
    const current = ["READY", "FAILED"].includes(step.status) ? baseline
      : step.executionResult?.candidateRevision ?? baseline;
    return { ...copy(), status: "EVOLVING", currentRevision: current,
      lastVerifiedRevision: step.status === "SUCCEEDED" ? current : baseline };
  });
  return { get: () => server, set: (value: EvolutionPlan) => { server = value; } };
}
function invoke(session: Session, operation: Operation) {
  if (operation === "EXECUTE") return session.execute("step-1", authorization);
  if (operation === "REPAIR") return session.repair("step-1", "b".repeat(40), authorization);
  if (operation === "ROLLBACK") return session.rollback("step-1", "b".repeat(40), baseline, authorization);
  return session.retry("step-1", nextFailure, baseline);
}
function posts(callback: (operation: Operation, args: (string | undefined)[]) => Promise<EvolutionPlan>) {
  return [
    vi.spyOn(api, "execute").mockImplementation((...args) => callback("EXECUTE", args)),
    vi.spyOn(api, "repair").mockImplementation((...args) => callback("REPAIR", args)),
    vi.spyOn(api, "rollback").mockImplementation((...args) => callback("ROLLBACK", args)),
    vi.spyOn(api, "retry").mockImplementation((...args) => callback("RETRY", args)),
  ];
}
function requestMarker(operation: Operation, legacy = false): ExecutionReceipt {
  return { ...(legacy ? {} : { requestId: "expected-request" }), planId: "plan-1", stepId: "step-1",
    startedAt: "2026-10-16T00:02:00Z",
    ...(operation === "EXECUTE" ? {} : { operation, authorizationId: "40000000-0000-4000-8000-000000000004" }),
    ...(operation === "RETRY" ? { failedCheckpointId: nextFailure } : { retryAuthorizationId: authorization }),
    ...(["REPAIR", "ROLLBACK"].includes(operation) ? { candidateRevision: "b".repeat(40) } : {}),
    ...(["ROLLBACK", "RETRY"].includes(operation) ? { baselineRevision: baseline } : {}) };
}
function completedPlan(operation: Operation, marker: ExecutionReceipt): EvolutionPlan {
  if (operation === "EXECUTE") {
    const result = successfulPlan(); result.steps[0]!.retryAuthorizations = readyPlan().steps[0]!.retryAuthorizations;
    return result;
  }
  if (operation === "RETRY") {
    const result = readyPlan(), prior = restoredPlan().steps[0]!;
    result.steps[0]!.retryAuthorizations!.push({ id: marker.authorizationId!, failedCheckpointId: nextFailure,
      baselineRevision: baseline, authorizedAt: marker.startedAt, previousExecution: prior.executionResult,
      previousVerification: prior.verificationResult, previousRepairs: [],
      previousRollbackAuthorization: prior.rollbackAuthorization, previousRollbackResult: prior.rollbackResult,
      previousExecutionFailure: null, previousAutomaticRollback: null });
    return result;
  }
  const result = candidatePlan(), step = result.steps[0]!;
  if (operation === "REPAIR") {
    step.repairAuthorizations = [{ id: marker.authorizationId!, candidateRevision: "b".repeat(40),
      authorizedAt: marker.startedAt, previousExecution: structuredClone(step.executionResult!),
      previousVerification: structuredClone(step.verificationResult!) }];
    step.executionResult!.candidateRevision = "c".repeat(40);
    step.verificationResult!.candidateRevision = "c".repeat(40);
  } else {
    step.status = "FAILED";
    step.rollbackAuthorization = { id: marker.authorizationId!, candidateRevision: "b".repeat(40),
      baselineRevision: baseline, authorizedAt: marker.startedAt };
    step.rollbackResult = { authorizationId: marker.authorizationId!, baselineRevision: baseline,
      observedRevision: baseline, restored: true, diagnostic: "EXACT_BASELINE_CONFIRMED", completedAt: marker.startedAt };
  }
  return result;
}

describe("M5-010 shared receipt protocol", () => {
  it.each(operations)("旧标签的 Plan/Copy GET 不能删除后来 %s 的 UNKNOWN 标记", async operation => {
    installWebLocks(); const server = serverFixture();
    localStorage.setItem(executionKey, JSON.stringify(oldRetryReceipt()));
    const older = tab(), newer = tab();
    const oldCopy = deferred<ReturnType<typeof copy>>();
    vi.mocked(api.workingCopy).mockImplementationOnce(() => oldCopy.promise);
    const pendingGet = older.refreshPlan(); await settle();
    await newer.refreshPlan(); expect(newer.receipt.value).toBeNull();
    server.set(beforeOperation(operation));
    const response = deferred<EvolutionPlan>();
    const requests = posts(async () => response.promise);
    const pendingPost = invoke(newer, operation); await settle();
    expect(requests.reduce((count, request) => count + request.mock.calls.length, 0)).toBe(1);
    const stored = localStorage.getItem(executionKey)!;
    try {
      expect(JSON.parse(stored).requestId).toMatch(/^[0-9a-f-]{36}$/);
      oldCopy.resolve({ ...copy(), status: "EVOLVING" }); await pendingGet;
      expect(localStorage.getItem(executionKey)).toBe(stored);
      storageEvent(null); // 迟到的“旧 receipt 被删除”事件必须读取当前持久化标记。
      expect(JSON.stringify(older.receipt.value)).toBe(stored);
      expect(JSON.stringify(tab().receipt.value)).toBe(stored);
    } finally {
      response.reject(new ApiError(0, "CONNECTION_LOST")); await pendingPost;
    }
    expect(localStorage.getItem(executionKey)).toBe(stored);
    expect(requests.reduce((count, request) => count + request.mock.calls.length, 0)).toBe(1);
  });

  it("清理排队期间替换标记：即使其余字段完全相同，也须按独立 requestId 拒绝删除", async () => {
    const locks = installWebLocks(); serverFixture();
    const original = oldRetryReceipt(), replacement = { ...original, requestId: "later-request" };
    localStorage.setItem(executionKey, JSON.stringify(original));
    const older = tab(), gate = deferred<void>();
    const writer = locks.request(receiptLock, {}, async () => {
      await gate.promise; localStorage.setItem(executionKey, JSON.stringify(replacement));
    });
    await settle(); const pending = older.refreshPlan(); await settle();
    expect(locks.request.mock.calls.filter(call => call[0] === receiptLock)).toHaveLength(2);
    gate.resolve(undefined); await writer; await pending;
    expect(JSON.parse(localStorage.getItem(executionKey)!)).toEqual(replacement);
    expect(older.receipt.value?.requestId).toBe(replacement.requestId);
    await older.refreshPlan();
    expect(localStorage.getItem(executionKey)).toBeNull();
  });

  it("清理等待锁期间出现更新 GET：旧快照失效，不能删除仍未决的授权", async () => {
    const locks = installWebLocks(), server = serverFixture();
    localStorage.setItem(executionKey, JSON.stringify(oldRetryReceipt()));
    const session = tab(), gate = deferred<void>();
    const writer = locks.request(receiptLock, {}, () => gate.promise);
    await settle(); const older = session.refreshPlan(); await settle();
    const executing = readyPlan(); executing.steps[0]!.status = "EXECUTING"; server.set(executing);
    const newer = session.refreshPlan(); await settle();
    gate.resolve(undefined); await writer;
    expect(await older).toBeNull();
    expect((await newer)?.plan.steps[0]?.status).toBe("EXECUTING");
    expect(localStorage.getItem(executionKey)).not.toBeNull();
    server.set(readyPlan()); await session.refreshPlan();
    expect(localStorage.getItem(executionKey)).toBeNull();
  });

  it.each(["older-first", "newer-first"])("%s：GET 乱序与 storage 替换不能误清理新请求", async order => {
    installWebLocks(); const server = serverFixture();
    localStorage.setItem(executionKey, JSON.stringify(oldRetryReceipt()));
    const session = tab(), first = deferred<EvolutionPlan>(), second = deferred<EvolutionPlan>();
    vi.mocked(api.plan).mockImplementationOnce(() => first.promise).mockImplementationOnce(() => second.promise);
    const older = session.refreshPlan(), newer = session.refreshPlan();
    const replacement = requestMarker("EXECUTE");
    await navigator.locks.request(receiptLock, {}, () => localStorage.setItem(executionKey, JSON.stringify(replacement)));
    storageEvent(); const executing = readyPlan(); executing.steps[0]!.status = "EXECUTING"; server.set(executing);
    if (order === "older-first") { first.resolve(readyPlan()); expect(await older).toBeNull(); second.resolve(executing); }
    else { second.resolve(executing); await newer; first.resolve(readyPlan()); }
    expect(await older).toBeNull(); expect(await newer).not.toBeNull();
    expect(JSON.parse(localStorage.getItem(executionKey)!)).toEqual(replacement);
  });

  it.each(operations)("%s 的全部写入/清理均在同名短时锁内，正常结果仍可清理", async operation => {
    const locks = installWebLocks(), server = serverFixture(beforeOperation(operation));
    const set = Storage.prototype.setItem, remove = Storage.prototype.removeItem;
    const written: ExecutionReceipt[] = [];
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(function (this: Storage, key, value) {
      if (key === executionKey) {
        expect(locks.held.has(receiptLock)).toBe(true);
        written.push(JSON.parse(value));
      }
      set.call(this, key, value);
    });
    const deleted = vi.spyOn(Storage.prototype, "removeItem").mockImplementation(function (this: Storage, key) {
      if (key === executionKey) expect(locks.held.has(receiptLock)).toBe(true);
      remove.call(this, key);
    });
    const requests = posts(async current => {
      const marker = JSON.parse(localStorage.getItem(executionKey)!) as ExecutionReceipt;
      expect(marker.requestId).toMatch(/^[0-9a-f-]{36}$/);
      expect(marker.requestId).not.toBe(marker.authorizationId);
      expect(marker.requestId).not.toBe(authorization);
      server.set(completedPlan(current, marker)); return structuredClone(server.get());
    });
    const session = tab(); await invoke(session, operation);
    expect(written).toHaveLength(1);
    expect(deleted.mock.calls.filter(call => call[0] === executionKey)).toHaveLength(1);
    expect(session.receipt.value).toBeNull(); expect(localStorage.getItem(executionKey)).toBeNull();
    expect(requests.reduce((count, request) => count + request.mock.calls.length, 0)).toBe(1);
  });

  it.each(operations)("旧格式 %s 标记可在完整匹配的可信终态正常清理", async operation => {
    installWebLocks(); const marker = requestMarker(operation, true);
    serverFixture(completedPlan(operation, marker));
    localStorage.setItem(executionKey, JSON.stringify(marker));
    const session = tab(), requests = posts(async () => { throw new Error("unexpected POST"); });
    await session.refreshPlan();
    expect(session.receipt.value).toBeNull(); expect(localStorage.getItem(executionKey)).toBeNull();
    requests.forEach(request => expect(request).not.toHaveBeenCalled());
  });

  it("同一父 Retry、同一时间的两次显式 Execute 仍有不同请求身份", async () => {
    vi.useFakeTimers({ toFake: ["Date"] }); vi.setSystemTime(new Date("2026-10-16T00:03:00Z"));
    installWebLocks(); serverFixture(); const markers: ExecutionReceipt[] = [];
    const execute = vi.spyOn(api, "execute").mockImplementation(async () => {
      markers.push(JSON.parse(localStorage.getItem(executionKey)!));
      throw new ApiError(409, "ASSET_READ_PERMISSION_REQUIRED");
    });
    const session = tab(); await invoke(session, "EXECUTE"); await invoke(session, "EXECUTE");
    expect(execute).toHaveBeenCalledTimes(2); expect(markers).toHaveLength(2);
    expect(markers[0]!.startedAt).toBe(markers[1]!.startedAt);
    expect(markers[0]!.retryAuthorizationId).toBe(markers[1]!.retryAuthorizationId);
    expect(markers[0]!.requestId).not.toBe(markers[1]!.requestId);
    expect(localStorage.getItem(executionKey)).toBeNull();
  });

  it.each(["EXECUTING", "VERIFYING", "ROLLING_BACK"] as const)("%s 与页面重载始终只 GET，不能自动重发", async status => {
    installWebLocks(); const server = readyPlan(); server.steps[0]!.status = status; serverFixture(server);
    const marker = requestMarker("EXECUTE"); localStorage.setItem(executionKey, JSON.stringify(marker));
    const first = tab(), requests = posts(async () => { throw new Error("unexpected POST"); });
    await first.refreshPlan(); storageEvent(); const restored = tab(); await restored.refreshPlan();
    expect(JSON.parse(localStorage.getItem(executionKey)!)).toEqual(marker);
    await restored.execute("step-1", authorization);
    requests.forEach(request => expect(request).not.toHaveBeenCalled());
  });

  it("GET 开始时没有 receipt，也不能在 storage 事件后借用新请求清理终态", async () => {
    installWebLocks(); const server = serverFixture();
    const older = tab(), newer = tab(), oldGet = deferred<EvolutionPlan>();
    vi.mocked(api.plan).mockImplementationOnce(() => oldGet.promise);
    const pendingGet = older.refreshPlan();
    const response = deferred<EvolutionPlan>();
    const execute = vi.spyOn(api, "execute").mockImplementation(() => response.promise);
    const pendingPost = invoke(newer, "EXECUTE"); await settle();
    const marker = localStorage.getItem(executionKey);
    try {
      expect(execute).toHaveBeenCalledOnce(); storageEvent();
      const terminal = successfulPlan(); terminal.steps[0]!.retryAuthorizations = readyPlan().steps[0]!.retryAuthorizations;
      oldGet.resolve(terminal); await pendingGet;
      expect(localStorage.getItem(executionKey)).toBe(marker);
    } finally {
      server.set(readyPlan()); response.reject(new ApiError(0, "CONNECTION_LOST")); await pendingPost;
    }
  });

  it("发送前即使错过 storage 事件，也不能覆盖另一标签在锁内写入的标记", async () => {
    const locks = installWebLocks(); serverFixture(); const marker = requestMarker("EXECUTE");
    const session = tab(), gate = deferred<void>();
    const writer = locks.request(receiptLock, {}, async () => {
      await gate.promise; localStorage.setItem(executionKey, JSON.stringify(marker));
    });
    await settle(); const execute = vi.spyOn(api, "execute");
    const pending = invoke(session, "EXECUTE"); await settle();
    gate.resolve(undefined); await writer; await pending;
    expect(execute).not.toHaveBeenCalled();
    expect(JSON.parse(localStorage.getItem(executionKey)!)).toEqual(marker);
    expect(session.receipt.value?.requestId).toBe(marker.requestId);
  });

  it("写入短时锁排队后必须重新确认查询版本；过期资格不能写入或 POST", async () => {
    const locks = installWebLocks(); serverFixture();
    const original = locks.request.getMockImplementation()!, gate = deferred<void>(); let calls = 0;
    locks.request.mockImplementation(async (name, options, callback) => {
      if (name === receiptLock && ++calls === 2) await gate.promise;
      return original(name, options, callback);
    });
    const session = tab(), execute = vi.spyOn(api, "execute");
    const pending = invoke(session, "EXECUTE"); await settle();
    expect(calls).toBe(2);
    await session.refreshPlan(); gate.resolve(undefined); await pending;
    expect(execute).not.toHaveBeenCalled();
    expect(localStorage.getItem(executionKey)).toBeNull();
  });

  it("清理不可获得 Web Lock 或存储不可读时保留 receipt；损坏标记也不能被写入覆盖", async () => {
    const locks = installWebLocks(); serverFixture(); const marker = oldRetryReceipt();
    localStorage.setItem(executionKey, JSON.stringify(marker)); const session = tab();
    Object.defineProperty(navigator, "locks", { configurable: true, value: undefined });
    await session.refreshPlan(); expect(localStorage.getItem(executionKey)).not.toBeNull();
    Object.defineProperty(navigator, "locks", { configurable: true, value: { request: locks.request } });
    const read = Storage.prototype.getItem;
    const blocked = vi.spyOn(Storage.prototype, "getItem").mockImplementation(function (this: Storage, key) {
      if (key === executionKey) throw new Error("storage unavailable");
      return read.call(this, key);
    });
    await session.refreshPlan(); expect(session.receipt.value).not.toBeNull(); blocked.mockRestore();
    expect(localStorage.getItem(executionKey)).not.toBeNull();
    localStorage.setItem(executionKey, "unreadable receipt");
    const another = tab(), execute = vi.spyOn(api, "execute"); await invoke(another, "EXECUTE");
    expect(execute).not.toHaveBeenCalled(); expect(localStorage.getItem(executionKey)).toBe("unreadable receipt");
  });
});
