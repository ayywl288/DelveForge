<script setup lang="ts">
import AssistantMarkdown from './AssistantMarkdown.vue'
import ToolActivity from './ToolActivity.vue'
import BrandSymbol from '../components/BrandSymbol.vue'
import type { Entry } from './activity'
import { previews, type Run } from './api'
import Icon from '../components/NavigationIcon.vue'
defineProps<{ conversation: string; entries: Entry[]; runs: Run[] }>()
function clock(value: string) {
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
</script>
<template>
    <ol class="transcript" aria-label="对话消息">
        <template v-for="entry in entries" :key="entry.key">
            <ToolActivity v-if="entry.kind === 'tool'" :row="entry" :conversation="conversation"
                :run-state="runs.find(r => r.id === entry.runId)?.state" />
            <li v-else class="message" :class="entry.message.role">
                <div class="author">
                    <BrandSymbol v-if="entry.message.role === 'assistant'" />
                    <strong>{{ entry.message.role === 'assistant' ? 'DelveForge' : '你' }}</strong>
                    <time :datetime="entry.message.recorded_at">{{ clock(entry.message.recorded_at) }}</time>
                    <span v-if="entry.message.role === 'pending'">已接收，待处理</span>
                </div>
                <template v-if="entry.message.role === 'assistant'">
                    <AssistantMarkdown :text="entry.message.text ?? ''" />
                    <a v-for="url in previews(entry.message.text)" :key="url" class="preview-link" :href="url"
                        target="_blank" rel="noopener noreferrer"><Icon name="external" />打开本机预览</a>
                </template>
                <p v-else>{{ entry.message.text }}</p>
            </li>
        </template>
    </ol>
</template>
