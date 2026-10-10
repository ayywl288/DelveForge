<script setup lang="ts">
import type { Evidence, EvidenceBasis } from "../api/types";
defineProps<{ items?: Evidence[]; bases?: EvidenceBasis[]; title?: string }>();
</script>
<template>
  <details class="evidence">
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
        {{ item.evidence.sourceType }} ·
        {{ item.evidence.confirmed ? "已确认" : "未确认" }}
        <span v-if="item.evidence.confidence != null">
          · confidence {{ item.evidence.confidence }}</span
        >
      </div>
      <code>{{ item.evidence.sourceRef }}</code>
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
