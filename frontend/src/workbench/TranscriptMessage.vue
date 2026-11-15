<script setup lang="ts">
import BrandSymbol from '../components/BrandSymbol.vue'
import Icon from '../components/NavigationIcon.vue'
import AssistantMarkdown from './AssistantMarkdown.vue'
import { previews, type Message } from './api'

defineProps<{ message: Message }>()
function clock(value: string) {
    const date = new Date(value)
    return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
}
</script>
<template>
    <li class="message" :class="message.role">
        <div class="author">
            <BrandSymbol v-if="message.role === 'assistant'" />
            <strong>{{ message.role === 'assistant' ? 'DelveForge' : '你' }}</strong>
            <time :datetime="message.recorded_at">{{ clock(message.recorded_at) }}</time>
            <span v-if="message.role === 'pending'">已接收，待处理</span>
        </div>
        <template v-if="message.role === 'assistant'">
            <AssistantMarkdown :text="message.text ?? ''" />
            <a v-for="url in previews(message.text)" :key="url" class="preview-link" :href="url"
                target="_blank" rel="noopener noreferrer"><Icon name="external" />打开本机预览</a>
        </template>
        <p v-else>{{ message.text }}</p>
    </li>
</template>
