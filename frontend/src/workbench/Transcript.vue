<script setup lang="ts">
import { ref, watch } from 'vue'
import { previews, proposedTools, receipt, request, type Message, type OutputPage } from './api'

const props = defineProps<{ conversation: string; messages: Message[] }>()
const outputs = ref<Record<string, { text: string; next: number; bytes: number; gap: boolean }>>({})
const error = ref('')
const decoders = new Map<string, Map<string, TextDecoder>>()
const loading = new Set<string>()
watch(() => props.conversation, () => { outputs.value = {}; error.value = ''; decoders.clear(); loading.clear() })
async function readOutput(id?: string) {
    if (!id || loading.has(id)) return
    loading.add(id)
    const current = props.conversation
    const old = outputs.value[id]
    try {
        const page = await request<OutputPage>('/conversations/' + current + '/outputs/' + id + '?offset=' + (old?.next || 0))
        if (current !== props.conversation) return
        const channels = decoders.get(id) || new Map<string, TextDecoder>()
        decoders.set(id, channels)
        let content = page.chunks.map(c => {
            const bytes = Uint8Array.from(atob(c.base64), ch => ch.charCodeAt(0))
            const decoder = channels.get(c.channel) || new TextDecoder()
            channels.set(c.channel, decoder)
            const part = decoder.decode(bytes, { stream: true })
            return part ? '[' + c.channel + '] ' + part : ''
        }).join('')
        if (page.next >= page.bytes) {
            for (const [channel, decoder] of channels) {
                const tail = decoder.decode()
                if (tail) content += '[' + channel + '] ' + tail
            }
            decoders.delete(id)
        }
        outputs.value[id] = { text: (old?.text || '') + content, next: page.next, bytes: page.bytes, gap: page.gap }
    } catch (e) { if (current === props.conversation) error.value = String(e) }
    finally { if (current === props.conversation) loading.delete(id) }
}
</script>
<template>
    <ol class="transcript" aria-label="对话消息">
        <template v-for="m in messages" :key="m.role + ':' + m.seq">
            <li v-if="m.role === 'tool'" class="tool-event">
                <details><summary><strong>{{ m.tool_name }}</strong>
                    <span>{{ receipt(m).notStarted ? '未开始' : receipt(m).exit == null ? '退出状态未知' : '退出码 ' + receipt(m).exit }}</span>
                    <span v-if="receipt(m).outputGap">部分输出未捕获</span></summary>
                    <p>{{ receipt(m).detail }}</p><pre v-if="receipt(m).stdout || receipt(m).stderr">{{ receipt(m).stdout }}{{ receipt(m).stderr }}</pre>
                    <p v-if="receipt(m).displayTruncated">这里只显示了部分已捕获输出。</p>
                    <button v-if="receipt(m).capturedBytes" type="button" @click="readOutput(receipt(m).invocation)">查看已捕获输出</button>
                    <template v-for="(log, id) in outputs" :key="id"><template v-if="id === receipt(m).invocation">
                        <pre>{{ log.text }}</pre><button v-if="log.next < log.bytes" @click="readOutput(id)">加载下一页</button>
                    </template></template>
                </details>
                <a v-for="url in previews(m.text)" :key="url" :href="url" target="_blank" rel="noopener noreferrer">打开本机预览 ↗</a>
            </li>
            <li v-else-if="m.text || proposedTools(m).length" :class="['message', m.role]">
                <strong>{{ m.role === 'assistant' ? 'DelveForge' : m.role === 'pending' ? '你 · 已接收，待处理' : '你' }}</strong>
                <p v-if="m.text">{{ m.text }}</p>
                <details v-for="call in proposedTools(m)" :key="call.id"><summary>{{ call.name }} · 调用参数</summary>
                    <pre>{{ call.arguments }}</pre>
                </details>
                <a v-for="url in previews(m.text)" :key="url" :href="url" target="_blank" rel="noopener noreferrer">打开本机预览 ↗</a>
            </li>
        </template>
    </ol>
    <p v-if="error" role="alert">{{ error }}</p>
</template>
