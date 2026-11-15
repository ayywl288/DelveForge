<script setup lang="ts">
import { computed, ref } from 'vue'
import Icon from '../components/NavigationIcon.vue'
import { toolStatus, toolTarget, type ToolActivity } from './activity'
import { previews, request, type OutputPage } from './api'

const props = defineProps<{ row: ToolActivity; conversation: string; projectId: string; runState?: string }>()
const target = computed(() => toolTarget(props.row, props.projectId))
const separator = computed(() => props.row.name === 'Bash' ? -1 : target.value.lastIndexOf('/'))
const status = computed(() => toolStatus(props.row, props.runState))
const labels: Record<string, string> = { Read: '读取', Edit: '编辑', Write: '写入', Bash: '运行命令' }
const icons = { Read: 'file', Edit: 'edit', Write: 'file', Bash: 'terminal' } as const
const log = ref(''), next = ref(0), bytes = ref(0), loaded = ref(false), gap = ref(false)
const busy = ref(false), error = ref('')
const decoders = new Map<string, TextDecoder>()
async function more() {
    const id = props.row.result?.invocation
    if (!id || busy.value) return
    busy.value = true; error.value = ''
    try {
        const page = await request<OutputPage>('/conversations/' + props.conversation + '/outputs/' + id + '?offset=' + next.value)
        for (const chunk of page.chunks) {
            const decoder = decoders.get(chunk.channel) || new TextDecoder()
            decoders.set(chunk.channel, decoder)
            const part = decoder.decode(Uint8Array.from(atob(chunk.base64), ch => ch.charCodeAt(0)), { stream: true })
            if (part) log.value += '[' + chunk.channel + '] ' + part
        }
        if (page.next >= page.bytes) {
            for (const [channel, decoder] of decoders) {
                const tail = decoder.decode()
                if (tail) log.value += '[' + channel + '] ' + tail
            }
            decoders.clear()
        }
        next.value = page.next; bytes.value = page.bytes; gap.value = page.gap; loaded.value = true
    } catch (e) { error.value = e instanceof Error ? e.message : String(e) }
    finally { busy.value = false }
}
</script>
<template>
    <li class="tool-event" :data-tone="status.tone">
        <details><summary>
            <Icon :name="icons[row.name as keyof typeof icons] ?? 'activity'" />
            <strong>{{ labels[row.name] ?? row.name }}</strong>
            <code v-if="separator >= 0 && separator < target.length - 1" class="file-target" :title="target"><span
                class="path-dir">{{ target.slice(0, separator) }}</span><span class="path-leaf">{{ target.slice(separator) }}</span></code>
            <code v-else :title="target">{{ target.split('\n')[0] }}</code>
            <span class="tool-state">{{ status.label }}</span><Icon class="disclosure" name="chevron" />
        </summary>
        <div class="tool-body">
            <p v-if="row.arguments" class="log-label">{{ row.name }} · 原始调用参数</p><pre v-if="row.arguments">{{ row.arguments }}</pre>
            <template v-if="row.result">
                <p v-if="row.result.detail">{{ row.result.detail }}</p>
                <p v-if="row.result.exit != null" class="log-label">退出码 {{ row.result.exit }}</p>
                <p v-if="row.result.stdout" class="log-label">stdout</p><pre v-if="row.result.stdout" aria-label="标准输出">{{ row.result.stdout }}</pre>
                <p v-if="row.result.stderr" class="log-label">stderr</p><pre v-if="row.result.stderr" class="stderr" aria-label="标准错误">{{ row.result.stderr }}</pre>
                <p v-if="row.result.displayTruncated">这里只显示部分已捕获输出。</p>
                <button v-if="row.result.capturedBytes && (!loaded || next < bytes)" :disabled="busy" @click="more">{{ loaded ? '加载下一页' : '查看已捕获输出' }}</button>
                <pre v-if="loaded">{{ log }}</pre>
            </template>
            <pre v-else-if="row.live?.frames.length">{{ row.live.frames.map(f => '[' + f.channel + '] ' + f.text).join('') }}</pre>
            <p v-else>{{ row.live ? '等待已捕获输出。' : '尚未收到执行回执。' }}</p>
            <small v-if="error" role="alert">{{ error }}</small>
        </div></details>
        <small v-if="row.result?.outputGap || gap" class="capture-gap">部分输出未捕获</small>
        <a v-for="url in previews(row.result?.stdout || '')" :key="url" class="preview-link" :href="url" target="_blank" rel="noopener noreferrer"><Icon name="external" />打开本机预览</a>
    </li>
</template>
