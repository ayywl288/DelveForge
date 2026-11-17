<script setup lang="ts">
export type NavigationIconName = keyof typeof paths;
defineProps<{ name: NavigationIconName }>();

const paths = {
  conversation: "M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6 3V6a2 2 0 0 1 2-2z M7 9h10 M7 13h6",
  plus: "M12 5v14 M5 12h14",
  file: "M5 3h9l5 5v13H5z M14 3v6h5 M8 13h8 M8 17h6",
  layers: "M12 3l10 6-10 6L2 9z M2 13l10 6 10-6 M2 17l10 6 10-6",
  git: "M6 3a2 2 0 1 0 0 4 2 2 0 0 0 0-4z M6 17a2 2 0 1 0 0 4 2 2 0 0 0 0-4z M18 3a2 2 0 1 0 0 4 2 2 0 0 0 0-4z M6 7v10 M18 7v2a4 4 0 0 1-4 4h-4a4 4 0 0 0-4 4",
  check: "M4 12l5 5L20 6",
  copy: "M9 9h12v12H9z M5 15H3V3h12v2",
  refresh: "M20 8a9 9 0 0 0-16-3L2 8 M2 3v5h5 M4 16a9 9 0 0 0 16 3l2-3 M22 21v-5h-5",
  activity: "M2 12h5l3-8 4 16 3-8h5",
  target: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z M12 11v2",
  close: "M6 6l12 12 M6 18L18 6",
  stop: "M6 6h12v12H6z",
  sidebar: "M4 4h16a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z M9 4v16",
  chevron: "m9 5 7 7-7 7",
  down: "M12 4v16m-6-6 6 6 6-6",
  send: "M12 20V4m-7 7 7-7 7 7",
  terminal: "M3 4h18v16H3z M6 9l3 3-3 3m5 0h5",
  folder: "M3 7V5a1 1 0 0 1 1-1h5l2 3h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1z",
  external: "M14 4h6v6m0-6-9 9m0-8H5v14h14v-6",
  sliders: "M3 7h5m4 0h9M3 17h9m4 0h5 M8 4v6m8 4v6",
  edit: "m4 16 11-11 4 4-11 11H4z M13 7l4 4",
};
</script>
<template>
  <svg class="navigation-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path :d="paths[name]" />
  </svg>
</template>
