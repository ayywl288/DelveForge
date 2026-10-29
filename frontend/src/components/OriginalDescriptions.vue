<script setup lang="ts">
import { computed } from "vue";
import type { Evidence } from "../api/types";
const props = defineProps<{ items: Evidence[] }>();
const descriptions = computed(() => [...new Set(props.items.filter(item => item.sourceType === "USER_INPUT")
  .map(item => item.sourceRef.trim()).filter(Boolean))]);
</script>
<template>
  <details v-if="descriptions.length" class="original-descriptions">
    <summary>查看原始描述 <span class="count-pill">{{ descriptions.length }}</span></summary>
    <blockquote v-for="text in descriptions" :key="text">{{ text }}</blockquote>
  </details>
</template>
