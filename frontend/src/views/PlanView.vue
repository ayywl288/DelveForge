<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from "vue";
import type { WorkspaceSession } from "../state/session";
import type { EvolutionStep, WorkspaceConfiguration } from "../api/types";
import { api } from "../api/client";
import ContentList from "../components/ContentList.vue";
import StatusBadge from "../components/StatusBadge.vue";
import EvidenceList from "../components/EvidenceList.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import RevisionText from "../components/RevisionText.vue";
import RepositoryCompatibility from "../components/RepositoryCompatibility.vue";
import StepPrerequisites from "../components/StepPrerequisites.vue";
import NavigationIcon from "../components/NavigationIcon.vue";
import DetailPanel from "../components/DetailPanel.vue";
import InlineBusy from "../components/InlineBusy.vue";
import { stepPrerequisiteIssue } from "../state/stepPrerequisite";
const props = defineProps<{ s: WorkspaceSession }>();
const workspaceConfiguration = ref<WorkspaceConfiguration | null>(null);
const workspaceConfigurationError = ref("");
let loadingConfiguration = false;
async function loadConfiguration() {
  if (loadingConfiguration) return;
  loadingConfiguration = true;
  workspaceConfiguration.value = null;
  workspaceConfigurationError.value = "";
  try {
    workspaceConfiguration.value = await api.workspaceConfiguration();
  } catch {
    workspaceConfigurationError.value = "未能读取存储配置。请核对后端连接；准备操作仍由后端校验。";
  } finally {
    loadingConfiguration = false;
  }
}
onMounted(loadConfiguration);
onActivated(loadConfiguration);
const emit = defineEmits<{ next: []; directions: []; authorize: [assetId: string] }>();
const base = ref("");
const reviewed = ref(false);
const prepareOpen = ref(false);
const stepToConfirm = ref<EvolutionStep | null>(null);
const stepToConfirmLabel = computed(() => {
  const index = props.s.plan?.steps.findIndex(step => step.id === stepToConfirm.value?.id) ?? -1;
  return index >= 0 ? `Step ${String(index + 1).padStart(2, '0')}` : "";
});
const detail = ref<"current" | "target" | "basis" | null>(null);
const expandedStepId = ref(props.s.plan?.steps.find(step => step.status !== "SUCCEEDED")?.id
  ?? props.s.plan?.steps[0]?.id ?? "");
watch(() => [props.s.contextEpoch, props.s.selected?.id], () => {
  // 分析快照是本轮用户选择，不能由上一项目或旧方向的表单残留代替。
  base.value = "";
  reviewed.value = false;
});
watch(() => props.s.plan?.id, () => {
  expandedStepId.value = props.s.plan?.steps.find(step => step.status !== "SUCCEEDED")?.id
    ?? props.s.plan?.steps[0]?.id ?? "";
  reviewed.value = false;
  prepareOpen.value = false;
  stepToConfirm.value = null;
  detail.value = null;
});
const bases = computed(() =>
  props.s.repositories.filter(
    (repository) =>
      props.s.selected?.candidateAssetIds.includes(repository.assetId),
  ),
);
const currentBasis = computed(
  // 画像确认仍由原契约校验，资产授权另行提示且最终以后端判断为准。
  () =>
    props.s.profile?.status === "CONFIRMED" &&
    props.s.selected?.userProfileRevision === props.s.profile.revision &&
    props.s.selected.userProfileId === props.s.profile.id,
);
const baseAssetId = computed(() => bases.value.find(repository => repository.id === base.value)?.assetId);
const asset = computed(() => props.s.assets.find(item => item.id === baseAssetId.value));
const authorizationReady = computed(() => !!asset.value?.readPermissionAllowed &&
  asset.value.usageAuthorization === "ALLOWED" && !!asset.value.licenseInfo?.trim());
function stepIssue(step: EvolutionStep) {
  return props.s.plan ? stepPrerequisiteIssue(props.s.plan, step, props.s.copy) : "请先读取计划。";
}
function confirmSelectedStep() {
  const current = props.s.plan?.steps.find(step => step.id === stepToConfirm.value?.id);
  if (current?.status === "PENDING_CONFIRMATION" && !props.s.busy && !stepIssue(current)) {
    void props.s.confirmStep(current.id);
  }
  stepToConfirm.value = null;
}
</script>
<template>
  <section v-if="s.plan?.status === 'SUPERSEDED'" class="panel inline-note">
    <h2>这份计划已失效，保留为历史记录</h2>
    <p>它的方向已被替代，或历史迁移已标记失效，不能继续授权或执行。原步骤、WorkingCopy 和验证结果保留。</p>
    <template v-if="s.selected">
      <h3>当前选中方向：{{ s.selected.title }}</h3><p>{{ s.selected.targetProduct }}</p>
    </template>
    <p v-else>当前流程没有 SELECTED 方向。旧 SUPERSEDED 方向不会自动恢复；可在新轮次中明确选择候选，或主动生成新方向。</p>
    <button :disabled="!!s.busy || !s.projectContext || s.projectIssues.includes('FLOW_OWNERSHIP_UNKNOWN')"
      @click="s.beginReplanning">{{ s.selected ? '基于当前方向重新规划' : '建立新规划轮次' }}</button>
    <p class="meta">这一步只建立关联轮次，不调用 AI。随后需重新选择合法基础仓库快照，显式生成计划。不会用旧副本 B 代替源仓库分析。</p>
  </section>
  <section v-if="!s.plan" class="panel plan-input">
    <h2>把方向拆成可验证的步骤</h2>
    <template v-if="s.selected"
      ><h3>{{ s.selected.title }}</h3>
      <p>{{ s.selected.targetProduct }}</p>
      <form class="stack" @submit.prevent="s.generatePlan(base)">
        <label for="plan-base">选择演化基础仓库快照</label
        ><select id="plan-base" name="plan-base" autocomplete="off" v-model="base" required :disabled="!!s.busy">
          <option disabled value="">请明确选择</option>
          <option
            v-for="repository in bases"
            :key="repository.id"
            :value="repository.id"
          >
            {{ repository.purpose }} ·
            {{ repository.analyzedRevision.slice(0, 12) }}
          </option>
        </select>
        <p class="meta">
          列出该方向候选资产的已有分析；授权仍需满足下方要求。计划生成不创建
          WorkingCopy，也不授权 Step。
        </p>
        <p v-if="!currentBasis" class="inline-note">
          需要确认与选中方向对应的当前画像版本。
        </p>
        <div v-if="base && (!authorizationReady || s.planningAuthorizationIssue?.assetId === baseAssetId)" class="inline-note">
          <p>演化基础需允许读取、明确允许使用，并记录许可证信息。补充当前资产的授权后，无需重新分析或重新生成方向。</p>
          <button type="button" class="secondary" :disabled="!!s.busy || !baseAssetId"
            @click="baseAssetId && emit('authorize', baseAssetId)">前往补充许可证与授权</button>
        </div>
        <button :disabled="!!s.busy || !base || !authorizationReady || !currentBasis">
          生成演化计划（调用 AI）
        </button>
        <InlineBusy v-if="s.aiActivity?.kind === 'planning'" label="正在生成演化计划…" />
      </form>
    </template>
    <div v-else class="empty"><p>请先明确选择一个产品方向。</p>
      <button class="secondary" @click="emit('directions')">前往产品方向</button></div>
  </section>
  <template v-else>
    <section class="panel">
      <div class="panel-header">
        <div>
          <h2 class="section-heading"><NavigationIcon name="planning" />演化计划</h2>
        </div>
        <StatusBadge :status="s.plan.status" />
      </div>
      <p class="meta">以下是保存的规划输入与目标。实现和验证结果请在「实施演化」查看。</p>
      <div class="state-comparison">
        <section>
          <span class="summary-label"><NavigationIcon name="layers" />规划时的现状</span>
          <h3>{{ s.plan.currentState.summary }}</h3>
          <button class="text-button state-detail-trigger" @click="detail = 'current'">查看完整现状 <NavigationIcon name="arrow" /></button>
        </section>
        <section>
          <span class="summary-label"><NavigationIcon name="target" />计划目标</span>
          <h3>{{ s.plan.targetState.targetProduct }}</h3>
          <button class="text-button state-detail-trigger" @click="detail = 'target'">查看完整目标与复用能力 <NavigationIcon name="arrow" /></button>
        </section>
      </div>
      <button class="text-button plan-more" @click="detail = 'basis'">规划改动、风险、依据与基础绑定 <NavigationIcon name="arrow" /></button>
    </section>
    <section v-if="['PROPOSED', 'ACTIVE'].includes(s.plan.status)" id="prepare-working-copy"
      class="panel prepare-box" aria-labelledby="prepare-heading">
      <div class="panel-header">
        <h2 id="prepare-heading">审阅计划与准备隔离环境</h2>
        <StatusBadge v-if="s.copy" :status="s.copy.status" :show-code="false" />
      </div>
      <p class="meta">先审阅目标与路线，再明确准备隔离副本，最后逐个授权 Step。</p>
      <RepositoryCompatibility :asset-id="s.plan.baseAssetId"
        :revision="s.repositories.find(item => item.id === s.plan?.baseRepositoryProfileId)?.analyzedRevision ?? ''"
        :copy="s.copy" />
      <template v-if="s.plan.status === 'PROPOSED'">
        <div class="workspace-configuration">
          <p class="meta">新 WorkingCopy 存储根目录：<code>{{ workspaceConfiguration?.root ?? '尚未读取' }}</code></p>
          <p v-if="workspaceConfigurationError" role="alert">{{ workspaceConfigurationError }}</p>
          <details><summary>存储配置与准备边界</summary>
            <p>如需换盘，在启动后端的终端设置 <code>DELVEFORGE_WORKSPACE_ROOT</code> 并重启后端，再回到本页核对。此处不接受任意目录输入；准备时仍会校验实际路径与权限。</p>
            <p v-if="workspaceConfiguration?.existingRoots.length">仅接纳旧副本的根目录：{{ workspaceConfiguration.existingRoots.join('；') }}</p>
            <p>已创建的副本不会移动；实际位置以准备成功后的 WorkingCopy 为准。</p>
            <button class="secondary" type="button" @click="loadConfiguration">重新读取存储配置</button>
          </details>
        </div>
        <label class="check"><input v-model="reviewed" type="checkbox" :disabled="!!s.busy" />
          我已审阅目标、范围、步骤与验收标准</label>
        <button :disabled="!!s.busy || !reviewed" @click="prepareOpen = true">准备隔离副本</button>
        <p class="meta">准备通过后激活计划、创建隔离副本；不会自动授权或执行任何 Step。</p>
      </template>
      <template v-else>
        <p v-if="s.copy" class="path">当前隔离副本：{{ s.copy.location }}</p>
        <p v-else class="inline-note">尚未读取隔离副本，请重新查询计划与副本状态。</p>
        <p class="meta">计划已激活。请在下方核对各 Step 的操作前提，再明确授权。</p>
      </template>
    </section>
    <section class="panel roadmap" aria-labelledby="roadmap-heading">
    <div class="panel-header"><h2 id="roadmap-heading" class="section-heading"><NavigationIcon name="planning" />演化路线</h2><span class="meta">{{ s.plan.steps.length }} 个步骤 · 按顺序验证</span></div>
    <p v-if="!s.plan.steps.length" class="empty">此计划未提供步骤。</p>
    <ol class="step-list">
      <li
        v-for="(step, index) in s.plan.steps"
        :key="step.id"
        class="roadmap-step" :class="{ expanded: expandedStepId === step.id }" :data-status="step.status"
      >
        <button class="roadmap-toggle" :aria-expanded="expandedStepId === step.id" :aria-controls="`plan-step-${step.id}`"
          @click="expandedStepId = expandedStepId === step.id ? '' : step.id">
          <span class="step-number" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
          <strong>{{ step.goal }}</strong><StatusBadge :status="step.status" :show-code="false" />
          <span aria-hidden="true" class="disclosure-chevron">{{ expandedStepId === step.id ? '−' : '+' }}</span>
        </button>
        <div v-if="expandedStepId === step.id" :id="`plan-step-${step.id}`" class="step-content">
          <h3 class="section-heading"><NavigationIcon name="target" />步骤目标</h3><p>{{ step.goal }}</p>
          <h3 class="section-heading"><NavigationIcon name="git" />允许范围</h3>
          <p class="scope">{{ step.scope }}</p>
          <ContentList title="计划改动" :items="step.plannedChanges" icon="code" />
          <StepPrerequisites :plan="s.plan" :step="step" :copy="s.copy" />
          <p>Agent 会根据实际源码选择检查；冻结结果及检查范围会在实施页显示。</p>
          <p class="meta">
            顺序 {{ index + 1 }} · Step <code>{{ step.id }}</code>
          </p>
          <details class="object-id"><summary>基线与原始状态</summary>
            <p>Step 状态：{{ step.status }}</p>
            <RevisionText label="baselineRevision · 执行基线" :value="step.baselineRevision" />
          </details>
          <button
            v-if="step.status === 'PENDING_CONFIRMATION'"
            class="step-authorize"
            :disabled="
              !!s.busy ||
              !!stepIssue(step)
            "
            @click="stepToConfirm = step"
          >
            <NavigationIcon name="lock" />审阅并授权此 Step
          </button>
          <p v-if="step.status === 'PENDING_CONFIRMATION' && stepIssue(step)" class="step-lock-reason">
            {{ stepIssue(step) }}
            <a v-if="s.plan.status === 'PROPOSED'" href="#prepare-working-copy">查看上方准备入口 ↑</a>
          </p>
          <p v-if="step.status === 'READY'" class="inline-note">
            此 Step 已授权；执行仍需在下一阶段单独点击确认。
          </p>
        </div>
      </li>
    </ol>
    </section>
    <div v-if="s.plan.status === 'ACTIVE'" class="stage-footer">
      <p>
        准备与授权是不同的决定。查看实际 WorkingCopy，再手动执行一个 READY
        Step。
      </p>
      <button @click="emit('next')">进入演化执行 →</button>
    </div>
  </template>
  <details v-if="s.projectContext" class="panel planning-history">
    <summary>规划轮次与历史 <span class="meta">{{ s.planningRounds.length }} 轮已读取</span></summary>
    <div class="panel-header"><h2>本项目的规划轮次</h2>
      <button class="text-button" :disabled="!!s.busy" @click="s.loadPlanningRounds">刷新轮次</button></div>
    <p class="meta">每轮保留自己的计划、步骤与验证结果。查看历史不会生成计划或执行代码。</p>
    <p v-if="s.roundsError" role="alert">{{ s.roundsError }}</p>
    <div class="actions">
      <button v-for="round in s.planningRounds" :key="round.key" class="secondary"
        :disabled="!!s.busy || !!round.deletedAt || round.key === 'project:' + s.projectContext.id" @click="s.openProject(round.key, false)">
        {{ round.planStatus ? `计划 ${round.planId?.slice(0, 8)}` : '尚未生成计划的轮次' }}
        <StatusBadge v-if="round.planStatus" :status="round.planStatus" :show-code="false" />
        · {{ round.succeededSteps }}/{{ round.totalSteps }} 已验证
        <span v-if="round.deletedAt"> · 已删除，可从工作台恢复</span>
      </button>
    </div>
  </details>
  <DetailPanel :open="!!detail && !!s.plan"
    :title="detail === 'current' ? '规划时的完整现状' : detail === 'target' ? '完整目标与复用能力' : '规划改动、风险与依据'"
    description="保存的规划原文，仅供阅读。关闭后继续审阅路线与授权要求；查看详情不会表示已审阅或授权。"
    @close="detail = null">
    <template v-if="s.plan">
      <template v-if="detail === 'current'">
        <h3>现状描述</h3><p class="reading-text">{{ s.plan.currentState.summary }}</p>
        <ContentList title="能力" :items="s.plan.currentState.capabilities" />
        <ContentList title="模块" :items="s.plan.currentState.modules" />
        <ContentList title="限制" :items="s.plan.currentState.limitations" />
      </template>
      <template v-else-if="detail === 'target'">
        <h3>要解决的问题</h3><p class="reading-text">{{ s.plan.targetState.problem }}</p>
        <h3>目标产品</h3><p class="reading-text">{{ s.plan.targetState.targetProduct }}</p>
        <h3>差异化</h3><p class="reading-text">{{ s.plan.targetState.differentiation }}</p>
        <ContentList title="可复用能力" :items="s.plan.reusableCapabilities" />
      </template>
      <template v-else-if="detail === 'basis'">
        <ContentList title="计划改变什么" :items="s.plan.changes" />
        <ContentList title="风险" :items="s.plan.risks" variant="risks" />
        <EvidenceList :bases="s.plan.evidence" title="规划依据" expanded />
        <h3>计划与基础绑定</h3>
        <dl class="project-meta-grid">
          <dt>Plan</dt><dd><code>{{ s.plan.id }}</code></dd>
          <dt>Direction</dt><dd><code>{{ s.plan.productDirectionId }}</code></dd>
          <dt>Asset</dt><dd><code>{{ s.plan.baseAssetId }}</code></dd>
          <dt>RepositoryProfile</dt><dd><code>{{ s.plan.baseRepositoryProfileId }}</code></dd>
        </dl>
      </template>
    </template>
  </DetailPanel>
  <ConfirmDialog
    :open="prepareOpen"
    :disabled="!!s.busy || !reviewed || s.plan?.status !== 'PROPOSED'"
    title="准备独立 WorkingCopy？"
    confirm-label="准备 WorkingCopy"
    @close="prepareOpen = false"
    @confirm="
      prepareOpen = false;
      s.prepare();
    "
    ><p>
      系统将从授权的基础 revision 创建隔离 Git 工作副本。原始 SoftwareAsset
      不作为代码修改目标。此操作会写入 Workspace，但不会执行 Step。
    </p></ConfirmDialog
  >
  <ConfirmDialog
    class="step-authorization-dialog"
    :open="!!stepToConfirm"
    :disabled="!!s.busy || !stepToConfirm || !!stepIssue(stepToConfirm)"
    title="确认授权此步骤？"
    cancel-label="返回审阅"
    confirm-label="确认授权"
    @close="stepToConfirm = null"
    @confirm="confirmSelectedStep"
    >
    <p class="step-authorization-identity">{{ stepToConfirmLabel }}</p>
    <section class="step-authorization-scope" aria-label="本次授权范围">
      <h3>本次授权范围</h3>
      <p class="step-authorization-scope-text" tabindex="0">{{ stepToConfirm?.scope }}</p>
    </section>
    <p v-if="stepToConfirm && stepIssue(stepToConfirm)" class="step-lock-reason">{{ stepIssue(stepToConfirm) }}</p>
    <p class="step-authorization-note">仅授权当前步骤，不会立即运行 Agent，也不会授权后续步骤。</p>
  </ConfirmDialog>
</template>

<style scoped>
.step-authorization-dialog {
  width: min(440px, calc(100% - 32px));
  padding: 24px;
}
.step-authorization-dialog :deep(h2) {
  margin: 0 0 16px;
  font-size: 20px;
}
.step-authorization-identity {
  margin: 0 0 12px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 600;
}
.step-authorization-scope {
  padding: 14px 16px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--canvas);
}
.step-authorization-scope h3 {
  margin: 0 0 8px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 500;
}
.step-authorization-scope-text {
  max-height: 36vh;
  margin: 0;
  overflow-y: auto;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
  line-height: 1.65;
}
.step-authorization-note {
  margin: 16px 0 0;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.7;
}
.step-authorization-dialog :deep(.actions) {
  justify-content: flex-end;
  margin-top: 20px;
}
@media (max-width: 480px) {
  .step-authorization-dialog { padding: 20px; }
  .step-authorization-dialog :deep(.actions) {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }
}
</style>
