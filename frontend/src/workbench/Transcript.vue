<script setup lang="ts">
import { computed } from 'vue'
import ActivityDisclosure from './ActivityDisclosure.vue'
import ToolActivity from './ToolActivity.vue'
import TranscriptMessage from './TranscriptMessage.vue'
import { awaitingReply, batchTitle, transcriptSections } from './transcriptLayout'
import type { Entry } from './activity'
import type { Run } from './api'

const props = defineProps<{ conversation: string; projectId: string; entries: Entry[]; runs: Run[] }>()
const sections = computed(() => transcriptSections(props.entries))
const states = computed(() => new Map(props.runs.map(run => [run.id, run.state])))
const waiting = computed(() => awaitingReply(props.entries, props.runs[0]))
</script>
<template>
    <ol class="transcript" aria-label="对话消息">
        <template v-for="section in sections" :key="section.key">
            <TranscriptMessage v-if="section.kind === 'message'" :message="section.message" />
            <ActivityDisclosure v-else class="work-history" icon="activity" :tools="section.tools" :project-id="projectId"
                :title="'工作过程 · ' + section.tools.length + ' 次工具调用'" :collapsible="section.batches > 1"
                :run-state="states.get(section.runId ?? '')" :initially-open="states.get(section.runId ?? '') === 'RUNNING'">
                <template v-for="step in section.steps" :key="step.key">
                    <TranscriptMessage v-if="step.kind === 'message'" :message="step.message" />
                    <ActivityDisclosure v-else class="tool-batch" :icon="step.tools[0]?.name === 'Bash' ? 'terminal' : 'layers'"
                        :title="batchTitle(step.tools)" :tools="step.tools" :project-id="projectId"
                        :run-state="states.get(section.runId ?? '')" :collapsible="step.tools.length > 1">
                        <ToolActivity v-for="tool in step.tools" :key="tool.key" :row="tool" :conversation="conversation"
                            :project-id="projectId" :run-state="states.get(section.runId ?? '')" />
                    </ActivityDisclosure>
                </template>
            </ActivityDisclosure>
        </template>
        <li v-if="waiting" class="reply-wait">
            <span role="status">等待回复<span class="reply-dots" aria-hidden="true"><i /><i /><i /></span></span>
        </li>
    </ol>
</template>
