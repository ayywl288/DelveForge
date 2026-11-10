import { createApp, nextTick, reactive } from "vue";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../src/api/client";
import type { ConversationHistory, DiscoveryTurn, ProjectContext } from "../src/api/types";
import { createSession, sessionKey } from "../src/state/session";
import DiscoveryView from "../src/views/DiscoveryView.vue";
import { profile } from "./fixtures";

const cleanup: (() => void)[] = [];
afterEach(() => {
  cleanup.splice(0).forEach(fn => fn());
  vi.restoreAllMocks();
});
function setup() {
  const s = reactive(createSession());
  s.profile = profile();
  s.ids.profileId = "profile-1";
  const root = document.createElement("div");
  document.body.append(root);
  const app = createApp(DiscoveryView, { s });
  app.mount(root);
  cleanup.push(() => { app.unmount(); s.dispose(); root.remove(); });
  return { s, root, composer: root.querySelector("textarea")! };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (failure: unknown) => void;
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; });
  return { promise, resolve, reject };
}
async function settle() {
  for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick(); }
}
async function write(composer: HTMLTextAreaElement, text: string) {
  composer.value = text;
  composer.dispatchEvent(new Event("input", { bubbles: true }));
  await nextTick();
}
function submit(root: HTMLElement) {
  root.querySelector("form")!.dispatchEvent(new Event("submit", { bubbles: true, cancelable: true }));
}
const result = (): DiscoveryTurn => ({ profile: { ...profile(), revision: 2, projectGoals: ["真实服务端目标"] },
  sufficient: false, missingAreas: ["场景"], nextQuestion: "服务端实际下一问？" });
const context = (): ProjectContext => ({ id: "project-1", name: "合成项目", userProfileId: "profile-1",
  assetIds: [], repositoryProfileIds: [], directionIds: [], planId: null, createdAt: "now", updatedAt: "now",
  version: 0, flowId: "project-1", previousContextId: null, deletedAt: null });

beforeEach(() => {
  vi.spyOn(api, "discoveryRequest").mockImplementation(async (_id, requestId) => ({
    requestId, state: "PENDING", revision: null, result: null,
  }));
});
describe("Discovery 消息反馈与请求边界", () => {
  it("替换原问后的即时消息与重新读取都呈现同一条追问，解释保持可见", async () => {
    const { s, root } = setup();
    const question = "哪个小场景最值得先动手？";
    const explanation = "为什么从生活中找例子？熟悉的场景比较容易描述。";
    vi.spyOn(api, "discoveryTurn").mockResolvedValue({ ...result(), nextAction: "ASK",
      previousQuestionState: "REPLACED", assistantReply: explanation, nextQuestion: question });
    await s.turn("我不知道怎么回答这个问题。"); await nextTick();
    const check = () => {
      const reply = root.querySelector(".message.agent")!;
      expect(reply.querySelector(".dialogue-text")?.textContent).toBe(explanation);
      expect(reply.querySelector(".dialogue-question")?.textContent).toBe(question);
      expect(reply.textContent!.split(question)).toHaveLength(2);
    };
    check();
    vi.spyOn(api, "conversation").mockResolvedValue({ messages: [{ sequence: 1, requestId: "replacement",
      role: "agent", text: explanation, question, questionState: "ASKED", nextAction: "ASK",
      createdAt: "2026-11-09T00:00:00Z", projectId: null }], before: null,
      pendingRequestId: null, unansweredQuestion: question });
    await s.loadConversation(); await nextTick();
    check();
  });

  it("恢复审阅邀请后可以直接续聊，接受新问题后只保留侧栏审阅入口", async () => {
    const { s, root } = setup();
    s.sufficiency = { sufficient: true, missingAreas: [], nextQuestion: null };
    vi.spyOn(api, "conversation").mockResolvedValue({ messages: [
      { sequence: 1, requestId: "invite", role: "agent", text: "现在可以检查这版目标了。", question: null,
        questionState: null, createdAt: "2026-11-09T00:00:00Z", projectId: null },
    ], before: null, pendingRequestId: null, invitationRequestId: "invite", unansweredQuestion: null });
    await s.loadConversation(); await nextTick();
    expect(root.querySelectorAll(".dialogue-choice button")).toHaveLength(2);
    const ask = vi.spyOn(api, "discoveryQuestion");
    vi.spyOn(api, "discoveryTurn").mockResolvedValue({ ...result(), sufficient: true, missingAreas: [],
      nextAction: "ASK", previousQuestionState: "NONE", assistantReply: "我们可以聊聊查找旧笔记的过程。" });
    await s.turn("我还想聊检索笔记"); await nextTick();
    expect(ask).not.toHaveBeenCalled();
    expect(root.querySelector(".dialogue-choice")).toBeNull();
    expect(root.querySelector(".dialogue-question")?.textContent).toBe(result().nextQuestion);
    expect(root.querySelector(".notebook-review")?.textContent).toContain("进入画像审阅");
  });

  it("聊天摘要的六区及原始描述参与正常渲染，整体收起后可重新展开", async () => {
    const { s, root } = setup();
    s.profile = { ...profile(), interests: ["阅读整理"], evidence: [
      { sourceType: "USER_INPUT", sourceRef: "想做阅读笔记工具", claim: "需求", confidence: null, confirmed: false },
    ] };
    await nextTick();
    const inspector = root.querySelector<HTMLElement>("#profile-inspector")!;
    const sections = [...inspector.querySelectorAll(".profile-section")];
    expect(sections).toHaveLength(6);
    for (const section of sections) {
      expect(section.closest("template")).toBeNull();
      expect(section.querySelector("summary")).not.toBeNull();
    }
    expect(inspector.querySelector(".original-descriptions")?.closest("template")).toBeNull();
    expect(inspector.textContent).toContain("阅读整理");
    const toggle = root.querySelector<HTMLButtonElement>('[aria-controls="profile-inspector"]')!;
    toggle.click(); await nextTick(); expect(inspector.style.display).toBe("none");
    toggle.click(); await nextTick(); expect(inspector.style.display).not.toBe("none");
  });

  it.each(["表单", "Ctrl+Enter", "会话方法"])("历史加载期间%s不能发送，读完后保留草稿并恢复发送", async entry => {
    const read = deferred<ConversationHistory>();
    vi.spyOn(api, "conversation").mockReturnValue(read.promise);
    const turn = vi.spyOn(api, "discoveryTurn").mockResolvedValue(result());
    const { s, root, composer } = setup();
    const loading = s.loadConversation();
    await write(composer, "历史读完后发送的草稿");
    const sendButton = [...root.querySelectorAll("button")].find(button => button.textContent?.trim() === "发送")!;
    expect(sendButton.disabled).toBe(true);
    if (entry === "表单") submit(root);
    else if (entry === "Ctrl+Enter") composer.dispatchEvent(new KeyboardEvent("keydown", {
      key: "Enter", ctrlKey: true, bubbles: true, cancelable: true,
    }));
    else await s.turn("直接调用也须等待历史读取");
    await settle();
    const callsDuringLoading = turn.mock.calls.length;
    read.resolve({ messages: [], before: null, pendingRequestId: null, unansweredQuestion: null });
    await loading; await settle();
    expect(callsDuringLoading).toBe(0);
    expect(s.conversationLoading).toBe(false);
    expect(composer.value).toBe("历史读完后发送的草稿");
    expect(sendButton.disabled).toBe(false);
    composer.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", ctrlKey: true, bubbles: true }));
    await settle();
    expect(turn).toHaveBeenCalledOnce();
    expect(root.querySelector(".message.user")?.getAttribute("data-state")).toBe("accepted");
    expect(s.conversationLoading).toBe(false);
  });

  it("发送立即显示消息并清空输入，等待时可编辑草稿且不能并发发送", async () => {
    const request = deferred<DiscoveryTurn>();
    const turn = vi.spyOn(api, "discoveryTurn").mockReturnValue(request.promise);
    const { s, root, composer } = setup();
    await write(composer, "第一条想法");
    submit(root);
    await nextTick();
    expect(root.querySelector(".message.user")?.textContent).toContain("第一条想法");
    expect(root.querySelector(".message.user")?.getAttribute("data-state")).toBe("pending");
    expect(root.querySelector(".message-spinner")).not.toBeNull();
    expect(root.querySelector(".assistant-waiting")?.textContent).toContain("DelveForge 正在整理你的想法…");
    expect(root.querySelectorAll(".waiting-dots i")).toHaveLength(3);
    expect(root.textContent).not.toContain("上一轮结果待核对");
    expect(composer.value).toBe("");
    expect(composer.disabled).toBe(false);
    await write(composer, "正在写的下一条草稿");
    submit(root);
    composer.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", ctrlKey: true, bubbles: true }));
    await s.turn("直接调用也不并发");
    expect(turn).toHaveBeenCalledTimes(1);
    expect(s.messages).toHaveLength(1);
    expect([...root.querySelectorAll("button")].find(button => button.textContent?.trim() === "发送")?.disabled).toBe(true);
    request.resolve(result());
    await settle();
    expect(composer.value).toBe("正在写的下一条草稿");
    expect(root.querySelector(".assistant-waiting")).toBeNull();
    expect(root.querySelector(".message.user")?.getAttribute("data-state")).toBe("accepted");
    expect(root.querySelector(".accepted-state")?.getAttribute("aria-label")).toContain("服务端接受");
    expect(root.querySelector(".accepted-state svg")).not.toBeNull();
    expect(root.querySelector(".message.user")?.textContent).not.toContain("本轮已接受");
    expect(root.querySelector(".message.agent")?.textContent).toContain(result().nextQuestion);
    expect(s.profile?.projectGoals).toEqual(["真实服务端目标"]);
    expect(s.sufficiency?.missingAreas).toEqual(["场景"]);
    expect(s.nextQuestion).toBe(result().nextQuestion);
    expect(localStorage.getItem(sessionKey) ?? "").not.toContain("第一条想法");
  });

  it("同一画像的版本或状态变化保留草稿，切换画像才清空", async () => {
    const { s, composer } = setup();
    await write(composer, "尚未发送的草稿");
    s.profile = { ...profile(), revision: 8, status: "REVIEWING" };
    await nextTick();
    expect(composer.value).toBe("尚未发送的草稿");
    s.profile = { ...profile(), id: "other-profile" };
    await nextTick();
    expect(composer.value).toBe("");
  });

  it.each([
    [400, "INVALID_REQUEST"], [404, "NOT_FOUND"], [409, "CONFLICT"],
    [502, "EXTERNAL_CAPABILITY_UNAVAILABLE"],
  ])("明确拒绝 %i/%s 显示内联失败，查询不重发", async (status, code) => {
    vi.mocked(api.discoveryRequest).mockImplementation(async (_id, requestId) => ({
      requestId, state: "FAILED", revision: null, result: null,
    }));
    const turn = vi.spyOn(api, "discoveryTurn").mockRejectedValue(new ApiError(status, code));
    const read = vi.spyOn(api, "profile").mockResolvedValue(profile());
    const { s, root, composer } = setup();
    await write(composer, "未接受的内容");
    submit(root);
    await settle();
    expect(s.messages[0]?.state).toBe("failed");
    expect(root.querySelector(".message-error")?.textContent).toContain("本轮未被接受");
    expect(root.querySelector("dialog[open]")).toBeNull();
    expect(s.messages.filter(message => message.role === "agent")).toHaveLength(0);
    (root.querySelector(".message-error button") as HTMLButtonElement).click();
    await settle();
    expect(read).toHaveBeenCalledTimes(2);
    expect(turn).toHaveBeenCalledTimes(1);
    await write(composer, "编辑后的内容");
    expect([...root.querySelectorAll("button")].find(button => button.textContent?.trim() === "发送")?.disabled).toBe(false);
  });

  it.each([
    [0, "CONNECTION_LOST"], [0, "RESPONSE_UNREADABLE"], [500, "INTERNAL_ERROR"], [504, ""],
  ])("未知结果 %i/%s 不凭画像版本判成功、不自动重试或发送下一轮", async (status, code) => {
    const turn = vi.spyOn(api, "discoveryTurn").mockRejectedValue(new ApiError(status, code));
    const read = vi.spyOn(api, "profile").mockResolvedValue({ ...profile(), revision: 3 });
    const { s, root, composer } = setup();
    await s.turn("结果未知的消息");
    await settle();
    expect(s.messages[0]?.state).toBe("uncertain");
    expect(root.querySelector(".message-error")?.textContent).toContain("结果尚不确定");
    (root.querySelector(".message-error button") as HTMLButtonElement).click();
    await settle();
    expect(read).toHaveBeenCalledTimes(1);
    expect(api.discoveryRequest).toHaveBeenCalledTimes(2);
    expect(s.messages[0]?.state).toBe("uncertain");
    expect(s.notice).toContain("仍在处理");
    await write(composer, "保留下一轮草稿");
    submit(root);
    await s.turn("直接调用也不重发");
    expect(turn).toHaveBeenCalledTimes(1);
    expect(composer.value).toBe("保留下一轮草稿");
    expect(s.messages.filter(message => message.role === "agent")).toHaveLength(0);
  });

  it("业务已接受后项目关联失败保留接受状态、问题、充分性和草稿，阻止再次提交", async () => {
    const sync = deferred<ProjectContext>();
    const turn = vi.spyOn(api, "discoveryTurn").mockResolvedValue(result());
    vi.spyOn(api, "updateProject").mockReturnValue(sync.promise);
    const { s, root, composer } = setup();
    s.projectContext = context();
    await write(composer, "已接受的输入");
    submit(root);
    await settle();
    await write(composer, "下一条草稿");
    expect(s.messages[0]?.state).toBe("accepted");
    expect(s.busy).toBe("保存项目关联");
    expect(root.querySelector(".assistant-waiting")).toBeNull();
    expect(root.querySelector(".message-error")).toBeNull();
    sync.reject(new ApiError(0, "CONNECTION_LOST"));
    await settle();
    expect(s.projectSyncError).toBe(true);
    expect(s.notice).toContain("项目关联保存失败");
    expect(s.messages[0]?.state).toBe("accepted");
    expect(s.nextQuestion).toBe(result().nextQuestion);
    expect(s.sufficiency?.missingAreas).toEqual(["场景"]);
    submit(root);
    await settle();
    expect(turn).toHaveBeenCalledTimes(1);
    expect(composer.value).toBe("下一条草稿");
  });

  it("迟到响应不覆盖已销毁的会话", async () => {
    const request = deferred<DiscoveryTurn>();
    vi.spyOn(api, "discoveryTurn").mockReturnValue(request.promise);
    const { s, composer } = setup();
    const pending = s.turn("旧会话输入");
    await write(composer, "新草稿");
    s.dispose();
    request.resolve(result());
    await pending;
    expect(s.profile?.revision).toBe(1);
    expect(s.messages.filter(message => message.role === "agent")).toHaveLength(0);
  });

  it("未知消息的只读查询失败不会清空草稿或解除保护", async () => {
    vi.spyOn(api, "discoveryTurn").mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
    vi.spyOn(api, "profile").mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
    const { s, composer } = setup();
    await s.turn("未知消息");
    await write(composer, "本地草稿");
    vi.mocked(api.discoveryRequest).mockRejectedValue(new ApiError(0, "CONNECTION_LOST"));
    await s.checkDiscoveryStatus();
    expect(s.discoveryUncertain).toBe(true);
    expect(s.messages[0]?.state).toBe("uncertain");
    expect(composer.value).toBe("本地草稿");
    expect(s.error).toBeTruthy();
  });
});


describe("自然回复、可选问题与有界语境", () => {
  it("正文与下一问合并显示一次，提问历史只包含问题", async () => {
    const turn = vi.spyOn(api, "discoveryTurn")
      .mockResolvedValueOnce({ ...result(), assistantReply: "可以先从熟悉的日常工具想起。" })
      .mockResolvedValueOnce({ ...result(), assistantReply: "好的，这一轮可以先停在这里。", nextQuestion: null, previousQuestionState: "ANSWERED" });
    const { s, root } = setup();
    await s.turn("不知道");
    await settle();
    expect(root.querySelectorAll(".message.agent")).toHaveLength(1);
    expect(root.querySelector(".message.agent")?.textContent).toContain("可以先从熟悉");
    expect(root.querySelector(".message.agent")?.textContent?.split(result().nextQuestion!)).toHaveLength(2);
    await s.turn("我想先想一想");
    expect(turn.mock.calls[1]?.[2]).toEqual([]);
expect(turn.mock.calls[1]?.[3]).toEqual([]);
    expect(turn.mock.calls[1]?.[4]?.requestId).toBeTypeOf("string");
    expect(s.nextQuestion).toBeNull();
    expect(s.messages.at(-1)?.text).toBe("好的，这一轮可以先停在这里。");
    expect(localStorage.getItem(sessionKey) ?? "").not.toContain("熟悉");
  });

  it("正文含有下一问时不重复展示，仅解释时保留未回答问题", async () => {
    vi.spyOn(api, "discoveryTurn").mockResolvedValueOnce({ ...result(),
      assistantReply: "先换个角度。" + result().nextQuestion }).mockResolvedValueOnce({ ...result(),
      assistantReply: "技术背景帮助判断可行范围，你也可以跳过。", nextQuestion: null });
    const ask = vi.spyOn(api, "discoveryQuestion").mockResolvedValue({ revision: 2, sufficient: false,
      missingAreas: ["场景"], assistantReply: "可以先从使用场景开始。", nextQuestion: "最先给谁用？" });
    const { s } = setup();
    await s.turn("换个问题");
    expect(s.messages.at(-1)?.text).toBe("先换个角度。" + result().nextQuestion);
    await s.turn("为什么需要技术背景");
    expect(s.nextQuestion).toBe(result().nextQuestion);
    await s.continueExploring();
    expect(ask).not.toHaveBeenCalled();
  });

  it("不发送客户端内存历史，服务端恢复连续语境", async () => {
    const turn = vi.spyOn(api, "discoveryTurn").mockResolvedValue({ ...result(),
      assistantReply: "解释当前问题。", nextQuestion: null });
    const { s } = setup();
    for (let i = 0; i < 12; i++) s.messages.push({ role: "user", text: `已接受${i}`, state: "accepted" });
    s.messages.push({ role: "user", text: "失败消息", state: "failed" });
    s.messages.push({ role: "agent", text: "x".repeat(3000) });
    await s.turn("本轮待提交");
    const context = turn.mock.calls[0]?.[3];
    expect(context).toEqual([]);
    expect(turn.mock.calls[0]?.[4]).toMatchObject({ revision: 1, requestId: expect.any(String) });
    expect(context?.some(message => message.text === "失败消息" || message.text === "本轮待提交")).toBe(false);
  });

  it("长正文截断时保留实际追问，让下一轮短回答仍有提问语境", async () => {
    const question = "你目前有什么技术经验？";
    const turn = vi.spyOn(api, "discoveryTurn")
      .mockResolvedValueOnce({ ...result(), assistantReply: "解释".repeat(1024), nextQuestion: question })
      .mockResolvedValueOnce({ ...result(), assistantReply: "收到。", nextQuestion: null });
    const { s } = setup();
    await s.turn("先问我一个问题");
    await s.turn("做过几个Spring Boot应用");
    const context = turn.mock.calls[1]?.[3];
    expect(context).toEqual([]);
    expect(turn.mock.calls[1]?.[4]?.requestId).not.toBe(turn.mock.calls[0]?.[4]?.requestId);
    expect(context?.some(message => message.text === "做过几个Spring Boot应用")).toBe(false);
    expect(s.messages[1]?.text).toBe("解释".repeat(1024) + "\n\n" + question);
  });

  it("正文前部已有实际问题时保留原语境，不再追加同一问题", async () => {
    const question = "你主要使用什么技术？";
    const reply = question + "解释".repeat(1024);
    const turn = vi.spyOn(api, "discoveryTurn")
      .mockResolvedValueOnce({ ...result(), assistantReply: reply, nextQuestion: question })
      .mockResolvedValueOnce({ ...result(), assistantReply: "收到。", nextQuestion: null });
    const { s } = setup();
    await s.turn("可以先聊技术");
    await s.turn("Spring Boot");
    expect(turn.mock.calls[1]?.[3]).toEqual([]);
    expect(s.messages[1]?.text).toBe(reply);
    expect(s.messages[1]?.text.split(question)).toHaveLength(2);
  });

  it("较早的本页问题也去重，回复正文仍然保留", async () => {
    vi.spyOn(api, "discoveryTurn").mockResolvedValue({ ...result(), assistantReply: "我们可以继续补充其他想法。" });
    const { s } = setup();
    s.messages.push({ role: "agent", text: result().nextQuestion!, question: result().nextQuestion! });
    for (let i = 0; i < 9; i++) s.messages.push({ role: "agent", text: `其他问题${i}`, question: `其他问题${i}` });
    await s.turn("继续补充");
    expect(s.messages.at(-1)?.text).toBe("我们可以继续补充其他想法。");
    expect(s.nextQuestion).toBeNull();
    expect(s.notice).toContain("已经提过");
  });

  it("同一画像在等待期间变版时，迟到回复不覆盖状态或显示为成功", async () => {
    const request = deferred<DiscoveryTurn>();
    vi.spyOn(api, "discoveryTurn").mockReturnValue(request.promise);
    const { s } = setup();
    const pending = s.turn("旧版本输入");
    s.profile = { ...profile(), revision: 9, status: "REVIEWING" };
    request.resolve({ ...result(), assistantReply: "旧回复不应显示" });
    await pending;
    expect(s.profile.revision).toBe(9);
    expect(s.profile.status).toBe("REVIEWING");
    expect(s.messages[0]?.state).toBe("uncertain");
    expect(s.messages.some(message => message.role === "agent")).toBe(false);
    expect(s.notice).toContain("迟到");
  });
});
