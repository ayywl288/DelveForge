<script setup lang="ts">
defineProps<{ label: string }>();
</script>
<template>
  <div class="inline-busy" role="status">
    <span class="inline-busy-spinner" aria-hidden="true"></span>
    <span>{{ label }}</span>
  </div>
</template>
