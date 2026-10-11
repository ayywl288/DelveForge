<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import type { WorkspaceSession } from "../state/session";
import type { ResourceSummary, ProjectSummary } from "../api/types";
import { api, ApiError } from "../api/client";
import { stageIndex, stageLabels, projectIssueText } from "../state/projectPresentation";
import StatusBadge from "../components/StatusBadge.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import NavigationIcon from "../components/NavigationIcon.vue";

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
const deleting = ref<ProjectSummary | null>(null);
async function deleteConfirmed() {
  const project = deleting.value;
  if (!project) return;
  if (await props.s.deleteProject(project)) deleting.value = null;
}
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
  <details class="panel new-project">
    <summary class="new-project-trigger"><NavigationIcon name="plus" />开始新流程<span class="meta">新建用户画像，或复用已确认画像</span></summary>
    <form class="stack" @submit.prevent="create">
      <label for="project-name">项目名称</label>
      <input id="project-name" v-model="name" required maxlength="120" :disabled="!!s.busy" placeholder="例如：我的本地 REST 服务" />
      <fieldset class="project-start-options" :disabled="!!s.busy">
        <legend>从哪里开始</legend>
        <label><input type="radio" v-model="mode" value="new" name="project-start" /><span>从头探索<small>创建新的用户画像</small></span></label>
        <label><input type="radio" v-model="mode" value="reuse" name="project-start" /><span>复用已确认画像<small>保留画像身份，再选择仓库</small></span></label>
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
  </details>
  <section class="history-section" aria-labelledby="history-heading">
    <div class="panel-header">
      <h2 id="history-heading">{{ s.showingDeletedProjects ? '已删除项目' : '我的项目' }}</h2>
      <div class="history-controls">
        <div class="project-filters" role="group" aria-label="项目可见性">
          <button class="secondary" :aria-pressed="!s.showingDeletedProjects" @click="s.loadProjects(false, false)">正常项目</button>
          <button class="secondary" :aria-pressed="s.showingDeletedProjects" @click="s.loadProjects(false, true)">已删除项目</button>
        </div>
        <button class="text-button" :disabled="s.historyLoading" @click="s.loadProjects(false)">刷新列表</button>
      </div>
    </div>
    <p class="history-description muted">打开项目只读取已保存结果，不会调用 AI、创建副本或执行步骤。</p>
    <p v-if="s.historyLoading" role="status" class="meta">正在读取已保存的项目…</p>
    <p v-if="s.historyError" class="alert" role="alert">{{ s.historyError }}</p>
    <p v-if="s.showingDeletedProjects" class="muted">这里仅恢复工作台导航记录。业务数据、本地代码和验证版本一直保留；恢复不会重新选择方向或激活旧计划。</p>
    <div v-if="!s.historyLoading && !s.historyError && !s.projects.length" class="panel workspace-empty">
      <NavigationIcon name="projects" />
      <h3>{{ s.showingDeletedProjects ? '没有已删除项目' : '从第一个项目开始' }}</h3>
      <p class="muted">{{ s.showingDeletedProjects ? '删除的导航记录会显示在这里，已有业务结果和代码保留。' : '展开「开始新流程」填写名称。已有画像和仓库可以在流程中明确选择。' }}</p>
    </div>
    <div v-if="s.projects.length" class="project-list" :aria-busy="s.historyLoading">
      <article v-for="project in s.projects" :key="project.key" class="project-row" :aria-label="project.name">
        <div class="project-row-main">
          <div class="project-identity">
            <span class="project-symbol"><NavigationIcon name="projects" /></span>
            <div>
              <div class="project-title"><h3>{{ project.name }}</h3><StatusBadge v-if="project.planStatus" :status="project.planStatus" :show-code="false" /></div>
              <p v-if="project.direction && project.direction !== project.name" class="project-description muted">{{ project.direction }}</p>
              <p v-if="project.legacy" class="meta">旧 Plan 历史投影 · 继续后建立导航身份</p>
            </div>
          </div>
          <div class="project-stage"><span class="meta">当前阶段</span><span>{{ stageLabels[project.stage] }}</span></div>
          <div class="project-activity">
            <p v-if="project.planId && project.totalSteps" class="project-progress"><strong>{{ project.succeededSteps }}/{{ project.totalSteps }}</strong> 个 Step 已验证通过</p>
            <p v-else class="meta">{{ project.planId ? '计划尚无步骤' : '尚未生成计划' }}</p>
            <p class="meta">{{ project.lastActivity ? '最近保存 ' : '未保存活动时间' }}<time v-if="project.lastActivity" :datetime="project.lastActivity">{{ new Date(project.lastActivity).toLocaleString('zh-CN', { dateStyle: 'short', timeStyle: 'short' }) }}</time></p>
          </div>
          <div class="project-actions">
            <button v-if="!project.deletedAt" class="secondary" :disabled="!!s.busy" @click="open(project.key)">打开并继续</button>
            <button v-else class="secondary" :disabled="!!s.busy" @click="s.restoreProject(project)">恢复到工作台</button>
          </div>
        </div>
        <div class="project-row-secondary">
          <details class="project-details">
            <summary>项目详情</summary>
            <div class="project-metadata">
              <p v-for="repo in project.repositories" :key="repo.assetId" class="meta project-path">仓库 <code>{{ repo.location }}</code></p>
              <template v-if="project.planId">
                <div class="history-step-counts"><span v-for="(count, status) in project.stepCounts" :key="status"><StatusBadge :status="String(status)" :show-code="false" /> {{ count }}</span></div>
                <p class="meta">Plan <code>{{ project.planId }}</code></p>
                <p v-if="project.workingCopyId" class="meta">WorkingCopy ID <code>{{ project.workingCopyId }}</code></p>
                <p v-if="project.lastVerifiedRevision" class="meta">最后验证版本 <code>{{ project.lastVerifiedRevision }}</code></p>
                <p v-if="project.currentRevision" class="meta">当前版本 <code>{{ project.currentRevision }}</code></p>
              </template>
              <p v-if="project.deletedAt" class="meta">删除时间：{{ new Date(project.deletedAt).toLocaleString('zh-CN') }}</p>
              <button v-if="!project.deletedAt" class="text-button" :disabled="!!s.busy || s.deletionBlocked(project)" @click="deleting = project">删除项目</button>
              <p v-if="!project.deletedAt && s.deletionBlocked(project)" class="meta">已授权或未决执行期间不能删除；请查询真实状态，不要重复 execute。</p>
            </div>
          </details>
          <details v-if="project.issues.length" class="project-row-issues">
            <summary>有 {{ project.issues.length }} 项需要核对</summary><p v-for="issue in project.issues" :key="issue">{{ projectIssueText(issue) }}</p>
          </details>
        </div>
      </article>
    </div>
    <button v-if="s.historyMore" class="secondary" :disabled="s.historyLoading" @click="s.loadProjects(true)">更多历史项目</button>
  </section>
  <ConfirmDialog :open="!!deleting" title="从工作台删除项目？" confirm-label="确认删除项目"
    cancel-label="保留项目" :disabled="!!s.busy || !!deleting && s.deletionBlocked(deleting)"
    @close="deleting = null" @confirm="deleteConfirmed">
    <p>「{{ deleting?.name }}」将从正常工作台移除。已保存的画像、资产、方向、计划、步骤和验证结果不会删除；本地仓库、WorkingCopy 和已验证 Commit 也不会删除。</p>
    <p>可以从「已删除项目」恢复。删除不代表取消后台任务或恢复失败步骤。</p>
  </ConfirmDialog>
</template>
