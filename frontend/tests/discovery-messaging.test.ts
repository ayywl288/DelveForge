import { createApp, nextTick, reactive } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../src/api/client";
import type { DiscoveryTurn, ProjectContext } from "../src/api/types";
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

describe("Discovery 消息反馈与请求边界", () => {
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
    expect(root.querySelector(".message.user")?.textContent).toContain("本轮已接受");
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
    expect(read).toHaveBeenCalledTimes(2);
    expect(s.messages[0]?.state).toBe("uncertain");
    expect(s.notice).toContain("无法确认");
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
    await s.checkDiscoveryStatus();
    expect(s.discoveryUncertain).toBe(true);
    expect(s.messages[0]?.state).toBe("uncertain");
    expect(composer.value).toBe("本地草稿");
    expect(s.error).toBeTruthy();
  });
});
