<script setup lang="ts">
import { computed, ref } from 'vue'
import { renderAssistant } from './markdown'

const props = defineProps<{ text: string }>()
const rendered = computed(() => renderAssistant(props.text))
const feedback = ref('')
async function copyCode(event: MouseEvent) {
    const target = event.target
    if (!(target instanceof Element)) return
    const button = target.closest('button[data-copy-code]')
    const code = button?.closest('.code-block')?.querySelector('pre code')
    if (!code) return
    try {
        await navigator.clipboard.writeText(code.textContent || '')
        feedback.value = '代码已复制'
    } catch {
        feedback.value = '复制失败，可选中代码后手动复制'
    }
}
</script>

<template>
    <div class="markdown" @click="copyCode" v-html="rendered" />
    <small v-if="feedback" role="status">{{ feedback }}</small>
</template>
