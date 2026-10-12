<script setup lang="ts">
import NavigationIcon, { type NavigationIconName } from "./NavigationIcon.vue";
withDefaults(defineProps<{ title: string; items: string[]; empty?: string; icon?: NavigationIconName;
    variant?: "rows" | "tags" | "modules" | "risks" }>(), { variant: "rows" });
</script>
<template>
  <section class="content-list" :class="`content-${variant}`">
    <h3 class="section-heading"><NavigationIcon v-if="icon" :name="icon" />{{ title }}</h3>
    <ul v-if="items.length">
      <li v-for="(item, index) in items" :key="index">
        <NavigationIcon v-if="variant === 'modules'" :name="icon === 'file' ? 'file' : 'repository'" /><span>{{ item }}</span>
      </li>
    </ul>
    <p v-else class="muted">{{ empty ?? "此结果未提供条目" }}</p>
  </section>
</template>
