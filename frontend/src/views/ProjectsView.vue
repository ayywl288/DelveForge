<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import type { WorkspaceSession } from "../state/session";
import type { ResourceSummary } from "../api/types";
import { api, ApiError } from "../api/client";
import { stageIndex, stageLabels, projectIssueText } from "../state/projectPresentation";
import StatusBadge from "../components/StatusBadge.vue";

const props = defineProps<{ s: WorkspaceSession }>();
const emit = defineEmits<{ open: [stage: number] }>();
const name = ref("");
const mode = ref<"new" | "reuse">("new");
const selectedProfile = ref("");
const profiles = ref<ResourceSummary[]>([]);
const resourceOffset = ref(0);
const moreProfiles = ref(false);
const loadingProfiles = ref(false);
const resourceError = ref("");
const confirmedProfiles = computed(() => profiles.value.filter(item => item.status === "CONFIRMED"));
async function loadProfiles(more = false) {
  if (loadingProfiles.value) return;
  loadingProfiles.value = true;
  resourceError.value = "";
  try {
    const offset = more ? resourceOffset.value : 0;
    const result = await api.resources("USER_PROFILE", offset);
    if (!Array.isArray(result)) throw new ApiError(0, "RESPONSE_UNREADABLE");
    profiles.value = more ? [...profiles.value, ...result] : result;
    resourceOffset.value = offset + result.length;
    moreProfiles.value = result.length === 50;
  } catch (failure) {
    resourceError.value = failure instanceof ApiError ? failure.message : "已有画像读取失败。";
  } finally {
    loadingProfiles.value = false;
  }
}
async function create() {
  const stage = await props.s.newProject(name.value.trim(), mode.value === "reuse" ? selectedProfile.value : null);
  if (stage) emit("open", stageIndex(stage));
}
async function open(key: string) {
  const stage = await props.s.openProject(key);
  if (stage) emit("open", stageIndex(stage));
}
onMounted(() => { props.s.loadProjects(); loadProfiles(); });
</script>
<template>
  <section class="panel new-project">
    <span class="eyebrow">YOUR PROJECT WORKSPACE</span>
    <h2>从一个新想法开始，也可以接着上次做</h2>
    <p class="muted">历史项目从后端读取。打开不会重新调用 AI、分析仓库、创建 WorkingCopy 或执行步骤。</p>
    <form class="stack" @submit.prevent="create">
      <label for="project-name">项目名称</label>
      <input id="project-name" v-model="name" required maxlength="120" :disabled="!!s.busy" placeholder="例如：我的本地 REST 服务" />
      <fieldset :disabled="!!s.busy">
        <legend>从哪里开始</legend>
        <label><input type="radio" v-model="mode" value="new" name="project-start" /> 从头探索，创建新的用户画像</label>
        <label><input type="radio" v-model="mode" value="reuse" name="project-start" /> 复用已有的已确认画像，再选择仓库</label>
      </fieldset>
      <template v-if="mode === 'reuse'">
        <label for="project-profile">选择已确认画像</label>
        <select id="project-profile" v-model="selectedProfile" :disabled="!!s.busy || loadingProfiles" required>
          <option value="" disabled>请选择</option>
          <option v-for="profile in confirmedProfiles" :key="profile.id" :value="profile.id">
            {{ profile.summary }} · 画像版本 {{ profile.revision }} · {{ profile.id.slice(0, 8) }}
          </option>
        </select>
        <p class="meta">复用身份，不复制、修改或自动重新确认画像；旧 Plan 的版本绑定保持不变。</p>
        <p v-if="!confirmedProfiles.length && !loadingProfiles" class="muted">当前页没有已确认画像。</p>
        <p v-if="resourceError" class="field-error" role="alert">{{ resourceError }}</p>
        <button type="button" class="text-button" :disabled="loadingProfiles" @click="loadProfiles(false)">重新读取画像</button>
        <button v-if="moreProfiles" type="button" class="text-button" :disabled="loadingProfiles" @click="loadProfiles(true)">更多画像</button>
      </template>
      <button :disabled="!!s.busy || !name.trim() || mode === 'reuse' && !selectedProfile">建立新流程</button>
    </form>
  </section>
  <section class="history-section" aria-labelledby="history-heading">
    <div class="panel-header">
      <h2 id="history-heading">历史项目</h2>
      <button class="secondary" :disabled="s.historyLoading" @click="s.loadProjects(false)">刷新历史项目</button>
    </div>
    <p v-if="s.historyLoading" role="status">正在读取已保存的项目…</p>
    <p v-if="s.historyError" class="alert" role="alert">{{ s.historyError }}</p>
    <p v-if="!s.historyLoading && !s.historyError && !s.projects.length" class="panel muted">还没有项目。已有但未关联的画像和仓库可以在新流程中明确选择。</p>
    <div class="project-grid">
      <article v-for="project in s.projects" :key="project.key" class="panel project-card">
        <span class="eyebrow">{{ stageLabels[project.stage] }}</span>
        <h3>{{ project.name }}</h3>
        <p v-if="project.direction && project.direction !== project.name">{{ project.direction }}</p>
        <p v-for="repo in project.repositories" :key="repo.assetId" class="meta project-path">{{ repo.location }}</p>
        <template v-if="project.planId">
          <p class="project-progress"><strong>{{ project.succeededSteps }}/{{ project.totalSteps }}</strong> 个 Step 已验证通过</p>
          <div class="history-step-counts"><span v-for="(count, status) in project.stepCounts" :key="status"><StatusBadge :status="String(status)" :show-code="false" /> {{ count }}</span></div>
          <p v-if="project.lastVerifiedRevision" class="meta">最后验证版本 <code>{{ project.lastVerifiedRevision.slice(0, 12) }}</code></p>
          <p v-if="project.currentRevision" class="meta">当前版本 <code>{{ project.currentRevision.slice(0, 12) }}</code></p>
        </template>
        <p class="meta">{{ project.lastActivity ? `最近保存的活动：${new Date(project.lastActivity).toLocaleString('zh-CN')}` : '历史记录未保存活动时间' }}</p>
        <p v-if="project.legacy" class="meta">旧 Plan 的只读历史投影；点击继续后才建立项目身份。</p>
        <details v-if="project.issues.length"><summary>有 {{ project.issues.length }} 项需要核对</summary><p v-for="issue in project.issues" :key="issue">{{ projectIssueText(issue) }}</p></details>
        <button :disabled="!!s.busy" @click="open(project.key)">打开并继续</button>
      </article>
    </div>
    <button v-if="s.historyMore" class="secondary" :disabled="s.historyLoading" @click="s.loadProjects(true)">更多历史项目</button>
  </section>
</template>
