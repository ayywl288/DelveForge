<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { createSession } from "./state/session";
import DiscoveryView from "./views/DiscoveryView.vue";
import RepositoryView from "./views/RepositoryView.vue";
import DirectionsView from "./views/DirectionsView.vue";
import PlanView from "./views/PlanView.vue";
import EvolutionView from "./views/EvolutionView.vue";
import ConfirmDialog from "./components/ConfirmDialog.vue";
import ProjectsView from "./views/ProjectsView.vue";
import NavigationIcon, { type NavigationIconName } from "./components/NavigationIcon.vue";
import { stageIndex, projectIssueText } from "./state/projectPresentation";
const s = reactive(createSession());
const stage = ref(-1);
const authorizationTargetId = ref("");
async function openAuthorization(assetId: string) {
  // 先按精确身份读取当前授权；找不到时保留原页和明确错误，不改选同路径记录。
  authorizationTargetId.value = "";
  if (await s.loadAsset(assetId)) {
    authorizationTargetId.value = assetId;
    stage.value = 1;
  }
}
function returnPlanning() {
  authorizationTargetId.value = "";
  stage.value = 3;
}
function projectOpened(value: number) {
  authorizationTargetId.value = "";
  stage.value = value;
}
async function inspectPendingProject() {
  if (!s.receipt) return;
  const result = await s.openProject("plan:" + s.receipt.planId);
  if (result) projectOpened(stageIndex(result));
}
const stages: { title: string; en: string; icon: NavigationIconName; description: string }[] = [
  {
    title: "需求探索",
    en: "User Discovery",
    icon: "discovery",
    description: "从你的兴趣、日常和目标中，找到值得解决的问题。",
  },
  {
    title: "理解仓库",
    en: "Repository Understanding",
    icon: "repository",
    description: "理解已有代码能做什么，让演化有据可依。",
  },
  {
    title: "产品方向",
    en: "Product Direction",
    icon: "direction",
    description: "比较真实候选，明确选择你愿意投入的方向。",
  },
  {
    title: "演化规划",
    en: "Evolution Planning",
    icon: "planning",
    description: "复用已有能力，把目标拆成有范围、有验收标准的步骤。",
  },
  {
    title: "验证演化",
    en: "Verified Evolution",
    icon: "evolution",
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
  const restored = await s.initialize();
  if (restored) stage.value = stageIndex(restored);
  else if (s.plan || s.receipt) stage.value = 4;
  else if (s.selected) stage.value = 3;
  else if (s.directions.length) stage.value = 2;
  else if (s.profile?.status === "CONFIRMED") stage.value = 1;
  else if (s.profile) stage.value = 0;
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
        @click.prevent="stage = -1"
        ><span class="brand-mark" aria-hidden="true">df</span
        ><span>DelveForge</span></a
      >
      <nav class="app-navigation" aria-label="工作空间">
        <button class="nav-stage project-entry" :class="{ active: stage === -1 }"
          :aria-current="stage === -1 ? 'page' : undefined" @click="stage = -1">
          <NavigationIcon name="projects" /><span>项目工作台</span>
        </button>
      </nav>
      <div class="project-navigation-heading">
        <span class="nav-label">当前项目</span>
        <p class="sidebar-project">{{ s.projectContext?.name ?? s.plan?.targetState.targetProduct ?? '尚未打开项目' }}</p>
      </div>
      <nav class="workflow-navigation" aria-label="演化阶段">
        <button
          v-for="(item, index) in stages"
          :key="item.en"
          class="nav-stage"
          :class="{ active: stage === index }"
          :aria-current="stage === index ? 'step' : undefined"
          @click="stage = index"
        >
          <NavigationIcon :name="item.icon" /><span>{{ item.title }}</span>
          <span v-if="done[index]" class="nav-result"><span class="sr-only">已有阶段结果</span></span>
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
        <details class="advanced-recovery"><summary>高级调试</summary><button
          class="sidebar-button"
          :disabled="!!s.busy || !!s.receipt"
          @click="openRestore"
        >
          恢复已知对象
        </button></details>
        <p class="meta">M0–M4 · 本地受控运行<br />M5 暂缓</p>
      </div>
    </aside>
    <main id="main-content" class="main-content" tabindex="-1">
      <header class="page-header">
        <div>
          <h1>{{ stage === -1 ? '项目工作台' : stages[stage]?.title }}</h1>
          <p>{{ stage === -1 ? '管理已保存的演化流程，继续上次进度。' : stages[stage]?.description }}</p>
        </div>
        <button
          class="secondary refresh"
          v-if="stage !== -1"
          :disabled="!!s.busy"
          @click="s.restore()"
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
      <div v-if="s.receipt && s.receipt.planId !== s.plan?.id" class="inline-note" role="status">
        另一个 Plan 的执行结果仍待核对。切换项目没有取消后端执行，新的 execute 暂停提交。
        <button class="text-button" :disabled="!!s.busy" @click="inspectPendingProject">查询上次执行项目</button>
      </div>
      <details v-if="stage !== -1 && s.projectIssues.length" class="panel project-issues" open>
        <summary>恢复到已保存结果，还有以下问题需要核对</summary>
        <p v-for="issue in s.projectIssues" :key="issue">{{ projectIssueText(issue) }}</p>
        <p v-if="s.boundProfileRevision !== null" class="meta">历史 Plan 绑定画像版本 {{ s.boundProfileRevision }}；当前画像版本 {{ s.profile?.revision ?? '不可读' }}。没有重写历史绑定。</p>
      </details>
      <ProjectsView v-if="stage === -1" :s="s" @open="projectOpened" />
      <KeepAlive v-else :key="s.contextEpoch"><DiscoveryView
        v-if="stage === 0"
        :s="s"
        @next="stage = 1"
      /><RepositoryView
        v-else-if="stage === 1"
        :s="s"
        :authorization-target-id="authorizationTargetId"
        @return-planning="returnPlanning"
        @next="stage = 2"
      /><DirectionsView
        v-else-if="stage === 2"
        :s="s"
        @next="stage = 3"
      /><PlanView
        v-else-if="stage === 3"
        :s="s"
        @authorize="openAuthorization"
          @next="stage = 4"
          @directions="stage = 2"
      /><EvolutionView v-else :s="s" /></KeepAlive>
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
      可从工作台打开历史项目；此入口用于精确查询已知 ID。
      空白字段表示不加载该类对象，不会删除后端数据。
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
