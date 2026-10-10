<script setup lang="ts">
defineProps<{ label: string; value: string | null | undefined }>();
</script>
<template>
  <div class="revision">
    <span>{{ label }}</span
    ><code>{{ value || "尚未产生" }}</code>
  </div>
</template>
