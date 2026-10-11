<script setup lang="ts">
export type NavigationIconName = "projects" | "discovery" | "repository" | "direction" | "planning" | "evolution" | "plus";
defineProps<{ name: NavigationIconName }>();

const paths: Record<NavigationIconName, string> = {
  projects: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  discovery: "M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6 3V6a2 2 0 0 1 2-2z M7 9h10 M7 13h6",
  repository: "M4 5h6l2 2h8v13H4z M4 5V3h6l2 2h8v2",
  direction: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z M16 8l-3 5-5 3 3-5z",
  planning: "M9 5h12 M9 12h12 M9 19h12 M3 4l1 1 2-2 M3 11l1 1 2-2 M3 18l1 1 2-2",
  evolution: "M12 3l8 3v6c0 4-4 7-8 9-4-2-8-5-8-9V6z M8 12l3 3 5-6",
  plus: "M12 5v14 M5 12h14",
};
</script>
<template>
  <svg class="navigation-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path :d="paths[name]" />
  </svg>
</template>
