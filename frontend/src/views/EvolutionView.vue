<script setup lang="ts">
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from "vue";
import type { WorkspaceSession } from "../state/session";
import type { EvolutionStep, VerificationResult } from "../api/types";
import StatusBadge from "../components/StatusBadge.vue";
import StepFinalChanges from "../components/StepFinalChanges.vue";
import StepEvolutionTrace from "../components/StepEvolutionTrace.vue";
import ContentList from "../components/ContentList.vue";
import RevisionText from "../components/RevisionText.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import StepPrerequisites from "../components/StepPrerequisites.vue";
import NavigationIcon from "../components/NavigationIcon.vue";
const props = defineProps<{ s: WorkspaceSession }>();
const retryOpen = ref(false);
const retryAcknowledged = ref(false);
const retryFailureId = ref("");
const retryBaseline = ref("");
const repairOpen = ref(false);
const repairAcknowledged = ref(false);
const repairCandidate = ref("");
const rollbackOpen = ref(false);
const rollbackAcknowledged = ref(false);
const rollbackCandidate = ref("");
const rollbackBaseline = ref("");
const emit = defineEmits<{ planning: [] }>();
const now = ref(Date.now());
const pollingPaused = ref(false);
let active = false;
let clock: ReturnType<typeof setInterval> | undefined;
const request = computed(() => props.s.executionPending ? props.s.executionRequest
  : props.s.receipt?.planId === props.s.plan?.id ? props.s.receipt : null);
const requestedStep = computed(() => props.s.plan && props.s.plan.id === request.value?.planId
  ? props.s.plan.steps.find(step => step.id === request.value?.stepId) : undefined);
const elapsed = computed(() => {
  const seconds = Math.max(0, Math.floor((now.value - Date.parse(request.value?.startedAt ?? "")) / 1_000));
  if (!Number.isFinite(seconds)) return "未知";
  return seconds < 60 ? `${seconds} 秒` : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
});
function updateClock() {
  clearInterval(clock);
  now.value = Date.now();
  if (active && props.s.executionPending) clock = setInterval(() => { now.value = Date.now(); }, 1_000);
}
function activate() {
  active = true;
  updateClock();
  props.s.observeExecution(!pollingPaused.value);
}
function deactivate() {
  active = false;
  clearInterval(clock);
  props.s.observeExecution(false);
}
function togglePolling() {
  pollingPaused.value = !pollingPaused.value;
  props.s.observeExecution(active && !pollingPaused.value);
}
onMounted(activate);
onActivated(activate);
onDeactivated(deactivate);
onUnmounted(deactivate);
watch(() => props.s.executionPending, updateClock);
const selectedId = ref("");
const chosen = computed(
  () =>
    props.s.plan?.steps.find((step) => step.id === selectedId.value) ??
    props.s.plan?.steps.find((step) => step.status !== "SUCCEEDED") ??
    props.s.plan?.steps.at(-1),
);
async function viewStep(id: string) {
  selectedId.value = id;
  await nextTick();
  if (window.innerWidth <= 960) document.getElementById("execution-detail")?.focus();
}
const tabs = [
  { id: "current", label: "当前状态" },
  { id: "changes", label: "最终变更" },
  { id: "trace", label: "演化轨迹" },
  { id: "verification", label: "验证详情" },
  { id: "history", label: "完整历史" },
] as const;
type StepTab = typeof tabs[number]["id"];
const selectedTab = ref<StepTab>("current");
const tabPanel = ref<HTMLElement | null>(null);
async function selectTab(id: StepTab) {
  selectedTab.value = id;
  await nextTick();
  if (tabPanel.value) tabPanel.value.scrollTop = 0;
}
function navigateTabs(event: KeyboardEvent, index: number) {
  let destination: number;
  if (event.key === "ArrowRight") destination = (index + 1) % tabs.length;
  else if (event.key === "ArrowLeft") destination = (index + tabs.length - 1) % tabs.length;
  else if (event.key === "Home") destination = 0;
  else if (event.key === "End") destination = tabs.length - 1;
  else return;
  event.preventDefault();
  const tab = tabs[destination]!;
  void selectTab(tab.id);
  document.getElementById(`step-tab-${tab.id}`)?.focus();
}
// 只在对象身份变化时重置；同一 Step 的只读刷新保留当前栏目。
watch([() => props.s.plan?.id, () => chosen.value?.id], () => {
  void selectTab("current");
});
const executeOpen = ref(false);
const acknowledged = ref(false);
const dialogStep = ref<EvolutionStep | null>(null);
watch(() => props.s.plan?.id, () => {
  selectedId.value = "";
  executeOpen.value = false;
  repairOpen.value = false;
  rollbackOpen.value = false;
  retryOpen.value = false;
  retryAcknowledged.value = false;
  acknowledged.value = false;
  dialogStep.value = null;
});
const canExecute = computed(() => {
  const plan = props.s.plan;
  const step = chosen.value;
  const copy = props.s.copy;
  return (
    !!plan &&
    !!step &&
    !!copy &&
    plan.status === "ACTIVE" &&
    step.status === "READY" &&
    ["READY", "EVOLVING"].includes(copy.status) &&
    copy.currentRevision === copy.lastVerifiedRevision &&
    !props.s.busy &&
    !props.s.receipt &&
    plan.steps
      .slice(0, plan.steps.indexOf(step))
      .every((item) => item.status === "SUCCEEDED")
  );
});
const actionReason = computed(() => {
  const step = chosen.value;
  const plan = props.s.plan;
  const copy = props.s.copy;
  if (props.s.receipt || props.s.executionPending) return "已有执行请求待核对。先查询真实状态，不能重复提交。";
  if (step?.status === "SUCCEEDED") return "此步骤已验证通过，不再执行；后续步骤仍需分别授权。";
  if (step?.status === "EXECUTING") return "后端记录正在执行。可以观察状态，不能再次执行。";
  if (step?.status === "VERIFYING") return "后端记录正在验证。Candidate 尚不能视为验证通过。";
  if (step?.status === "VERIFICATION_FAILED") return "验证已明确失败，Candidate 保留，最近可信版本未推进。可单独授权修复或回滚，后续步骤继续阻止。";
  if (step?.status === "ROLLING_BACK") return "已有回滚检查点，尚无最终恢复结果。只能查询，不能重放或推断原请求已经停止。";
  if (step?.status === "FAILED") return "此步骤未成功，回滚已确认恢复原始基线。可独立授权完整重试，历史记录继续保留。";
  if (step?.status === "READY" && step.retryAuthorizations?.length) return "本次重试已独立授权，尚未执行。审阅风险并点击执行后才会调用 AI 和修改副本。";
  if (step?.status === "RECOVERY_REQUIRED") return "回滚未能确认完整恢复，隔离副本已阻塞。本版没有 Recovery 入口。";
  if (step?.status === "CANCELLED") return "此步骤已取消，没有执行入口。";
  if (plan?.status === "SUPERSEDED") return "此计划已替代，仅供历史查看，不能继续授权或执行。";
  if (plan?.status !== "ACTIVE") return "执行需要进行中的计划。请在规划页核对当前生命周期。";
  if (step?.status === "PENDING_CONFIRMATION") return "当前步骤尚未授权。先在规划页审阅范围并明确确认。";
  if (step?.status !== "READY") return "当前步骤状态没有执行入口。失败与恢复状态需保留证据，本版没有恢复操作。";
  if (!copy) return "尚未创建或读取隔离副本。请回到规划页核对。";
  if (!["READY", "EVOLVING"].includes(copy.status)) return "当前隔离副本状态不允许执行；本版没有恢复操作。";
  if (copy.currentRevision !== copy.lastVerifiedRevision) return "当前 revision 与最近验证 revision 不一致，禁止继续执行。";
  if (plan.steps.slice(0, plan.steps.indexOf(step)).some(item => item.status !== "SUCCEEDED")) return "前序步骤尚未全部验证通过，不能执行此步骤。";
  if (props.s.busy) return "已有请求处理中，请等待实际响应。";
  return "当前记录满足页面执行条件。继续前需单独确认费用、范围与宿主风险，后端仍会校验。";
});
const recoveryBlocked = computed(() =>
  !!chosen.value && ["FAILED", "VERIFICATION_FAILED", "RECOVERY_REQUIRED", "ROLLING_BACK"].includes(chosen.value.status)
  || !!props.s.copy && ["FAILED", "RECOVERY_REQUIRED", "UNRECOVERABLE"].includes(props.s.copy.status));
const canRepair = computed(() => {
  const step = chosen.value;
  const copy = props.s.copy;
  const plan = props.s.plan;
  return !!step && !!copy && !!plan && plan.status === "ACTIVE" && step.status === "VERIFICATION_FAILED"
    && copy.status === "EVOLVING" && step.executionResult?.candidateRevision === copy.currentRevision
    && step.verificationResult?.candidateRevision === copy.currentRevision && step.verificationResult.passed === false
    && copy.lastVerifiedRevision === step.baselineRevision && !props.s.busy && !props.s.receipt
    && plan.steps.slice(0, plan.steps.indexOf(step)).every(item => item.status === "SUCCEEDED");
});
const canRetry = computed(() => {
  const step = chosen.value, copy = props.s.copy, plan = props.s.plan;
  return !!step && !!copy && !!plan && plan.status === "ACTIVE" && step.status === "FAILED"
    && copy.status === "EVOLVING" && step.baselineRevision === copy.currentRevision
    && copy.currentRevision === copy.lastVerifiedRevision
    && !!(step.executionFailureResult?.id ?? step.rollbackAuthorization?.id)
    && !!(step.automaticRollbackResult?.restored || step.rollbackResult?.restored)
    && !props.s.busy && !props.s.receipt
    && plan.steps.slice(0, plan.steps.indexOf(step)).every(item => item.status === "SUCCEEDED");
});
function openRetry() {
  dialogStep.value = chosen.value ?? null;
  retryAcknowledged.value = false;
  retryFailureId.value = chosen.value?.executionFailureResult?.id ?? chosen.value?.rollbackAuthorization?.id ?? "";
  retryBaseline.value = chosen.value?.baselineRevision ?? "";
  retryOpen.value = true;
}
function retry() {
  if (!dialogStep.value || !retryAcknowledged.value || !canRetry.value
    || dialogStep.value.id !== chosen.value?.id || retryBaseline.value !== chosen.value?.baselineRevision
    || retryFailureId.value !== (chosen.value?.executionFailureResult?.id ?? chosen.value?.rollbackAuthorization?.id)) return;
  retryOpen.value = false;
  props.s.retry(dialogStep.value.id, retryFailureId.value, retryBaseline.value);
}
function openRepair() {
  dialogStep.value = chosen.value ?? null;
  repairCandidate.value = props.s.copy?.currentRevision ?? "";
  repairAcknowledged.value = false;
  repairOpen.value = true;
}
function openRollback() {
  dialogStep.value = chosen.value ?? null;
  rollbackCandidate.value = props.s.copy?.currentRevision ?? "";
  rollbackBaseline.value = chosen.value?.baselineRevision ?? "";
  rollbackAcknowledged.value = false;
  rollbackOpen.value = true;
}
function rollback() {
  if (!dialogStep.value || !rollbackAcknowledged.value || !canRepair.value
    || dialogStep.value.id !== chosen.value?.id || rollbackCandidate.value !== props.s.copy?.currentRevision
    || rollbackBaseline.value !== chosen.value?.baselineRevision) return;
  rollbackOpen.value = false;
  const retryId = dialogStep.value.retryAuthorizations?.at(-1)?.id;
  if (retryId) props.s.rollback(dialogStep.value.id, rollbackCandidate.value, rollbackBaseline.value, retryId);
  else props.s.rollback(dialogStep.value.id, rollbackCandidate.value, rollbackBaseline.value);
}
function repair() {
  if (!dialogStep.value || !repairAcknowledged.value || !canRepair.value
    || dialogStep.value.id !== chosen.value?.id || repairCandidate.value !== props.s.copy?.currentRevision) return;
  repairOpen.value = false;
  const retryId = dialogStep.value.retryAuthorizations?.at(-1)?.id;
  if (retryId) props.s.repair(dialogStep.value.id, repairCandidate.value, retryId);
  else props.s.repair(dialogStep.value.id, repairCandidate.value);
}
function openExecute() {
  dialogStep.value = chosen.value ?? null;
  acknowledged.value = false;
  executeOpen.value = true;
}
function execute() {
  if (!dialogStep.value || !acknowledged.value || !canExecute.value) return;
  executeOpen.value = false;
  const retryId = dialogStep.value.retryAuthorizations?.at(-1)?.id;
  if (retryId) props.s.execute(dialogStep.value.id, retryId);
  else props.s.execute(dialogStep.value.id);
}
function checkOutcome(check: VerificationResult["checks"][number]) {
  if (check.outcome === "NOT_RUN") return "未执行";
  if (check.outcome === "INCONCLUSIVE") return "结果未确认";
  return check.passed ? "PASS" : "FAIL";
}
</script>
<template>
  <div v-if="!s.plan" class="panel empty">
    <template v-if="s.receipt">
      <h2>执行状态尚未读取</h2>
      <p>此浏览器保留了执行请求标记，结果未知。只查询真实状态，不重复提交 execute。</p>
      <button class="secondary" @click="s.refreshPlan().catch(() => { s.pollError = '无法读取执行状态，请检查连接。'; })">查询当前状态</button>
      <p v-if="s.pollError" role="alert" class="field-error">{{ s.pollError }}</p>
    </template>
    <template v-else><h2>尚无演化计划</h2><p>先生成计划、准备隔离副本，并明确授权 Step。</p>
      <button class="secondary" @click="emit('planning')">前往演化规划</button></template>
  </div>
  <template v-else>
    <section class="panel execution-context">
      <div class="panel-header">
        <div>
          <h2>隔离副本与实际状态</h2>
        </div>
        <button
          class="secondary"
          :disabled="!!s.busy && !s.executionPending"
          @click="
            s.refreshPlan().catch(() => {
              s.pollError = '无法读取当前状态，请检查连接。';
            })
          "
        >
          查询当前状态
        </button>
      </div>
      <div
        v-if="s.receipt || s.executionPending"
        class="execution-notice"
      >
        <strong role="status">{{
          request?.operation === "RETRY"
            ? (s.executionPending ? "重试授权请求已提交，正在等待确认" : "重试授权结果待核对，仅查询状态")
            : s.executionPending ? "执行请求已提交，正在等待后端结果" : "执行结果待核对，仅查询状态"
        }}</strong>
        <p v-if="s.executionPending" class="waiting-duration" role="timer" aria-live="off">前端已等待 {{ elapsed }}。这不是后台执行进度。</p>
        <p>
          {{ pollingPaused ? "自动刷新已暂停，可手动查询。" : "本页约每 3 秒只读查询持久化状态。" }}
          请求可能持续数分钟，请勿重复提交。READY 不能证明后台准备已结束。
        </p>
        <p v-if="request" class="meta">
          请求开始：{{ new Date(request.startedAt).toLocaleString("zh-CN") }} · Step <code>{{ request.stepId }}</code>
        </p>
        <div class="observed-execution">
          <p>后端最近确认：<StatusBadge v-if="requestedStep" :status="requestedStep.status" /><span v-else>尚未读取到请求对应的 Step</span></p>
          <RevisionText v-if="requestedStep?.executionResult" label="已保存的 Candidate" :value="requestedStep.executionResult.candidateRevision" />
          <p v-for="(check, index) in requestedStep?.verificationResult?.checks ?? []" :key="index">
            {{ check.action }}{{ check.target ? ` · ${check.target}` : '' }}：{{ checkOutcome(check) }}
          </p>
        </div>
        <button class="secondary" type="button" @click="togglePolling">{{ pollingPaused ? '恢复只读刷新' : '暂停状态刷新' }}</button>
        <p v-if="!s.executionPending">
          此浏览器保留等待标记，再次执行已禁用。若状态一直不变化，需在宿主核对原请求是否结束；当前
          API 没有请求任务查询或恢复入口。
        </p>
      </div>
      <p v-if="s.pollError" role="alert" class="field-error">
        {{ s.pollError }}
      </p>
      <p v-if="s.lastChecked" class="meta">
        最近成功读取：{{ s.lastChecked }} · 不代表 Git 工作目录的客户端检查
      </p>
      <div v-if="s.copy" class="working-copy">
        <div class="panel-header">
          <h3 class="section-heading"><NavigationIcon name="repository" />WorkingCopy <span class="meta">隔离副本</span></h3>
          <StatusBadge :status="s.copy.status" />
        </div>
        <p class="copy-summary meta"><NavigationIcon name="git" />当前 {{ s.copy.currentRevision.slice(0, 12) }}
          <span>最近验证 {{ s.copy.lastVerifiedRevision.slice(0, 12) }}</span></p>
        <details class="copy-details"><summary>路径、完整 revision 与资产绑定</summary>
        <p class="path">{{ s.copy.location }}</p>
        <div class="revision-grid">
          <RevisionText
            label="source · 克隆基线"
            :value="s.copy.sourceRevision"
          /><RevisionText
            label="baseline · 当前 Step 执行基线"
            :value="chosen?.baselineRevision"
          /><RevisionText
            label="current · 当前代码"
            :value="s.copy.currentRevision"
          /><RevisionText
            label="lastVerified · 最近验证通过"
            :value="s.copy.lastVerifiedRevision"
          />
        </div>
        <details class="object-id">
          <summary>WorkingCopy 与资产绑定</summary>
          <p>
            WorkingCopy <code>{{ s.copy.id }}</code>
          </p>
          <p>
            SoftwareAsset <code>{{ s.copy.sourceAssetId }}</code>
          </p>
        </details>
        </details>
      </div>
      <p v-else class="empty">
        WorkingCopy 尚未创建或读取，请回到规划阶段核对。
      </p>
    </section>
    <div class="evolution-layout">
      <aside id="execution-steps" class="panel execution-steps">
        <h2>计划步骤</h2>
        <ol>
          <li v-for="(step, index) in s.plan.steps" :key="step.id">
            <button
              class="step-select"
              :class="{ active: chosen?.id === step.id }"
              :aria-pressed="chosen?.id === step.id"
              @click="viewStep(step.id)"
            >
              <span class="navigator-heading"><span class="step-number" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
                <span><span class="meta">步骤 {{ index + 1 }}</span><strong>{{ step.goal }}</strong></span></span>
              <StatusBadge :status="step.status" :show-code="false" />
            </button>
          </li>
        </ol>
      </aside>
      <section v-if="chosen" id="execution-detail" class="panel execution-detail" tabindex="-1">
        <a href="#execution-steps" class="mobile-return">返回步骤列表 ↑</a>
        <div class="panel-header">
          <h2>{{ chosen.goal }}</h2>
          <StatusBadge :status="chosen.status" />
        </div>
          <div class="execution-action" :class="{ 'inline-note': recoveryBlocked }" :data-status="chosen.status">
            <p role="status">{{ actionReason }}</p>
            <button v-if="chosen.status === 'PENDING_CONFIRMATION' || !s.copy" @click="emit('planning')"><NavigationIcon name="lock" />前往规划页核对与授权</button>
            <button v-if="chosen.status === 'FAILED'" :disabled="!canRetry" @click="openRetry"><NavigationIcon name="play" />审阅并独立授权完整重试</button>
            <button v-if="chosen.status === 'READY'" :disabled="!canExecute" @click="openExecute"><NavigationIcon name="play" />审阅风险并执行此 Step</button>
            <button v-if="chosen.status === 'VERIFICATION_FAILED'" :disabled="!canRepair" @click="openRepair"><NavigationIcon name="play" />审阅并授权本次修复</button>
            <button v-if="chosen.status === 'VERIFICATION_FAILED'" class="secondary" :disabled="!canRepair" @click="openRollback"><NavigationIcon name="lock" />审阅并授权回滚</button>
          </div>
          <div v-if="chosen.executionFailureResult || chosen.rollbackAuthorization" class="execution-action inline-note" role="status">
            <strong>{{ chosen.executionFailureResult ? '执行已确认失败，未形成合法 Candidate。' : '此轮 Candidate 已申请用户回滚。' }}</strong>
            <p v-if="chosen.automaticRollbackResult || chosen.rollbackResult">{{ (chosen.automaticRollbackResult ?? chosen.rollbackResult)?.restored ? '已确认精确恢复原始基线。此步骤未成功，后续操作仍需独立授权。' : '完整恢复未确认，禁止继续修改。' }}</p>
            <p v-else>尚无最终恢复结果。不会自动重放执行或回滚。</p>
          </div>
        <div class="step-tabs" role="tablist" aria-label="当前步骤分区">
          <button v-for="(tab, index) in tabs" :id="`step-tab-${tab.id}`" :key="tab.id"
            type="button" role="tab" :aria-selected="selectedTab === tab.id"
            :tabindex="selectedTab === tab.id ? 0 : -1" :aria-controls="`step-panel-${tab.id}`"
            @click="selectTab(tab.id)" @keydown="navigateTabs($event, index)">{{ tab.label }}</button>
        </div>
        <div :id="`step-panel-${selectedTab}`" :key="`${s.plan.id}:${chosen.id}:${selectedTab}`"
          ref="tabPanel" class="execution-tab-panel" role="tabpanel" tabindex="0"
          :aria-labelledby="`step-tab-${selectedTab}`">
        <section v-if="selectedTab === 'current'" id="execution-goal" class="execution-section">
          <h3 class="section-heading"><NavigationIcon name="lock" />当前 Step 状态与操作</h3>
          <div v-if="chosen.status === 'SUCCEEDED' && chosen.verificationResult?.passed && chosen.verificationResult.candidateRevision === chosen.executionResult?.candidateRevision" class="success-note step-outcome">
            <strong>SUCCEEDED · 此 Step 验证通过</strong>
            <p class="meta">基于此 Step 自身保存的验证事实；后续 Step 推进不会改变此结果。</p>
            <RevisionText label="此 Step 的 Verified Revision" :value="chosen.verificationResult.candidateRevision" />
            <p class="meta">验证完成：{{ new Date(chosen.verificationResult.verifiedAt).toLocaleString('zh-CN') }} · {{ chosen.verificationResult.checks.length }} 项已保存检查</p>
            <p class="meta">{{ chosen.verificationResult.checks.map(check => `${check.action}：${checkOutcome(check)}`).join(' · ') }}</p>
          </div>
          <details :key="chosen.id" class="reading-disclosure" :open="!chosen.executionResult && !chosen.baselineRevision">
            <summary>目标、范围与操作前提</summary>
            <h4>允许范围</h4><p class="scope">{{ chosen.scope }}</p>
            <ContentList title="计划改动" :items="chosen.plannedChanges" icon="code" />
            <StepPrerequisites :plan="s.plan" :step="chosen" :copy="s.copy" execution />
            <h3>验收标准</h3><ol class="criteria"><li v-for="(criterion, index) in chosen.verificationCriteria" :key="index">
              <p>{{ criterion.description }}</p><code>{{ criterion.kind }}</code>
            </li></ol>
          </details>
          <p v-if="chosen.status === 'READY'" class="meta">READY 表示已授权的就绪状态；准备提案期间也可能保持 READY，不能据此判断请求已结束。</p>
        </section>
        <StepFinalChanges v-else-if="selectedTab === 'changes'" :plan-id="s.plan.id" :step="chosen" />
        <StepEvolutionTrace v-else-if="selectedTab === 'trace'" :step="chosen" />
        <section v-else-if="selectedTab === 'verification'" id="verification-result" class="execution-section">
        <h3 class="section-heading"><NavigationIcon name="evolution" />验证结果与可信版本</h3>
        <template v-if="chosen.verificationResult"
          ><div class="result-heading">
            <h4>保存的验证结果 <span class="meta">VerificationResult</span></h4>
            <span
              class="tag"
              :class="{ danger: !chosen.verificationResult.passed }"
              >{{ chosen.verificationResult.passed ? "PASS" : "FAIL" }}</span
            >
          </div>
          <RevisionText
            label="被验证的 Candidate"
            :value="chosen.verificationResult.candidateRevision"
          />
          <p>{{ chosen.verificationResult.summary }}</p>
          <article
            v-for="(check, index) in chosen.verificationResult.checks"
            :key="index"
            class="check-result" :data-passed="check.passed"
          >
            <div class="section-row">
              <h4>
                {{ check.action
                }}<span v-if="check.target"> · {{ check.target }}</span>
              </h4>
              <strong class="check-outcome"><NavigationIcon :name="check.passed ? 'check' : 'warning'" />{{ checkOutcome(check) }}</strong>
            </div>
            <p>{{ check.summary }}</p>
            <p class="meta">
              对应标准：{{ check.criterionIndexes.join(", ") }}
            </p>
          </article>
          <ContentList
            v-if="chosen.verificationResult.failures.length"
            title="失败事实"
            :items="chosen.verificationResult.failures"
          />
          <p class="meta">
            验证完成：{{
              new Date(chosen.verificationResult.verifiedAt).toLocaleString(
                "zh-CN",
              )
            }}
          </p>
        </template>
        <p v-else class="context-note">
          尚无 VerificationResult。Candidate 出现不等于验证成功。
        </p>
        <RevisionText label="lastVerifiedRevision · 隔离副本最近验证通过的版本" :value="s.copy?.lastVerifiedRevision" />
        <p v-if="s.copy && s.copy.currentRevision !== s.copy.lastVerifiedRevision" class="inline-note">
          隔离副本当前代码与最近验证版本不一致，不能继续执行下一步。请保留当前状态与证据。
        </p>
        </section>
        <section v-else-if="selectedTab === 'history'" id="step-complete-history" class="execution-section complete-history">
          <h3>完整历史与本轮记录</h3>
          <details :key="chosen.id" class="reading-disclosure">
            <summary>展开完整记录 · {{ chosen.repairAuthorizations?.length ?? 0 }} 次修复授权 · {{ chosen.retryAuthorizations?.length ?? 0 }} 次完整重试授权</summary>
        <section v-if="chosen.executionFailureResult" class="execution-section">
          <h3>执行失败与自动回滚</h3>
          <p>执行已确认失败，未形成合法 Candidate。</p>
          <p class="meta">阶段：{{ chosen.executionFailureResult.phase }} · {{ chosen.executionFailureResult.diagnostic }}</p>
          <RevisionText label="原始可信基线" :value="chosen.executionFailureResult.baselineRevision" />
          <p v-if="chosen.automaticRollbackResult">{{ chosen.automaticRollbackResult.restored
            ? '已确认精确恢复原始基线。此步骤未成功，等待后续明确操作。'
            : '完整恢复未确认，禁止继续修改。' }}</p>
          <p v-else>尚无最终恢复结果。不会自动重放执行或回滚。</p>
          <details class="reading-disclosure">
            <summary>失败现场的文件证据</summary>
            <p v-if="!chosen.executionFailureResult.diffComplete">现场证据未完整读取，不能据此确认恢复范围。</p>
            <p v-else-if="!chosen.executionFailureResult.diff.length">已核对的现场没有相对基线的字节差异。</p>
            <p v-for="entry in chosen.executionFailureResult.diff" :key="entry.path">{{ entry.path }}</p>
          </details>
        </section>
        <section v-if="chosen.rollbackAuthorization" class="execution-section">
          <h3>回滚授权与恢复结果</h3>
          <p class="meta">授权 ID：{{ chosen.rollbackAuthorization.id }}</p>
          <RevisionText label="放弃的 Candidate" :value="chosen.rollbackAuthorization.candidateRevision" />
          <RevisionText label="原始基线" :value="chosen.rollbackAuthorization.baselineRevision" />
          <p v-if="chosen.rollbackResult">{{ chosen.rollbackResult.restored ? '已确认精确恢复原始基线' : '完整恢复未确认，禁止继续修改' }}</p>
          <p v-if="chosen.rollbackResult" class="meta">{{ chosen.rollbackResult.diagnostic }}</p>
          <p v-else>尚无最终结果。不会自动重放回滚。</p>
        </section>
        <section id="execution-result" class="execution-section">
        <div class="section-row"><h3 class="section-heading"><NavigationIcon name="code" />本轮 Execution／Repair 记录</h3><StatusBadge :status="chosen.status" /></div>
        <p class="meta">此处仅记录最近一次执行或 Repair，文件范围不是整个 Step 的最终累计差异。</p>
        <RevisionText v-if="chosen.baselineRevision" label="Step 原始 baseline" :value="chosen.baselineRevision" />
        <template v-if="chosen.executionResult"
          ><RevisionText label="本轮起点（Repair 时为此前失败 Candidate）" :value="chosen.repairAuthorizations?.at(-1)?.candidateRevision ?? chosen.baselineRevision" /><div class="result-heading">
            <h4>本轮执行结果 <span class="meta">ExecutionResult</span></h4>
            <time>{{
              new Date(chosen.executionResult.executedAt).toLocaleString(
                "zh-CN",
              )
            }}</time>
          </div>
          <RevisionText
            label="Candidate · 已保存的候选代码版本"
            :value="chosen.executionResult.candidateRevision"
          /><ContentList
            class="round-statistics" title="本轮原始变更统计／说明"
            :items="chosen.executionResult.changes"
          /><ContentList
            title="本轮受影响文件"
            variant="modules" icon="file"
            :items="chosen.executionResult.affectedPaths"
          /><ContentList
            v-if="chosen.executionResult.warnings.length"
            title="执行警告"
            :items="chosen.executionResult.warnings"
          /><ContentList
            v-if="chosen.executionResult.errors.length"
            title="执行错误"
            :items="chosen.executionResult.errors"
          />
          <p class="meta">
            这些条目来自持久化结果。现有 API 不提供源码、unifiedDiff 或已接受
            CodingProposal 的历史查询。
          </p></template
        >
        <p v-else class="context-note">
          尚无 ExecutionResult。<span v-if="chosen.status === 'READY'">代码是否已开始准备，不能仅从 READY 判断。</span>
        </p>
        <p v-if="chosen.executionResult" class="meta">Candidate 是执行产生的代码版本；出现 Candidate 不表示验证通过。</p>
        </section>
        <section v-if="chosen.repairAuthorizations?.length" id="repair-history" class="execution-section">
          <h3>修复授权与此前失败记录</h3>
          <details v-for="authorization in chosen.repairAuthorizations" :key="authorization.id" class="reading-disclosure">
            <summary>修复授权 · {{ new Date(authorization.authorizedAt).toLocaleString('zh-CN') }}</summary>
            <p class="meta">授权 ID：{{ authorization.id }}</p>
            <RevisionText label="本次授权的失败 Candidate" :value="authorization.candidateRevision" />
            <ContentList title="此前执行的文件" :items="authorization.previousExecution.affectedPaths" />
            <p>{{ authorization.previousVerification.summary }}</p>
            <p v-for="(check, index) in authorization.previousVerification.checks" :key="index">
              {{ check.action }}{{ check.target ? ` · ${check.target}` : '' }}：{{ checkOutcome(check) }} · {{ check.summary }}
            </p>
            <ContentList title="此前失败事实" :items="authorization.previousVerification.failures" />
          </details>
        </section>
        <section v-if="chosen.retryAuthorizations?.length" id="retry-history" class="execution-section">
          <h3>完整重试授权与此前失败历史</h3>
          <details v-for="authorization in chosen.retryAuthorizations" :key="authorization.id" class="reading-disclosure">
            <summary>完整重试授权 · {{ new Date(authorization.authorizedAt).toLocaleString('zh-CN') }}</summary>
            <p class="meta">授权 ID：{{ authorization.id }} · 此前失败 ID：{{ authorization.failedCheckpointId }}</p>
            <RevisionText label="原始可信基线" :value="authorization.baselineRevision" />
            <ContentList v-if="authorization.previousExecution" title="此前 Candidate 的文件" :items="authorization.previousExecution.affectedPaths" />
            <template v-if="authorization.previousExecutionFailure">
              <p>此前执行失败阶段：{{ authorization.previousExecutionFailure.phase }} · {{ authorization.previousExecutionFailure.diagnostic }}</p>
              <ContentList title="失败现场差异路径" :items="authorization.previousExecutionFailure.diff.map(item => item.path)" />
            </template>
            <template v-if="authorization.previousVerification">
              <p>{{ authorization.previousVerification.summary }}</p>
              <p v-for="(check, index) in authorization.previousVerification.checks" :key="index">
                {{ check.action }} {{ check.target }}：{{ checkOutcome(check) }} · {{ check.summary }}
              </p>
            </template>
            <article v-for="repair in authorization.previousRepairs" :key="repair.id" class="archived-repair">
              <h4>此前修复授权 · {{ new Date(repair.authorizedAt).toLocaleString('zh-CN') }}</h4>
              <p class="meta">授权 ID：{{ repair.id }}</p>
              <RevisionText label="此前失败 Candidate" :value="repair.candidateRevision" />
              <ContentList title="此前执行的文件" :items="repair.previousExecution.affectedPaths" />
              <p v-for="(check, index) in repair.previousVerification.checks" :key="index">
                {{ check.action }} {{ check.target }}：{{ checkOutcome(check) }} · {{ check.summary }}
              </p>
            </article>
            <p v-if="authorization.previousRollbackAuthorization" class="meta">用户回滚授权 ID：{{ authorization.previousRollbackAuthorization.id }}</p>
            <p v-if="authorization.previousRollbackResult">用户回滚：{{ authorization.previousRollbackResult.diagnostic }}</p>
            <p v-if="authorization.previousAutomaticRollback">系统回滚：{{ authorization.previousAutomaticRollback.diagnostic }}</p>
          </details>
        </section>
          </details>
        </section>
        </div>
        <p
          v-if="
            ![
              'READY',
              'PENDING_CONFIRMATION',
              'SUCCEEDED',
              'EXECUTING',
              'VERIFYING',
              'VERIFICATION_FAILED',
            ].includes(chosen.status)
          "
          class="inline-note"
        >
          当前状态没有恢复入口，请保留实际状态及证据，不能强制重试 Candidate。
        </p>
        <p v-if="chosen.status === 'PENDING_CONFIRMATION'" class="inline-note">
          请先在规划阶段审阅范围并确认此 Step。
        </p>
        <p class="meta">一次明确授权、一次执行。不会自动执行下一 Step。</p>
      </section>
    </div>
  </template>
  <ConfirmDialog
    :open="executeOpen"
    title="执行此 Step 并运行验证？"
    confirm-label="执行当前 Step"
    :disabled="!acknowledged || !canExecute"
    @close="executeOpen = false"
    @confirm="execute"
  >
    <h3>{{ dialogStep?.goal }}</h3>
    <p>{{ dialogStep?.scope }}</p>
    <p>
      此请求将调用真实 Provider、在独立 WorkingCopy 修改代码、创建
      Candidate，并运行 Maven 构建和测试。可能持续十余分钟并产生 Provider 费用。
    </p>
    <p>
      Windows 本地 Maven 会执行仓库代码和构建插件。WorkingCopy 是 Git
      隔离，不是操作系统沙箱；只使用用户授权、受信任的 Java / Maven 仓库。
    </p>
    <label class="check"
      ><input
        v-model="acknowledged"
        type="checkbox"
      />我确认信任此仓库、授权当前 Step 的范围，接受费用与宿主执行风险</label
    >
  </ConfirmDialog>
  <ConfirmDialog :open="retryOpen" title="独立授权完整重试这个 Step？" confirm-label="授权完整重试，返回待执行"
    :disabled="!retryAcknowledged || !canRetry || retryBaseline !== chosen?.baselineRevision
      || retryFailureId !== (chosen?.executionFailureResult?.id ?? chosen?.rollbackAuthorization?.id) || dialogStep?.id !== chosen?.id"
    @close="retryOpen = false" @confirm="retry">
    <h3>{{ dialogStep?.goal }}</h3><p>{{ dialogStep?.scope }}</p>
    <RevisionText label="保持不变的原始可信基线" :value="retryBaseline" />
    <p>从已精确恢复的原始基线重新完整执行原 Step。此前执行、验证、修复与回滚历史全部保留。</p>
    <p>此按钮只保存本次独立授权，不调用 AI、不修改代码。随后仍须审阅风险并点击执行；执行将调用 Provider、产生费用，并重新验证全部标准。</p>
    <label class="check"><input v-model="retryAcknowledged" type="checkbox" />我重新授权从上述基线完整重试原 Step，不复用此前执行、修复或回滚授权</label>
  </ConfirmDialog>
  <ConfirmDialog :open="repairOpen" title="授权修复这个失败 Candidate？" confirm-label="授权并开始本次修复"
    :disabled="!repairAcknowledged || !canRepair || repairCandidate !== s.copy?.currentRevision || dialogStep?.id !== chosen?.id"
    @close="repairOpen = false" @confirm="repair">
    <h3>{{ dialogStep?.goal }}</h3><p>{{ dialogStep?.scope }}</p>
    <RevisionText label="本次修复的 Candidate" :value="repairCandidate" />
    <p>本次授权只修复原 Step 的当前 Candidate。原 baseline 和失败记录保留，修复后重新验证全部标准。</p>
    <p>此请求将调用 Provider、产生费用，并在受控 WorkingCopy 中运行仓库代码。只有实际验证通过后才推进可信版本。</p>
    <label class="check"><input v-model="repairAcknowledged" type="checkbox" />我单独授权本次修复原 Step 的范围，接受 Provider 费用与宿主执行风险</label>
  </ConfirmDialog>
  <ConfirmDialog :open="rollbackOpen" title="授权回滚这个失败 Candidate？" confirm-label="授权并回滚到原始基线"
    :disabled="!rollbackAcknowledged || !canRepair || rollbackCandidate !== s.copy?.currentRevision
      || rollbackBaseline !== chosen?.baselineRevision || dialogStep?.id !== chosen?.id"
    @close="rollbackOpen = false" @confirm="rollback">
    <h3>{{ dialogStep?.goal }}</h3><p>{{ dialogStep?.scope }}</p>
    <RevisionText label="放弃的 Candidate" :value="rollbackCandidate" />
    <RevisionText label="精确恢复目标" :value="rollbackBaseline" />
    <p>本次回滚放弃原 Step 所有 Candidate 改动，包括多次修复，恢复到该 Step 最初的基线；失败与授权历史保留。</p>
    <p>无需调用 Provider。若发现额外文件或用户修改，会拒绝或阻塞，不强制覆盖，也不清理这些文件。</p>
    <label class="check"><input v-model="rollbackAcknowledged" type="checkbox" />我单独授权放弃当前 Candidate 的改动，精确恢复上述原始基线</label>
  </ConfirmDialog>
</template>
