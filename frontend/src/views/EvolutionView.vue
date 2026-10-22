<script setup lang="ts">
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from "vue";
import { api } from "../api/client";
import type { AgentCandidate, AgentEvent, AgentRun, Delivery } from "../api/agentTypes";
import type { WorkspaceSession } from "../state/session";
import ContentList from "../components/ContentList.vue";
import RevisionText from "../components/RevisionText.vue";
import StatusBadge from "../components/StatusBadge.vue";
import StepFinalChanges from "../components/StepFinalChanges.vue";

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
const canStart = computed(() => props.s.plan?.status === "ACTIVE" && step.value?.status === "READY"
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
    const actual = await api.agentRun(selected.id);
    if (!belongs()) return;
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
  run.value = selected;
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
    const result = await api.agentRuns(plan.id, chosen.id, older ? runs.value.at(-1)?.id : undefined);
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
  if (!run.value) return;
  await action(selected => api.cancelAgent(selected.runId!), actual => { run.value = actual; });
}
async function deliverCandidate() {
  if (!candidate.value) return;
  await action(selected => api.deliver(selected.candidateId!), actual => { delivery.value = actual; });
}
async function confirmStep() {
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
function summary(event: AgentEvent) {
  try {
    const value = JSON.parse(event.summary) as Record<string, unknown>;
    return Object.entries(value).filter(([, entry]) => entry !== "" && entry !== null)
      .map(([key, entry]) => `${key}: ${typeof entry === "object" ? JSON.stringify(entry) : entry}`).join(" · ");
  } catch { return event.summary; }
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
    <section class="panel">
      <h2>实施演化步骤</h2>
      <p>逐步授权，Agent 在隔离沙箱中编码与检查。冻结结果通过校验后自动交付，后续步骤需分别确认。</p>
      <label for="agent-step">当前步骤</label>
      <select id="agent-step" :value="step?.id" @change="stepId = ($event.target as HTMLSelectElement).value">
        <option v-for="item in s.plan.steps" :key="item.id" :value="item.id">{{ item.goal }} · {{ item.status }}</option>
      </select>
      <template v-if="step">
        <h3>{{ step.goal }}</h3><StatusBadge :status="step.status" /><p>{{ step.scope }}</p>
        <ContentList title="计划变更" :items="step.plannedChanges" />
        <button v-if="step.status === 'PENDING_CONFIRMATION'" :disabled="pending || !!s.busy"
          @click="confirmStep">确认此步骤</button>
        <template v-if="step.status === 'READY'">
          <label for="agent-jdk">运行环境</label>
          <select id="agent-jdk" v-model="jdk"><option value="">自动选择 JDK</option><option value="17">JDK 17</option><option value="21">JDK 21</option></select>
          <button :disabled="!canStart" @click="startRun">{{ latest ? '重新开始编码' : '开始 Agent' }}</button>
        </template>
      </template>
      <p v-if="error || syncError" role="alert" class="error">{{ error || syncError }}</p>
    </section>
    <section class="panel">
      <h3>运行记录</h3><button :disabled="pending" @click="loadRuns()">刷新记录</button>
      <p v-if="!runs.length">此步骤还没有编码记录。</p>
      <ul><li v-for="item in runs" :key="item.id"><button class="text-button" :aria-pressed="run?.id === item.id"
        @click="selectRun(item)">{{ new Date(item.startedAt).toLocaleString() }} · {{ label(item.state) }}</button></li></ul>
      <button v-if="runs.length >= 50" @click="loadRuns(true)">读取更早的运行</button>
      <template v-if="run">
        <p>Agent：{{ label(run.state) }} <span v-if="run.reason">· {{ run.reason }}</span></p>
        <p class="meta">模型调用 {{ run.modelCalls }} 次 · 工具调用 {{ run.toolCalls }} 次<span v-if="run.tokens !== null"> · {{ run.tokens }} tokens</span></p>
        <RevisionText label="起点" :value="run.baseline" />
        <button v-if="!terminal(run)" :disabled="pending || run.cancelRequested"
          @click="cancelRun">{{ run.cancelRequested ? '正在取消' : '取消运行' }}</button>
        <p v-if="['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(run.state)">本次编码未交付。当前步骤仍保留确认，可从可信版本启动全新的运行。</p>
        <p v-if="run.retained && terminal(run)">最终化材料已保留，请核对失败原因。</p>
      </template>
    </section>
    <section v-if="candidate" class="panel">
      <h3>冻结结果与交付</h3>
      <p>Candidate 已冻结 · {{ candidate.diffBytes }} bytes</p>
      <p>Delivery：{{ delivery ? label(delivery.state) : '等待自动交付' }}</p>
      <p v-if="delivery?.reason">{{ delivery.reason === 'MUTATION_NOT_STARTED'
        ? '本次未写入正式副本。请核对外部文件或运行环境，处理后可重新交付已有结果。' : delivery.reason }}</p>
      <p v-if="delivery?.state === 'RECOVERY_REQUIRED'" role="alert">正式副本状态无法证明安全，已保留现场并阻止后续执行。请核对文件、Git 与交付日志。</p>
      <RevisionText v-if="delivery?.revision" label="正式提交" :value="delivery.revision" />
      <button v-if="!delivery || ['DELIVERY_BLOCKED', 'PUBLISHED', 'COMMIT_READY', 'APPLIED'].includes(delivery.state)
        || delivery.state === 'RECOVERY_REQUIRED' && delivery.reason === 'MUTATION_NOT_STARTED'" :disabled="pending"
        @click="deliverCandidate">重新核对并交付已有结果</button>
      <ContentList title="变化文件" :items="candidate.changedPaths" />
      <details><summary>实际检查证据</summary><pre class="agent-output">{{ candidate.testEvidence }}</pre></details>
      <button :disabled="pending" @click="loadDiff">读取最终差异</button>
      <pre v-if="diff" class="agent-output">{{ diff }}</pre>
      <StepFinalChanges v-if="delivery?.state === 'COMPLETED' && step && step.id === run?.stepId"
        :plan-id="s.plan.id" :step="step" />
      <p>检查证据保留实际范围与 NOT_TESTED 状态；交付完成表示代码一致性已接受。</p>
    </section>
    <section v-if="run" class="panel">
      <h3>实际活动时间线</h3><p role="status">{{ connection }}</p>
      <p v-if="!events.length">等待持久化事件。</p>
      <ol class="agent-timeline"><li v-for="event in events" :key="event.sequence">
        <time :datetime="event.at">{{ new Date(event.at).toLocaleTimeString() }}</time>
        <strong>{{ label(event.kind) }}</strong><p>{{ summary(event) }}</p>
      </li></ol>
      <p v-if="events.length === 1000" class="meta">显示最近 1000 条事件。</p>
    </section>
  </template>
</template>

<style scoped>
.agent-output { max-height: 32rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; }
.agent-timeline { max-height: 38rem; overflow: auto; padding-left: 1.5rem; }
.agent-timeline li { padding: .7rem 0; border-bottom: 1px solid var(--border, #ddd); overflow-wrap: anywhere; }
.agent-timeline time { margin-right: .7rem; font-variant-numeric: tabular-nums; }
select { max-width: 100%; }
</style>
