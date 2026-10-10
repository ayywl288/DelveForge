import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../src/api/client";
import {
  createSession,
  executionKey,
  sessionKey,
  type Session,
} from "../src/state/session";
import { copy, plan, profile, successfulPlan } from "./fixtures";
const sessions: Session[] = [];
const make = () => {
  const s = createSession();
  sessions.push(s);
  return s;
};
afterEach(() => sessions.splice(0).forEach((s) => s.dispose()));
function ready(s: Session) {
  s.ids.planId = "plan-1";
  s.plan.value = plan();
  s.copy.value = copy();
  vi.spyOn(api, "plan").mockImplementation(async () => plan());
  vi.spyOn(api, "workingCopy").mockImplementation(async () => copy());
}
describe("恢复与显式授权", () => {
  it("多轮探索显示真实 nextQuestion，信息不足时不进入确认", async () => {
    const s = make(); s.profile.value = profile(); s.ids.profileId = "profile-1";
    const discovery = vi.spyOn(api, "discoveryTurn");
    discovery.mockResolvedValueOnce({ profile: { ...profile(), revision: 2, painPoints: ["重复导出"] }, sufficient: false, missingAreas: ["constraints"], nextQuestion: "每周能投入多少时间？" });
    discovery.mockResolvedValueOnce({ profile: { ...profile(), revision: 3, status: "REVIEWING", constraints: ["每周三小时"] }, sufficient: true, missingAreas: [], nextQuestion: null });
    await s.turn("重复导出报表");
    expect(s.profile.value?.status).toBe("EXPLORING"); expect(s.messages.value.at(-1)?.text).toBe("每周能投入多少时间？");
    await s.turn("每周三小时");
    expect(s.profile.value?.status).toBe("REVIEWING"); expect(s.profile.value?.constraints).toEqual(["每周三小时"]);
    expect(s.messages.value.map(message => message.role)).toEqual(["user", "agent", "user"]);
  });
  it("刷新仅 GET，聊天与敏感业务内容不会进入存储", async () => {
    localStorage.setItem(
      sessionKey,
      JSON.stringify({
        profileId: "profile-1",
        assetIds: [],
        repositoryIds: [],
        directionIds: [],
        planId: "plan-1",
        prompt: "SECRET_IGNORED",
      }),
    );
    const fetcher = vi.fn(
      async (url: string, _options?: RequestInit) =>
        new Response(
          JSON.stringify(
            url.endsWith("connectivity")
              ? { status: "UP" }
              : url.includes("user-profiles")
                ? profile()
                : url.includes("working-copies")
                  ? copy()
                  : plan(),
          ),
        ),
    );
    vi.stubGlobal("fetch", fetcher);
    const s = make();
    await s.restore();
    expect(
      fetcher.mock.calls.every(
        (call) => (call[1] as RequestInit | undefined)?.method === "GET",
      ),
    ).toBe(true);
    expect(s.messages.value).toEqual([]);
    expect(s.sufficiency.value).toBeNull();
    expect(localStorage.getItem(sessionKey)).not.toContain("SECRET_IGNORED");
    expect(localStorage.getItem(sessionKey)).not.toContain("currentState");
  });
  it("discovery-turn 原子响应更新画像、问题与充分性；confirm 使用已审阅 revision", async () => {
    const s = make();
    s.profile.value = profile();
    s.ids.profileId = "profile-1";
    const reviewed = {
      ...profile(),
      status: "REVIEWING" as const,
      revision: 8,
      painPoints: ["真实响应条目"],
    };
    vi.spyOn(api, "discoveryTurn").mockResolvedValue({
      profile: reviewed,
      sufficient: true,
      missingAreas: [],
      nextQuestion: null,
    });
    const confirm = vi
      .spyOn(api, "confirmProfile")
      .mockRejectedValue(new ApiError(409, "CONFLICT"));
    vi.spyOn(api, "profile").mockResolvedValue({ ...reviewed, revision: 9 });
    await s.turn("输入");
    expect(s.profile.value?.painPoints).toEqual(["真实响应条目"]);
    expect(s.sufficiency.value?.sufficient).toBe(true);
    await s.confirmProfile(8);
    expect(confirm).toHaveBeenCalledWith("profile-1", 8);
    expect(s.profile.value?.revision).toBe(9);
    expect(s.profile.value?.status).toBe("REVIEWING");
    expect(s.error.value).toContain("版本");
    expect(localStorage.getItem(sessionKey) ?? "").not.toContain(
      "真实响应条目",
    );
  });
});
describe("长同步 execute", () => {
  it("702 秒仍等待且只发送一次 POST，轮询 READY 不制造 PREPARING", async () => {
    vi.useFakeTimers();
    const s = make();
    ready(s);
    let resolve!: (value: ReturnType<typeof plan>) => void;
    const execute = vi.spyOn(api, "execute").mockImplementation(() => {
      expect(localStorage.getItem(executionKey)).toContain("step-1");
      return new Promise((r) => {
        resolve = r;
      });
    });
    const pending = s.execute("step-1");
    await vi.advanceTimersByTimeAsync(0);
    await s.execute("step-1");
    expect(execute).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(710_000);
    expect(execute).toHaveBeenCalledTimes(1);
    expect(s.plan.value?.steps[0]?.status).toBe("READY");
    expect(s.executionPending.value).toBe(true);
    vi.mocked(api.plan).mockResolvedValue(successfulPlan());
    vi.mocked(api.workingCopy).mockResolvedValue({
      ...copy(),
      currentRevision: "b".repeat(40),
      lastVerifiedRevision: "b".repeat(40),
    });
    resolve(successfulPlan());
    await pending;
    expect(s.plan.value?.steps[0]?.status).toBe("SUCCEEDED");
    expect(s.copy.value?.lastVerifiedRevision).toBe("b".repeat(40));
    expect(localStorage.getItem(executionKey)).toBeNull();
  });
  it("断线后查询真实 READY 并保留等待标记，刷新也不重复 execute", async () => {
    const s = make();
    ready(s);
    const execute = vi
      .spyOn(api, "execute")
      .mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
    await s.execute("step-1");
    expect(s.receipt.value).not.toBeNull();
    expect(s.error.value).toContain("结果尚不确定");
    await s.execute("step-1");
    const restored = make();
    ready(restored);
    vi.spyOn(api, "connectivity").mockResolvedValue({
      status: "UP",
      service: "backend",
      timestamp: "",
    });
    await restored.restore();
    await restored.execute("step-1");
    expect(execute).toHaveBeenCalledTimes(1);
    expect(restored.receipt.value?.stepId).toBe("step-1");
  });
  it("明确后端错误且仍为干净 READY 时可手动再次操作，但不会自动重试", async () => {
    const s = make();
    ready(s);
    const execute = vi
      .spyOn(api, "execute")
      .mockRejectedValue(new ApiError(502, "EXTERNAL_CAPABILITY_UNAVAILABLE"));
    await s.execute("step-1");
    expect(execute).toHaveBeenCalledTimes(1);
    expect(s.receipt.value).toBeNull();
  });
  it("代理错误的未知正文不当作后端完成凭据", async () => {
    const s = make();
    ready(s);
    vi.spyOn(api, "execute").mockRejectedValue(new ApiError(504, ""));
    await s.execute("step-1");
    expect(s.receipt.value).not.toBeNull();
  });
  it("跨标签页锁、已存在等待标记、不可用存储均阻止发送", async () => {
    const s = make();
    ready(s);
    const execute = vi.spyOn(api, "execute");
    Object.defineProperty(navigator, "locks", {
      configurable: true,
      value: {
        request: async (
          _name: string,
          _options: unknown,
          callback: (lock: null) => Promise<void>,
        ) => callback(null),
      },
    });
    await s.execute("step-1");
    expect(execute).not.toHaveBeenCalled();
    Object.defineProperty(navigator, "locks", {
      configurable: true,
      value: {
        request: async (
          _name: string,
          _options: unknown,
          callback: (lock: object) => Promise<void>,
        ) => callback({}),
      },
    });
    localStorage.setItem(
      executionKey,
      JSON.stringify({
        planId: "other-plan",
        stepId: "other-step",
        startedAt: "2026-10-10",
      }),
    );
    await s.execute("step-1");
    expect(execute).not.toHaveBeenCalled();
    localStorage.removeItem(executionKey);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    await s.execute("step-1");
    expect(execute).not.toHaveBeenCalled();
  });
  it("GET 发现已变化 revision 或失败 Candidate，禁止发送 execute", async () => {
    const s = make();
    ready(s);
    const execute = vi.spyOn(api, "execute");
    vi.mocked(api.workingCopy).mockResolvedValue({
      ...copy(),
      currentRevision: "b".repeat(40),
    });
    await s.execute("step-1");
    expect(execute).not.toHaveBeenCalled();
    vi.mocked(api.workingCopy).mockResolvedValue(copy());
    const failed = plan();
    failed.steps[0]!.status = "VERIFICATION_FAILED";
    vi.mocked(api.plan).mockResolvedValue(failed);
    await s.execute("step-1");
    expect(execute).not.toHaveBeenCalled();
  });
});
