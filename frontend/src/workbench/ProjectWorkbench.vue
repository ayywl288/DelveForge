<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import BrandSymbol from '../components/BrandSymbol.vue'
import Transcript from './Transcript.vue'
import { request, stateText, type Ack, type Conversation, type Feed, type Project } from './api'

const projects = ref<Project[]>([]), conversations = ref<Conversation[]>([])
const projectId = ref(''), conversationId = ref('')
const feed = ref<Feed>({messages: [], runs: []})
const title = ref(''), source = ref(''), conversationTitle = ref(''), draft = ref('')
const panel = ref<'project' | 'conversation' | ''>(''), mobileNav = ref(false)
const pending = ref(false), error = ref(''), notice = ref(''), composer = ref<HTMLTextAreaElement>()
const project = computed(() => projects.value.find(p => p.id === projectId.value))
const conversation = computed(() => conversations.value.find(c => c.id === conversationId.value))
const run = computed(() => feed.value.runs[0])
const active = computed(() => !!run.value && ['RUNNING','STOPPING','RECONCILING','BLOCKED'].includes(run.value.state))
const selectionKey = 'delveforge.workbench.selection'
const drafts = new Map<string, string>()
const outgoing = new Map<string, { text: string; id: string }>()
let timer: ReturnType<typeof setTimeout> | undefined
let epoch = 0, feedVersion = 0, disposed = false

function tell(e: unknown) { error.value = e instanceof Error ? e.message : String(e) }
function remember() { localStorage.setItem(selectionKey, JSON.stringify([projectId.value, conversationId.value])) }
async function refresh() {
    const id = conversationId.value, turn = ++feedVersion
    if (!id) return
    const result = await request<Feed>('/conversations/' + id)
    if (!disposed && id === conversationId.value && turn === feedVersion) feed.value = result
}
async function selectConversation(id: string) {
    if (conversationId.value) drafts.set(conversationId.value, draft.value)
    conversationId.value = id; draft.value = drafts.get(id) || ''; feed.value = { messages: [], runs: [] }
    notice.value = ''; error.value = ''; mobileNav.value = false; panel.value = ''; remember()
    try { await refresh() } catch (e) { tell(e) }
}
async function selectProject(id: string, preferred = '') {
    const turn = ++epoch
    if (conversationId.value) drafts.set(conversationId.value, draft.value)
    projectId.value = id; conversationId.value = ''; conversations.value = []; draft.value = ''
    feed.value = { messages: [], runs: [] }; error.value = ''; notice.value = ''; panel.value = ''
    remember()
    if (!id) return
    const list = await request<Conversation[]>('/projects/' + id + '/conversations')
    if (turn !== epoch || disposed) return
    conversations.value = list
    await selectConversation(list.some(c => c.id === preferred) ? preferred : list[0]?.id || '')
}
async function load() {
    let previous = ['', '']
    try { previous = JSON.parse(localStorage.getItem(selectionKey) || '["",""]') } catch { /* 首次打开使用当前项目列表。 */ }
    projects.value = await request<Project[]>('/projects')
    const saved = projects.value.find(p => p.id === previous[0] && p.state === 'READY')
    await selectProject(saved?.id || projects.value.find(p => p.state === 'READY')?.id || '', previous[1])
}
async function poll() {
    try { await refresh() } catch (e) { if (!disposed) tell(e) }
    if (!disposed) timer = setTimeout(poll, active.value ? 800 : 2500)
}
onMounted(async () => { try { await load() } catch (e) { tell(e) } if (!disposed) timer = setTimeout(poll, 800) })
onUnmounted(() => { disposed = true; clearTimeout(timer) })
async function action(f: () => Promise<void>) {
    if (pending.value) return
    pending.value = true; error.value = ''; notice.value = ''
    try { await f() } catch (e) { tell(e) } finally { pending.value = false }
}
function createProject() {
    return action(async () => {
        const p = await request<{id: string}>('/projects', { name: title.value.trim(), source: source.value.trim() || null })
        projects.value = await request<Project[]>('/projects')
        const c = await request<{id: string}>('/projects/' + p.id + '/conversations', { title: '项目对话' })
        title.value = ''; source.value = ''; await selectProject(p.id, c.id)
    })
}
function createConversation() {
    return action(async () => {
        const project = projectId.value
        const c = await request<{id: string}>('/projects/' + project + '/conversations', { title: conversationTitle.value.trim() })
        conversationTitle.value = ''; await selectProject(project, c.id)
    })
}
function send() {
    const id = conversationId.value, text = draft.value.trim()
    if (!id || !text) return
    return action(async () => {
        const old = outgoing.get(id)
        const delivery = old?.text === text ? old : { text, id: crypto.randomUUID() }
        outgoing.set(id, delivery)
        const ack = await request<Ack>('/conversations/' + id + '/messages', { requestId: delivery.id, text })
        outgoing.delete(id)
        if (conversationId.value !== id) { if (drafts.get(id)?.trim() === text) drafts.delete(id); return }
        if (draft.value.trim() === text) draft.value = ''
        notice.value = ack.runId ? '消息已保存' : '消息已保存，等待此对话继续'
        await refresh(); await nextTick(); composer.value?.focus()
    })
}
function control(op: 'stop' | 'reconcile' | 'continue') {
    return action(async () => {
        const path = op === 'continue' ? '/conversations/' + conversationId.value + '/continue' : '/runs/' + run.value!.id + '/' + op
        await request(path, {}); await refresh()
    })
}
async function copyPath() {
    if (project.value?.workcopy) {
        try { await navigator.clipboard.writeText(project.value.workcopy); notice.value = '工作副本路径已复制' }
        catch { notice.value = '可选中并复制下方的工作副本路径' }
    }
}
</script>
<template>
    <div class="workbench">
        <aside class="rail" :data-mobile-open="mobileNav">
            <div class="wordmark"><BrandSymbol /><strong>DelveForge</strong></div>
            <button class="mobile-toggle" type="button" aria-controls="workspace-navigation" :aria-expanded="mobileNav" @click="mobileNav = !mobileNav">项目与对话</button>
            <nav id="workspace-navigation" aria-label="项目与对话">
                <div class="nav-section"><h2>项目</h2><button aria-label="新建或导入项目" @click="panel = 'project'">＋</button></div>
                <button v-for="p in projects" :key="p.id" class="nav-item" :aria-current="p.id === projectId ? 'true' : undefined" @click="selectProject(p.id).catch(tell)" :title="p.detail || p.name">{{ p.name }}<small v-if="p.state !== 'READY'"> · {{ p.state }}</small></button>
                <div v-if="project" class="nav-section"><h2>对话</h2><button aria-label="新建对话" @click="panel = 'conversation'">＋</button></div>
                <button v-for="c in conversations" :key="c.id" class="nav-item chat-item" :aria-current="c.id === conversationId ? 'true' : undefined" @click="selectConversation(c.id)">{{ c.title }}</button>
            </nav>
            <p class="environment-note">共享 Linux 环境 · <br>同一时间运行一个前台 Run</p>
        </aside>
        <main class="desk">
            <header><div><p class="project-label">{{ project?.name || '项目工作台' }}</p><h1>{{ conversation?.title || (project ? '项目对话' : '打开你的项目') }}</h1></div>
                <span v-if="run" class="run-state" :data-state="run.state">{{ stateText[run.state] || run.state }}</span></header>
            <div v-if="project?.workcopy" class="workcopy"><code tabindex="0">{{ project.workcopy }}</code><button @click="copyPath">复制路径</button><button @click="action(() => request<void>('/projects/' + projectId + '/open', {}))">打开文件夹</button></div>
            <p v-if="error" class="notice error" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
            <form v-if="panel === 'project'" class="editor-form" @submit.prevent="createProject">
                <h2>新建或导入项目</h2><label>项目名称<input v-model="title" required maxlength="160" autofocus></label>
                <label>本机源目录 <small>留空创建空项目</small><input v-model="source" placeholder="D:\我的项目"></label>
                <div><button class="primary" :disabled="pending">创建工作副本</button><button type="button" @click="panel = ''">取消</button></div>
            </form>
            <form v-else-if="panel === 'conversation'" class="editor-form" @submit.prevent="createConversation">
                <h2>新建对话</h2><label>对话名称<input v-model="conversationTitle" required maxlength="160" autofocus></label>
                <div><button class="primary" :disabled="pending">创建对话</button><button type="button" @click="panel = ''">取消</button></div>
            </form>
            <section v-else-if="!project || !conversation" class="empty">
                <h2>{{ project ? '开始项目中的第一段对话' : '创建独立的 Windows 工作副本' }}</h2>
                <p>{{ project ? '项目中的对话共享文件，各自保存消息。' : '导入本机目录，或从空项目开始。修改直接保存在工作副本中。' }}</p>
                <button class="primary" @click="panel = project ? 'conversation' : 'project'">{{ project ? '新建对话' : '新建或导入项目' }}</button>
            </section>
            <template v-else>
                <Transcript :conversation="conversationId" :messages="feed.messages" />
                <section v-for="tool in feed.activeTools" :key="tool.id" class="tool-event"><strong>{{ tool.name }} · 执行中</strong>
                    <pre v-if="tool.frames.length">{{ tool.frames.map(f => '[' + f.channel + '] ' + f.text).join('') }}</pre>
                </section>
                <div v-if="run" class="run-controls"><span>模型请求 {{ run.requests }} / {{ run.allowance }} · 已报告 {{ run.tokens }} tokens</span>
                    <span v-if="run.detail">{{ run.detail }}</span>
                    <button v-if="active && run.state !== 'STOPPING'" :disabled="pending" @click="control('stop')">停止 Run</button>
                    <button v-if="run.state === 'BLOCKED'" :disabled="pending" @click="control('reconcile')">重新核对执行</button>
                </div>
                <button v-if="!active && (run?.state === 'PAUSED' || feed.messages.some(m => m.role === 'pending'))" class="continue" :disabled="pending" @click="control('continue')">继续此对话</button>
                <form class="composer" @submit.prevent="send"><label for="task">{{ active ? '补充或纠正当前任务' : '给 DelveForge 的消息' }}</label>
                    <textarea id="task" ref="composer" v-model="draft" rows="3" maxlength="64000" placeholder="描述任务，或询问项目中的代码…" @keydown.ctrl.enter.prevent="send" />
                    <div><small>Ctrl + Enter</small><button class="primary" :disabled="pending || !draft.trim()">发送</button></div>
                </form>
            </template>
        </main>
    </div>
</template>
