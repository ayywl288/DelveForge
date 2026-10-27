import { createApp, h, nextTick, reactive } from "vue";
import { afterEach, expect, it } from "vitest";
import FileChangeSummary from "../src/components/FileChangeSummary.vue";
import AgentTestEvidence from "../src/components/AgentTestEvidence.vue";
import type { PresentedFileChange } from "../src/components/agentResultPresentation";
const cleanup:(()=>void)[]=[];
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
it("renders TESTED counts and NOT_TESTED honestly while original JSON stays in a closed disclosure",async()=>{
  const props=reactive({evidence:JSON.stringify({status:"TESTED",finalSnapshot:"a".repeat(64),checks:[{stale:false,snapshot:"a".repeat(64),
    classification:"TESTS_PASSED",executedTests:28,tests:30,skipped:2,failures:0,errors:0}]})});
  const root=document.createElement("div");const app=createApp({render:()=>h(AgentTestEvidence,props)});app.mount(root);
  cleanup.push(()=>app.unmount());await settle();
  expect(root.querySelector(".evidence-status")?.textContent).toBe("已测试");
  expect([...root.querySelectorAll("dd")].map(node=>node.textContent)).toEqual(["28","28","0","0","2"]);
  expect(root.querySelector<HTMLDetailsElement>("details")!.open).toBe(false);
  expect(root.querySelector("pre")?.textContent).toBe(props.evidence);
  props.evidence='{"status":"NOT_TESTED"}';await settle();
  expect(root.querySelector(".evidence-status")?.textContent).toBe("未运行测试");
  expect(root.querySelector(".test-counts")).toBeNull();
  expect(root.querySelector(".verification-outcome")?.textContent).not.toContain("通过");
});
