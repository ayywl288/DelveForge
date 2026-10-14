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
  it("刷新只读取本版服务端充分性并显示非阻塞提示，不重开弹窗或生成问题", async () => {
    const s = make(); s.ids.profileId = "profile-1";
    vi.spyOn(api, "connectivity").mockResolvedValue({ service: "delveforge", status: "UP", timestamp: "now" });
    vi.spyOn(api, "profile").mockResolvedValue(profile());
    vi.spyOn(api, "reviewReadiness").mockResolvedValue({ profileId: "profile-1", revision: 1, sufficient: true });
    const ask = vi.spyOn(api, "discoveryQuestion");
    await s.restore();
    expect(s.sufficiency.value?.sufficient).toBe(true);
    expect(s.reviewChoicePending.value).toBe(false); expect(s.messages.value).toEqual([]);
    expect(ask).not.toHaveBeenCalled();
    vi.spyOn(api, "reviewReadiness").mockResolvedValue({ profileId: "profile-1", revision: 0, sufficient: true });
    s.sufficiency.value = null; await s.restore(); expect(s.sufficiency.value).toBeNull();
  });
  it("恢复尚无充分记录的画像，不抑制后续首次真实充分弹窗", async () => {
    const s = make(); s.ids.profileId = "profile-1";
    vi.spyOn(api, "connectivity").mockResolvedValue({ service: "delveforge", status: "UP", timestamp: "now" });
    vi.spyOn(api, "profile").mockResolvedValue(profile());
    vi.spyOn(api, "reviewReadiness").mockResolvedValue({ profileId: "profile-1", revision: 1, sufficient: null });
    await s.restore();
    vi.spyOn(api, "discoveryTurn").mockResolvedValue({ profile: profile(), sufficient: true,
      missingAreas: [], nextQuestion: "先展示什么能力？" });
    await s.turn("先展示事务能力"); expect(s.reviewChoicePending.value).toBe(true);
  });
  it("评估与对话的真实问题只在聊天中插入一次，GET 不生成问题", async () => {
    const s = make(); s.profile.value = profile(); s.ids.profileId = "profile-1";
    const assessment = { sufficient: false, missingAreas: ["具体场景"], nextQuestion: "具体哪个环节最麻烦？", profileStatus: "EXPLORING" as const };
    vi.spyOn(api, "assess").mockResolvedValue(assessment);
    vi.spyOn(api, "profile").mockResolvedValue(profile());
    await s.assess(); await s.assess();
    expect(s.messages.value).toHaveLength(0);
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
  it.each(["FAILED", "RECOVERY_REQUIRED"] as const)("Rollback 独立授权；旧 FAIL 不清标记，匹配 %s 结果才结束等待", async status => {
    const s = make(); ready(s);
    const failed = successfulPlan(); failed.status = "ACTIVE";
    failed.steps[0]!.status = "VERIFICATION_FAILED";
    failed.steps[0]!.verificationResult!.passed = false;
    failed.steps[0]!.verificationResult!.failures = ["failure"];
    vi.mocked(api.plan).mockResolvedValue(failed);
    vi.mocked(api.workingCopy).mockResolvedValue({ ...copy(), status: "EVOLVING", currentRevision: "b".repeat(40) });
    let resolve!: (result: ReturnType<typeof plan>) => void;
    let authorization = "";
    const rollback = vi.spyOn(api, "rollback").mockImplementation((_plan, _step, candidate, baseline, id) => {
      expect(candidate).toBe("b".repeat(40)); expect(baseline).toBe("a".repeat(40)); authorization = id;
      return new Promise(r => { resolve = r; });
    });
    const repair = vi.spyOn(api, "repair"); const execute = vi.spyOn(api, "execute");
    const pending = s.rollback("step-1", "b".repeat(40), "a".repeat(40));
    for (let index = 0; index < 20; index++) await Promise.resolve();
    expect(rollback).toHaveBeenCalledOnce();
    await s.refreshPlan();
    expect(s.receipt.value?.operation).toBe("ROLLBACK");
    expect(s.receipt.value?.baselineRevision).toBe("a".repeat(40));
    await s.rollback("step-1", "b".repeat(40), "a".repeat(40));
    expect(rollback).toHaveBeenCalledOnce();
    const completed = structuredClone(failed);
    const step = completed.steps[0]!; step.status = status;
    step.rollbackAuthorization = { id: authorization, candidateRevision: "b".repeat(40),
      baselineRevision: "a".repeat(40), authorizedAt: "2026-10-13T10:00:00Z" };
    step.rollbackResult = { authorizationId: authorization, baselineRevision: "a".repeat(40),
      observedRevision: status === "FAILED" ? "a".repeat(40) : null, restored: status === "FAILED",
      diagnostic: "test fact", completedAt: "2026-10-13T10:01:00Z" };
    vi.mocked(api.plan).mockResolvedValue(completed);
    resolve(completed); await pending;
    expect(s.receipt.value).toBeNull(); expect(repair).not.toHaveBeenCalled(); expect(execute).not.toHaveBeenCalled();
    expect(s.plan.value?.steps[0]?.verificationResult?.failures).toEqual(["failure"]);
  });

  it("Rollback 断线与重启读到 ROLLING_BACK 时仅查询，不改判或重放", async () => {
    const s = make(); ready(s);
    const failed = successfulPlan(); failed.status = "ACTIVE"; failed.steps[0]!.status = "VERIFICATION_FAILED";
    failed.steps[0]!.verificationResult!.passed = false;
    vi.mocked(api.plan).mockResolvedValue(failed);
    vi.mocked(api.workingCopy).mockResolvedValue({ ...copy(), status: "EVOLVING", currentRevision: "b".repeat(40) });
    const rollback = vi.spyOn(api, "rollback").mockImplementation(async (_plan, _step, candidate, baseline, id) => {
      const rolling = structuredClone(failed); rolling.steps[0]!.status = "ROLLING_BACK";
      rolling.steps[0]!.rollbackAuthorization = { id, candidateRevision: candidate, baselineRevision: baseline, authorizedAt: "2026-10-13T10:00:00Z" };
      vi.mocked(api.plan).mockResolvedValue(rolling);
      throw new ApiError(0, "REQUEST_DISCONNECTED");
    });
    await s.rollback("step-1", "b".repeat(40), "a".repeat(40));
    const restored = make(); restored.ids.planId = "plan-1";
    vi.spyOn(api, "connectivity").mockResolvedValue({ status: "UP", service: "backend", timestamp: "" });
    await restored.restore();
    await restored.rollback("step-1", "b".repeat(40), "a".repeat(40));
    expect(rollback).toHaveBeenCalledOnce();
    expect(restored.plan.value?.steps[0]?.status).toBe("ROLLING_BACK");
    expect(restored.plan.value?.steps[0]?.rollbackResult).toBeUndefined();
    expect(restored.receipt.value?.operation).toBe("ROLLBACK");
  });

  it("Repair 的旧 FAIL 查询不清除新授权标记；匹配授权的新结果才结束等待", async () => {
    const s = make(); ready(s);
    const failed = successfulPlan();
    failed.status = "ACTIVE";
    failed.steps[0]!.status = "VERIFICATION_FAILED";
    failed.steps[0]!.verificationResult!.passed = false;
    failed.steps[0]!.verificationResult!.failures = ["Old failure"];
    failed.steps[0]!.repairAuthorizations = [];
    const candidate = { ...copy(), status: "EVOLVING" as const, currentRevision: "b".repeat(40) };
    vi.mocked(api.plan).mockResolvedValue(failed);
    vi.mocked(api.workingCopy).mockResolvedValue(candidate);
    let resolve!: (value: ReturnType<typeof plan>) => void;
    let authorization = "";
    const repair = vi.spyOn(api, "repair").mockImplementation((_plan, _step, revision, id) => {
      expect(revision).toBe("b".repeat(40)); authorization = id;
      return new Promise(r => { resolve = r; });
    });
    const execute = vi.spyOn(api, "execute");
    const pending = s.repair("step-1", "b".repeat(40));
    for (let index = 0; index < 20; index++) await Promise.resolve();
    expect(repair).toHaveBeenCalledOnce();
    await s.refreshPlan();
    expect(s.receipt.value?.authorizationId).toBe(authorization);
    await s.repair("step-1", "b".repeat(40));
    expect(repair).toHaveBeenCalledOnce();
    const repaired = successfulPlan();
    repaired.status = "ACTIVE";
    repaired.steps[0]!.executionResult!.candidateRevision = "c".repeat(40);
    repaired.steps[0]!.verificationResult!.candidateRevision = "c".repeat(40);
    repaired.steps[0]!.repairAuthorizations = [{ id: authorization, candidateRevision: "b".repeat(40),
      authorizedAt: new Date().toISOString(), previousExecution: failed.steps[0]!.executionResult!,
      previousVerification: failed.steps[0]!.verificationResult! }];
    vi.mocked(api.plan).mockResolvedValue(repaired);
    vi.mocked(api.workingCopy).mockResolvedValue({ ...candidate, currentRevision: "c".repeat(40), lastVerifiedRevision: "c".repeat(40) });
    resolve(repaired); await pending;
    expect(s.receipt.value).toBeNull();
    expect(s.plan.value?.steps[0]?.repairAuthorizations?.[0]?.id).toBe(authorization);
    expect(execute).not.toHaveBeenCalled();
  });

  it("Repair 断线后刷新只 GET，EXECUTING 与授权历史不会触发 Provider 重放", async () => {
    const s = make(); ready(s);
    const failed = successfulPlan(); failed.steps[0]!.status = "VERIFICATION_FAILED";
    failed.status = "ACTIVE";
    failed.steps[0]!.verificationResult!.passed = false;
    vi.mocked(api.plan).mockResolvedValue(failed);
    vi.mocked(api.workingCopy).mockResolvedValue({ ...copy(), status: "EVOLVING", currentRevision: "b".repeat(40) });
    const repair = vi.spyOn(api, "repair").mockImplementation(async (_plan, _step, candidate, id) => {
      const executing = { ...failed, steps: [{ ...failed.steps[0]!, status: "EXECUTING" as const,
        executionResult: null, verificationResult: null, repairAuthorizations: [{ id, candidateRevision: candidate,
          authorizedAt: new Date().toISOString(), previousExecution: failed.steps[0]!.executionResult!,
          previousVerification: failed.steps[0]!.verificationResult! }] }] };
      vi.mocked(api.plan).mockResolvedValue(executing);
      throw new ApiError(0, "CONNECTION_LOST");
    });
    await s.repair("step-1", "b".repeat(40));
    expect(s.receipt.value?.operation).toBe("REPAIR");
    const restored = make(); restored.ids.planId = "plan-1";
    vi.spyOn(api, "connectivity").mockResolvedValue({ status: "UP", service: "backend", timestamp: "" });
    await restored.restore();
    await restored.repair("step-1", "b".repeat(40));
    expect(repair).toHaveBeenCalledOnce();
    expect(restored.plan.value?.steps[0]?.status).toBe("EXECUTING");
    expect(restored.receipt.value).not.toBeNull();
  });

  it("execute 返回明确 FAIL 后保留结果与 Candidate，清除等待标记且禁止重放", async () => {
    const s = make();
    ready(s);
    const failed = successfulPlan();
    failed.steps[0]!.status = "VERIFICATION_FAILED";
    failed.steps[0]!.verificationResult!.passed = false;
    failed.steps[0]!.verificationResult!.failures = ["Check failed"];
    failed.steps[0]!.verificationResult!.checks[0]!.passed = false;
    failed.steps[0]!.verificationResult!.checks[0]!.outcome = "FAILED";
    const candidate = { ...copy(), status: "EVOLVING" as const, currentRevision: "b".repeat(40) };
    const execute = vi.spyOn(api, "execute").mockImplementation(async () => {
      vi.mocked(api.plan).mockResolvedValue(failed);
      vi.mocked(api.workingCopy).mockResolvedValue(candidate);
      return failed;
    });
    await s.execute("step-1");
    expect(s.plan.value?.steps[0]?.status).toBe("VERIFICATION_FAILED");
    expect(s.plan.value?.steps[0]?.verificationResult?.passed).toBe(false);
    expect(s.copy.value?.currentRevision).toBe("b".repeat(40));
    expect(s.copy.value?.lastVerifiedRevision).toBe("a".repeat(40));
    expect(s.receipt.value).toBeNull();
    expect(localStorage.getItem(executionKey)).toBeNull();
    await s.execute("step-1");
    expect(execute).toHaveBeenCalledOnce();
  });

  it("702 秒仍等待且只发送一次 POST，轮询 READY 不制造 PREPARING", async () => {
    vi.useFakeTimers();
    const s = make();
    ready(s);
    s.observeExecution(true);
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
  it.each([
    [422, "EXECUTION_CRITERIA_UNSUPPORTED", "无法自动验证"],
    [502, "EXECUTION_AI_FAILED", "Provider 响应处理失败"],
    [422, "CODING_PROPOSAL_INVALID", "未通过解析或契约校验"],
  ])("执行分类 %s/%s 仍须回读 READY，显示具体原因且不自动重试", async (status, code, text) => {
    const s = make();
    ready(s);
    const execute = vi.spyOn(api, "execute").mockRejectedValue(new ApiError(Number(status), String(code)));
    await s.execute("step-1");
    expect(execute).toHaveBeenCalledOnce();
    expect(api.plan).toHaveBeenCalledTimes(2);
    expect(api.workingCopy).toHaveBeenCalledTimes(2);
    expect(s.receipt.value).toBeNull();
    expect(s.error.value).toContain(String(text));
    expect(s.error.value).not.toContain("重试即可");
  });
  it.each(["EXECUTION_CRITERIA_UNSUPPORTED", "EXECUTION_AI_FAILED", "CODING_PROPOSAL_INVALID"])(
    "%s 在拒绝后的状态查询失败时仍保留等待标记", async (code) => {
      const s = make();
      ready(s);
      const execute = vi.spyOn(api, "execute").mockImplementation(async () => {
        vi.mocked(api.plan).mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
        throw new ApiError(code === "EXECUTION_AI_FAILED" ? 502 : 422, code);
      });
      await s.execute("step-1");
      await s.execute("step-1");
      expect(execute).toHaveBeenCalledOnce();
      expect(s.receipt.value).not.toBeNull();
    },
  );
  it.each(["EXECUTION_CRITERIA_UNSUPPORTED", "EXECUTION_AI_FAILED", "CODING_PROPOSAL_INVALID"])(
    "%s 在查询发现 Candidate 时仍保留等待标记", async (code) => {
      const s = make();
      ready(s);
      const execute = vi.spyOn(api, "execute").mockImplementation(async () => {
        vi.mocked(api.workingCopy).mockResolvedValue({ ...copy(), currentRevision: "b".repeat(40) });
        throw new ApiError(code === "EXECUTION_AI_FAILED" ? 502 : 422, code);
      });
      await s.execute("step-1");
      await s.execute("step-1");
      expect(execute).toHaveBeenCalledOnce();
      expect(s.receipt.value).not.toBeNull();
    },
  );
  it.each(["REPAIR", "ROLLBACK"] as const)("%s 明确资产拒绝且 GET 确认授权未保存时允许重新显式授权", async (operation) => {
    for (const code of ["ASSET_TYPE_UNSUPPORTED", "ASSET_READ_PERMISSION_REQUIRED", "ASSET_USAGE_AUTHORIZATION_REQUIRED",
      "ASSET_LICENSE_INFORMATION_REQUIRED", "ASSET_AUTHORIZATION_CONFLICT"]) {
      const s = make(); ready(s);
      const failed = successfulPlan(); failed.status = "ACTIVE";
      failed.steps[0]!.status = "VERIFICATION_FAILED";
      failed.steps[0]!.verificationResult!.passed = false;
      failed.steps[0]!.repairAuthorizations = [];
      vi.mocked(api.plan).mockResolvedValue(failed);
      vi.mocked(api.workingCopy).mockResolvedValue({ ...copy(), status: "EVOLVING", currentRevision: "b".repeat(40) });
      const request = operation === "REPAIR" ? vi.spyOn(api, "repair") : vi.spyOn(api, "rollback");
      request.mockRejectedValue(new ApiError(409, code));
      if (operation === "REPAIR") await s.repair("step-1", "b".repeat(40));
      else await s.rollback("step-1", "b".repeat(40), "a".repeat(40));
      expect(request).toHaveBeenCalledOnce();
      expect(s.receipt.value).toBeNull();
      expect(localStorage.getItem(executionKey)).toBeNull();
      expect(api.plan).toHaveBeenCalledTimes(2);
      if (operation === "REPAIR") await s.repair("step-1", "b".repeat(40));
      else await s.rollback("step-1", "b".repeat(40), "a".repeat(40));
      expect(request).toHaveBeenCalledTimes(2);
      const first = request.mock.calls[0]!;
      const second = request.mock.calls[1]!;
      expect(first[first.length - 1]).not.toBe(second[second.length - 1]);
      s.dispose(); vi.restoreAllMocks();
    }
  });

  it.each(["REPAIR", "ROLLBACK"] as const)("%s 拒绝后的 GET 不充分或状态未决仍阻止重发", async (operation) => {
    for (const fault of ["get-failed", "copy-drift", "result-drift", "baseline-drift", "grant-saved", "in-flight", "network"]) {
      const s = make(); ready(s);
      const failed = successfulPlan(); failed.status = "ACTIVE";
      const step = failed.steps[0]!; step.status = "VERIFICATION_FAILED";
      step.verificationResult!.passed = false; step.repairAuthorizations = [];
      vi.mocked(api.plan).mockResolvedValue(failed);
      vi.mocked(api.workingCopy).mockResolvedValue({ ...copy(), status: "EVOLVING", currentRevision: "b".repeat(40) });
      const deny = async (...args: string[]) => {
        const id = args[args.length - 1]!;
        if (fault === "get-failed") vi.mocked(api.plan).mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
        if (fault === "copy-drift") vi.mocked(api.workingCopy).mockResolvedValue({ ...copy(), status: "EVOLVING", currentRevision: "c".repeat(40) });
        if (fault === "result-drift") step.executionResult!.candidateRevision = "c".repeat(40);
        if (fault === "baseline-drift") step.baselineRevision = "c".repeat(40);
        if (fault === "grant-saved") {
          step.status = operation === "ROLLBACK" ? "ROLLING_BACK" : "EXECUTING";
          if (operation === "ROLLBACK") step.rollbackAuthorization = { id, candidateRevision: "b".repeat(40),
            baselineRevision: "a".repeat(40), authorizedAt: new Date().toISOString() };
          else step.repairAuthorizations = [{ id, candidateRevision: "b".repeat(40), authorizedAt: new Date().toISOString(),
            previousExecution: step.executionResult!, previousVerification: step.verificationResult! }];
        }
        if (fault === "in-flight") step.status = operation === "ROLLBACK" ? "ROLLING_BACK" : "EXECUTING";
        throw new ApiError(fault === "network" ? 0 : 409, fault === "network" ? "CONNECTION_LOST" : "ASSET_READ_PERMISSION_REQUIRED");
      };
      const request = operation === "REPAIR" ? vi.spyOn(api, "repair").mockImplementation(deny)
        : vi.spyOn(api, "rollback").mockImplementation(deny);
      if (operation === "REPAIR") await s.repair("step-1", "b".repeat(40));
      else await s.rollback("step-1", "b".repeat(40), "a".repeat(40));
      expect(s.receipt.value, fault).not.toBeNull();
      if (operation === "REPAIR") await s.repair("step-1", "b".repeat(40));
      else await s.rollback("step-1", "b".repeat(40), "a".repeat(40));
      expect(request, fault).toHaveBeenCalledOnce();
      s.dispose(); vi.restoreAllMocks(); localStorage.removeItem(executionKey);
    }
  });

  it("具体授权拒绝沿用原有状态核对，不自动重试 execute", async () => {
    const s = make(); ready(s);
    const execute = vi.spyOn(api, "execute").mockRejectedValue(new ApiError(409, "ASSET_USAGE_AUTHORIZATION_REQUIRED"));
    await s.execute("step-1");
    expect(execute).toHaveBeenCalledOnce();
    expect(s.receipt.value).toBeNull();
    expect(s.error.value).toContain("使用授权");
  });
  it("即使错误已知，查询出现 Candidate 时仍保留等待标记", async () => {
    const s = make(); ready(s);
    vi.spyOn(api, "execute").mockImplementation(async () => {
      vi.mocked(api.workingCopy).mockResolvedValue({ ...copy(), currentRevision: "b".repeat(40) });
      throw new ApiError(409, "ASSET_AUTHORIZATION_CONFLICT");
    });
    await s.execute("step-1");
    expect(s.receipt.value).not.toBeNull();
    expect(s.copy.value?.lastVerifiedRevision).toBe("a".repeat(40));
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
