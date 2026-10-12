<script setup lang="ts">
import NavigationIcon from "./NavigationIcon.vue";
defineProps<{ label: string; value: string | null | undefined }>();
</script>
<template>
  <div class="revision">
    <span><NavigationIcon name="git" />{{ label }}</span
    ><code>{{ value || "尚未产生" }}</code>
  </div>
</template>
