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
        toggle.click();
        await nextTick();
        root.querySelector<HTMLButtonElement>(".workflow-navigation .nav-stage")!.click();
        await nextTick();
        await nextTick();
        expect(toggle.getAttribute("aria-expanded")).toBe("false");
        expect(document.activeElement?.id).toBe("main-content");
    } finally {
        app.unmount();
        root.remove();
    }
    expect(remove).toHaveBeenCalledOnce();
});

it("断点隐藏已聚焦导航后，即使浏览器先将焦点退回 body，仍交回可见控件", async () => {
    let changed: (() => void) | undefined;
    const media = { matches: false, addEventListener: (_: string, fn: () => void) => { changed = fn; }, removeEventListener: vi.fn() };
    vi.stubGlobal("matchMedia", () => media);
    vi.stubGlobal("fetch", vi.fn(async (url: string) => new Response(JSON.stringify(
        url.includes("connectivity") ? { status: "UP" } : [],
    ))));
    const root = document.createElement("div");
    document.body.append(root);
    const app = createApp(App);
    app.mount(root);
    try {
        for (let i = 0; i < 30; i++) { await Promise.resolve(); await nextTick(); }
        const item = root.querySelector<HTMLButtonElement>(".workflow-navigation .nav-stage")!;
        item.focus();
        item.blur();
        media.matches = true;
        changed?.();
        expect(document.activeElement?.classList.contains("mobile-navigation-toggle")).toBe(true);
        (document.activeElement as HTMLElement).blur();
        media.matches = false;
        changed?.();
        expect(document.activeElement?.classList.contains("brand")).toBe(true);
    } finally {
        app.unmount();
        root.remove();
    }
});
