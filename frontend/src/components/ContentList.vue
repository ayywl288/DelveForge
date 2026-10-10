<script setup lang="ts">
defineProps<{ title: string; items: string[]; empty?: string }>();
</script>
<template>
  <section class="content-list">
    <h3>{{ title }}</h3>
    <ul v-if="items.length">
      <li v-for="(item, index) in items" :key="index">{{ item }}</li>
    </ul>
    <p v-else class="muted">{{ empty ?? "此结果未提供条目" }}</p>
  </section>
</template>
