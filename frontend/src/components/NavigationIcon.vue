<script setup lang="ts">
export type NavigationIconName = "projects" | "discovery" | "repository" | "direction" | "planning" | "evolution"
  | "plus" | "file" | "code" | "layers" | "reuse" | "warning" | "evidence" | "git" | "lock"
  | "check" | "clock" | "play" | "arrow" | "refresh" | "user" | "activity" | "target" | "heart" | "close";
defineProps<{ name: NavigationIconName }>();

const paths: Record<NavigationIconName, string> = {
  projects: "M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z",
  discovery: "M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-6 3V6a2 2 0 0 1 2-2z M7 9h10 M7 13h6",
  repository: "M4 5h6l2 2h8v13H4z M4 5V3h6l2 2h8v2",
  direction: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z M16 8l-3 5-5 3 3-5z",
  planning: "M9 5h12 M9 12h12 M9 19h12 M3 4l1 1 2-2 M3 11l1 1 2-2 M3 18l1 1 2-2",
  evolution: "M12 3l8 3v6c0 4-4 7-8 9-4-2-8-5-8-9V6z M8 12l3 3 5-6",
  plus: "M12 5v14 M5 12h14",
  file: "M5 3h9l5 5v13H5z M14 3v6h5 M8 13h8 M8 17h6",
  code: "M8 6l-6 6 6 6 M16 6l6 6-6 6 M14 3l-4 18",
  layers: "M12 3l10 6-10 6L2 9z M2 13l10 6 10-6 M2 17l10 6 10-6",
  reuse: "M20 7l-4-4H8L4 7 M4 3v4h4 M4 17l4 4h8l4-4 M20 21v-4h-4 M4 11v2 M20 11v2",
  warning: "M12 3L2 21h20z M12 9v5 M12 18h.01",
  evidence: "M6 3h12v18H6z M9 7h6 M9 11h6 M9 15h3",
  git: "M6 3a2 2 0 1 0 0 4 2 2 0 0 0 0-4z M6 17a2 2 0 1 0 0 4 2 2 0 0 0 0-4z M18 3a2 2 0 1 0 0 4 2 2 0 0 0 0-4z M6 7v10 M18 7v2a4 4 0 0 1-4 4h-4a4 4 0 0 0-4 4",
  lock: "M5 10h14v11H5z M8 10V6a4 4 0 0 1 8 0v4 M12 14v3",
  check: "M4 12l5 5L20 6",
  clock: "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z M12 6v6l4 2",
  play: "M7 3l14 9-14 9z",
  arrow: "M4 12h16 M14 6l6 6-6 6",
  refresh: "M20 8a9 9 0 0 0-16-3L2 8 M2 3v5h5 M4 16a9 9 0 0 0 16 3l2-3 M22 21v-5h-5",
  user: "M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z M4 21v-2a8 8 0 0 1 16 0v2z",
  activity: "M2 12h5l3-8 4 16 3-8h5",
  target: "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10z M12 11v2",
  heart: "M12 21L3 12a5 5 0 0 1 9-7 5 5 0 0 1 9 7z",
  close: "M6 6l12 12 M6 18L18 6",
};
</script>
<template>
  <svg class="navigation-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"
    stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">
    <path :d="paths[name]" />
  </svg>
</template>
