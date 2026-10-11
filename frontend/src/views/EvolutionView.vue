<script setup lang="ts">
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from "vue";
import type { WorkspaceSession } from "../state/session";
import type { EvolutionStep } from "../api/types";
import StatusBadge from "../components/StatusBadge.vue";
import ContentList from "../components/ContentList.vue";
import RevisionText from "../components/RevisionText.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import StepPrerequisites from "../components/StepPrerequisites.vue";
const props = defineProps<{ s: WorkspaceSession }>();
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
const executeOpen = ref(false);
const acknowledged = ref(false);
const dialogStep = ref<EvolutionStep | null>(null);
watch(() => props.s.plan?.id, () => {
  selectedId.value = "";
  executeOpen.value = false;
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
function openExecute() {
  dialogStep.value = chosen.value ?? null;
  acknowledged.value = false;
  executeOpen.value = true;
}
function execute() {
  if (!dialogStep.value || !acknowledged.value || !canExecute.value) return;
  executeOpen.value = false;
  props.s.execute(dialogStep.value.id);
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
          s.executionPending ? "执行请求已提交，正在等待后端结果" : "执行结果待核对，仅查询状态"
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
            {{ check.action }}{{ check.target ? ` · ${check.target}` : '' }}：{{ check.passed ? 'PASS' : 'FAIL' }}
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
          <h3>WorkingCopy <span class="meta">{{ s.copy.id }}</span></h3>
          <StatusBadge :status="s.copy.status" />
        </div>
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
              <span class="meta">步骤 {{ index + 1 }}</span
              ><strong>{{ step.goal }}</strong
              ><StatusBadge :status="step.status" :show-code="false" />
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
        <nav class="section-links" aria-label="当前步骤分区">
          <a href="#execution-goal">目标与授权</a><a href="#execution-result">执行状态</a><a href="#verification-result">验证结果</a>
        </nav>
        <section id="execution-goal" class="execution-section">
          <h3>目标与授权</h3>
          <div class="execution-action" :class="{ 'inline-note': recoveryBlocked }">
            <p role="status">{{ actionReason }}</p>
            <button v-if="chosen.status === 'PENDING_CONFIRMATION' || !s.copy" class="secondary" @click="emit('planning')">前往规划页核对与授权</button>
            <button v-if="chosen.status === 'READY'" :disabled="!canExecute" @click="openExecute">审阅风险并执行此 Step</button>
          </div>
          <details :key="chosen.id" class="reading-disclosure" :open="!chosen.executionResult && !chosen.baselineRevision">
            <summary>目标、范围与操作前提</summary>
            <h4>允许范围</h4><p class="scope">{{ chosen.scope }}</p>
            <ContentList title="计划改动" :items="chosen.plannedChanges" />
            <StepPrerequisites :plan="s.plan" :step="chosen" :copy="s.copy" execution />
            <h3>验收标准</h3><ol class="criteria"><li v-for="(criterion, index) in chosen.verificationCriteria" :key="index">
              <p>{{ criterion.description }}</p><code>{{ criterion.kind }}</code>
            </li></ol>
          </details>
          <p v-if="chosen.status === 'READY'" class="meta">READY 表示已授权的就绪状态；准备提案期间也可能保持 READY，不能据此判断请求已结束。</p>
        </section>
        <section id="execution-result" class="execution-section">
        <div class="section-row"><h3>执行状态与 Candidate</h3><StatusBadge :status="chosen.status" /></div>
        <RevisionText v-if="chosen.baselineRevision" label="baselineRevision · 执行基线" :value="chosen.baselineRevision" />
        <template v-if="chosen.executionResult"
          ><div class="result-heading">
            <h4>执行结果 <span class="meta">ExecutionResult</span></h4>
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
            title="实际变更说明"
            :items="chosen.executionResult.changes"
          /><ContentList
            title="受影响文件"
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
        <section id="verification-result" class="execution-section">
        <h3>验证结果与可信版本</h3>
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
            class="check-result"
          >
            <div class="section-row">
              <h4>
                {{ check.action
                }}<span v-if="check.target"> · {{ check.target }}</span>
              </h4>
              <strong>{{ check.passed ? "PASS" : "FAIL" }}</strong>
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
        <div
          v-if="
            chosen.status === 'SUCCEEDED' &&
            s.copy?.lastVerifiedRevision ===
              chosen.executionResult?.candidateRevision
          "
          class="success-note"
        >
          <strong>SUCCEEDED · 此 Step 验证通过</strong>
          <RevisionText label="lastVerifiedRevision" :value="s.copy?.lastVerifiedRevision" />
          <p v-for="(check, index) in chosen.verificationResult?.checks ?? []" :key="index">
            {{ check.action }}{{ check.target ? ` · ${check.target}` : '' }}：{{ check.passed ? 'PASS' : 'FAIL' }}
          </p>
          <p class="meta">状态、revision 与检查结果均来自后端；不会自动执行下一 Step。</p>
        </div>
        </section>
        <p
          v-if="
            ![
              'READY',
              'PENDING_CONFIRMATION',
              'SUCCEEDED',
              'EXECUTING',
              'VERIFYING',
            ].includes(chosen.status)
          "
          class="inline-note"
        >
          当前状态没有 M4 页面恢复操作。M5
          暂缓，请保留实际状态及证据，不能强制重试 Candidate。
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
</template>
