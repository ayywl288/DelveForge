import { createApp, nextTick } from "vue";
import { expect, it, vi } from "vitest";
import App from "../src/App.vue";

it("窄屏菜单保留阶段入口，选择阶段后关闭并将焦点交回主内容", async () => {
    const remove = vi.fn();
    vi.stubGlobal("matchMedia", () => ({ matches: true, addEventListener: vi.fn(), removeEventListener: remove }));
    vi.stubGlobal("fetch", vi.fn(async (url: string) => new Response(JSON.stringify(
        url.includes("connectivity") ? { status: "UP" } : [],
    ))));
    const root = document.createElement("div");
    document.body.append(root);
    const app = createApp(App);
    app.mount(root);
    try {
        for (let i = 0; i < 30; i++) { await Promise.resolve(); await nextTick(); }
        const toggle = root.querySelector<HTMLButtonElement>(".mobile-navigation-toggle")!;
        expect(toggle.getAttribute("aria-expanded")).toBe("false");
        toggle.click();
        await nextTick();
        expect(toggle.getAttribute("aria-expanded")).toBe("true");
        expect(root.querySelectorAll(".workflow-navigation .nav-stage")).toHaveLength(5);
        root.querySelector<HTMLButtonElement>(".workflow-navigation .nav-stage")!.click();
        await nextTick();
        await nextTick();
        expect(toggle.getAttribute("aria-expanded")).toBe("false");
        expect(document.activeElement?.id).toBe("main-content");
        expect(root.querySelector("h1")?.textContent).toContain("需求探索");
    } finally {
        app.unmount();
        root.remove();
    }
    expect(remove).toHaveBeenCalledOnce();
});
