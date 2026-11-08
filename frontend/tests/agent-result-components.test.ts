import { createApp, h, nextTick, reactive } from "vue";
import { afterEach, expect, it } from "vitest";
import FileChangeSummary from "../src/components/FileChangeSummary.vue";
import AgentTestEvidence from "../src/components/AgentTestEvidence.vue";
import type { PresentedFileChange } from "../src/components/agentResultPresentation";
const cleanup:(()=>void)[]=[];

it("explains projected checks without implying full-checkout execution or exposing resource content", async () => {
    const props = reactive({ evidence: JSON.stringify({ status: "NOT_TESTED", sourceView: {
        kind: "PROJECTED", preservedOnlyCount: 2, readOnlyResourceCount: 1 } }) });
    const root = document.createElement("div");
    const app = createApp({ render: () => h(AgentTestEvidence, props) });
    app.mount(root); cleanup.push(() => app.unmount()); await settle();
    expect(root.textContent).toContain("检查使用执行投影；2 项资源保留在原仓库，未进入执行环境。");
    expect(root.textContent).toContain("未运行测试");
    props.evidence = JSON.stringify({ status: "NOT_TESTED", sourceView: {
        kind: "FULL", preservedOnlyCount: 0, readOnlyResourceCount: 1 } });
    await settle();
    expect(root.textContent).toContain("1 项只读资源");
    expect(root.textContent).not.toContain("检查使用执行投影");
    props.evidence = '{"status":"NOT_TESTED","sourceView":{"kind":"PROJECTED","preservedOnlyCount":-1}}';
    await settle();
    expect(root.textContent).toContain("执行材料范围待核对。");
});
afterEach(()=>cleanup.splice(0).forEach(fn=>fn()));
async function settle(){await nextTick();await nextTick();}
it("shows distinct change kinds with accurate counts, readable full paths and an expandable complete list",async()=>{
  const long="backend/"+"very-long-segment/".repeat(15)+"Example.java";
  const files:PresentedFileChange[]=[{path:long,changeType:"ADDED"},{path:"changed.java",changeType:"MODIFIED"},
    {path:"removed.java",changeType:"DELETED"},...Array.from({length:5},(_,i)=>({path:`new-${i}.java`,changeType:"ADDED" as const}))];
  const root=document.createElement("div");const app=createApp({render:()=>h(FileChangeSummary,{files})});app.mount(root);
  cleanup.push(()=>app.unmount());await settle();
  expect(root.querySelector(".change-counts")?.textContent).toContain("8 个文件新增 6修改 1删除 1");
  expect([...root.querySelectorAll(".file-change-type")].slice(0,3).map(node=>node.getAttribute("data-type"))).toEqual(["ADDED","MODIFIED","DELETED"]);
  expect(root.querySelector(".file-path")?.textContent).toBe(long);
  expect(root.querySelectorAll("li")).toHaveLength(6);
  root.querySelector<HTMLButtonElement>("button")!.click();await settle();
  expect(root.querySelectorAll("li")).toHaveLength(8);
  expect(root.querySelector("button")?.getAttribute("aria-expanded")).toBe("true");
});
it("renders TESTED counts and NOT_TESTED honestly without rendering original JSON or developer disclosures",async()=>{
  const props=reactive({evidence:JSON.stringify({status:"TESTED",finalSnapshot:"a".repeat(64),checks:[{stale:false,snapshot:"a".repeat(64),
    classification:"TESTS_PASSED",executedTests:28,tests:30,skipped:2,failures:0,errors:0}]})});
  const root=document.createElement("div");const app=createApp({render:()=>h(AgentTestEvidence,props)});app.mount(root);
  cleanup.push(()=>app.unmount());await settle();
  expect(root.querySelector(".evidence-status")?.textContent).toBe("已测试");
  expect([...root.querySelectorAll("dd")].map(node=>node.textContent)).toEqual(["28","28","0","0","2"]);
  expect(root.querySelector("details, pre")).toBeNull();
  expect(root.textContent).not.toContain(props.evidence);
  props.evidence='{"status":"NOT_TESTED"}';await settle();
  expect(root.querySelector(".evidence-status")?.textContent).toBe("未运行测试");
  expect(root.querySelector(".test-counts")).toBeNull();
  expect(root.querySelector(".verification-outcome")?.textContent).not.toContain("通过");
});

it("shows independent Maven and npm/pnpm checks with goals and stale source labels", async () => {
    const snapshot = "a".repeat(64);
    const check = { snapshot, stale: false, executedTests: 0, tests: 0, failures: 0, errors: 0 };
    const evidence = JSON.stringify({ status: "TESTED", finalSnapshot: snapshot, checks: [
        { ...check, checkType: "FRONTEND", directory: "web", goal: "test", classification: "TESTS_FAILED", stale: true },
        { ...check, checkType: "MAVEN", directory: "backend", goal: "test", classification: "TESTS_PASSED", executedTests: 2 },
        { ...check, checkType: "FRONTEND", directory: "web", goal: "typecheck", classification: "BUILD_PASSED_NOT_TESTED" },
        { ...check, checkType: "FRONTEND", directory: "web", goal: "build", classification: "BUILD_PASSED_NOT_TESTED" },
        { ...check, checkType: "FRONTEND", directory: "web", goal: "test", classification: "TESTS_PASSED", executedTests: 3 },
    ] });
    const root = document.createElement("div");
    const app = createApp({ render: () => h(AgentTestEvidence, { evidence }) });
    app.mount(root); cleanup.push(() => app.unmount()); await settle();
    const rows = [...root.querySelectorAll(".check-evidence-list li")].map(row => row.textContent);
    expect(rows).toHaveLength(5);
    expect(rows[4]).toContain("旧源码结果");
    expect(rows[3]).toContain("Maven · backend · test");
    expect(rows[2]).toContain("前端 · web · typecheck");
    expect(rows[2]).toContain("未运行测试");
    expect(rows[2]).not.toContain("测试通过");
    expect(root.querySelector(".evidence-scope")?.textContent).toContain("前端 · web · test");
});

it("keeps unavailable optional checks distinct from failed or passing tests", async () => {
    const snapshot = "c".repeat(64);
    const evidence = JSON.stringify({ status: "NOT_TESTED", finalSnapshot: snapshot, checks: [{
        checkType: "FRONTEND", directory: ".", goal: "test", classification: "CHECK_NOT_AVAILABLE",
        snapshot, stale: false, executedTests: 0, tests: 0, reports: 0, failures: 0, errors: 0, skipped: 0,
    }] });
    const root = document.createElement("div");
    const app = createApp({ render: () => h(AgentTestEvidence, { evidence }) });
    app.mount(root);
    cleanup.push(() => app.unmount());
    await settle();
    expect(root.textContent).toContain("未运行测试");
    expect(root.textContent).toContain("项目未提供此项受支持检查");
    expect(root.textContent).not.toContain("测试通过");
    expect(root.textContent).not.toContain("测试未通过");
    expect(root.querySelector(".test-counts")).toBeNull();
});


it("renders parser-free generic success without invented test counts and keeps attempts in history", async () => {
    const check = { checkType: "COMMAND", command: "sh check.sh", directory: ".", snapshot: "a".repeat(64),
        afterSnapshot: "a".repeat(64), exitCode: 0, timedOut: false, cancelled: false, classification: "COMMAND_SUCCEEDED" };
    const evidence = JSON.stringify({ status: "NOT_TESTED", evidenceVersion: 6, verificationLevel: "COMMAND_VERIFIED",
        finalSnapshot: "a".repeat(64), requiredChecks: [{command: "sh check.sh", directory: "."}],
        checks: [{...check, exitCode: 1, classification: "COMMAND_FAILED"}, check] });
    const root = document.createElement("div");
    const app = createApp({ render: () => h(AgentTestEvidence, { evidence }) });
    app.mount(root); cleanup.push(() => app.unmount()); await settle();
    expect(root.querySelector(".evidence-status")?.textContent).toBe("必要命令已通过");
    expect(root.querySelector(".test-counts")).toBeNull();
    expect(root.querySelectorAll(".current-evidence-list li")).toHaveLength(0);
    expect(root.querySelector<HTMLDetailsElement>(".evidence-history")?.open).toBe(false);
    expect(root.querySelectorAll(".evidence-history li")).toHaveLength(2);
    expect(root.querySelector(".evidence-history li")?.textContent).toContain("命令成功");
});
