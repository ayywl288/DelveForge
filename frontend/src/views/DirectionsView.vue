<script setup lang="ts">
import { computed, ref } from "vue";
import type { WorkspaceSession } from "../state/session";
import type { ProductDirection } from "../api/types";
import StatusBadge from "../components/StatusBadge.vue";
import ContentList from "../components/ContentList.vue";
import EvidenceList from "../components/EvidenceList.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
const props = defineProps<{ s: WorkspaceSession }>();
const emit = defineEmits<{ next: [] }>();
const repositoryIds = ref<string[]>([]);
const decision = ref<{
  direction: ProductDirection;
  action: "select" | "reject";
} | null>(null);
const eligible = computed(
  () =>
    props.s.profile?.status === "CONFIRMED" && repositoryIds.value.length > 0,
);
function stale(direction: ProductDirection) {
  return (
    direction.userProfileId !== props.s.profile?.id ||
    direction.userProfileRevision !== props.s.profile?.revision ||
    props.s.profile?.status !== "CONFIRMED"
  );
}
function decide() {
  if (!decision.value) return;
  props.s.decide(decision.value.direction.id, decision.value.action);
  decision.value = null;
}
</script>
<template>
  <section class="panel direction-input">
    <div>
      <span class="eyebrow">HUMAN CHOOSES DIRECTION</span>
      <h2>选择值得做的下一步</h2>
      <p class="muted">
        AI 提出候选，你决定方向。生成会调用 Provider，选择不会自动生成计划。
      </p>
    </div>
    <fieldset :disabled="!!s.busy">
      <legend>本次使用的 RepositoryProfile 快照</legend>
      <p v-if="!s.repositories.length" class="muted">先注册并分析仓库。</p>
      <label
        v-for="repository in s.repositories"
        :key="repository.id"
        class="check"
        ><input
          v-model="repositoryIds"
          type="checkbox"
          :value="repository.id"
        /><span
          >{{ repository.purpose
          }}<small class="meta block"
            >revision {{ repository.analyzedRevision.slice(0, 12) }}</small
          ></span
        ></label
      >
    </fieldset>
    <p v-if="s.profile?.status !== 'CONFIRMED'" class="inline-note">
      生成方向需要已确认的 UserProfile。
    </p>
    <button
      :disabled="!!s.busy || !eligible || !!s.receipt"
      @click="s.discover(repositoryIds)"
    >
      生成真实候选方向
    </button>
  </section>
  <div v-if="!s.directions.length" class="empty panel">
    尚无候选。选定分析快照后，开始发现。
  </div>
  <div class="direction-grid">
    <article
      v-for="(direction, index) in s.directions"
      :key="direction.id"
      class="panel direction-card"
      :class="{ selected: direction.status === 'SELECTED' }"
    >
      <div class="panel-header">
        <span class="eyebrow"
          >DIRECTION {{ String(index + 1).padStart(2, "0") }}</span
        ><StatusBadge :status="direction.status" />
      </div>
      <h2>{{ direction.title }}</h2>
      <p class="direction-problem">{{ direction.problem }}</p>
      <dl class="business-fields">
        <dt>目标产品</dt>
        <dd>{{ direction.targetProduct }}</dd>
        <dt>为什么适合你</dt>
        <dd>{{ direction.userFit }}</dd>
      </dl>
      <details class="direction-details">
        <summary>展开技术价值、差异与依据</summary>
        <dl class="business-fields">
          <dt>技术价值</dt>
          <dd>{{ direction.technicalValue }}</dd>
          <dt>差异</dt>
          <dd>{{ direction.differentiation }}</dd>
          <dt>复杂度</dt>
          <dd>{{ direction.estimatedComplexity }}</dd>
        </dl>
        <ContentList title="风险" :items="direction.risks" /><EvidenceList
          :bases="direction.evidenceSupport.userNeed"
          title="用户需求依据"
        /><EvidenceList
          :bases="direction.evidenceSupport.userFit"
          title="用户匹配依据"
        /><EvidenceList
          :bases="direction.evidenceSupport.reusableCapability"
          title="复用能力依据"
        />
        <p class="meta">画像 revision {{ direction.userProfileRevision }}</p>
        <details class="object-id">
          <summary>历史绑定</summary>
          <p>
            方向 <code>{{ direction.id }}</code>
          </p>
          <p>
            画像 <code>{{ direction.userProfileId }}</code>
          </p>
          <p>
            RepositoryProfiles
            <code>{{ direction.repositoryProfileIds.join(", ") }}</code>
          </p>
          <p>
            候选资产 <code>{{ direction.candidateAssetIds.join(", ") }}</code>
          </p>
        </details>
      </details>
      <p v-if="stale(direction)" class="inline-note">
        当前画像与此候选的依据版本不同。历史内容保留，不能用作本页的新规划输入。
      </p>
      <div v-if="direction.status === 'CANDIDATE'" class="actions">
        <button
          :disabled="!!s.busy || stale(direction) || !!s.receipt"
          @click="decision = { direction, action: 'select' }"
        >
          选择此方向</button
        ><button
          class="secondary"
          :disabled="!!s.busy"
          @click="decision = { direction, action: 'reject' }"
        >
          拒绝
        </button>
      </div>
      <button
        v-if="direction.status === 'SELECTED'"
        class="secondary"
        :disabled="stale(direction)"
        @click="emit('next')"
      >
        审阅演化规划 →
      </button>
    </article>
  </div>
  <ConfirmDialog
    :open="!!decision"
    :title="
      decision?.action === 'select' ? '选择这个产品方向？' : '拒绝这个候选？'
    "
    :confirm-label="decision?.action === 'select' ? '明确选择' : '明确拒绝'"
    @close="decision = null"
    @confirm="decide"
  >
    <h3>{{ decision?.direction.title }}</h3>
    <p>{{ decision?.direction.targetProduct }}</p>
    <p v-if="decision?.action === 'select'">
      如已有选中方向，它会被标记为 SUPERSEDED。此操作不授权代码执行。
    </p>
  </ConfirmDialog>
</template>
