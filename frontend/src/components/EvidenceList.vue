<script setup lang="ts">
import type { Evidence, EvidenceBasis } from "../api/types";
defineProps<{
  items?: Evidence[];
  bases?: EvidenceBasis[];
  title?: string;
  friendly?: boolean;
  expanded?: boolean;
}>();
const sources: Record<string, string> = {
  USER_INPUT: "来自你的输入",
  REPOSITORY: "来自仓库事实",
};
</script>
<template>
  <details class="evidence" :open="expanded">
    <summary>
      {{ title ?? "查看依据" }} · {{ bases?.length ?? items?.length ?? 0 }}
    </summary>
    <p v-if="!(bases?.length || items?.length)" class="muted">
      此项没有可追溯的依据。
    </p>
    <article
      v-for="(item, index) in bases ??
      (items ?? []).map((evidence) => ({ evidence, origin: null }))"
      :key="index"
    >
      <p>{{ item.evidence.claim }}</p>
      <div class="meta">
        {{ friendly
          ? (sources[item.evidence.sourceType] ?? item.evidence.sourceType)
          : item.evidence.sourceType }} ·
        {{ friendly
          ? (item.evidence.confirmed ? "依据已核对" : "提取结论待核对")
          : (item.evidence.confirmed ? "已确认" : "未确认") }}
        <span v-if="item.evidence.confidence != null">
          · {{ friendly ? "推断可信程度" : "confidence" }}
          {{ item.evidence.confidence }}</span
        >
      </div>
      <code>{{ item.evidence.sourceRef }}</code>
      <details v-if="friendly">
        <summary>原始字段</summary>
        <code>sourceType={{ item.evidence.sourceType }} ·
          confirmed={{ item.evidence.confirmed }}</code>
      </details>
      <p v-if="item.origin" class="meta">
        {{ item.origin.kind }} ·
        {{ item.origin.userProfileId ?? item.origin.repositoryProfileId
        }}<span v-if="item.origin.userProfileRevision">
          · revision {{ item.origin.userProfileRevision }}</span
        >
      </p>
    </article>
  </details>
</template>
