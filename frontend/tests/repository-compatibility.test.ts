import { createApp, h, nextTick, reactive } from "vue";
import { afterEach, describe, expect, it, vi } from "vitest";
import RepositoryCompatibilityView from "../src/components/RepositoryCompatibility.vue";
import { api } from "../src/api/client";
import type { RepositoryCompatibility } from "../src/api/types";

const cleanups: (() => void)[] = [];
afterEach(() => { cleanups.splice(0).forEach(cleanup => cleanup()); vi.restoreAllMocks(); });
const report = (revision: string): RepositoryCompatibility => ({
  revision, status: "PREPARATION_REQUIRED", policyVersion: "maven-generated-v1", mavenDirectory: "backend",
  modules: ["backend"], buildJdk: 17, sourceBytes: 1234, files: 4, textFiles: 3, opaqueFiles: 1,
  generatedPaths: ["backend/target/classes/Example.class"], immutablePaths: ["logo.png"], issues: [],
});
const settle = async () => { for (let i = 0; i < 8; i++) { await Promise.resolve(); await nextTick(); } };
function mount(props: { assetId: string; revision: string }) {
  const root = document.createElement("div"); document.body.append(root);
  const app = createApp({ render: () => h(RepositoryCompatibilityView, { ...props }) });
  app.mount(root); cleanups.push(() => { app.unmount(); root.remove(); }); return root;
}

describe("repository compatibility preparation", () => {
  it("shows automatic actions and keeps preflight distinct from actual tests and Step authorization", async () => {
    const read = vi.spyOn(api, "repositoryCompatibility").mockResolvedValue(report("a"));
    const root = mount(reactive({ assetId: "asset", revision: "a" }));
    await settle();
    expect(read).toHaveBeenCalledWith("asset", "a");
    expect(root.textContent).toContain("可自动准备隔离副本");
    expect(root.textContent).toContain("backend/target/classes/Example.class");
    expect(root.textContent).toContain("准备不会启动 AI 或授权 Step");
  });

  it("discards the previous repository response after selection changes", async () => {
    let resolveOld!: (value: RepositoryCompatibility) => void;
    vi.spyOn(api, "repositoryCompatibility")
      .mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
      .mockResolvedValueOnce({ ...report("b"), status: "BLOCKED", generatedPaths: [], issues: [{
        classification: "REQUIRES_EXPLICIT_USER_DECISION", code: "GENERATED_OUTPUT_AMBIGUITY",
        path: "target/business.json", action: "保留业务内容并明确用途", blocking: true,
      }] });
    const props = reactive({ assetId: "old", revision: "a" });
    const root = mount(props);
    props.assetId = "new"; props.revision = "b";
    await settle();
    resolveOld(report("a")); await settle();
    expect(root.textContent).toContain("保留业务内容并明确用途");
    expect(root.textContent).not.toContain("backend/target/classes/Example.class");
  });

  it("clears old results when no pinned revision is selected", async () => {
    vi.spyOn(api, "repositoryCompatibility").mockResolvedValue(report("a"));
    const props = reactive({ assetId: "asset", revision: "a" }); const root = mount(props);
    await settle(); props.revision = ""; await settle();
    expect(root.textContent).not.toContain("可自动准备隔离副本");
    expect(root.textContent).not.toContain("正在检查");
  });
});

it("contains extensive resource diagnostics while keeping blockers visible", async () => {
  const paths = Array.from({ length: 250 }, (_, i) => `resources/archive/${i}/long-name.png`);
  vi.spyOn(api, "repositoryCompatibility").mockResolvedValue({ ...report("large"), immutablePaths: paths,
    issues: [{ classification: "REQUIRES_EXPLICIT_USER_DECISION", code: "BLOCKED", action: "需要确认资源用途", path: "target/real.json", blocking: true },
      ...paths.map(path => ({ classification: "SUPPORTED" as const, code: "RESOURCE", action: "保留资源", path, blocking: false }))] });
  const root = mount({ assetId: "asset", revision: "large" }); await settle();
  const details = root.querySelector<HTMLDetailsElement>(".compatibility-details")!;
  expect(details.open).toBe(false);
  expect(root.querySelector(".compatibility-blockers")?.textContent).toContain("需要确认资源用途");
  expect(details.textContent).not.toContain("需要确认资源用途");
  details.querySelector("summary")!.click();
  expect(details.open).toBe(true);
  expect(details.textContent).toContain(paths.at(-1));
  expect(details.querySelector('[role="region"]')?.getAttribute("tabindex")).toBe("0");
});
