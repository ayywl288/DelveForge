<script setup lang="ts">
import { computed } from "vue";
import type { AgentEvent } from "../api/agentTypes";
import { diagnosticText, eventMetadata } from "./agentEventPresentation";

const props = defineProps<{ event: AgentEvent }>();
const metadata = computed(() => eventMetadata(props.event));
</script>

<template>
    <details class="event-details">
        <summary>技术详情 · {{ event.kind }}</summary>
        <template v-if="metadata.structured">
            <dl class="event-fields"><div v-for="field in metadata.rows" :key="field.label">
                <dt>{{ field.label }}</dt><dd>{{ field.value }}</dd>
            </div></dl>
            <details class="advanced-event-details"><summary>原始诊断与技术标识</summary>
                <pre>{{ diagnosticText(event) }}</pre></details>
        </template>
        <pre v-else>{{ diagnosticText(event) }}</pre>
    </details>
</template>

<style scoped>
.event-details { margin-top: 4px; min-width: 0; }
summary { color: var(--muted); font-size: 12px; cursor: pointer; overflow-wrap: anywhere; padding: 4px 0; min-height: 28px; }
.event-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 20px; margin: 6px 0 10px; }
.event-fields > div { display: flex; align-items: baseline; gap: 10px; min-width: 0; }
dt { flex-shrink: 0; color: var(--muted); font-size: 12px; }
dd { font-size: 13px; line-height: 1.6; margin: 0; min-width: 0; overflow-wrap: anywhere; white-space: pre-wrap; }
.advanced-event-details { margin: 4px 0 8px; }
pre { width: fit-content; max-width: 100%; font-size: 12px; line-height: 1.6; max-height: 18rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; background: var(--canvas); padding: 8px 10px; border-radius: 6px; margin: 6px 0 0; }
@media (max-width: 640px) {
    summary { min-height: 44px; padding: 10px 0; }
    .event-fields { grid-template-columns: minmax(0, 1fr); }
}
</style>
