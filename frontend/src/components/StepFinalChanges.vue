<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from "vue";
import { api, ApiError } from "../api/client";
import type { EvolutionStep, StepFinalChanges } from "../api/types";
import FileChangeSummary from "./FileChangeSummary.vue";

const props = defineProps<{ planId: string; step: EvolutionStep }>();
const result = ref<StepFinalChanges | null>(null);
const loading = ref(false);
const error = ref("");
let generation = 0;
const reasons: Record<string, string> = {
  HISTORICAL_OBJECT_MISSING: "比较所需的历史 Git 对象已不可用。",
  WORKSPACE_MISSING: "历史隔离副本记录不可用。",
  WORKSPACE_DIRECTORY_MISSING: "历史隔离副本目录已不可用。",
  WORKSPACE_ROOT_NOT_ALLOWED: "历史副本不在当前配置允许的根目录中。",
  WORKSPACE_METADATA_UNSAFE: "无法确认历史仓库的隔离与 Git 元数据安全。",
  TREE_UNREADABLE_OR_UNSUPPORTED: "历史 Tree 无法完整读取，或含当前不支持的文件类型／路径。",
  READ_FAILED_OR_LIMIT_EXCEEDED: "只读查询未完成，或超过读取期限／输出预算。",
};
async function load() {
  const own = ++generation;
  result.value = null;
  error.value = "";
  loading.value = false;
  const step = props.step;
  if (step.status !== "SUCCEEDED") return;
  if (!step.baselineRevision || !step.deliveryRevision) {
    error.value = "保存的成功结果与版本不一致，不能展示最终差异。";
    return;
  }
  loading.value = true;
  try {
    const response = await api.finalChanges(props.planId, step.id);
    if (own !== generation) return;
    if (response.planId !== props.planId || response.stepId !== step.id
        || response.baselineRevision !== step.baselineRevision
        || response.verifiedRevision !== step.deliveryRevision
        || !["AVAILABLE", "NOT_APPLICABLE", "UNAVAILABLE"].includes(response.availability)
        || (response.availability === "AVAILABLE" && (!Array.isArray(response.files) || !response.counts))) {
      throw new Error("只读结果与当前 Step 的保存版本不一致，请重新查询。");
    }
    result.value = response;
  } catch (failure) {
    if (own === generation) {
      error.value = failure instanceof ApiError && failure.status === 404
        ? "差异接口或对应历史对象不存在（HTTP 404）。请确认后端已构建并运行包含 final-changes 的版本，再核对 Plan／Step；刷新页面不会更新后端。"
        : failure instanceof Error ? failure.message : "只读差异暂不可用。";
    }
  } finally {
    if (own === generation) loading.value = false;
  }
}
watch(() => [props.planId, props.step.id, props.step.status, props.step.baselineRevision,
  props.step.deliveryRevision], load, { immediate: true });
onBeforeUnmount(() => { generation++; });

</script>
<template>
  <section id="step-final-changes" class="execution-section step-final-changes" :aria-busy="loading">
    <div class="section-row"><h4>已交付文件</h4>
      <button v-if="step.status === 'SUCCEEDED'" class="secondary compact-button" :disabled="loading" @click="load">刷新文件</button>
    </div>
    <p class="meta">以下清单来自已正式接受的完整步骤变更。</p>
    <p v-if="step.status !== 'SUCCEEDED'" class="context-note">此步骤尚未正式交付，暂无最终文件清单。</p>
    <p v-else-if="loading" role="status">正在读取已交付文件…</p>
    <p v-else-if="error" role="alert" class="field-error">最终差异不可用：{{ error }}</p>
    <template v-else-if="result?.availability === 'AVAILABLE'">
      <FileChangeSummary :key="step.id + step.deliveryRevision" :files="result.files!" />
      <p v-if="!result.files!.length" class="context-note">最终没有净文件变化。</p>
    </template>
    <p v-else-if="result" class="context-note">最终差异不可用：{{ reasons[result.reason ?? ''] ?? '当前无法安全完成历史 Tree 比较。' }}
      <span class="meta">{{ result.reason }}</span>不会以本轮或历史文件列表替代。</p>
  </section>
</template>
