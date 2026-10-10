<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { createSession } from "./state/session";
import DiscoveryView from "./views/DiscoveryView.vue";
import RepositoryView from "./views/RepositoryView.vue";
import DirectionsView from "./views/DirectionsView.vue";
import PlanView from "./views/PlanView.vue";
import EvolutionView from "./views/EvolutionView.vue";
import ConfirmDialog from "./components/ConfirmDialog.vue";
const s = reactive(createSession());
const stage = ref(0);
const stages = [
  {
    title: "需求探索",
    en: "User Discovery",
    description: "从你的兴趣、日常和目标中，找到值得解决的问题。",
  },
  {
    title: "理解仓库",
    en: "Repository Understanding",
    description: "理解已有代码能做什么，让演化有据可依。",
  },
  {
    title: "产品方向",
    en: "Product Direction",
    description: "比较真实候选，明确选择你愿意投入的方向。",
  },
  {
    title: "演化规划",
    en: "Evolution Planning",
    description: "复用已有能力，把目标拆成有范围、有验收标准的步骤。",
  },
  {
    title: "验证演化",
    en: "Verified Evolution",
    description: "观察代码变更与真实验证，只有通过验证才推进可信 revision。",
  },
];
const done = computed(() => [
  s.profile?.status === "CONFIRMED",
  s.repositories.length > 0,
  !!s.selected,
  !!s.plan?.workingCopyId,
  s.plan?.steps.some((step) => step.status === "SUCCEEDED"),
]);
const restoreOpen = ref(false);
const recover = reactive({
  profile: "",
  assets: "",
  repositories: "",
  directions: "",
  plan: "",
});
function openRestore() {
  recover.profile = s.ids.profileId;
  recover.assets = s.ids.assetIds.join("\n");
  recover.repositories = s.ids.repositoryIds.join("\n");
  recover.directions = s.ids.directionIds.join("\n");
  recover.plan = s.ids.planId;
  restoreOpen.value = true;
}
function restoreIds() {
  const list = (value: string) =>
    value
      .split(/[\s,]+/)
      .map((item) => item.trim())
      .filter(Boolean)
      .slice(0, 100);
  restoreOpen.value = false;
  s.importIds({
    profileId: recover.profile.trim(),
    assetIds: list(recover.assets),
    repositoryIds: list(recover.repositories),
    directionIds: list(recover.directions),
    planId: recover.plan.trim(),
  });
}
onMounted(async () => {
  await s.restore();
  if (s.plan || s.receipt) stage.value = 4;
  else if (s.selected) stage.value = 3;
  else if (s.directions.length) stage.value = 2;
  else if (s.profile?.status === "CONFIRMED") stage.value = 1;
});
onUnmounted(s.dispose);
</script>
<template>
  <a href="#main-content" class="skip-link">跳至主要内容</a>
  <div class="app-shell">
    <aside class="sidebar">
      <a
        class="brand"
        href="#"
        aria-label="DelveForge 首页"
        @click.prevent="stage = 0"
        ><span class="brand-mark" aria-hidden="true">d<span>f</span></span
        ><span>DelveForge<small>PERSONAL SOFTWARE EVOLUTION</small></span></a
      >
      <div class="nav-label">从想法，到下一次提交</div>
      <nav aria-label="演化阶段">
        <button
          v-for="(item, index) in stages"
          :key="item.en"
          class="nav-stage"
          :class="{ active: stage === index }"
          :aria-current="stage === index ? 'step' : undefined"
          @click="stage = index"
        >
          <span class="nav-number"
            >{{ done[index] ? "✓" : `0${index + 1}`
            }}<span class="sr-only">{{
              done[index] ? "已有阶段结果" : ""
            }}</span></span
          ><span
            >{{ item.title }}<small>{{ item.en }}</small></span
          ><span class="nav-arrow" aria-hidden="true">↗</span>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <p>
          <span
            class="connection-dot"
            :class="{ online: s.connected }"
            aria-hidden="true"
          />{{ s.connected ? "后端连接已确认" : "后端尚未连接" }}
        </p>
        <button
          class="sidebar-button"
          :disabled="!!s.busy || !!s.receipt"
          @click="openRestore"
        >
          恢复已知对象
        </button>
        <p class="meta">M0–M4 · 本地受控运行<br />M5 暂缓</p>
      </div>
    </aside>
    <main id="main-content" class="main-content" tabindex="-1">
      <header class="page-header">
        <div>
          <div class="eyebrow">
            DELVEFORGE / {{ stages[stage]?.en.toUpperCase() }}
          </div>
          <h1>{{ stages[stage]?.title }}<span class="heading-dot">.</span></h1>
          <p>{{ stages[stage]?.description }}</p>
        </div>
        <button
          class="secondary refresh"
          :disabled="!!s.busy"
          @click="s.restore"
        >
          刷新已知对象
        </button>
      </header>
      <div v-if="s.error" class="alert" role="alert">
        <strong>操作需要核对</strong>
        <p>{{ s.error }}</p>
        <button class="text-button" :disabled="!!s.busy" @click="s.restore">
          重新查询已知对象
        </button>
      </div>
      <div v-if="s.notice" class="inline-note" role="status">
        {{ s.notice }}
      </div>
      <div v-if="s.busy" class="global-request" role="status">
        <span class="request-dot" aria-hidden="true" />{{ s.busy }} ·
        等待实际响应，未自动重试
      </div>
      <KeepAlive><DiscoveryView
        v-if="stage === 0"
        :s="s"
        @next="stage = 1"
      /><RepositoryView
        v-else-if="stage === 1"
        :s="s"
        @next="stage = 2"
      /><DirectionsView
        v-else-if="stage === 2"
        :s="s"
        @next="stage = 3"
      /><PlanView
        v-else-if="stage === 3"
        :s="s"
        @next="stage = 4"
      /><EvolutionView v-else :s="s" /></KeepAlive>
      <footer class="app-footer">
        <span>AI 提出建议 · 你选择方向 · 后端决定状态</span
        ><span>本地 Web Experience</span>
      </footer>
    </main>
  </div>
  <ConfirmDialog
    :open="restoreOpen"
    title="恢复已知业务对象"
    confirm-label="只读查询并恢复"
    @close="restoreOpen = false"
    @confirm="restoreIds"
  >
    <p>
      这里只保存对象 ID；画像、源码、模型响应和凭据不写入浏览器存储。现有 API
      没有历史列表，请使用已知
      ID。空白字段表示不加载该类对象，不会删除后端数据。
    </p>
    <div class="stack">
      <label for="recover-profile">UserProfile ID</label
      ><input id="recover-profile" name="recover-profile" autocomplete="off" v-model="recover.profile" /><label
        for="recover-assets"
        >SoftwareAsset IDs（每行一个）</label
      ><textarea id="recover-assets" name="recover-assets" autocomplete="off" v-model="recover.assets" rows="2" /><label
        for="recover-repos"
        >RepositoryProfile IDs（每行一个）</label
      ><textarea
        id="recover-repos" name="recover-repos" autocomplete="off"
        v-model="recover.repositories"
        rows="2"
      /><label for="recover-directions">ProductDirection IDs（每行一个）</label
      ><textarea
        id="recover-directions" name="recover-directions" autocomplete="off"
        v-model="recover.directions"
        rows="2"
      /><label for="recover-plan">EvolutionPlan ID</label
      ><input id="recover-plan" name="recover-plan" autocomplete="off" v-model="recover.plan" />
    </div>
  </ConfirmDialog>
</template>
