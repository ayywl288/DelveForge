<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { api } from "../api/client";
import type { RepositoryCompatibility, WorkingCopy } from "../api/types";
const props = defineProps<{ assetId: string; revision: string; copy?: WorkingCopy | null }>();
const result = ref<RepositoryCompatibility | null>(null);
const loading = ref(false);
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
    <h3>演化环境检查</h3>
    <p v-if="loading">正在检查固定版本的构建结构与准备条件…</p>
    <p v-if="error" role="status">{{ error }}</p>
    <template v-if="result">
      <p>{{ result.status === 'BLOCKED' ? '需要处理下列限制' : result.generatedPaths.length ? '可自动准备隔离副本' : '当前结构可进入准备流程' }}</p>
      <p v-if="result.mavenDirectory" class="meta">Maven 项目：{{ result.mavenDirectory === '.' ? '仓库根目录' : result.mavenDirectory }}；构建 JDK {{ result.buildJdk }}；{{ result.files }} 个文件，约 {{ (result.sourceBytes / 1048576).toFixed(1) }} MiB。</p>
      <p v-else-if="result.status !== 'BLOCKED'" class="meta">前端工程：{{ result.files }} 个文件，约 {{ (result.sourceBytes / 1048576).toFixed(1) }} MiB；npm/pnpm 依赖、锁文件与脚本将在受控检查时核对。</p>
      <p v-if="result.generatedPaths.length">准备时自动排除 {{ result.generatedPaths.length }} 个已确认的 Maven 生成物，原仓库保持不变。</p>
      <p v-if="result.immutablePaths.length" class="meta">{{ result.immutablePaths.length }} 个资源或受保护文件仅保留原样，不作为可编辑源码。</p>
      <details v-if="result.generatedPaths.length"><summary>自动准备明细</summary><ul><li v-for="path in result.generatedPaths" :key="path"><code>{{ path }}</code></li></ul></details>
      <ul v-if="result.issues.length"><li v-for="(issue, index) in result.issues" :key="index">{{ issue.action }} <code v-if="issue.path">{{ issue.path }}</code></li></ul>
      <p class="meta">这是环境与结构检查。实际构建、测试和功能结果将在执行后分别记录；准备不会启动 AI 或授权 Step。</p>
    </template>
    <p v-if="copy" class="meta">原始版本：<code>{{ copy.sourceRevision.slice(0, 12) }}</code>；准备版本：<code>{{ (copy.preparedRevision ?? copy.sourceRevision).slice(0, 12) }}</code>。</p>
    <p v-if="copy?.preparation && copy.preparation.policyVersion !== 'IDENTITY'" class="meta">准备策略 {{ copy.preparation.policyVersion }}，完整来源记录已保存。</p>
    <button type="button" class="secondary" :disabled="loading" @click="load">重新检查兼容性</button>
  </section>
</template>
