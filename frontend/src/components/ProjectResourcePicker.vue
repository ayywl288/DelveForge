<script setup lang="ts">
import { ref } from "vue";
import { api, ApiError } from "../api/client";
import type { ResourceKind, ResourceSummary } from "../api/types";
import type { WorkspaceSession } from "../state/session";

const props = defineProps<{ s: WorkspaceSession }>();
const kind = ref<Exclude<ResourceKind, "USER_PROFILE">>("REPOSITORY_PROFILE");
const resources = ref<ResourceSummary[]>([]);
const chosen = ref("");
const loading = ref(false);
const error = ref("");
const more = ref(false);
let offset = 0;
let loadedKind = kind.value;
async function load(next = false) {
  if (loading.value) return;
  loading.value = true;
  error.value = "";
  try {
    const requestKind = kind.value;
    const requestOffset = next && loadedKind === requestKind ? offset : 0;
    const result = await api.resources(requestKind, requestOffset);
    if (kind.value !== requestKind) return;
    if (!Array.isArray(result)) throw new ApiError(0, "RESPONSE_UNREADABLE");
    resources.value = requestOffset ? [...resources.value, ...result] : result;
    loadedKind = requestKind;
    offset = requestOffset + result.length;
    more.value = result.length === 50;
    chosen.value = "";
  } catch (failure) {
    error.value = failure instanceof ApiError ? failure.message : "已有资源读取失败。";
  } finally {
    loading.value = false;
  }
}
</script>
<template>
  <details v-if="s.projectContext" class="panel resource-picker">
    <summary>选择已有仓库或已保存的分析</summary>
    <p class="meta">只复用明确选中的身份，不按路径猜测归属，不重新分析。未关联资源也列在这里。</p>
    <div class="stack">
      <label for="resource-kind">资源类型</label>
      <select id="resource-kind" v-model="kind" :disabled="loading || !!s.busy" @change="resources = []; chosen = ''; more = false">
        <option value="REPOSITORY_PROFILE">已保存的仓库分析快照</option>
        <option value="SOFTWARE_ASSET">已登记仓库</option>
      </select>
      <button type="button" class="secondary" :disabled="loading" @click="load(false)">查询可复用资源</button>
      <p v-if="loading" role="status">正在读取…</p><p v-if="error" role="alert">{{ error }}</p>
      <label v-if="resources.length" for="existing-resource">明确选择资源</label>
      <select v-if="resources.length" id="existing-resource" v-model="chosen" :disabled="!!s.busy || loading">
        <option value="" disabled>请选择</option>
        <option v-for="resource in resources" :key="resource.id" :value="resource.id">
          {{ resource.summary }} · {{ resource.analyzedRevision?.slice(0, 12) ?? resource.status }} · {{ resource.id.slice(0, 8) }}
        </option>
      </select>
      <button v-if="resources.length" :disabled="!!s.busy || !chosen || loading" @click="s.selectResource(kind, chosen)">加入当前项目</button>
      <button v-if="more" class="text-button" :disabled="loading" @click="load(true)">更多资源</button>
    </div>
  </details>
</template>
