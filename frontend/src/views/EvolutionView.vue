<script setup lang="ts">
import { noProgressExplanation, resourcesBlocked } from "../components/agentEventPresentation";
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from "vue";
import { api } from "../api/client";
import type { AgentCandidate, AgentEvent, AgentRun, Delivery } from "../api/agentTypes";
import type { WorkspaceSession } from "../state/session";
import StatusBadge from "../components/StatusBadge.vue";
import AgentResultSurface from "../components/AgentResultSurface.vue";
import ExecutionWorkspace from "../components/ExecutionWorkspace.vue";
import AgentTimeline from "../components/AgentTimeline.vue";
import { candidateFileChanges, presentRun, runStates, verificationLabel, type PresentedFileChange, type RunPresentation } from "../components/agentResultPresentation";
import NavigationIcon from "../components/NavigationIcon.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import type { NavigationIconName } from "../components/NavigationIcon.vue";
import { stepPrerequisiteIssue } from "../state/stepPrerequisite";

const props = defineProps<{ s: WorkspaceSession }>();
const emit = defineEmits<{ planning: [] }>();
const stepId = ref("");
const step = computed(() => props.s.plan?.steps.find(item => item.id === stepId.value)
  ?? props.s.plan?.steps.find(item => item.status !== "SUCCEEDED") ?? props.s.plan?.steps.at(-1));
const runs = ref<AgentRun[]>([]);
const run = ref<AgentRun | null>(null);
const initialLayout = computed(() => !run.value && runs.value.length === 0);
const candidate = ref<AgentCandidate | null>(null);
const delivery = ref<Delivery | null>(null);
const deliveryStateLabels: Record<Delivery["state"], string> = {
  INTENT: "交付意图已保存", APPLIED: "正式差异已应用", COMMIT_READY: "交付提交已准备",
  PUBLISHED: "交付提交已发布", COMPLETED: "正式交付已完成", DELIVERY_BLOCKED: "正式交付受阻",
  RECOVERY_REQUIRED: "正式副本需要核对",
};
const events = ref<AgentEvent[]>([]);
const candidateFiles = ref<PresentedFileChange[]>([]);
const filesLoading = ref(false);
const filesUnavailable = ref(false);
let filesAttemptedId = "";
const runDeliveries = ref<Record<string, Delivery | null>>({});
const historyDeliveryError = ref(false);
let historyEpoch = 0;
const runPresentation = computed(() => run.value ? presentRun(run.value,
    run.value.candidateId ? runDeliveries.value[run.value.candidateId] : null) : null);
const completedDelivery = computed(() => run.value?.state === "SUCCEEDED" && candidate.value
    && delivery.value?.candidateId === candidate.value.id && delivery.value.state === "COMPLETED");
const officialStep = computed(() => completedDelivery.value && step.value?.id === run.value?.stepId
    && step.value?.status === "SUCCEEDED" && step.value.deliveryRevision === delivery.value?.revision);
const error = ref("");
const syncError = ref("");
const pending = ref(false);
const connection = ref("尚未连接");
const jdk = ref("");
const checkDirectories = ref("");
const mavenSettingsRelevant = computed(() => {
  const profile = props.s.repositories.find(item => item.id === props.s.plan?.baseRepositoryProfileId);
  return profile?.techStack.some(value => /\bmaven\b/i.test(value)) ?? false;
});
const javaSettingsRelevant = computed(() => {
  const profile = props.s.repositories.find(item => item.id === props.s.plan?.baseRepositoryProfileId);
  return !profile?.techStack.length || profile.techStack.some(value => /java|maven|spring|jdk/i.test(value))
    || preflight.value?.outcome === "SELECTION_REQUIRED";
});
const preflightDescriptions: Record<string, string> = {
  READY_BASELINE_FAILURE: "检查路径可执行；基线已有失败，处理情况以最终报告和执行事实为准。",
  ENVIRONMENT_BLOCKED: "检查环境尚未就绪，本次未调用模型。",
  SECURITY_BLOCKED: "源码或沙箱边界未获确认，本次未调用模型。",
  SELECTION_REQUIRED: "多个工程需要明确选择，本次未调用模型。",
};
const preflight = computed(() => {
  const summary = events.value.filter(event => event.kind === "ENVIRONMENT_PREFLIGHT").at(-1)?.summary;
  if (!summary) return null;
  const separator = summary.indexOf("\n");
  const outcome = summary.slice(0, separator);
  if (!["READY", "READY_BASELINE_FAILURE", "ENVIRONMENT_BLOCKED", "SECURITY_BLOCKED", "SELECTION_REQUIRED"].includes(outcome)) return null;
  try {
    const data = JSON.parse(summary.slice(separator + 1));
    const directories: string[] = Array.isArray(data.availableDirectories) ? data.availableDirectories
      .filter((value: unknown): value is string => typeof value === "string" && value.length <= 512).slice(0, 148) : [];
    const probes = Array.isArray(data.probes) ? data.probes.slice(0, 32).filter((value: unknown) => value && typeof value === "object") : [];
    return { outcome, directories, probes, operation: data.directory && data.goal ? `${data.directory} · ${data.goal}` : "", reason: typeof data.diagnostic === "string" ? data.diagnostic.slice(0, 1200)
      : typeof data.reason === "string" ? data.reason.slice(0, 1200) : "" };
  } catch { return null; }
});
const startReview = ref(false);
const budgetAcknowledged = ref(false);
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
const resourceBlocked = computed(() => resourcesBlocked(events.value, run.value?.id));
const dockerPreparationFailed = computed(() => run.value?.state === "FAILED" && run.value.reason === "DOCKER_FAILURE"
  && run.value.modelCalls === 0 && run.value.toolCalls === 0
  && !events.value.some(event => ["SANDBOX_READY", "RUNNING"].includes(event.kind)));
const prerequisiteIssue = computed(() => step.value?.status === "SUCCEEDED" ? "" : props.s.plan && step.value
  ? stepPrerequisiteIssue(props.s.plan, step.value, props.s.copy) : "请先读取计划与步骤。");
const canConfirm = computed(() => step.value?.status === "PENDING_CONFIRMATION" && !prerequisiteIssue.value
  && !pending.value && !props.s.busy);
const canStart = computed(() => props.s.plan?.status === "ACTIVE" && step.value?.status === "READY"
  && !prerequisiteIssue.value
  && !pending.value && !props.s.busy && (!latest.value || terminal(latest.value) && !latest.value.candidateId)
  && props.s.plan.steps.slice(0, props.s.plan.steps.indexOf(step.value)).every(item => item.status === "SUCCEEDED"));
function runIcon(presentation: RunPresentation): NavigationIconName {
    return presentation.tone === "failed" ? "warning" : presentation.tone === "blocked" ? "lock"
        : presentation.tone === "completed" ? "check" : "activity";
}
function rememberDelivery(id: string, actual: Delivery | null) {
    if (actual && actual.candidateId !== id) throw new Error("交付结果与当前候选不一致。");
    const saved = runDeliveries.value[id];
    if (saved?.state === "COMPLETED") return saved;
    runDeliveries.value = { ...runDeliveries.value, [id]: actual };
    return actual;
}
async function loadHistoryDeliveries(items: AgentRun[], planId: string, selectedStep: string, version: number) {
    const belongs = () => active && historyEpoch === version && props.s.plan?.id === planId && step.value?.id === selectedStep;
    const results = await Promise.allSettled(items.filter(item => item.state === "SUCCEEDED" && item.candidateId
        && !Object.hasOwn(runDeliveries.value, item.candidateId)).map(async item => {
        const accepted = await api.delivery(item.candidateId!);
        if (belongs() && runs.value.some(current => current.id === item.id && current.candidateId === item.candidateId)) {
            rememberDelivery(item.candidateId!, accepted);
        }
    }));
    if (belongs()) historyDeliveryError.value = results.some(result => result.status === "rejected");
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
      if (frozen.id !== actual.candidateId || frozen.runId !== actual.id) throw new Error("冻结结果与当前运行不一致。");
      if (candidate.value?.id !== frozen.id) candidateFiles.value = candidateFileChanges(frozen.changedPaths, "");
      candidate.value = frozen;
      delivery.value = rememberDelivery(frozen.id, accepted);
      if (accepted?.state === "COMPLETED" && step.value?.status !== "SUCCEEDED") await props.s.refreshPlan();
      if (belongs() && delivery.value?.state !== "COMPLETED" && filesAttemptedId !== frozen.id) void loadCandidateFiles();
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
  candidateFiles.value = [];
  filesLoading.value = false;
  filesUnavailable.value = false;
  filesAttemptedId = "";
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
    const historyVersion = historyEpoch;
    if (!older && result[0]) await selectRun(result[0]);
    if (active && plan.id === props.s.plan?.id && chosen.id === step.value?.id && historyVersion === historyEpoch) {
      void loadHistoryDeliveries(result, plan.id, chosen.id, historyVersion);
    }
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
function reviewStart() {
  if (!canStart.value) return;
  budgetAcknowledged.value = false;
  startReview.value = true;
}
async function startRun() {
  if (!canStart.value || !startReview.value || !budgetAcknowledged.value || !props.s.plan || !step.value) return;
  startReview.value = false;
  budgetAcknowledged.value = false;
  const selectedJdk = jdk.value ? Number(jdk.value) : undefined;
  const directories = mavenSettingsRelevant.value
    ? checkDirectories.value.split(/[,，\n]/).map(value => value.trim()).filter(Boolean) : [];
  await action(selected => directories.length
    ? api.startAgent(selected.planId!, selected.stepId!, true, selectedJdk, directories)
    : api.startAgent(selected.planId!, selected.stepId!, true, selectedJdk), async started => {
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
  await action(selected => api.deliver(selected.candidateId!), actual => {
    delivery.value = rememberDelivery(actual.candidateId, actual);
  });
}
async function confirmStep() {
  if (!canConfirm.value) return;
  await action(selected => props.s.confirmStep(selected.stepId!, () => current(selected)));
}
async function loadCandidateFiles() {
  const frozen = candidate.value;
  if (!frozen || filesLoading.value) return;
  const scope = selection();
  filesAttemptedId = frozen.id;
  filesLoading.value = true;
  filesUnavailable.value = false;
  try {
    // 未交付 Candidate 仍需从冻结 diff 判定文件类型；只保留分类，不展示或持有原始正文。
    const response = await fetch(`/api/agent-candidates/${encodeURIComponent(frozen.id)}/diff`, { cache: "no-store" });
    if (!current(scope)) return;
    if (!response.ok) throw new Error("Candidate file classification unavailable");
    const text = await response.text();
    if (current(scope)) candidateFiles.value = candidateFileChanges(frozen.changedPaths, text);
  } catch {
    if (current(scope)) filesUnavailable.value = true;
  } finally {
    if (current(scope)) filesLoading.value = false;
  }
}

function activate() {
  if (active) return;
  active = true;
  stepId.value = step.value?.id ?? "";
  void loadRuns();
}
function deactivate() {
  active = false; ++epoch; ++actionId; ++historyEpoch; pending.value = false; startReview.value = false;
  budgetAcknowledged.value = false; stop();
}
watch([() => props.s.plan?.id, () => step.value?.id], async () => {
  const version = ++epoch;
  ++historyEpoch;
  runDeliveries.value = {};
  historyDeliveryError.value = false;
  ++actionId;
  stop();
  pending.value = false;
  startReview.value = false;
  budgetAcknowledged.value = false;
  runs.value = [];
  checkDirectories.value = "";
  run.value = null;
  events.value = [];
  candidate.value = null;
  delivery.value = null;
  candidateFiles.value = [];
  filesLoading.value = false;
  filesUnavailable.value = false;
  filesAttemptedId = "";
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
  <div v-else class="evolution-workspace">
    <ol v-if="!initialLayout" class="execution-chain" aria-label="编码与交付边界">
      <li><span>Agent Run</span><strong>{{ run ? runStates[run.state].label : '尚未读到运行' }}</strong></li>
      <li><span>Frozen Candidate</span><strong>{{ candidate ? '已冻结编码结果' : run?.candidateId ? '冻结详情待读取' : '尚无冻结结果' }}</strong></li>
      <li><span>Formal Delivery</span><strong>{{ delivery ? deliveryStateLabels[delivery.state] : (run?.candidateId ? '交付状态未读回' : '尚无正式交付') }}</strong></li>
    </ol>
    <a v-if="activeRun" class="activity-jump" href="#agent-run-workspace">查看当前活动与停止控制 <NavigationIcon name="arrow" /></a>
    <a v-else-if="candidate" class="activity-jump" href="#agent-changes-title">{{ delivery?.state === 'DELIVERY_BLOCKED' || delivery?.state === 'RECOVERY_REQUIRED' ? '交付需核对：查看原因、证据与代码结果' : '查看冻结文件、检查证据与正式交付' }} <NavigationIcon name="arrow" /></a>
    <ExecutionWorkspace :initial="initialLayout">
    <template #activity>

    <section id="agent-run-workspace" class="panel agent-run-panel" aria-label="运行与实际活动" tabindex="-1">
      <div class="section-row"><h3>运行记录</h3><button class="secondary compact-button" :disabled="pending" @click="loadRuns()">刷新记录</button></div>
      <p v-if="!runs.length" class="workbench-empty">此步骤还没有编码记录。开始后可在此查看实际运行与活动。</p>
      <div v-else class="run-workspace">
      <details class="run-history"><summary>运行历史 · {{ runs.length }} 次已读取</summary>
        <ul><li v-for="(item, index) in runs" :key="item.id"><button class="text-button run-choice" :aria-pressed="run?.id === item.id"
          @click="selectRun(item)"><span class="run-choice-heading"><strong>{{ presentRun(item, item.candidateId ? runDeliveries[item.candidateId] : null).label }}</strong><small v-if="index === 0">最新</small></span>
            <time :datetime="item.startedAt">{{ new Date(item.startedAt).toLocaleString() }}</time>
            </button></li></ul>
        <p v-if="historyDeliveryError" class="meta" role="status">部分历史交付状态尚未读取，刷新记录可重试。</p>
        <button v-if="runs.length >= 50" class="secondary" @click="loadRuns(true)">读取更早的运行</button>
      </details>
      <div class="selected-run">
      <div v-if="activeRun" class="monitor-stop" aria-label="当前运行控制">
        <div><strong>当前步骤正在运行</strong><p v-if="run?.id !== activeRun.id" class="meta">正在查看历史记录；停止操作针对本步骤当前运行。</p>
          <p id="run-stop-status" class="meta" role="status">{{ stopPending ? '停止请求发送中' : stopConfirmed ? '停止请求已接受，正在等待实际终态；模型或沙箱可能不会立即返回。' : '可停止当前 Run；不支持暂停或续跑。' }}</p>
          <p v-if="stopError" role="alert" class="error">{{ stopError }}</p></div>
        <button class="stop-run compact-button" :disabled="stopPending || stopConfirmed" :aria-busy="stopPending || stopConfirmed"
          aria-describedby="run-stop-status" @click="cancelRun"><NavigationIcon name="stop" />{{ stopPending || stopConfirmed ? '正在停止' : '停止运行' }}</button>
      </div>
      <template v-if="run">
        <AgentTimeline :key="run.id" :events="events" :run="run" :connection="connection" :presentation="runPresentation ?? undefined" :show-usage="false">
          <template #developer>
        <p v-if="run.reason" class="run-reason"><span>原因</span><code>{{ run.reason }}</code></p>
        <dl class="run-statistics" aria-label="已保存运行统计">
          <div><dt>模型调用</dt><dd>{{ numberText(run.modelCalls) }} <span>次</span></dd></div>
          <div><dt>工具调用</dt><dd>{{ numberText(run.toolCalls) }} <span>次</span></dd></div>
          <div><dt>累计 tokens</dt><dd>{{ run.tokens === null ? '未报告' : numberText(run.tokens) }}</dd></div>
        </dl>
        <div v-if="preflight && (preflight.outcome !== 'READY' || preflight.directories.length || preflight.probes.length || preflight.operation)" class="preflight-summary">
          <p v-if="preflight.outcome !== 'READY'">{{ preflightDescriptions[preflight.outcome] }}</p>
          <p v-if="preflight.directories.length" class="meta">可选检查入口：<template v-for="(directory, index) in preflight.directories" :key="directory"><span v-if="index">、</span><code>{{ directory }}</code></template></p>
          <ul v-if="preflight.probes.length"><li v-for="(probe, index) in preflight.probes" :key="index">
            {{ probe.directory }} · {{ probe.goal }}：{{ probe.readiness === 'NOT_DECLARED' ? '未声明此项检查' : verificationLabel(probe.classification) }}
            <p v-if="probe.readinessReason" class="meta">{{ probe.readinessReason }}</p>
            <p v-if="probe.missingArtifacts?.length" class="meta">缺失工件：{{ probe.missingArtifacts.join("、") }}</p>
            <details v-if="probe.output"><summary>检查诊断</summary><pre>{{ probe.output }}</pre></details>
          </li></ul>
          <p v-if="preflight.operation" class="meta">最后检查：{{ preflight.operation }}</p>
          <p v-if="preflight.outcome !== 'READY' && preflight.reason" class="meta">{{ preflight.reason }}</p>
        </div>
          </template>
        </AgentTimeline>
        <div v-if="terminal(run) && runPresentation?.tone !== 'completed'" class="run-summary" :data-state="run.state">
        <div v-if="runPresentation && runPresentation.label !== runStates[run.state].label" class="section-row run-status-row"><h3 class="run-status" :data-state="run.state" :data-tone="runPresentation.tone" role="status"><NavigationIcon :name="runIcon(runPresentation)" />{{ runPresentation.label }}</h3>
          </div>
        <p v-if="dockerPreparationFailed" class="run-failure">Docker 沙箱准备失败，尚未开始编码；模型与工具调用均为 0。</p>
        <p v-else-if="['FAILED', 'CANCELLED', 'INTERRUPTED'].includes(run.state)" class="meta">本次编码未交付。当前步骤仍保留确认；新运行仍须通过执行前核对。</p>
        <p v-else-if="runPresentation" class="meta">{{ runPresentation.detail }}</p>
        <p v-if="run.reason === 'SANDBOX_PROTOCOL_INCOMPATIBLE'" class="run-failure" role="alert">
          沙箱工具协议不兼容。请按 docs/LOCAL_SETUP.md 的协议排障说明构建兼容镜像，显式配置镜像后再重启应用。
        </p>
        <p v-if="run.reason === 'NO_PROGRESS'" class="run-failure" role="alert">{{ noProgressExplanation(events, run.id) }}</p>

        </div>
        <p v-if="preflight && runPresentation?.tone !== 'completed' && (run.state !== 'RUNNING' || preflight.outcome !== 'READY')" class="preflight-status" :data-ready="preflight.outcome.startsWith('READY')" role="status">{{ preflight.outcome.startsWith('READY') ? '运行环境已准备' : preflightDescriptions[preflight.outcome] }}<span v-if="preflight.outcome === 'READY_BASELINE_FAILURE'"> · 基线检查有失败，详见开发者详情。</span><span v-else-if="!preflight.outcome.startsWith('READY') && preflight.reason"> {{ preflight.reason.slice(0, 240) }}</span></p>
        <p v-if="resourceBlocked" class="resource-warning" role="alert"><NavigationIcon name="warning" />资源协调待核对：所属资源或保留材料尚未完成核对。请先检查 Docker 与后端安全诊断，再判断能否重新运行。</p>
        <p v-if="run.retained && terminal(run)" class="meta">最终化材料已保留，请核对失败原因。</p>
      </template>
      </div></div>
    </section>
    </template>
    <template #controls>
    <section class="panel agent-controls" aria-labelledby="agent-controls-title">
      <div class="workbench-heading"><div><h2 id="agent-controls-title">{{ initialLayout ? '准备执行当前步骤' : '步骤与运行配置' }}</h2></div>
        <span class="meta"><NavigationIcon name="lock" />隔离编码 · 逐步授权</span></div>

      <label for="agent-step">当前步骤</label>
      <div class="step-configuration">
      <select id="agent-step" :value="step?.id" @change="stepId = ($event.target as HTMLSelectElement).value">
        <option v-for="(item, index) in s.plan.steps" :key="item.id" :value="item.id">Step {{ String(index + 1).padStart(2, '0') }} · {{ item.goal }}</option>
      </select>
      <template v-if="step">
        <div class="selected-step-summary"><StatusBadge :status="step.status" :show-code="false" /><p>{{ step.scope }}</p></div>
        <details class="planned-changes"><summary>计划变更 · {{ step.plannedChanges.length }} 项</summary>
          <ul class="planned-change-list"><li v-for="(change, index) in step.plannedChanges" :key="index">{{ change }}</li></ul></details>
      </template></div>
      <template v-if="step">
        <div class="execution-action-area">
        <div v-if="prerequisiteIssue" class="step-lock-reason">
          <button class="text-button" @click="emit('planning')">前往演化规划核对准备与授权</button>
          <p>{{ prerequisiteIssue }}</p>
        </div>
        <button v-if="step.status === 'PENDING_CONFIRMATION' && !prerequisiteIssue" :disabled="!canConfirm"
          @click="confirmStep">确认此步骤</button>
        <template v-if="step.status === 'READY'">
          <details v-if="javaSettingsRelevant" class="advanced-run-settings"><summary>高级设置</summary>
          <div class="jdk-field"><label for="agent-jdk">目标项目构建 JDK（Agent 沙箱）</label>
            <select id="agent-jdk" v-model="jdk"><option value="">自动选择 JDK</option><option value="17">JDK 17</option><option value="21">JDK 21</option></select></div>
          <div v-if="mavenSettingsRelevant" class="check-directories-field"><label for="agent-check-directories">Maven 工程根目录覆盖（可选）</label>
            <input id="agent-check-directories" v-model="checkDirectories" maxlength="2000" placeholder="例如：backend/bookstore" aria-describedby="agent-check-directories-help" />
            <p id="agent-check-directories-help" class="meta">仅影响 Maven / JDK 选择，不影响 Agent 仓库探索或修改授权。留空自动选择；多项用逗号分隔，根目录填“.”。</p></div>
          </details>
          <div class="start-action"><button :disabled="!canStart" @click="reviewStart">{{ latest ? '重新开始编码' : '开始 Agent' }}</button>
</div>
        </template>
        </div>
      </template>

      <p v-if="error || syncError" role="alert" class="error">{{ error || syncError }}</p>
    </section>
    </template>
    </ExecutionWorkspace>
    <AgentResultSurface :plan-id="s.plan.id" :step="step" :run="run" :candidate="candidate"
      :delivery="delivery" :run-presentation="runPresentation" :official-step="!!officialStep"
      :completed-delivery="!!completedDelivery" :candidate-files="candidateFiles" :files-loading="filesLoading"
      :files-unavailable="filesUnavailable" :pending="pending" @deliver="deliverCandidate" />
  </div>
  <ConfirmDialog :open="startReview" title="确认 API 费用与预算" confirm-label="确认费用并开始"
    :disabled="!budgetAcknowledged || !canStart" @close="startReview = false; budgetAcknowledged = false" @confirm="startRun">
    <p>Agent 会调用已配置的外部模型 API，发送当前步骤目标和所选仓库材料，并持续编码、测试至可验证交付。</p>
    <p>系统先检查隔离执行能力，并如实报告网络状态；依赖网络不可用时仍可执行离线任务，下载需要由实际命令确认。</p>
    <p>API 调用会产生费用，实际费用以服务商账单为准。累计调用、tokens 与总运行时长没有自动停止门槛；取消不能撤回已发生的费用。</p>
    <p>所选仓库内容将提供给模型和隔离命令，也可能出现在日志中。请勿包含不愿暴露的有效凭据；日志脱敏不能保证任意命令的保密性。</p>
    <label class="budget-acknowledgment"><input v-model="budgetAcknowledged" type="checkbox" />我确认有足够的 API 预算，并愿意承担本次运行的实际费用。</label>
  </ConfirmDialog>
</template>

<style scoped>
.advanced-run-settings { width: 100%; }
.advanced-run-settings summary { cursor: pointer; font-size: 14px; }
.advanced-run-settings .jdk-field { width: 100%; max-width: 360px; margin: 16px 0; }
.preflight-status { font-size: 14px; color: var(--warning); }
.preflight-status[data-ready="true"] { color: var(--muted); }
.budget-acknowledgment { display: flex; align-items: flex-start; gap: 10px; margin-top: 16px; }
.budget-acknowledgment input { flex: 0 0 auto; margin-top: 4px; }
.check-directories-field { flex: 1 1 280px; max-width: 560px; min-width: 0; }
.check-directories-field label { display: block; font-size: 14px; font-weight: 600; margin-bottom: 8px; }
.check-directories-field input { width: 100%; }
.preflight-summary { margin-top: 20px; overflow-wrap: anywhere; }
.workbench-heading, .workbench-heading > .meta { display: flex; align-items: center; gap: 10px; }
.workbench-heading { justify-content: space-between; flex-wrap: wrap; }
.workbench-heading h2 { margin: 0; font-size: 19px; }
.workbench-eyebrow { font-size: 12px; letter-spacing: .06em; color: var(--muted); margin: 0 0 6px; }
.workbench-intro { font-size: 14px; line-height: 1.65; margin: 12px 0 22px; }
.step-configuration { max-width: 780px; }
.agent-controls > label[for="agent-step"], .jdk-field label { display: block; font-size: 14px; font-weight: 600; margin-bottom: 8px; }
.agent-controls > label[for="agent-step"] { grid-column: 1 / -1; margin-top: 16px; }
.execution-action-area .step-lock-reason { margin: 0; }
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
.run-history ul { list-style: none; padding: 0; margin: 10px 0; }
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
.monitor-stop .stop-run { display: inline-flex; align-items: center; justify-content: center; gap: 8px; color: var(--danger); border-color: var(--danger); background: var(--danger-bg); }
.monitor-stop .stop-run .navigation-icon { width: 16px; height: 16px; }
.monitor-stop .stop-run:hover:not(:disabled) { background: var(--danger-bg); box-shadow: inset 0 0 0 1px var(--danger); }
.monitor-stop .stop-run:focus-visible { outline: 2px solid var(--danger); outline-offset: 3px; }
.monitor-stop .stop-run:disabled { opacity: .65; }
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
.run-status[data-state="FAILED"] { color: var(--danger); }
.run-status[data-state="INTERRUPTED"], .run-status[data-state="CANCELLED"] { color: var(--muted); }
.run-status[data-tone="recorded"] { color: var(--muted); }
.run-failure { color: var(--danger); font-size: 14px; line-height: 1.65; }
.resource-warning { display: flex; align-items: flex-start; gap: 10px; color: var(--warning); border-left: 2px solid var(--warning); padding: 8px 12px; font-size: 14px; line-height: 1.65; }
.resource-warning .navigation-icon { flex-shrink: 0; }
.workbench-empty { font-size: 14px; line-height: 1.65; color: var(--muted); padding: 12px 0; }
.candidate-overview { min-width: 0; margin: 20px 0; }
.candidate-overview h4 { font-size: 15px; margin: 0 0 12px; }
.candidate-overview p { font-size: 14px; }
.candidate-evidence { margin: 16px 0; font-size: 13px; }
.agent-changes :deep(.step-final-changes) { border-top: 0; padding-top: 0; }
select { max-width: 100%; min-width: 0; text-overflow: ellipsis; }
@media (max-width: 1050px) {
  .run-workspace { grid-template-columns: minmax(0, 1fr); gap: 20px; }
  .run-history { border-right: 0; padding-right: 0; }
  .run-history ul { display: flex; gap: 8px; overflow-x: auto; }
  .run-history li { flex: 0 0 210px; }
}
@media (max-width: 640px) {
  .workbench-heading > .meta { font-size: 12px; }
  .execution-action-area { gap: 18px; align-items: stretch; }
  .jdk-field { width: 100%; }
  .start-action { width: 100%; }
  .selected-step-summary { align-items: flex-start; }
  .run-statistics { gap: 10px; }
  .run-statistics dd { font-size: 17px; }
}
.run-status[data-tone="blocked"] { color: var(--warning); }
.run-status[data-tone="completed"] { color: var(--success); }
</style>
