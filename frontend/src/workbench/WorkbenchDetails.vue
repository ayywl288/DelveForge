<script setup lang="ts">
import { ref } from 'vue'
import ProjectEnvironment from './ProjectEnvironment.vue'
import Icon from '../components/NavigationIcon.vue'
import type { Project, RuntimeSettings, Run } from './api'
import { projectRelativePath, type ToolActivity } from './activity'
const props = defineProps<{ mode: 'workspace' | 'model'; project?: Project; changes: ToolActivity[];
    previews: string[]; settings?: RuntimeSettings; run?: Run; pending: boolean }>()
const emit = defineEmits<{ open: [] }>()
const copied = ref('')
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
        <ProjectEnvironment :key="project.id" :project="project.id" />
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
        <details class="detail-section"><summary>运行配置 · {{ settings.model.requestedModel }}</summary>
            <dl class="facts"><dt>适配器</dt><dd>{{ settings.model.adapter }}</dd><dt>端点来源</dt><dd>{{ settings.model.endpoint }}</dd>
                <dt>请求模型 ID</dt><dd>{{ settings.model.requestedModel }}</dd><dt>思考模式</dt><dd>{{ settings.model.thinking }}</dd>
                <dt>单次上下文</dt><dd>{{ settings.contextWindowTokens.toLocaleString() }} tokens</dd><dt>单次输出预留</dt><dd>{{ settings.responseTokens.toLocaleString() }} tokens</dd>
                <dt>命令超时</dt><dd>{{ settings.commandSeconds }} 秒</dd><dt>默认任务上限</dt><dd>{{ settings.defaultRequestLimit }} 次请求</dd>
                <dt>同时运行的任务</dt><dd>每个项目 1 个，工作台最多 {{ settings.maxConcurrentRuns ?? 2 }} 个</dd>
            </dl><p class="helper">模型 ID 来自本次请求配置，实际部署的权重由提供方管理。</p>
        </details>
        <section class="detail-section" v-if="run"><h3>本次任务用量</h3><dl class="facts">
            <dt>模型请求</dt><dd>{{ run.requests }} / {{ run.allowance }}</dd>
            <dt>已报告用量</dt><dd>{{ run.tokens == null || (run.requests > 0 && run.tokens === 0) ? '暂无报告' : run.tokens.toLocaleString() + ' tokens' }}</dd><dt>实际费用</dt><dd>以提供方账单为准</dd></dl></section>
    </template>
</template>
