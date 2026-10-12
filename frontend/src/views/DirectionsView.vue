<script setup lang="ts">
import { computed, nextTick, ref } from "vue";
import type { WorkspaceSession } from "../state/session";
import type { ProductDirection } from "../api/types";
import StatusBadge from "../components/StatusBadge.vue";
import ContentList from "../components/ContentList.vue";
import EvidenceList from "../components/EvidenceList.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import DirectionListEntry from "../components/DirectionListEntry.vue";
import NavigationIcon from "../components/NavigationIcon.vue";
const props = defineProps<{ s: WorkspaceSession }>();
const emit = defineEmits<{ next: [] }>();
const repositoryIds = ref<string[]>([]);
const viewedId = ref("");
const currentDirections = computed(() => props.s.directions.filter(item =>
  item.status === "CANDIDATE" || item.status === "SELECTED"));
const historicalDirections = computed(() => props.s.directions.filter(item =>
  item.status === "REJECTED" || item.status === "SUPERSEDED"));
const viewed = computed(() => props.s.directions.find(item => item.id === viewedId.value)
  ?? currentDirections.value.find(item => item.status === "SELECTED")
  ?? currentDirections.value[0] ?? historicalDirections.value[0]);
async function viewDirection(id: string) {
  viewedId.value = id;
  await nextTick();
  if (window.innerWidth <= 960) {
    document.getElementById("direction-detail")?.focus();
  }
}
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
  <details class="panel direction-input" :open="!s.directions.length">
    <summary>生成候选方向 <span class="meta">选择已保存的仓库分析</span></summary>
    <div>
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
      生成候选方向（调用 AI）
    </button>
  </details>
  <div v-if="!s.directions.length" class="empty panel">
    尚无候选。选定分析快照后，开始发现。
  </div>
  <div v-if="viewed" class="direction-workspace">
    <aside id="direction-list" class="panel direction-list" aria-label="方向列表">
      <h2 class="section-heading"><NavigationIcon name="direction" />候选与已选方向 <span class="count-pill">{{ currentDirections.length }}</span></h2>
      <p class="meta">点击查看详情，选择需单独确认。</p>
      <div class="direction-list-scroll" tabindex="0" role="region" aria-label="候选与历史方向列表">
      <DirectionListEntry v-for="(direction, index) in currentDirections" :key="direction.id"
        :direction="direction" :index="index" :active="viewed.id === direction.id" @view="viewDirection(direction.id)" />
      <p v-if="!currentDirections.length" class="empty">当前只有历史方向。</p>
      <details v-if="historicalDirections.length" class="direction-history"
        :open="viewed.status === 'REJECTED' || viewed.status === 'SUPERSEDED'">
        <summary>历史方向 · {{ historicalDirections.length }}</summary>
        <DirectionListEntry v-for="(direction, index) in historicalDirections" :key="direction.id"
          :direction="direction" :index="index" :active="viewed.id === direction.id" @view="viewDirection(direction.id)" />
      </details>
      </div>
    </aside>
    <article id="direction-detail" :key="viewed.id" class="panel direction-detail" aria-label="当前查看的方向" tabindex="-1">
      <div class="direction-detail-controls">
      <a href="#direction-list" class="mobile-return">返回方向列表 ↑</a>
      <div class="panel-header">
        <h2>{{ viewed.title }}</h2><StatusBadge :status="viewed.status" />
      </div>
      <p v-if="stale(viewed)" class="inline-note">
        当前画像与此候选的依据版本不同。历史内容保留，不能用作本页的新规划输入。
      </p>
      <div v-if="viewed.status === 'CANDIDATE' || viewed.status === 'SELECTED'" class="direction-decision">
        <div v-if="viewed.status === 'CANDIDATE'" class="actions detail-actions">
          <button
            :disabled="!!s.busy || stale(viewed) || !!s.receipt"
            @click="decision = { direction: viewed, action: 'select' }"
          >
            <NavigationIcon name="check" />选择此方向
          </button>
          <button
            class="secondary"
            :disabled="!!s.busy"
            @click="decision = { direction: viewed, action: 'reject' }"
          >
            拒绝
          </button>
        </div>
        <button
          v-if="viewed.status === 'SELECTED'"
          class="direction-next"
          :disabled="stale(viewed)"
          @click="emit('next')"
        >
          <NavigationIcon name="planning" />审阅演化规划 <NavigationIcon name="arrow" />
        </button>
      </div>
      </div>
      <div class="direction-detail-body" tabindex="0" role="region" aria-label="方向原文与依据">
      <section class="reading-section problem-section"><h3 class="section-heading"><NavigationIcon name="discovery" />要解决的问题</h3><p>{{ viewed.problem }}</p></section>
      <section class="reading-section target-section"><h3 class="section-heading"><NavigationIcon name="target" />目标产品</h3><p>{{ viewed.targetProduct }}</p></section>
      <details class="reading-disclosure" open><summary><NavigationIcon name="user" />为什么适合你</summary><p>{{ viewed.userFit }}</p></details>
      <details class="reading-disclosure"><summary>差异化</summary><p>{{ viewed.differentiation }}</p></details>
      <details class="reading-disclosure"><summary><NavigationIcon name="code" />技术价值与复杂度</summary>
        <p>{{ viewed.technicalValue }}</p><h3>预估复杂度</h3><p>{{ viewed.estimatedComplexity }}</p>
      </details>
      <details class="reading-disclosure"><summary>风险 · {{ viewed.risks.length }}</summary>
        <ContentList title="已提出的风险" :items="viewed.risks" />
      </details>
      <details class="reading-disclosure direction-evidence"><summary>方向依据</summary><EvidenceList
          :bases="viewed.evidenceSupport.userNeed"
          title="用户需求依据"
        /><EvidenceList
          :bases="viewed.evidenceSupport.userFit"
          title="用户匹配依据"
        /><EvidenceList
          :bases="viewed.evidenceSupport.reusableCapability"
          title="复用能力依据"
        />
      </details>
        <p class="meta">依据画像版本 {{ viewed.userProfileRevision }}</p>
        <details class="object-id">
          <summary>历史绑定</summary>
          <p>
            方向 <code>{{ viewed.id }}</code>
          </p>
          <p>
            画像 <code>{{ viewed.userProfileId }}</code>
          </p>
          <p>
            RepositoryProfiles
            <code>{{ viewed.repositoryProfileIds.join(", ") }}</code>
          </p>
          <p>
            候选资产 <code>{{ viewed.candidateAssetIds.join(", ") }}</code>
          </p>
        </details>
      <p v-if="viewed.status === 'REJECTED' || viewed.status === 'SUPERSEDED'" class="context-note">
        历史方向仅供查看，不能从此处恢复为已选择状态。
      </p>
      </div>
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
