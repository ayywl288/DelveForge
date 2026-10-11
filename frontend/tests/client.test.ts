import { describe, expect, it, vi } from "vitest";
import { api, ApiError, request } from "../src/api/client";

describe("真实 DTO 请求契约", () => {
  it("规划阶段错误使用固定提示，拒绝与结果未知分开且不重试", async () => {
    const messages: Record<string, string> = {
      PLANNING_AI_FAILED: "Provider 响应处理失败",
      PLANNING_PROPOSAL_INVALID: "结构要求",
      PLANNING_EVIDENCE_INVALID: "输入中不存在的依据",
      PLANNING_CURRENT_STATE_INVALID: "现状条目",
      PLANNING_REJECTED: "领域校验",
    };
    const fetcher = vi.fn(); vi.stubGlobal("fetch", fetcher);
    for (const [code, text] of Object.entries(messages)) {
      fetcher.mockResolvedValueOnce(new Response(JSON.stringify({ code, message: "PRIVATE_PROVIDER_RESPONSE" }), { status: 502 }));
      await expect(api.generatePlan("d", "a", "r")).rejects.toThrow(text);
    }
    expect(fetcher).toHaveBeenCalledTimes(5);
    expect(new ApiError(0, "CONNECTION_LOST").message).toContain("结果尚不确定");
    expect(new ApiError(502, "PLANNING_CURRENT_STATE_INVALID").message).not.toContain("PRIVATE_PROVIDER_RESPONSE");
  });

  it("使用 Controller 字段名、无体决策和相对代理 URL", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response("{}", { status: 200 }));
    // 每次返回新的 Response，正文只能读取一次。
    fetcher.mockImplementation(async () => new Response("{}", { status: 200 }));
    vi.stubGlobal("fetch", fetcher);
    await api.discoveryTurn("p", "需求");
    await api.confirmProfile("p", 7);
    await api.registerAsset({
      location: "C:\\sample",
      readPermissionAllowed: false,
      licenseInfo: null,
      usageAuthorization: "UNCLEAR",
    });
    await api.discover("p", 7, ["r"]);
    await api.generatePlan("d", "a", "r");
    await api.decideDirection("d", "select");
    await api.decideDirection("d", "reject");
    await api.prepare("plan");
    await api.confirmStep("plan", "step");
    await api.execute("plan", "step");
    expect(
      fetcher.mock.calls.map(([url, options]) => [
        url,
        options.method,
        options.body && JSON.parse(options.body),
      ]),
    ).toEqual([
      ["/api/user-profiles/p/discovery-turn", "POST", { input: "需求", previousQuestions: [] }],
      ["/api/user-profiles/p/confirm", "POST", { revision: 7 }],
      [
        "/api/software-assets",
        "POST",
        {
          location: "C:\\sample",
          readPermissionAllowed: false,
          licenseInfo: null,
          usageAuthorization: "UNCLEAR",
        },
      ],
      [
        "/api/product-directions/discovery",
        "POST",
        {
          userProfileId: "p",
          expectedRevision: 7,
          repositoryProfileIds: ["r"],
        },
      ],
      [
        "/api/evolution-plans/planning",
        "POST",
        {
          productDirectionId: "d",
          baseAssetId: "a",
          baseRepositoryProfileId: "r",
        },
      ],
      ["/api/product-directions/d/select", "POST", undefined],
      ["/api/product-directions/d/reject", "POST", undefined],
      ["/api/evolution-plans/plan/prepare", "POST", undefined],
      ["/api/evolution-plans/plan/steps/step/confirm", "POST", undefined],
      ["/api/evolution-plans/plan/steps/step/execute", "POST", undefined],
    ]);
    expect(
      fetcher.mock.calls.every(([, options]) => options.signal === undefined),
    ).toBe(true);
    await api.profile("id/with?input");
    expect(fetcher.mock.calls.at(-1)?.[0]).toBe(
      "/api/user-profiles/id%2Fwith%3Finput",
    );
    expect(fetcher.mock.calls.at(-1)?.[1].signal).toBeDefined();
  });
  it("不向 UI 暴露原始错误正文，不重试失败请求", async () => {
    const fetcher = vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            code: "INTERNAL_ERROR",
            message: "PRIVATE_PROVIDER_CONTENT",
          }),
          { status: 500 },
        ),
    );
    vi.stubGlobal("fetch", fetcher);
    await expect(request("/test")).rejects.toThrow("后端操作失败");
    expect(fetcher).toHaveBeenCalledTimes(1);
    fetcher.mockImplementation(async () => {
      throw new Error("SECRET_KEY");
    });
    await expect(request("/test", "POST")).rejects.toEqual(
      new ApiError(0, "CONNECTION_LOST"),
    );
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it("只读提问携带当前画像版本与有界问题上下文，reopen 仍使用原接口", async () => {
    const fetcher = vi.fn(async (_url: string, _options?: RequestInit) => new Response("{}"));
    vi.stubGlobal("fetch", fetcher);
    await api.discoveryQuestion("p", 3, ["已经提过的问题？"]);
    await api.resumeDiscovery("p", true);
    await api.resumeDiscovery("p", false);
    expect(fetcher.mock.calls.map(([url, options]) => [url, options?.method, options?.body])).toEqual([
      ["/api/user-profiles/p/discovery-question", "POST", '{"revision":3,"previousQuestions":["已经提过的问题？"]}'],
      ["/api/user-profiles/p/reopen-discovery", "POST", undefined],
      ["/api/user-profiles/p/continue-discovery", "POST", undefined],
    ]);
  });
});
