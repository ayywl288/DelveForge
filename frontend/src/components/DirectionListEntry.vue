<script setup lang="ts">
import type { ProductDirection } from "../api/types";
import StatusBadge from "./StatusBadge.vue";
import NavigationIcon from "./NavigationIcon.vue";
defineProps<{ direction: ProductDirection; active: boolean; index: number }>();
defineEmits<{ view: [] }>();
</script>
<template>
  <button class="direction-entry" :aria-pressed="active" @click="$emit('view')">
    <span class="direction-entry-heading"><span class="list-number" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
      <strong>{{ direction.title }}</strong><NavigationIcon name="arrow" /></span>
    <span class="original-preview">{{ direction.problem }}</span>
    <StatusBadge :status="direction.status" :show-code="false" />
  </button>
</template>
