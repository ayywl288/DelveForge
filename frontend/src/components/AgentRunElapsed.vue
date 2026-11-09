<script setup lang="ts">
import { computed, onActivated, onDeactivated, ref, watchEffect } from "vue";

const props = defineProps<{ startedAt: string; endedAt: string | null; terminal: boolean }>();
const active = ref(true);
const now = ref(Date.now());
onActivated(() => { active.value = true; });
onDeactivated(() => { active.value = false; });

watchEffect(onCleanup => {
    if (!active.value || props.terminal || props.endedAt) return;
    now.value = Date.now();
    const timer = window.setInterval(() => { now.value = Date.now(); }, 1000);
    onCleanup(() => window.clearInterval(timer));
});

const elapsed = computed(() => {
    const start = Date.parse(props.startedAt);
    const end = props.endedAt ? Date.parse(props.endedAt) : props.terminal ? NaN : now.value;
    if (!Number.isFinite(end - start)) return "—";
    const seconds = Math.max(0, Math.floor((end - start) / 1000));
    const minutes = Math.floor(seconds / 60);
    const mm = String(minutes % 60).padStart(2, "0");
    const ss = String(seconds % 60).padStart(2, "0");
    return minutes < 60 ? `${mm}:${ss}` : `${Math.floor(minutes / 60)}:${mm}:${ss}`;
});
</script>

<template>
    <span class="run-elapsed" role="timer" aria-label="运行用时">运行用时 {{ elapsed }}</span>
</template>

<style scoped>
.run-elapsed { color: var(--muted); font-size: 13px; font-variant-numeric: tabular-nums; white-space: nowrap; }
</style>
