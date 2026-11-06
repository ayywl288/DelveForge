<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { api } from "../api/client";
import type { RepositoryCompatibility, WorkingCopy } from "../api/types";
const props = defineProps<{ assetId: string; revision: string; copy?: WorkingCopy | null }>();
const result = ref<RepositoryCompatibility | null>(null);
const loading = ref(false);
const blockingIssues = computed(() => result.value?.issues.filter(issue => issue.blocking) ?? []);
const detailIssues = computed(() => result.value?.issues.filter(issue => !issue.blocking) ?? []);
const error = ref("");
let epoch = 0;
async function load() {
  const current = ++epoch;
  result.value = null;
  error.value = "";
  if (!props.assetId || !props.revision) { loading.value = false; return; }
  loading.value = true;
  try {
    const response = await api.repositoryCompatibility(props.assetId, props.revision);
    if (current === epoch) result.value = response;
  } catch {
    if (current === epoch) error.value = "未能读取兼容性检查；准备操作仍会由后端重新核对。";
  } finally {
    if (current === epoch) loading.value = false;
  }
}
watch(() => [props.assetId, props.revision], load, { immediate: true });
onBeforeUnmount(() => { epoch++; });
</script>
<template>
  <section class="repository-compatibility" aria-label="仓库兼容性">
    <h3>仓库安全与副本准备</h3>
    <p v-if="loading">正在检查固定版本的源码边界与准备条件…</p>
    <p v-if="error" role="status">{{ error }}</p>
    <template v-if="result">
      <p>{{ result.status === 'BLOCKED' ? '需要处理下列限制' : result.generatedPaths.length ? '可自动准备隔离副本' : '当前结构可进入准备流程' }}</p>
      <p v-if="result.mavenDirectory" class="meta">Maven 项目：{{ result.mavenDirectory === '.' ? '仓库根目录' : result.mavenDirectory }}；建议 JDK {{ result.buildJdk }}；{{ result.files }} 个文件，约 {{ (result.sourceBytes / 1048576).toFixed(1) }} MiB。</p>
      <p v-else-if="result.status !== 'BLOCKED'" class="meta">{{ result.files }} 个文件，约 {{ (result.sourceBytes / 1048576).toFixed(1) }} MiB；尚未确定唯一 Maven 入口，实际检查入口与环境将在运行前预检。</p>
      <p v-if="result.generatedPaths.length">准备时自动排除 {{ result.generatedPaths.length }} 个已确认的 Maven 生成物，原仓库保持不变。</p>
      <p v-if="result.immutablePaths.length" class="meta">{{ result.immutablePaths.length }} 个资源或受保护文件仅保留原样，不作为可编辑源码。</p>
      <ul v-if="blockingIssues.length" class="compatibility-blockers" aria-label="阻塞准备的问题"><li v-for="(issue, index) in blockingIssues" :key="index">{{ issue.action }} <code v-if="issue.path">{{ issue.path }}</code></li></ul>
      <details v-if="detailIssues.length || result.generatedPaths.length || result.immutablePaths.length" class="compatibility-details">
        <summary>查看路径与准备明细</summary>
        <div class="compatibility-detail-scroll" tabindex="0" role="region" aria-label="完整仓库准备明细">
          <ul v-if="detailIssues.length"><li v-for="(issue, index) in detailIssues" :key="index">{{ issue.action }} <code v-if="issue.path">{{ issue.path }}</code></li></ul>
          <template v-if="result.generatedPaths.length"><h4>自动排除的生成物</h4><ul><li v-for="path in result.generatedPaths" :key="path"><code>{{ path }}</code></li></ul></template>
          <template v-if="result.immutablePaths.length"><h4>原样保留的资源</h4><ul><li v-for="path in result.immutablePaths" :key="path"><code>{{ path }}</code></li></ul></template>
        </div>
      </details>
      <p class="meta">这是固定版本的安全准入与副本准备诊断，不证明检查环境就绪。首次 Coding Agent 模型调用前还会实际预检；准备不会启动 AI 或授权 Step。</p>
    </template>
    <p v-if="copy" class="meta">原始版本：<code>{{ copy.sourceRevision.slice(0, 12) }}</code>；准备版本：<code>{{ (copy.preparedRevision ?? copy.sourceRevision).slice(0, 12) }}</code>。</p>
    <p v-if="copy?.preparation && copy.preparation.policyVersion !== 'IDENTITY'" class="meta">准备策略 {{ copy.preparation.policyVersion }}，完整来源记录已保存。</p>
    <button type="button" class="secondary" :disabled="loading" @click="load">重新检查兼容性</button>
  </section>
</template>
