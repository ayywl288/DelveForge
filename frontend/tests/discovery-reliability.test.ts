import { createApp, nextTick, reactive } from "vue";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { api, ApiError } from "../src/api/client";
import type { ConversationHistory, ProfileUpdateProposal } from "../src/api/types";
import { discoveryDraftKey, readDiscoveryDrafts } from "../src/state/discoveryDrafts";
import { createSession } from "../src/state/session";
import DiscoveryView from "../src/views/DiscoveryView.vue";
import { profile } from "./fixtures";

const cleanup: (() => void)[] = [];
const history = (proposal: ProfileUpdateProposal | null = null): ConversationHistory => ({
  messages: [], before: null, pendingRequestId: null, unansweredQuestion: null, pendingProposal: proposal,
});
const proposal = (): ProfileUpdateProposal => ({ id: "proposal", profileId: "profile-1", sourceRequestId: "source",
  projectId: null, baseRevision: 1, baseStatus: "EXPLORING", currentSections: { projectGoals: ["Coding Agent"] },
  proposedSections: { projectGoals: ["课程检索"] }, userInput: "不再做 Coding Agent", evidenceClaims: ["更改目标"],
  state: "PENDING", resultingRevision: null });
function setup(mount = false) {
  const s = reactive(createSession()); s.profile = profile(); s.ids.profileId = "profile-1";
  cleanup.push(() => s.dispose());
  const root = document.createElement("div");
  if (mount) {
    document.body.append(root); const app = createApp(DiscoveryView, { s }); app.mount(root);
    cleanup.push(() => { app.unmount(); root.remove(); });
  }
  return { s, root };
}
beforeEach(() => {
  vi.spyOn(api, "profile").mockResolvedValue(profile());
  vi.spyOn(api, "conversation").mockResolvedValue(history());
  vi.spyOn(api, "reviewReadiness").mockResolvedValue({ profileId: "profile-1", revision: 1, sufficient: true });
});
afterEach(() => { cleanup.splice(0).reverse().forEach(fn => fn()); vi.restoreAllMocks(); });
async function settle() { for (let i = 0; i < 40; i++) { await Promise.resolve(); await nextTick(); } }

it("确认失败保留原文、刷新后可编辑，新发送使用新身份且不会被当成已保存消息", async () => {
  const ids: string[] = [];
  const turn = vi.spyOn(api, "discoveryTurn").mockImplementation(async (_id, _text, _q, _c, identity) => {
    ids.push(identity!.requestId); throw new ApiError(502, "EXTERNAL_CAPABILITY_UNAVAILABLE");
  });
  vi.spyOn(api, "discoveryRequest").mockImplementation(async (_id, requestId) => ({ requestId, state: "FAILED", revision: null, result: null }));
  const first = setup(); await first.s.turn("不想再做 coding agent，想换成资料检索");
  expect(first.s.messages[0]?.state).toBe("failed");
  expect(first.s.discoveryUncertain).toBe(false);
  const second = setup(true); await second.s.loadConversation(); await nextTick();
  expect(second.s.messages[0]?.text).toContain("资料检索");
  expect(second.s.messages[0]?.state).toBe("failed");
  const edit = [...second.root.querySelectorAll<HTMLButtonElement>("button")].find(button => button.textContent === "编辑并重新发送")!;
  edit.click(); await settle();
  expect(second.root.querySelector("textarea")!.value).toContain("资料检索");
  expect(turn).toHaveBeenCalledOnce();
  await second.s.turn(second.s.discoveryDraft + "，给课程使用");
  expect(ids).toHaveLength(2); expect(ids[0]).not.toBe(ids[1]);
  expect(localStorage.getItem("delveforge.discovery.pending.v1")).not.toContain("资料检索");
  expect(second.s.messages.every(message => message.state !== "accepted")).toBe(true);
});

it("502 但原请求仍待定时保留身份，只读恢复成功后清除旧警告且没有双重发送", async () => {
  let requestId = "";
  const turn = vi.spyOn(api, "discoveryTurn").mockImplementation(async (_id, _text, _q, _c, identity) => {
    requestId = identity!.requestId; throw new ApiError(502, "EXTERNAL_CAPABILITY_UNAVAILABLE");
  });
  vi.spyOn(api, "discoveryRequest").mockImplementation(async (_id, id) => ({ requestId: id, state: "PENDING", revision: null, result: null }));
  const first = setup(); await first.s.turn("服务器是否保存尚不确定的方向变更");
  first.s.setDiscoveryDraft("接下来准备补充的草稿");
  const second = setup(); await second.s.loadConversation();
  expect(second.s.discoveryUncertain).toBe(true);
  expect(second.s.discoveryDraft).toContain("草稿");
  expect(second.s.messages[0]?.state).toBe("uncertain");
  await second.s.turn("不能盲重发"); expect(turn).toHaveBeenCalledOnce();
  vi.mocked(api.discoveryRequest).mockResolvedValue({ requestId, state: "COMPLETED", revision: 1,
    result: { sufficient: true, missingAreas: [], nextQuestion: null } });
  vi.mocked(api.conversation).mockResolvedValue({ ...history(), messages: [
    { sequence: 1, requestId, role: "user", text: "服务器保存的方向变更", question: null,
      questionState: null, createdAt: "2026-11-11T00:00:00Z", projectId: null },
  ] });
  await second.s.checkDiscoveryStatus();
  expect(second.s.error).toBe(""); expect(second.s.discoveryUncertain).toBe(false);
  expect(second.s.messages).toHaveLength(1); expect(second.s.messages[0]?.state).toBe("accepted");
  expect(turn).toHaveBeenCalledOnce();
  expect(sessionStorage.getItem(discoveryDraftKey)).not.toContain("服务器是否保存");
});

it("旧失败原文按发送时间恢复，不把它放到后来已接受的回复之后", async () => {
  vi.spyOn(api, "discoveryTurn").mockRejectedValue(new ApiError(502, "EXTERNAL_CAPABILITY_UNAVAILABLE"));
  vi.spyOn(api, "discoveryRequest").mockImplementation(async (_id, requestId) => ({ requestId, state: "FAILED", revision: null, result: null }));
  const { s } = setup(); await s.turn("早先失败的想法");
  vi.mocked(api.conversation).mockResolvedValue({ ...history(), messages: [
    { sequence: 1, requestId: "later", role: "agent", text: "后来已保存的回应", question: null,
      questionState: null, createdAt: new Date(Date.now() + 1000).toISOString(), projectId: null },
  ] });
  await s.loadConversation();
  expect(s.messages.map(message => message.text)).toEqual(["早先失败的想法", "后来已保存的回应"]);
  expect(s.messages[0]?.state).toBe("failed");
});

it("接受并继续使用独立问题身份，后续失败仍明确保留已批准结果", async () => {
  const { s, root } = setup(true); s.profileUpdateProposal = proposal();
  vi.spyOn(api, "resolveProfileUpdate").mockResolvedValue({ ...proposal(), state: "ACCEPTED", resultingRevision: 2 });
  vi.mocked(api.profile).mockResolvedValue({ ...profile(), revision: 2, projectGoals: ["课程检索"] });
  vi.spyOn(api, "discoveryRequest").mockImplementation(async (_id, requestId) => ({ requestId, state: "FAILED", revision: null, result: null }));
  const ask = vi.spyOn(api, "discoveryQuestion").mockRejectedValue(new ApiError(502, "EXTERNAL_CAPABILITY_UNAVAILABLE"));
  await nextTick();
  [...root.querySelectorAll<HTMLButtonElement>("button")].find(button => button.textContent === "接受更新并继续")!.click();
  await settle();
  expect(s.profile?.projectGoals).toEqual(["课程检索"]);
  expect(s.approvalNotice).toContain("已接受并保存");
  expect(root.querySelector(".approval-result")?.textContent).toContain("后续提问未完成");
  expect(s.notice).toContain("后续提问未完成"); expect(s.error).toBe("");
  expect(ask).toHaveBeenCalledOnce(); expect(ask.mock.calls[0]?.[4]?.requestId).not.toBe("source");
  expect(api.resolveProfileUpdate).toHaveBeenCalledOnce();
});

it("接受后若有旧问题则直接显示，单独接受与恢复均不生成问题", async () => {
  const { s, root } = setup(true); s.profileUpdateProposal = proposal();
  vi.spyOn(api, "resolveProfileUpdate").mockResolvedValue({ ...proposal(), state: "ACCEPTED", resultingRevision: 2 });
  vi.mocked(api.conversation).mockResolvedValue({ ...history(), unansweredQuestion: "你想先整理哪门课程？" });
  const ask = vi.spyOn(api, "discoveryQuestion");
  await s.resolveProfileUpdate(true, true); await nextTick();
  expect(root.querySelector("#pending-question")?.textContent).toContain("哪门课程");
  expect(root.textContent).toContain("跳过这问，换个话题"); expect(ask).not.toHaveBeenCalled();
  s.profileUpdateProposal = proposal(); await s.resolveProfileUpdate(true, false);
  expect(ask).not.toHaveBeenCalled();
});

it("批准响应丢失只读取原提案终态，不重发批准或自动生成后续问题", async () => {
  const { s } = setup(); s.profileUpdateProposal = proposal();
  const accept = vi.spyOn(api, "resolveProfileUpdate").mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
  const read = vi.spyOn(api, "profileUpdate").mockResolvedValue({ ...proposal(), state: "ACCEPTED", resultingRevision: 2 });
  const ask = vi.spyOn(api, "discoveryQuestion");
  await s.resolveProfileUpdate(true);
  expect(accept).toHaveBeenCalledOnce(); expect(read).toHaveBeenCalledOnce();
  expect(s.approvalNotice).toContain("已接受并保存"); expect(s.error).toBe(""); expect(ask).not.toHaveBeenCalled();
});

it("后续问题结果不确定时禁止再次生成，查询完成后清除局部待核对提示", async () => {
  const { s } = setup(); s.profileUpdateProposal = proposal();
  vi.spyOn(api, "resolveProfileUpdate").mockResolvedValue({ ...proposal(), state: "ACCEPTED", resultingRevision: 2 });
  vi.mocked(api.profile).mockResolvedValue({ ...profile(), revision: 2 });
  const ask = vi.spyOn(api, "discoveryQuestion").mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
  const status = vi.spyOn(api, "discoveryRequest").mockImplementation(async (_id, requestId) => ({
    requestId, state: "PENDING", revision: null, result: null,
  }));
  await s.resolveProfileUpdate(true, true);
  expect(s.discoveryUncertain).toBe(true); expect(s.approvalNotice).toContain("结果待核对");
  await s.continueExploring(); expect(ask).toHaveBeenCalledOnce();
  const requestId = ask.mock.calls[0]![4]!.requestId;
  status.mockResolvedValue({ requestId, state: "COMPLETED", revision: 2,
    result: { sufficient: true, missingAreas: [], nextQuestion: "课程资料怎样归档？" } });
  vi.mocked(api.conversation).mockResolvedValue({ ...history(), unansweredQuestion: "课程资料怎样归档？" });
  await s.checkDiscoveryStatus();
  expect(s.discoveryUncertain).toBe(false); expect(s.error).toBe("");
  expect(s.approvalNotice).toContain("结果已恢复"); expect(s.approvalNotice).not.toContain("待核对");
  expect(s.nextQuestion).toBe("课程资料怎样归档？"); expect(ask).toHaveBeenCalledOnce();
});

it("本地草稿限定五份画像、四条失败原文及一天有效期，已接受原文不放入会话存储", async () => {
  const { s } = setup();
  for (let i = 0; i < 7; i++) { s.profile = { ...profile(), id: `draft-${i}` }; s.setDiscoveryDraft("本地草稿".repeat(4000)); }
  const records = readDiscoveryDrafts();
  expect(Object.keys(records)).toHaveLength(5);
  expect(records["draft-6"]!.composer.length).toBe(12_000);
  vi.spyOn(api, "discoveryTurn").mockRejectedValue(new ApiError(502, "EXTERNAL_CAPABILITY_UNAVAILABLE"));
  vi.mocked(api.profile).mockResolvedValue({ ...profile(), id: "draft-6" });
  vi.spyOn(api, "discoveryRequest").mockImplementation(async (_id, requestId) => ({ requestId, state: "FAILED", revision: null, result: null }));
  for (let i = 0; i < 6; i++) await s.turn(`失败原文 ${i}`);
  expect(readDiscoveryDrafts()["draft-6"]!.attempts).toHaveLength(4);
  expect(readDiscoveryDrafts()["draft-6"]!.attempts[0]!.text).toBe("失败原文 2");
  sessionStorage.setItem(discoveryDraftKey, JSON.stringify({ old: { composer: "旧草稿", attempts: [], updatedAt: Date.now() - 90_000_000 } }));
  expect(readDiscoveryDrafts()).toEqual({});
});
