<script setup lang="ts">
import type { Evidence, EvidenceBasis } from "../api/types";
import NavigationIcon from "./NavigationIcon.vue";
defineProps<{
  items?: Evidence[];
  bases?: EvidenceBasis[];
  title?: string;
  friendly?: boolean;
  expanded?: boolean;
}>();
const sources: Record<string, string> = {
  USER_INPUT: "来自用户描述",
  REPOSITORY: "来自仓库文件",
};
</script>
<template>
  <details class="evidence" :open="expanded">
    <summary>
      <NavigationIcon name="evidence" />{{ title ?? "查看依据" }}
      <span class="count-pill">{{ bases?.length ?? items?.length ?? 0 }}</span>
    </summary>
    <p v-if="!(bases?.length || items?.length)" class="muted">
      此项没有可追溯的依据。
    </p>
    <article
      v-for="(item, index) in bases ??
      (items ?? []).map((evidence) => ({ evidence, origin: null }))"
      :key="index"
    >
      <div class="evidence-source meta"><NavigationIcon :name="item.evidence.sourceType === 'REPOSITORY' ? 'file' : 'discovery'" />
        {{ sources[item.evidence.sourceType] ?? "其他来源" }}
        <span v-if="item.evidence.confidence != null">
          · {{ friendly ? "推断可信程度" : "confidence" }}
          {{ item.evidence.confidence }}</span
        >
      </div>
      <p class="evidence-claim">{{ item.evidence.claim }}</p>
      <code class="source-reference">{{ item.evidence.sourceRef }}</code>
      <details>
        <summary>技术详情</summary>
        <p class="meta">confirmed 表示单条依据是否核对，与整体画像确认及方向选择不同；本界面不提供逐条确认操作。</p>
        <code>sourceType={{ item.evidence.sourceType }} ·
          confirmed={{ item.evidence.confirmed }}</code>
      <p v-if="item.origin" class="meta">
        {{ item.origin.kind }} ·
        {{ item.origin.userProfileId ?? item.origin.repositoryProfileId
        }}<span v-if="item.origin.userProfileRevision">
          · revision {{ item.origin.userProfileRevision }}</span
        >
      </p>
      </details>
    </article>
  </details>
</template>
