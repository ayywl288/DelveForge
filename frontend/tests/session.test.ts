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
    discovery.mockResolvedValueOnce({ profile: { ...profile(), revision: 3, status: "EXPLORING", constraints: ["每周三小时"] }, sufficient: true, missingAreas: [], nextQuestion: null });
    await s.turn("重复导出报表");
    expect(s.profile.value?.status).toBe("EXPLORING"); expect(s.messages.value.at(-1)?.text).toBe("每周能投入多少时间？");
    await s.turn("每周三小时");
    expect(s.profile.value?.status).toBe("EXPLORING"); expect(s.profile.value?.constraints).toEqual(["每周三小时"]);
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
      profile: { ...reviewed, status: "EXPLORING" },
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
    vi.spyOn(api, "assess").mockResolvedValue({ sufficient: true, missingAreas: [], nextQuestion: null, profileStatus: "REVIEWING" });
    await s.assess();
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
describe("M1 问题显示与连续探索", () => {
  it("评估与对话的真实问题只在聊天中插入一次，GET 不生成问题", async () => {
    const s = make(); s.profile.value = profile(); s.ids.profileId = "profile-1";
    const assessment = { sufficient: false, missingAreas: ["具体场景"], nextQuestion: "具体哪个环节最麻烦？", profileStatus: "EXPLORING" as const };
    vi.spyOn(api, "assess").mockResolvedValue(assessment);
    vi.spyOn(api, "profile").mockResolvedValue(profile());
    await s.assess(); await s.assess();
    vi.spyOn(api, "discoveryTurn").mockResolvedValue({ ...assessment, profile: profile() });
    await s.turn("集卡周期长");
    expect(s.messages.value.filter(m => m.role === "agent")).toEqual([{ role: "agent", text: assessment.nextQuestion }]);
    vi.spyOn(api, "resumeDiscovery").mockResolvedValue(profile());
    s.profile.value = { ...profile(), status: "REVIEWING" };
    vi.spyOn(api, "discoveryQuestion").mockResolvedValue({ ...assessment, revision: 1 });
    await s.resumeDiscovery();
    expect(s.messages.value.filter(m => m.role === "agent")).toHaveLength(1);
    vi.spyOn(api, "connectivity").mockResolvedValue({ service: "delveforge", status: "UP", timestamp: "2026-10-10" });
    await s.restore();
    expect(s.messages.value.filter(m => m.role === "agent")).toHaveLength(1);
  });
  it("继续探索后多轮充分仍保持探索，评估失败不伪造 Agent 问题", async () => {
    const s = make(); s.profile.value = { ...profile(), status: "REVIEWING" }; s.ids.profileId = "profile-1";
    vi.spyOn(api, "resumeDiscovery").mockResolvedValue(profile());
    vi.spyOn(api, "discoveryQuestion").mockRejectedValue(new ApiError(502, "EXTERNAL_CAPABILITY_UNAVAILABLE"));
    vi.spyOn(api, "profile").mockResolvedValue(profile());
    await s.resumeDiscovery();
    const turns = vi.spyOn(api, "discoveryTurn").mockResolvedValue({ profile: profile(), sufficient: true, missingAreas: [], nextQuestion: null });
    await s.turn("补充一"); await s.turn("补充二");
    expect(turns).toHaveBeenCalledTimes(2); expect(s.profile.value?.status).toBe("EXPLORING");
    expect(s.messages.value.filter(m => m.role === "agent")).toHaveLength(0);
    vi.spyOn(api, "assess").mockRejectedValue(new ApiError(502, "EXTERNAL_CAPABILITY_UNAVAILABLE"));
    await s.assess(); expect(s.sufficiency.value).toBeNull(); expect(s.error.value).toBeTruthy();
    expect(s.messages.value.filter(m => m.role === "agent")).toHaveLength(0);
    turns.mockRejectedValue(new ApiError(502, "EXTERNAL_CAPABILITY_UNAVAILABLE"));
    vi.spyOn(api, "profile").mockResolvedValue({ ...profile(), status: "CONFIRMED" });
    await s.turn("这一轮未收到成功响应");
    expect(turns).toHaveBeenCalledTimes(3);
    expect(s.profile.value?.status).toBe("CONFIRMED");
    expect(s.messages.value.at(-1)?.failed).toBe(true);
    expect(s.messages.value.filter(m => m.role === "agent")).toHaveLength(0);
  });
});
describe("持续引导与只读下一问", () => {
  it("充分前后都有实际问题，继续探索复用当前问题；三轮没有第三次独立追问", async () => {
    const s = make(); s.profile.value = profile(); s.ids.profileId = "profile-1";
    const turn = vi.spyOn(api, "discoveryTurn");
    const questions = ["每周能投入多少时间？", "首先想展示哪项能力？", "首个使用场景是什么？"];
    questions.forEach((nextQuestion, index) => turn.mockResolvedValueOnce({
      profile: { ...profile(), revision: index + 2 }, sufficient: index > 0,
      missingAreas: index === 0 ? ["投入时间"] : [], nextQuestion,
    }));
    const ask = vi.spyOn(api, "discoveryQuestion");
    await s.turn("想做Java作品");
    await s.turn("每周五小时");
    await s.continueExploring();
    expect(ask).not.toHaveBeenCalled();
    await s.turn("想展示持久化和测试");
    expect(s.messages.value.filter(m => m.role === "agent").map(m => m.text)).toEqual(questions);
    expect(turn).toHaveBeenCalledTimes(3);
    expect(turn.mock.calls[2]?.[2]).toEqual(questions.slice(0, 2));
    expect(s.profile.value?.status).toBe("EXPLORING");
  });
  it("没有问题时按需调用一次，不伪造用户消息，不并发或自动重试", async () => {
    const s = make(); s.profile.value = profile(); s.ids.profileId = "profile-1";
    let resolve!: (value: { revision: number; sufficient: boolean; missingAreas: string[]; nextQuestion: string }) => void;
    const ask = vi.spyOn(api, "discoveryQuestion").mockImplementation(() => new Promise(r => { resolve = r; }));
    const first = s.continueExploring();
    await s.continueExploring();
    expect(ask).toHaveBeenCalledTimes(1);
    resolve({ revision: 1, sufficient: true, missingAreas: [], nextQuestion: "你希望谁先使用这个项目？" });
    await first;
    expect(ask).toHaveBeenCalledWith("profile-1", 1, []);
    expect(s.messages.value).toEqual([{ role: "agent", text: "你希望谁先使用这个项目？" }]);
    await s.continueExploring();
    expect(ask).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem(sessionKey) ?? "").not.toContain("这个项目");
  });
  it("确认不会提问；用户主动 reopen 才切换状态并调用一次只读提问", async () => {
    const s = make(); s.profile.value = { ...profile(), status: "REVIEWING" }; s.ids.profileId = "profile-1";
    vi.spyOn(api, "confirmProfile").mockResolvedValue({ ...profile(), status: "CONFIRMED" });
    const ask = vi.spyOn(api, "discoveryQuestion").mockResolvedValue({ revision: 1, sufficient: true, missingAreas: [], nextQuestion: "你想继续深入哪个使用场景？" });
    const turn = vi.spyOn(api, "discoveryTurn");
    await s.confirmProfile(1);
    await s.turn("静默修改已确认画像"); await s.continueExploring();
    expect(ask).not.toHaveBeenCalled(); expect(turn).not.toHaveBeenCalled();
    const resume = vi.spyOn(api, "resumeDiscovery").mockResolvedValue(profile());
    await s.resumeDiscovery();
    expect(resume).toHaveBeenCalledWith("profile-1", true); expect(ask).toHaveBeenCalledTimes(1);
    expect(s.profile.value?.revision).toBe(1); expect(s.messages.value.at(-1)?.role).toBe("agent");
  });
  it("返回探索后提问断线保留实际探索状态，不造问题、不重复切换或调用", async () => {
    const s = make(); s.profile.value = { ...profile(), status: "REVIEWING" }; s.ids.profileId = "profile-1";
    const resume = vi.spyOn(api, "resumeDiscovery").mockResolvedValue(profile());
    const ask = vi.spyOn(api, "discoveryQuestion").mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
    vi.spyOn(api, "profile").mockResolvedValue(profile());
    await s.resumeDiscovery();
    expect(s.profile.value?.status).toBe("EXPLORING"); expect(s.messages.value).toEqual([]);
    expect(s.sufficiency.value).toBeNull(); expect(s.error.value).toContain("结果尚不确定");
    expect(resume).toHaveBeenCalledTimes(1); expect(ask).toHaveBeenCalledTimes(1);
  });
  it("问题绑定旧版本时丢弃，GET 和刷新只恢复画像，不自动调用 Provider", async () => {
    const s = make(); s.profile.value = profile(); s.ids.profileId = "profile-1";
    const ask = vi.spyOn(api, "discoveryQuestion").mockResolvedValue({ revision: 0, sufficient: true, missingAreas: [], nextQuestion: "过期问题" });
    await s.continueExploring(); expect(s.messages.value).toEqual([]);
    vi.spyOn(api, "connectivity").mockResolvedValue({ service: "backend", status: "UP", timestamp: "" });
    vi.spyOn(api, "profile").mockResolvedValue({ ...profile(), revision: 2, status: "REVIEWING" });
    await s.restore();
    expect(s.sufficiency.value).toBeNull(); expect(ask).toHaveBeenCalledTimes(1);
    expect(s.messages.value).toEqual([]);
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
