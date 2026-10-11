<script setup lang="ts">
import type { ProductDirection } from "../api/types";
import StatusBadge from "./StatusBadge.vue";
defineProps<{ direction: ProductDirection; active: boolean }>();
defineEmits<{ view: [] }>();
</script>
<template>
  <button class="direction-entry" :aria-pressed="active" @click="$emit('view')">
    <span class="section-row"><strong>{{ direction.title }}</strong>
      <StatusBadge :status="direction.status" :show-code="false" /></span>
    <span class="original-preview">{{ direction.problem }}</span>
    <span class="meta">方向 {{ direction.id }}</span>
  </button>
</template>
