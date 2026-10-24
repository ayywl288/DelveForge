<script setup lang="ts">
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from "vue";
import { api } from "../api/client";
import type { AgentCandidate, AgentEvent, AgentRun, Delivery } from "../api/agentTypes";
import type { WorkspaceSession } from "../state/session";
import ContentList from "../components/ContentList.vue";
import RevisionText from "../components/RevisionText.vue";
import StatusBadge from "../components/StatusBadge.vue";
import StepFinalChanges from "../components/StepFinalChanges.vue";
import AgentTimeline from "../components/AgentTimeline.vue";
import NavigationIcon from "../components/NavigationIcon.vue";
import type { NavigationIconName } from "../components/NavigationIcon.vue";
import { stepPrerequisiteIssue } from "../state/stepPrerequisite";

const props = defineProps<{ s: WorkspaceSession }>();
const emit = defineEmits<{ planning: [] }>();
const stepId = ref("");
const step = computed(() => props.s.plan?.steps.find(item => item.id === stepId.value)
  ?? props.s.plan?.steps.find(item => item.status !== "SUCCEEDED") ?? props.s.plan?.steps.at(-1));
const runs = ref<AgentRun[]>([]);
const run = ref<AgentRun | null>(null);
const candidate = ref<AgentCandidate | null>(null);
const delivery = ref<Delivery | null>(null);
const events = ref<AgentEvent[]>([]);
const diff = ref("");
const error = ref("");
const syncError = ref("");
const pending = ref(false);
const connection = ref("尚未连接");
const jdk = ref("");
let active = false;
let epoch = 0;
let actionId = 0;
let stream: EventSource | undefined;
let refreshTimer: ReturnType<typeof setTimeout> | undefined;
const terminal = (item: AgentRun) => ["SUCCEEDED", "FAILED", "CANCELLED", "INTERRUPTED"].includes(item.state);
const latest = computed(() => runs.value[0]);
const activeRun = computed(() => run.value && !terminal(run.value) ? run.value : runs.value.find(item => !terminal(item)));
const stopping = ref(new Set<string>());
const stopAccepted = ref(new Set<string>());
const stopError = ref("");
const stopPending = computed(() => !!activeRun.value && stopping.value.has(activeRun.value.id));
const stopConfirmed = computed(() => !!activeRun.value && (activeRun.value.cancelRequested || stopAccepted.value.has(activeRun.value.id)));
const resourceBlocked = computed(() => events.value.some(event =>
  ["CLEANUP_BLOCKED", "RECONCILIATION_BLOCKED"].includes(event.kind)));
const dockerPreparationFailed = computed(() => run.value?.state === "FAILED" && run.value.reason === "DOCKER_FAILURE"
  && run.value.modelCalls === 0 && run.value.toolCalls === 0
  && !events.value.some(event => ["SANDBOX_READY", "RUNNING"].includes(event.kind)));
const evidenceStatus = computed(() => {
  try {
    const evidence = JSON.parse(candidate.value?.testEvidence ?? "{}");
    return evidence.status ?? "查看实际检查证据";
  } catch { return "查看实际检查证据"; }
});
const prerequisiteIssue = computed(() => props.s.plan && step.value
  ? stepPrerequisiteIssue(props.s.plan, step.value, props.s.copy) : "请先读取计划与步骤。");
const canConfirm = computed(() => step.value?.status === "PENDING_CONFIRMATION" && !prerequisiteIssue.value
  && !pending.value && !props.s.busy);
const canStart = computed(() => props.s.plan?.status === "ACTIVE" && step.value?.status === "READY"
  && !prerequisiteIssue.value
  && !pending.value && !props.s.busy && (!latest.value || terminal(latest.value) && !latest.value.candidateId)
  && props.s.plan.steps.slice(0, props.s.plan.steps.indexOf(step.value)).every(item => item.status === "SUCCEEDED"));
const labels: Record<string, string> = {
  CREATED: "已预约", PREPARING: "准备沙箱", RUNNING: "编码中", FINALIZING: "冻结结果中", SUCCEEDED: "Candidate 已冻结",
  FAILED: "编码失败", CANCELLED: "已取消", INTERRUPTED: "运行已中断", INTENT: "交付已预约", APPLIED: "正式文件已核对",
  COMMIT_READY: "正式提交已准备", PUBLISHED: "Git 已发布，等待接受", COMPLETED: "交付完成", DELIVERY_BLOCKED: "交付阻塞",
  RECOVERY_REQUIRED: "需要核对恢复", MODEL_REQUEST: "请求模型", MODEL_COMPLETED: "模型完成，等待冻结",
  TOOL_STARTED: "工具开始", TOOL_FINISHED: "工具结果", SANDBOX_READY: "沙箱已就绪", DELIVERY_PENDING: "交付等待协调",
};
function label(value: string) { return labels[value] ?? labels[value.replace(/^DELIVERY_/, "")] ?? value; }
function runIcon(state: AgentRun["state"]): NavigationIconName {
  if (state === "FAILED") return "warning";
  if (state === "SUCCEEDED") return "check";
  if (["INTERRUPTED", "CANCELLED"].includes(state)) return "close";
  return "activity";
}
const numberText = (value: number) => value.toLocaleString("zh-CN");
function stop() {
  stream?.close();
  stream = undefined;
  clearTimeout(refreshTimer);
}
function selection() {
  return { version: epoch, planId: props.s.plan?.id, stepId: step.value?.id,
    runId: run.value?.id, candidateId: candidate.value?.id };
}
type Selection = ReturnType<typeof selection>;
function retainStopFact(item: AgentRun) {
  return stopAccepted.value.has(item.id) && !terminal(item) ? { ...item, cancelRequested: true } : item;
}
function current(selected: Selection) {
  return active && selected.version === epoch && selected.planId === props.s.plan?.id
    && selected.stepId === step.value?.id && selected.runId === run.value?.id
    && selected.candidateId === candidate.value?.id;
}
async function updateStatus(version: number) {
  const selected = run.value;
  if (!selected) return;
  const scope = selection();
  const belongs = () => active && version === epoch && scope.planId === props.s.plan?.id
    && scope.stepId === step.value?.id && selected.id === run.value?.id;
  try {
    const actual = retainStopFact(await api.agentRun(selected.id));
    if (!belongs()) return;
    if (run.value && terminal(run.value) && !terminal(actual)) return;
    run.value = actual;
    runs.value = runs.value.map(item => item.id === actual.id ? actual : item);
    if (actual.candidateId) {
      const [frozen, accepted] = await Promise.all([api.candidate(actual.candidateId), api.delivery(actual.candidateId)]);
      if (!belongs()) return;
      candidate.value = frozen;
      delivery.value = accepted;
      if (accepted?.state === "COMPLETED" && step.value?.status !== "SUCCEEDED") await props.s.refreshPlan();
    }
    if (belongs()) syncError.value = "";
  } catch (failure) {
    if (belongs()) syncError.value = String(failure);
  }
}
async function selectRun(selected: AgentRun) {
  stop();
  const version = ++epoch;
  ++actionId;
  pending.value = false;
  error.value = "";
  syncError.value = "";
  run.value = retainStopFact(selected);
  candidate.value = null;
  delivery.value = null;
  events.value = [];
  diff.value = "";
  if (!active) return;
  if (typeof EventSource === "undefined") {
    connection.value = "浏览器不支持实时事件";
    await updateStatus(version);
    return;
  }
  connection.value = "连接中";
  stream = new EventSource(`/api/agent-runs/${encodeURIComponent(selected.id)}/stream?after=0`);
  stream.onopen = () => {
    if (version !== epoch) return;
    connection.value = "已连接";
    void updateStatus(version);
  };
  stream.onerror = () => {
    if (version === epoch) connection.value = "连接中断，正在重连并补齐事件";
  };
  stream.addEventListener("activity", event => {
    if (version !== epoch) return;
    try {
      const fact = JSON.parse((event as MessageEvent).data) as AgentEvent;
      if (fact.runId !== selected.id || !Number.isSafeInteger(fact.sequence) || fact.sequence < 1) return;
      if (!events.value.length || fact.sequence > events.value.at(-1)!.sequence) {
        events.value = [...events.value, fact].slice(-1000);
      }
      clearTimeout(refreshTimer);
      refreshTimer = setTimeout(() => void updateStatus(version), 200);
    } catch {
      error.value = "事件内容无法读取；请刷新以重新读取持久化历史。";
    }
  });
  await updateStatus(version);
}
async function loadRuns(older = false) {
  const plan = props.s.plan;
  const chosen = step.value;
  if (!plan || !chosen) return;
  const scope = selection();
  try {
    const result = (await api.agentRuns(plan.id, chosen.id, older ? runs.value.at(-1)?.id : undefined)).map(retainStopFact);
    if (!current(scope)) return;
    runs.value = older ? [...runs.value, ...result.filter(item => !runs.value.some(old => old.id === item.id))] : result;
    syncError.value = "";
    if (!older && result[0]) await selectRun(result[0]);
  } catch (failure) {
    if (current(scope)) syncError.value = String(failure);
  }
}
async function action<T>(operation: (selected: Selection) => Promise<T>,
  accept?: (result: T) => void | Promise<void>) {
  const scope = selection();
  if (pending.value) return;
  const owner = ++actionId;
  pending.value = true;
  error.value = "";
  try {
    const result = await operation(scope);
    if (current(scope)) await accept?.(result);
  } catch (failure) {
    if (current(scope)) error.value = String(failure);
  } finally {
    if (owner === actionId) pending.value = false;
  }
}
async function startRun() {
  if (!canStart.value || !props.s.plan || !step.value) return;
  const selectedJdk = jdk.value ? Number(jdk.value) : undefined;
  await action(selected => api.startAgent(selected.planId!, selected.stepId!, selectedJdk), async started => {
    runs.value = [started, ...runs.value];
    await selectRun(started);
  });
}
async function cancelRun() {
  const target = activeRun.value;
  if (!target || stopping.value.has(target.id) || target.cancelRequested || stopAccepted.value.has(target.id)) return;
  const scope = selection();
  stopping.value.add(target.id);
  stopError.value = "";
  try {
    const actual = await api.cancelAgent(target.id);
    if (actual.id !== target.id) return;
    if (actual.cancelRequested) stopAccepted.value.add(target.id);
    if (!active || scope.planId !== props.s.plan?.id || scope.stepId !== step.value?.id) return;
    // 只补充已接受的停止事实；迟到响应不得把已终止快照改回运行中。
    const applyStop = (item: AgentRun) => item.id !== target.id || terminal(item) ? item
      : terminal(actual) ? actual : { ...item, cancelRequested: item.cancelRequested || actual.cancelRequested };
    runs.value = runs.value.map(applyStop);
    if (run.value) run.value = applyStop(run.value);
  } catch (failure) {
    if (current(scope)) { error.value = String(failure); stopError.value = "停止请求未确认，请刷新运行状态。"; }
  } finally {
    stopping.value.delete(target.id);
  }
}
async function deliverCandidate() {
  if (!candidate.value) return;
  await action(selected => api.deliver(selected.candidateId!), actual => { delivery.value = actual; });
}
async function confirmStep() {
  if (!canConfirm.value) return;
  await action(selected => props.s.confirmStep(selected.stepId!, () => current(selected)));
}
async function loadDiff() {
  const id = candidate.value?.id;
  if (!id) return;
  await action(async selected => {
    const response = await fetch(`/api/agent-candidates/${encodeURIComponent(id)}/diff`, { cache: "no-store" });
    if (!current(selected)) return "";
    if (!response.ok) throw new Error("最终差异读取失败");
    return response.text();
  }, text => { diff.value = text; });
}
function activate() {
  if (active) return;
  active = true;
  stepId.value = step.value?.id ?? "";
  void loadRuns();
}
function deactivate() { active = false; ++epoch; ++actionId; pending.value = false; stop(); }
watch([() => props.s.plan?.id, () => step.value?.id], async () => {
  const version = ++epoch;
  ++actionId;
  stop();
  pending.value = false;
  runs.value = [];
  run.value = null;
  events.value = [];
  candidate.value = null;
  delivery.value = null;
  diff.value = "";
  error.value = "";
  syncError.value = "";
  await nextTick();
  if (version !== epoch) return;
  stepId.value = step.value?.id ?? "";
  if (active) void loadRuns();
});
onMounted(activate);
onActivated(activate);
onDeactivated(deactivate);
onUnmounted(deactivate);
</script>

<template>
  <section v-if="!s.plan" class="panel"><h2>实施演化步骤</h2><p>先创建并激活演化计划。</p>
    <button @click="emit('planning')">前往规划</button></section>
  <template v-else>
    <section class="panel agent-controls" aria-labelledby="agent-controls-title">
      <div class="workbench-heading"><div><p class="workbench-eyebrow">CODING AGENT</p><h2 id="agent-controls-title">执行工作台</h2></div>
        <span class="meta"><NavigationIcon name="lock" />隔离编码 · 逐步授权</span></div>
      <p class="workbench-intro">Agent 在隔离沙箱中编码与检查，冻结结果通过校验后自动交付。</p>
      <div class="step-configuration"><label for="agent-step">当前步骤</label>
      <select id="agent-step" :value="step?.id" @change="stepId = ($event.target as HTMLSelectElement).value">
        <option v-for="(item, index) in s.plan.steps" :key="item.id" :value="item.id">Step {{ String(index + 1).padStart(2, '0') }} · {{ item.goal }}</option>
      </select>
      <template v-if="step">
        <div class="selected-step-summary"><StatusBadge :status="step.status" /><p>{{ step.scope }}</p></div>
        <details class="planned-changes"><summary>计划变更 · {{ step.plannedChanges.length }} 项</summary>
          <ul class="planned-change-list"><li v-for="(change, index) in step.plannedChanges" :key="index">{{ change }}</li></ul></details>
      </template></div>
      <template v-if="step">
        <div v-if="prerequisiteIssue" class="step-lock-reason">
          <p>{{ prerequisiteIssue }}</p>
          <button class="text-button" @click="emit('planning')">前往演化规划核对准备与授权</button>
        </div>
        <div class="execution-action-area">
        <button v-if="step.status === 'PENDING_CONFIRMATION' && !prerequisiteIssue" :disabled="!canConfirm"
          @click="confirmStep">确认此步骤</button>
        <template v-if="step.status === 'READY'">
          <div class="jdk-field"><label for="agent-jdk">运行环境</label>
            <select id="agent-jdk" v-model="jdk"><option value="">自动选择 JDK</option><option value="17">JDK 17</option><option value="21">JDK 21</option></select></div>
          <div class="start-action"><button :disabled="!canStart" @click="startRun">{{ latest ? '重新开始编码' : '开始 Agent' }}</button>
            <p class="meta">仅运行当前已授权 Step</p></div>
        </template>
        </div>
      </template>
      <p class="agent-usage-notice">执行消耗外部 API 额度；累计模型、工具次数和 tokens 默认只统计。可随时停止，但停止不是暂停，当前不支持断点续跑；墙钟、上下文和沙箱资源等限制仍生效。</p>
      <p v-if="error || syncError" role="alert" class="error">{{ error || syncError }}</p>
    </section>
    <section class="panel agent-run-panel" aria-label="运行与实际活动">
      <div class="section-row"><h3>运行记录</h3><button class="secondary compact-button" :disabled="pending" @click="loadRuns()">刷新记录</button></div>
      <p v-if="!runs.length" class="workbench-empty">此步骤还没有编码记录。开始后可在此查看实际运行与活动。</p>
      <div v-else class="run-workspace">
      <aside class="run-history" aria-label="历史运行"><p class="workbench-eyebrow">运行历史 · {{ runs.length }}</p>
        <ul><li v-for="(item, index) in runs" :key="item.id"><button class="text-button run-choice" :aria-pressed="run?.id === item.id"
          @click="selectRun(item)"><span class="run-choice-heading"><strong>{{ label(item.state) }}</strong><small v-if="index === 0">最新</small></span>
            <time :datetime="item.startedAt">{{ new Date(item.startedAt).toLocaleString() }}</time>
            <span>模型 {{ item.modelCalls }} · 工具 {{ item.toolCalls }}</span></button></li></ul>
        <button v-if="runs.length >= 50" class="secondary" @click="loadRuns(true)">读取更早的运行</button>
      </aside>
      <div class="selected-run">
      <div v-if="activeRun" class="monitor-stop" aria-label="当前运行控制">
        <div><strong>当前步骤正在运行</strong><p v-if="run?.id !== activeRun.id" class="meta">正在查看历史记录；停止操作针对本步骤当前运行。</p>
          <p id="run-stop-status" class="meta" role="status">{{ stopPending ? '停止请求发送中' : stopConfirmed ? '停止请求已接受，正在等待实际终态；模型或沙箱可能不会立即返回。' : '可停止当前 Run；不支持暂停或续跑。' }}</p>
          <p v-if="stopError" role="alert" class="error">{{ stopError }}</p></div>
        <button class="secondary compact-button" :disabled="stopPending || stopConfirmed" aria-describedby="run-stop-status" @click="cancelRun">{{ stopConfirmed ? '正在停止' : '停止运行' }}</button>
      </div>
      <template v-if="run">
        <div class="run-summary" :data-state="run.state">
        <div class="section-row run-status-row"><h3 class="run-status" :data-state="run.state" role="status"><NavigationIcon :name="runIcon(run.state)" />Agent：{{ label(run.state) }}</h3>
          </div>
        <dl class="run-statistics" aria-label="已保存运行统计">
          <div><dt>模型调用</dt><dd>{{ numberText(run.modelCalls) }} <span>次</span></dd></div>
          <div><dt>工具调用</dt><dd>{{ numberText(run.toolCalls) }} <span>次</span></dd></div>
          <div><dt>累计 tokens</dt><dd>{{ run.tokens === null ? '未报告' : numberText(run.tokens) }}</dd></div>
        </dl>
        <p v-if="dockerPreparationFailed" class="run-failure">Docker 沙箱准备失败，尚未开始编码；模型与工具调用均为 0。</p>
        <p v-else-if="['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(run.state)" class="meta">本次编码未交付。当前步骤仍保留确认；新运行仍须通过执行前核对。</p>
        <p v-else-if="run.state === 'SUCCEEDED'" class="meta">本次运行已冻结 Candidate；正式接受状态以交付结果为准。</p>
        <p v-if="run.reason === 'SANDBOX_PROTOCOL_INCOMPATIBLE'" class="run-failure" role="alert">
          沙箱工具协议不兼容。请按 docs/AUTONOMOUS_AGENT.md 构建兼容镜像，显式配置镜像后再重启应用。
        </p>
        <p v-if="run.reason" class="run-reason"><span>原因</span><code>{{ run.reason }}</code></p>
        <dl class="run-times">
          <div><dt>开始时间</dt><dd><time :datetime="run.startedAt">{{ new Date(run.startedAt).toLocaleString() }}</time></dd></div>
          <div><dt>结束时间</dt><dd><time v-if="run.endedAt" :datetime="run.endedAt">{{ new Date(run.endedAt).toLocaleString() }}</time><span v-else>未记录</span></dd></div>
        </dl>
        </div>
        <p v-if="resourceBlocked" class="resource-warning" role="alert"><NavigationIcon name="warning" />资源协调待核对：所属资源或保留材料尚未完成核对。请先检查 Docker 与后端安全诊断，再判断能否重新运行。</p>
        <p v-if="run.retained && terminal(run)" class="meta">最终化材料已保留，请核对失败原因。</p>
        <AgentTimeline :key="run.id" :events="events" :run="run" :connection="connection" />
      </template>
      </div></div>
    </section>
    <section class="panel agent-changes" aria-labelledby="agent-changes-title">
      <div class="section-row"><h3 id="agent-changes-title">代码变更与交付</h3><span v-if="candidate" class="meta">冻结 Candidate · {{ candidate.changedPaths.length }} 个文件</span></div>
      <p v-if="!candidate" class="workbench-empty">{{ run?.candidateId
        ? '此 Run 已记录冻结 Candidate，但详情尚未读回。请刷新记录核对，暂不展示文件列表。'
        : run ? '此 Run 尚无冻结 Candidate，因此没有 Candidate 文件列表。'
          : '选择或开始运行后，可在此查看冻结 Candidate 的文件、检查证据与交付结果。' }}</p>
      <template v-else>
      <p class="meta">Candidate 变更来自本次冻结结果；正式接受的变更以完成的 Delivery 与最终累计差异为准。</p>
      <div class="candidate-overview"><div>
      <h4>Candidate 变更</h4>
      <p>Candidate 已冻结 · {{ candidate.diffBytes }} bytes</p>
      <ContentList title="变化文件" :items="candidate.changedPaths" />
      <details class="candidate-evidence"><summary>实际检查证据 · {{ evidenceStatus }}</summary><pre class="agent-output">{{ candidate.testEvidence }}</pre></details>
      <button class="secondary compact-button" :disabled="pending" @click="loadDiff">读取最终差异</button>
      </div><div class="delivery-overview">
      <h4>正式交付</h4>
      <p>Delivery：{{ delivery ? label(delivery.state) : '等待自动交付' }}</p>
      <p v-if="delivery?.reason">{{ delivery.reason === 'MUTATION_NOT_STARTED'
        ? '本次未写入正式副本。请核对外部文件或运行环境，处理后可重新交付已有结果。' : delivery.reason }}</p>
      <p v-if="delivery?.state === 'RECOVERY_REQUIRED'" role="alert">正式副本状态无法证明安全，已保留现场并阻止后续执行。请核对文件、Git 与交付日志。</p>
      <RevisionText v-if="delivery?.revision" label="正式提交" :value="delivery.revision" />
      <button v-if="!delivery || ['DELIVERY_BLOCKED', 'PUBLISHED', 'COMMIT_READY', 'APPLIED'].includes(delivery.state)
        || delivery.state === 'RECOVERY_REQUIRED' && delivery.reason === 'MUTATION_NOT_STARTED'" :disabled="pending"
        @click="deliverCandidate">重新核对并交付已有结果</button>
      </div></div>
      <pre v-if="diff" class="agent-output">{{ diff }}</pre>
      <StepFinalChanges v-if="delivery?.state === 'COMPLETED' && step && step.id === run?.stepId"
        :plan-id="s.plan.id" :step="step" />
      <p class="meta">检查证据保留实际范围与 NOT_TESTED 状态；交付完成表示代码一致性已接受。</p>
      </template>
    </section>
  </template>
</template>

<style scoped>
.agent-output { max-height: 32rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 12px; background: var(--canvas); padding: 14px; border-radius: 8px; }
.workbench-heading, .workbench-heading > .meta { display: flex; align-items: center; gap: 10px; }
.workbench-heading { justify-content: space-between; flex-wrap: wrap; }
.workbench-heading h2 { margin: 0; font-size: 19px; }
.workbench-eyebrow { font-size: 12px; letter-spacing: .06em; color: var(--muted); margin: 0 0 6px; }
.workbench-intro { font-size: 14px; line-height: 1.65; margin: 12px 0 22px; }
.step-configuration { max-width: 780px; }
.step-configuration > label, .jdk-field label { display: block; font-size: 14px; font-weight: 600; margin-bottom: 8px; }
.selected-step-summary { display: flex; align-items: baseline; gap: 12px; margin: 14px 0; }
.selected-step-summary p { margin: 0; font-size: 14px; line-height: 1.65; overflow-wrap: anywhere; }
.planned-changes { margin-top: 12px; font-size: 14px; }
.planned-changes summary { cursor: pointer; }
.planned-change-list { margin: 4px 0 12px; padding: 0 0 0 20px; }
.planned-change-list li { padding: 9px 0; margin: 0; font-size: 14px; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.planned-change-list li + li { border-top: 1px solid var(--line); }
.execution-action-area { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 20px 32px; margin-top: 24px; }
.execution-action-area:empty { display: none; }
.jdk-field { width: 220px; max-width: 100%; }
.start-action { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.start-action p { margin: 0; font-size: 13px; }
.agent-run-panel > .section-row { margin-bottom: 20px; }
.agent-run-panel h3, .agent-changes h3 { margin: 0; font-size: 17px; line-height: 1.5; }
.run-workspace { display: grid; grid-template-columns: 220px minmax(0, 1fr); gap: 24px; }
.run-history { min-width: 0; border-right: 1px solid var(--line); padding-right: 20px; }
.run-history ul { list-style: none; padding: 0; margin: 10px 0; max-height: 34rem; overflow: auto; }
.run-history .run-choice { width: 100%; display: flex; flex-direction: column; align-items: stretch; gap: 5px; padding: 10px; text-align: left; color: var(--muted); font-size: 12px; line-height: 1.5; margin-bottom: 4px; }
.run-choice-heading { display: flex; justify-content: space-between; gap: 8px; align-items: center; }
.run-choice-heading strong { font-size: 14px; color: var(--ink); }
.run-choice-heading small { font-size: 12px; font-weight: 400; }
.run-history .run-choice:hover:not(:disabled) { background: var(--canvas); }
.run-history .run-choice[aria-pressed="true"] { background: var(--selection); color: var(--accent-hover); }
.selected-run { min-width: 0; }
.monitor-stop { position: sticky; top: 12px; z-index: 4; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px; margin-bottom: 16px; background: var(--surface); border: 1px solid var(--line); border-radius: 8px; box-shadow: 0 3px 10px #25293608; }
.monitor-stop strong { font-size: 14px; }
.monitor-stop p { margin: 3px 0 0; font-size: 12px; }
.monitor-stop button { flex-shrink: 0; }
.run-summary { margin-bottom: 14px; }
.run-status { display: flex; align-items: center; gap: 8px; }
.run-status .navigation-icon { width: 20px; height: 20px; flex-shrink: 0; }
.run-statistics { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; margin: 18px 0 14px; }
.run-statistics > div { min-width: 0; }
.run-statistics dt { color: var(--muted); font-size: 12px; margin-bottom: 4px; }
.run-statistics dd { margin: 0; font-size: 19px; font-weight: 600; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.run-statistics dd span { font-size: 12px; font-weight: 400; color: var(--muted); }
.agent-usage-notice { color: var(--muted); font-size: 12px; line-height: 1.65; margin: 18px 0 0; max-width: 90ch; }
.run-reason { display: flex; flex-wrap: wrap; gap: 10px; align-items: baseline; font-size: 13px; margin: 12px 0; }
.run-reason span { color: var(--muted); }
.run-reason code { font-size: 12px; overflow-wrap: anywhere; }
.run-times { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px 24px; margin: 12px 0 20px; }
.run-times dt { color: var(--muted); font-size: 12px; }
.run-times dd { font-size: 13px; margin: 3px 0 0; }
.run-status[data-state="FAILED"] { color: var(--danger); }
.run-status[data-state="INTERRUPTED"], .run-status[data-state="CANCELLED"] { color: var(--muted); }
.run-status[data-state="SUCCEEDED"] { color: var(--success); }
.run-failure { color: var(--danger); font-size: 14px; line-height: 1.65; }
.resource-warning { display: flex; align-items: flex-start; gap: 10px; color: var(--warning); border-left: 2px solid var(--warning); padding: 8px 12px; font-size: 14px; line-height: 1.65; }
.resource-warning .navigation-icon { flex-shrink: 0; }
.workbench-empty { font-size: 14px; line-height: 1.65; color: var(--muted); padding: 12px 0; }
.candidate-overview { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 28px; margin: 20px 0; }
.candidate-overview > div { min-width: 0; overflow-wrap: anywhere; }
.candidate-overview h4 { font-size: 15px; margin: 0 0 12px; }
.candidate-overview p { font-size: 14px; }
.delivery-overview { border-left: 1px solid var(--line); padding-left: 28px; }
.candidate-evidence { margin: 16px 0; font-size: 13px; }
.candidate-evidence summary { cursor: pointer; }
.agent-changes :deep(.step-final-changes) { border-top: 1px solid var(--line); padding-top: 20px; }
select { max-width: 100%; min-width: 0; text-overflow: ellipsis; }
@media (max-width: 1050px) {
  .run-workspace { grid-template-columns: minmax(0, 1fr); gap: 20px; }
  .run-history { border-right: 0; padding-right: 0; }
  .run-history ul { display: flex; gap: 8px; max-height: none; }
  .run-history li { flex: 0 0 210px; }
}
@media (max-width: 640px) {
  .workbench-heading > .meta { font-size: 12px; }
  .execution-action-area { gap: 18px; align-items: stretch; }
  .jdk-field { width: 100%; }
  .start-action { width: 100%; }
  .candidate-overview { grid-template-columns: minmax(0, 1fr); gap: 24px; }
  .delivery-overview { border-left: 0; padding-left: 0; }
  .selected-step-summary { align-items: flex-start; }
  .run-times { grid-template-columns: minmax(0, 1fr); }
  .run-statistics { gap: 10px; }
  .run-statistics dd { font-size: 17px; }
}
</style>
