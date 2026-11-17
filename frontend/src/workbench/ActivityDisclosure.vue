<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import Icon, { type NavigationIconName } from '../components/NavigationIcon.vue'
import { previews } from './api'
import type { ToolActivity } from './activity'
import { activitySummary } from './transcriptLayout'

const props = defineProps<{ title: string; icon: NavigationIconName; tools: ToolActivity[]; projectId: string;
    runState?: string; collapsible: boolean; initiallyOpen?: boolean }>()
const id = useId()
const choice = ref<boolean>()
const expanded = computed(() => !props.collapsible || (choice.value ?? !!props.initiallyOpen))
const visited = ref(false)
watch(expanded, value => { if (value) visited.value = true }, { immediate: true })
const summary = computed(() => activitySummary(props.tools, props.projectId, props.runState))
const preview = computed(() => props.tools.flatMap(row => previews(row.result?.stdout ?? '')).at(-1))

function keepOpen(event: MouseEvent) {
    if (props.collapsible && event.target instanceof Element && event.target.closest('button, summary, a')) {
        choice.value = true
    }
}
</script>
<template>
    <li class="activity-section" :class="{ foldable: collapsible }">
        <div v-if="collapsible" class="activity-heading">
            <button type="button" class="activity-toggle" :aria-controls="id" :aria-expanded="expanded"
                @click="choice = !expanded">
                <Icon :name="icon" /><span class="activity-title">{{ title }}</span>
                <span v-if="expanded && summary.warnings" class="activity-warning" :data-tone="summary.tone">{{ summary.warnings }}</span>
                <Icon class="disclosure" name="chevron" />
            </button>
            <span v-if="!expanded && summary.current" class="activity-current" :title="summary.current">{{ summary.current }}</span>
            <a v-if="!expanded && preview" class="preview-link" :href="preview" target="_blank" rel="noopener noreferrer"><Icon name="external" />打开本机预览</a>
        </div>
        <ol :id="id" v-show="expanded" class="activity-items" @click="keepOpen"><slot v-if="visited" /></ol>
    </li>
</template>
