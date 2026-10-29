import { createApp, nextTick, reactive } from "vue";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../src/api/client";
import type { ConversationHistory, DiscoveryOperation } from "../src/api/types";
import { createSession, sessionKey } from "../src/state/session";
import DiscoveryView from "../src/views/DiscoveryView.vue";
import { profile } from "./fixtures";

const cleanup: (() => void)[] = [];
const history = (): ConversationHistory => ({ messages: [], before: null, pendingRequestId: null, unansweredQuestion: null });
const question = "你最希望先改善阅读的哪个环节？";
const recorded = (): ConversationHistory => ({ ...history(), unansweredQuestion: question, messages: [
  { sequence: 1, requestId: "recorded", role: "user", text: "想整理自己的阅读笔记", question: null,
    questionState: null, createdAt: "2026-10-29T00:00:00Z", projectId: null },
  { sequence: 2, requestId: "recorded", role: "agent", text: "可以从日常场景开始。", question,
    questionState: "ASKED", createdAt: "2026-10-29T00:00:01Z", projectId: null },
] });
function make() {
  const s = createSession(); s.profile.value = profile(); s.ids.profileId = "profile-1";
  cleanup.push(() => s.dispose());
  return s;
}
async function settle() { for (let i = 0; i < 50; i++) { await Promise.resolve(); await nextTick(); } }
beforeEach(() => {
  vi.spyOn(api, "profile").mockResolvedValue(profile());
  vi.spyOn(api, "connectivity").mockResolvedValue({ service: "delveforge", status: "UP", timestamp: "now" });
  vi.spyOn(api, "reviewReadiness").mockResolvedValue({ profileId: "profile-1", revision: 1, sufficient: true });
  vi.spyOn(api, "conversation").mockResolvedValue(history());
});
afterEach(() => cleanup.splice(0).forEach(dispose => dispose()));

describe("服务端对话恢复与请求结果核对", () => {
  it("刷新恢复原始顺序、时间和未回答问题，不调用任何 AI 入口", async () => {
    vi.mocked(api.conversation).mockResolvedValue(recorded());
    const ask = vi.spyOn(api, "discoveryQuestion");
    const turn = vi.spyOn(api, "discoveryTurn");
    const s = make(); await s.restore();
    expect(s.messages.value.map(message => message.role)).toEqual(["user", "agent"]);
    expect(s.messages.value[1]?.createdAt).toBe("2026-10-29T00:00:01Z");
    expect(s.nextQuestion.value).toBe(question);
    expect(s.messages.value[1]?.text).toBe("可以从日常场景开始。\n\n" + question);
    await s.continueExploring();
    expect(ask).not.toHaveBeenCalled(); expect(turn).not.toHaveBeenCalled();
    const restored = make(); await restored.restore();
    expect(restored.messages.value).toEqual(s.messages.value);
    expect((localStorage.getItem(sessionKey) ?? "")).not.toContain("阅读笔记");
  });

  it("手动纠正内容会清除旧充分性，但保留尚未回答的历史问题", async () => {
    vi.mocked(api.conversation).mockResolvedValue(recorded());
    const patch = vi.spyOn(api, "patchProfile").mockResolvedValue({ ...profile(), revision: 2, interests: ["阅读"] });
    const ask = vi.spyOn(api, "discoveryQuestion");
    const s = make(); await s.restore(); await s.patch({ interests: ["阅读"] });
    expect(patch).toHaveBeenCalledWith("profile-1", { interests: ["阅读"] }, 1);
    expect(s.sufficiency.value).toBeNull(); expect(s.nextQuestion.value).toBe(question);
    await s.continueExploring(); expect(ask).not.toHaveBeenCalled();
  });

  it("问题专用入口也带持久请求身份，成功后可从历史恢复", async () => {
    const ask = vi.spyOn(api, "discoveryQuestion").mockResolvedValue({ revision: 1, sufficient: true,
      missingAreas: [], assistantReply: "可以从日常场景开始。", nextQuestion: question });
    const s = make(); await s.continueExploring();
    expect(ask).toHaveBeenCalledWith("profile-1", 1, [], [], expect.objectContaining({ requestId: expect.any(String), revision: 1 }));
    expect(s.nextQuestion.value).toBe(question);
    vi.mocked(api.conversation).mockResolvedValue({ ...recorded(), messages: [recorded().messages[1]!] });
    await s.loadConversation();
    expect(s.messages.value).toHaveLength(1); expect(s.nextQuestion.value).toBe(question);
    expect(ask).toHaveBeenCalledOnce();
  });

  it("响应丢失后按同一请求编号恢复已提交结果，不重复发送", async () => {
    let requestId = "";
    const turn = vi.spyOn(api, "discoveryTurn").mockImplementation(async (_id, _text, _questions, _context, identity) => {
      requestId = identity!.requestId;
      throw new ApiError(0, "CONNECTION_LOST");
    });
    vi.spyOn(api, "discoveryRequest").mockImplementation(async (_id, id): Promise<DiscoveryOperation> => ({
      requestId: id, state: "COMPLETED", revision: 2,
      result: { sufficient: true, missingAreas: [], nextQuestion: question },
    }));
    vi.mocked(api.profile).mockResolvedValue({ ...profile(), revision: 2 });
    vi.mocked(api.conversation).mockImplementation(async () => ({ ...recorded(), messages: recorded().messages.map(message => ({ ...message, requestId })) }));
    const s = make(); await s.turn("想整理自己的阅读笔记");
    expect(s.messages.value).toHaveLength(2);
    expect(s.messages.value[0]?.state).toBe("accepted");
    expect(s.profile.value?.revision).toBe(2);
    expect(s.discoveryUncertain.value).toBe(false);
    expect(s.error.value).toBe("");
    expect(turn).toHaveBeenCalledOnce();
    expect(api.discoveryRequest).toHaveBeenCalledWith("profile-1", requestId);
    expect(localStorage.getItem("delveforge.discovery.pending.v1")).toBe("{}");
    expect((localStorage.getItem(sessionKey) ?? "")).not.toContain("阅读笔记");
  });

  it("刷新仍未完成的请求只查询状态，不依据画像变化推定成功或重发", async () => {
    localStorage.setItem("delveforge.discovery.pending.v1", JSON.stringify({ "profile-1": "pending-id" }));
    vi.spyOn(api, "discoveryRequest").mockResolvedValue({ requestId: "pending-id", state: "PENDING", revision: null, result: null });
    const turn = vi.spyOn(api, "discoveryTurn");
    const s = make(); await s.restore();
    expect(s.discoveryUncertain.value).toBe(true);
    await s.turn("另一条输入"); await s.checkDiscoveryStatus();
    expect(turn).not.toHaveBeenCalled();
    expect(s.messages.value).toEqual([]);
    expect(s.notice.value).toContain("仍在处理");
  });

  it.each(["NOT_FOUND", "PENDING"] as const)("%s 不自动解除未决；显式放弃先获得服务端拒绝迟到结果的记录", async state => {
    localStorage.setItem("delveforge.discovery.pending.v1", JSON.stringify({ "profile-1": "pending-id" }));
    vi.spyOn(api, "discoveryRequest").mockResolvedValue({ requestId: "pending-id", state, revision: null, result: null });
    const cancel = vi.spyOn(api, "cancelDiscoveryRequest").mockResolvedValue({ requestId: "pending-id", state: "CANCELLED", revision: null, result: null });
    const s = make(); await s.checkDiscoveryStatus();
    expect(s.discoveryUncertain.value).toBe(true);
    await s.abandonDiscovery();
    expect(cancel).toHaveBeenCalledExactlyOnceWith("profile-1", "pending-id");
    expect(s.discoveryUncertain.value).toBe(false);
    expect(s.notice.value).toContain("迟到结果不会写入");
  });

  it("不可读的请求状态保留请求身份，不将未知响应当作失败解除", async () => {
    localStorage.setItem("delveforge.discovery.pending.v1", JSON.stringify({ "profile-1": "pending-id" }));
    vi.spyOn(api, "discoveryRequest").mockResolvedValue({ requestId: "wrong-id", state: "FAILED", revision: null, result: null });
    const s = make(); await s.checkDiscoveryStatus();
    expect(s.discoveryUncertain.value).toBe(true);
    expect(s.error.value).not.toBe("");
  });

  it("切换画像时丢弃迟到历史，不把上一项目的消息插到当前项目", async () => {
    let resolve!: (value: ConversationHistory) => void;
    vi.mocked(api.conversation).mockReturnValueOnce(new Promise(value => { resolve = value; })).mockResolvedValue(history());
    const s = make(); const pending = s.loadConversation();
    s.profile.value = { ...profile(), id: "profile-2" }; s.ids.profileId = "profile-2";
    await s.loadConversation(); resolve(recorded()); await pending;
    expect(s.profile.value.id).toBe("profile-2"); expect(s.messages.value).toEqual([]);
    expect(s.nextQuestion.value).toBeNull(); expect(s.conversationLoading.value).toBe(false);
  });

  it("旧画像没有聊天记录时不从 Evidence 伪造消息", async () => {
    const s = make(); s.profile.value!.evidence = [{ sourceType: "USER_INPUT", sourceRef: "旧的原始输入", claim: "旧事实", confidence: null, confirmed: false }];
    await s.loadConversation();
    expect(s.messages.value).toEqual([]); expect(s.nextQuestion.value).toBeNull();
  });

  it("恢复期间完成的请求同步读取其画像版本，不把新消息配到旧画像", async () => {
    localStorage.setItem("delveforge.discovery.pending.v1", JSON.stringify({ "profile-1": "recorded" }));
    vi.mocked(api.profile).mockResolvedValueOnce(profile()).mockResolvedValue({ ...profile(), revision: 2 });
    vi.mocked(api.conversation).mockResolvedValue(recorded());
    vi.spyOn(api, "discoveryRequest").mockResolvedValue({ requestId: "recorded", state: "COMPLETED", revision: 2,
      result: { sufficient: true, missingAreas: [], nextQuestion: question } });
    const s = make(); await s.restore();
    expect(s.profile.value?.revision).toBe(2);
    expect(s.messages.value).toHaveLength(2);
    expect(s.sufficiency.value?.sufficient).toBe(true);
    expect(s.discoveryUncertain.value).toBe(false);
  });

  it("已完成请求的历史查询迟到时不将旧画像结论写进新项目", async () => {
    vi.spyOn(api, "discoveryTurn").mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
    vi.spyOn(api, "discoveryRequest").mockImplementation(async (_profile, requestId) => ({
      requestId, state: "COMPLETED", revision: 2,
      result: { sufficient: true, missingAreas: [], nextQuestion: question },
    }));
    vi.mocked(api.profile).mockResolvedValue({ ...profile(), revision: 2 });
    let finish!: (value: ConversationHistory) => void;
    vi.mocked(api.conversation).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
    const s = make(); const pending = s.turn("旧项目输入"); await settle();
    expect(api.conversation).toHaveBeenCalledOnce();
    s.profile.value = { ...profile(), id: "other-profile" }; s.sufficiency.value = null;
    s.messages.value = []; s.notice.value = "新项目";
    finish(recorded()); await pending;
    expect(s.profile.value.id).toBe("other-profile");
    expect(s.sufficiency.value).toBeNull(); expect(s.messages.value).toEqual([]);
    expect(s.notice.value).toBe("新项目");
  });

  it("取消接口未确认终态时仍阻止重新发送", async () => {
    localStorage.setItem("delveforge.discovery.pending.v1", JSON.stringify({ "profile-1": "pending-id" }));
    vi.spyOn(api, "cancelDiscoveryRequest").mockResolvedValue({ requestId: "pending-id", state: "PENDING", revision: null, result: null });
    const s = make(); await s.abandonDiscovery();
    expect(s.discoveryUncertain.value).toBe(true); expect(s.error.value).toBeTruthy();
  });

  it("审阅为单一宽版六卡片，原始描述去重，原始 Evidence 数据不变", async () => {
    const plain = make(); const s = reactive(plain);
    s.profile = { ...profile(), status: "REVIEWING", revision: 4, evidence: [
      { sourceType: "USER_INPUT", sourceRef: "相同原始描述", claim: "判断一", confidence: null, confirmed: false },
      { sourceType: "USER_INPUT", sourceRef: "相同原始描述", claim: "判断二", confidence: 0.8, confirmed: true },
    ] };
    const original = JSON.stringify(s.profile.evidence);
    const root = document.createElement("div"); document.body.append(root);
    const app = createApp(DiscoveryView, { s }); app.mount(root); cleanup.push(() => { app.unmount(); root.remove(); });
    expect(root.querySelectorAll(".profile-card")).toHaveLength(6);
    expect(root.querySelector(".profile-panel")).toBeNull();
    expect(root.querySelector(".inspector-index")).toBeNull();
    expect(root.querySelector(".discovery-resume")).toBeNull();
    expect(root.querySelectorAll(".original-descriptions")).toHaveLength(1);
    expect(root.querySelectorAll(".original-descriptions blockquote")).toHaveLength(1);
    for (const text of ["sourceType", "confidence", "confirmed=", "画像依据", "关联字段"]) expect(root.textContent).not.toContain(text);
    const confirm = [...root.querySelectorAll("button")].find(button => button.textContent?.includes("确认当前画像"))!;
    const check = root.querySelector<HTMLInputElement>(".review-actions input")!;
    check.checked = true; check.dispatchEvent(new Event("change", { bubbles: true })); await settle();
    expect(confirm.disabled).toBe(false);
    s.profile = { ...s.profile!, revision: 5 }; await settle(); expect(confirm.disabled).toBe(true);
    expect(JSON.stringify(s.profile.evidence)).toBe(original);
  });
});
