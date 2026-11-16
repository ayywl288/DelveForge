<script setup lang="ts">
import { ref, watch } from 'vue'
import { request } from './api'
import Icon from '../components/NavigationIcon.vue'
import type { Project, RuntimeSettings, Run } from './api'
import { projectRelativePath, type ToolActivity } from './activity'
const props = defineProps<{ mode: 'workspace' | 'model'; project?: Project; changes: ToolActivity[];
    previews: string[]; settings?: RuntimeSettings; run?: Run; pending: boolean }>()
const emit = defineEmits<{ open: [] }>()
const copied = ref('')
const maintenance = ref(''), choice = ref<'Restart' | 'Rebuild' | 'Check' | ''>('')
const working = ref(false), environmentNotice = ref('')
watch(() => props.project?.id, () => { choice.value = ''; maintenance.value = ''; environmentNotice.value = '' })
async function readEnvironment(event: Event) {
    const id = props.project?.id
    if (!id || !(event.currentTarget as HTMLDetailsElement).open) return
    try {
        const state = await request<{operation: string}>('/projects/' + id + '/maintenance')
        if (id === props.project?.id) maintenance.value = state.operation
    } catch (error) {
        if (id === props.project?.id) environmentNotice.value = error instanceof Error ? error.message : String(error)
    }
}
async function maintain() {
    const id = props.project?.id, action = choice.value
    if (!id || !action || working.value) return
    working.value = true
    try {
        await request('/projects/' + id + '/environment', { action })
        if (id === props.project?.id) { maintenance.value = ''; environmentNotice.value = '项目环境已就绪' }
    } catch (error) {
        if (id === props.project?.id) environmentNotice.value = error instanceof Error ? error.message : String(error)
    } finally { working.value = false; choice.value = '' }
}
async function copy() {
    if (!props.project?.workcopy) return
    try { await navigator.clipboard.writeText(props.project.workcopy); copied.value = '已复制路径' }
    catch { copied.value = '可选中上方路径手动复制' }
}
</script>
<template>
    <template v-if="mode === 'workspace' && project">
        <section class="detail-section"><h3><Icon name="folder" />{{ project.name }}</h3>
            <code class="file-path">{{ project.workcopy || project.detail }}</code>
            <div v-if="project.workcopy" class="button-row">
                <button class="primary" :disabled="pending" @click="emit('open')"><Icon name="external" />打开文件夹</button>
                <button @click="copy"><Icon name="copy" />复制路径</button>
            </div><small role="status">{{ copied }}</small>
        </section>
        <details class="detail-section" @toggle="readEnvironment"><summary>项目开发环境</summary>
            <div class="button-row" v-if="!choice">
                <button v-if="maintenance" @click="choice = 'Check'">核对上次维护</button>
                <template v-else><button @click="choice = 'Restart'">重启此项目</button>
                    <button @click="choice = 'Rebuild'">重建此项目</button></template>
            </div>
            <template v-else><p>{{ choice === 'Check' ? '核对容器状态后再释放这个项目的维护占用。'
                : choice === 'Restart' ? '此项目的后台服务会停止。已安装的软件和文件保留。'
                : '新版容器使用同一工作副本和数据卷。旧系统层会保留，私下安装的软件可能需要重新安装。' }}</p>
                <div class="button-row"><button :disabled="working" @click="choice = ''">取消</button>
                    <button :disabled="working" @click="maintain">{{ working ? '处理中…' : '确认' }}</button></div></template>
            <small role="status">{{ environmentNotice }}</small>
        </details>
        <section class="detail-section"><h3>本对话的文件写入 <span class="count">{{ changes.length }}</span></h3>
            <p class="helper">来自成功的 Edit 和 Write 调用。工作副本中可查看文件当前内容。</p>
            <p v-if="!changes.length">尚无已确认的文件写入。</p>
            <details v-for="row in changes" :key="row.key" class="file-change">
                <summary><Icon name="file" /><code>{{ projectRelativePath(String(row.values.path), project.id) }}</code><span>{{ row.name }}</span><Icon class="disclosure" name="chevron" /></summary>
                <template v-if="row.name === 'Edit'"><p>替换前</p><pre class="before-edit">{{ row.values.old }}</pre><p>替换后</p><pre class="after-edit">{{ row.values.new }}</pre></template>
                <template v-else><p>此次写入的内容</p><pre>{{ row.values.content }}</pre></template>
            </details>
        </section>
        <section class="detail-section"><h3>本机预览</h3>
            <a v-for="(url, index) in previews" :key="url" class="preview-entry" :href="url" target="_blank" rel="noopener noreferrer">
                <Icon name="external" /><span>{{ index === 0 ? '打开本机预览' : '打开较早的预览' }}<code>{{ url.slice('http://'.length) }}</code></span>
            </a>
            <p v-if="previews.length > 1" class="helper">最近出现的地址排在最前。旧服务停止后，对应的链接会失效。</p>
            <p v-if="!previews.length" class="helper">对话中出现的本机预览链接会显示在这里。</p>
        </section>
    </template>
    <template v-else-if="mode === 'model' && settings">
        <section class="detail-section"><h3>{{ settings.model.requestedModel }}</h3>
            <dl class="facts"><dt>适配器</dt><dd>{{ settings.model.adapter }}</dd><dt>端点来源</dt><dd>{{ settings.model.endpoint }}</dd>
                <dt>请求模型 ID</dt><dd>{{ settings.model.requestedModel }}</dd><dt>思考模式</dt><dd>{{ settings.model.thinking }}</dd>
                <dt>单次上下文</dt><dd>{{ settings.contextWindowTokens.toLocaleString() }} tokens</dd><dt>单次输出预留</dt><dd>{{ settings.responseTokens.toLocaleString() }} tokens</dd>
                <dt>命令超时</dt><dd>{{ settings.commandSeconds }} 秒</dd><dt>默认任务上限</dt><dd>{{ settings.defaultRequestLimit }} 次请求</dd>
                <dt>同时运行的任务</dt><dd>每个项目 1 个，工作台最多 {{ settings.maxConcurrentRuns ?? 2 }} 个</dd>
            </dl><p class="helper">模型 ID 来自本次请求配置，实际部署的权重由提供方管理。</p>
        </section>
        <section class="detail-section" v-if="run"><h3>本次任务用量</h3><dl class="facts">
            <dt>模型请求</dt><dd>{{ run.requests }} / {{ run.allowance }}</dd>
            <dt>已报告用量</dt><dd>{{ run.tokens.toLocaleString() }} tokens</dd><dt>实际费用</dt><dd>以提供方账单为准</dd></dl></section>
    </template>
</template>
