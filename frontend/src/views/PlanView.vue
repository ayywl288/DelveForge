<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { WorkspaceSession } from "../state/session";
import type { EvolutionStep } from "../api/types";
import ContentList from "../components/ContentList.vue";
import StatusBadge from "../components/StatusBadge.vue";
import EvidenceList from "../components/EvidenceList.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
const props = defineProps<{ s: WorkspaceSession }>();
const emit = defineEmits<{ next: [] }>();
const base = ref("");
const reviewed = ref(false);
const prepareOpen = ref(false);
const stepToConfirm = ref<EvolutionStep | null>(null);
watch(() => props.s.plan?.id, () => {
  reviewed.value = false;
  prepareOpen.value = false;
  stepToConfirm.value = null;
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
const asset = computed(() => props.s.assets.find(item =>
  item.id === bases.value.find(repository => repository.id === base.value)?.assetId));
const authorizationReady = computed(() => !!asset.value?.readPermissionAllowed &&
  asset.value.usageAuthorization === "ALLOWED" && !!asset.value.licenseInfo?.trim());
</script>
<template>
  <section v-if="!s.plan" class="panel plan-input">
    <span class="eyebrow">EVOLUTION BEFORE REWRITE</span>
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
        <p v-if="base && !authorizationReady" class="inline-note">
          演化基础需允许读取、明确允许使用，并记录许可证信息。请返回左侧「仓库理解」，在此资产的「完善许可证与授权」中显式保存；无需重新分析或重新生成方向。
        </p>
        <button :disabled="!!s.busy || !base || !authorizationReady || !currentBasis || !!s.receipt">
          生成演化计划（调用 AI）
        </button>
      </form>
    </template>
    <p v-else class="empty">请先明确选择一个产品方向。</p>
  </section>
  <template v-else>
    <section class="panel">
      <div class="panel-header">
        <div>
          <span class="eyebrow">EVOLUTION PLAN</span>
          <h2>{{ s.plan.targetState.targetProduct }}</h2>
        </div>
        <StatusBadge :status="s.plan.status" />
      </div>
      <div class="state-comparison">
        <section>
          <span class="eyebrow">CURRENT STATE</span>
          <h3>{{ s.plan.currentState.summary }}</h3>
          <ContentList
            title="能力"
            :items="s.plan.currentState.capabilities"
          /><ContentList
            title="模块"
            :items="s.plan.currentState.modules"
          /><ContentList
            title="限制"
            :items="s.plan.currentState.limitations"
          />
        </section>
        <section>
          <span class="eyebrow">TARGET STATE</span>
          <h3>{{ s.plan.targetState.problem }}</h3>
          <p>{{ s.plan.targetState.targetProduct }}</p>
          <p>{{ s.plan.targetState.differentiation }}</p>
          <ContentList
            title="可复用能力"
            :items="s.plan.reusableCapabilities"
          />
        </section>
      </div>
      <div class="two-column">
        <ContentList title="计划改变什么" :items="s.plan.changes" /><ContentList
          title="风险"
          :items="s.plan.risks"
        />
      </div>
      <EvidenceList :bases="s.plan.evidence" title="规划依据" />
      <details class="object-id">
        <summary>计划与基础绑定</summary>
        <p>
          Plan <code>{{ s.plan.id }}</code>
        </p>
        <p>
          Direction <code>{{ s.plan.productDirectionId }}</code>
        </p>
        <p>
          Asset <code>{{ s.plan.baseAssetId }}</code>
        </p>
        <p>
          RepositoryProfile <code>{{ s.plan.baseRepositoryProfileId }}</code>
        </p>
      </details>
    </section>
    <ol class="step-list">
      <li
        v-for="(step, index) in s.plan.steps"
        :key="step.id"
        class="panel step-card"
      >
        <div class="step-number" aria-hidden="true">
          {{ String(index + 1).padStart(2, "0") }}
        </div>
        <div class="step-content">
          <div class="panel-header">
            <h2>{{ step.goal }}</h2>
            <StatusBadge :status="step.status" />
          </div>
          <h3>允许范围</h3>
          <p class="scope">{{ step.scope }}</p>
          <ContentList title="Planned Changes" :items="step.plannedChanges" />
          <h3>Verification Criteria</h3>
          <ol class="criteria">
            <li
              v-for="(criterion, criterionIndex) in step.verificationCriteria"
              :key="criterionIndex"
            >
              <p>{{ criterion.description }}</p>
              <code>{{ criterion.kind }}</code>
            </li>
          </ol>
          <p class="meta">
            顺序 {{ index + 1 }} · Step <code>{{ step.id }}</code>
          </p>
          <button
            v-if="step.status === 'PENDING_CONFIRMATION'"
            class="secondary"
            :disabled="
              !!s.busy ||
              !!s.receipt ||
              s.plan.status !== 'ACTIVE' ||
              s.copy?.status !== 'READY' ||
              s.plan.steps
                .slice(0, index)
                .some((item) => item.status !== 'SUCCEEDED')
            "
            @click="stepToConfirm = step"
          >
            审阅并授权此 Step
          </button>
          <p v-if="step.status === 'READY'" class="inline-note">
            此 Step 已授权；执行仍需在下一阶段单独点击确认。
          </p>
        </div>
      </li>
    </ol>
    <section v-if="s.plan.status === 'PROPOSED'" class="panel prepare-box">
      <h2>审阅计划，然后准备隔离环境</h2>
      <p>
        没有独立的 Plan confirm API。下列审阅确认仅是页面交互；Prepare
        通过后端规则激活 Plan 并创建 WorkingCopy。各 Step 仍需独立授权。
      </p>
      <label class="check"
        ><input
          v-model="reviewed"
          type="checkbox"
          :disabled="!!s.busy"
        />我已审阅目标、范围、步骤与验收标准</label
      ><button
        :disabled="!!s.busy || !reviewed || !!s.receipt"
        @click="prepareOpen = true"
      >
        Prepare WorkingCopy
      </button>
    </section>
    <div v-else class="stage-footer">
      <p>
        准备与授权是不同的决定。查看实际 WorkingCopy，再手动执行一个 READY
        Step。
      </p>
      <button @click="emit('next')">进入演化执行 →</button>
    </div>
  </template>
  <ConfirmDialog
    :open="prepareOpen"
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
    :open="!!stepToConfirm"
    title="授权此 Step？"
    confirm-label="明确授权 Step"
    @close="stepToConfirm = null"
    @confirm="
      s.confirmStep(stepToConfirm!.id);
      stepToConfirm = null;
    "
    ><h3>{{ stepToConfirm?.goal }}</h3>
    <p>{{ stepToConfirm?.scope }}</p>
    <p>授权仅覆盖当前 Step，不会自动执行或授权后续步骤。</p></ConfirmDialog
  >
</template>
