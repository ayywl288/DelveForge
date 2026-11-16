<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import BrandSymbol from '../components/BrandSymbol.vue'
import Icon from '../components/NavigationIcon.vue'
import ConversationComposer from './ConversationComposer.vue'
import ProjectForm from './ProjectForm.vue'
import Transcript from './Transcript.vue'
import WorkbenchDetails from './WorkbenchDetails.vue'
import WorkbenchDialog from './WorkbenchDialog.vue'
import WorkbenchNavigation from './WorkbenchNavigation.vue'
import { previewLinks, recordedChanges, timeline } from './activity'
import { request, stateText } from './api'
import { useWorkbench } from './useWorkbench'

const { projects, conversations, projectId, conversationId, project, conversation, feed, run, settings, active, elsewhere,
    loading, pending, error, notice, draft, limit, exhausted, minLimit, validLimit, selectProject, selectConversation,
    createProject, createConversation, send, control, action } = useWorkbench()
const entries = computed(() => timeline(feed.value))
const changes = computed(() => recordedChanges(entries.value))
const links = computed(() => previewLinks(feed.value.messages))
const queued = computed(() => run.value?.state === 'QUEUED')
const starting = computed(() => !!conversation.value && !loading.value && !entries.value.length && !run.value)
const media = window.matchMedia('(max-width: 860px)')
const narrow = ref(media.matches), expanded = ref(true), mobileNav = ref(false)
const setWidth = () => { narrow.value = media.matches; mobileNav.value = false }
onMounted(() => media.addEventListener('change', setWidth))
onUnmounted(() => media.removeEventListener('change', setWidth))
const panel = ref<'' | 'project' | 'conversation' | 'workspace' | 'model'>('')
const formName = ref(''), importing = ref(false)
const navToggle = ref<HTMLButtonElement>(), scroll = ref<HTMLElement>()
const composer = ref<InstanceType<typeof ConversationComposer>>()
const following = ref(true)
function trackScroll() {
    if (scroll.value) following.value = scroll.value.scrollHeight - scroll.value.scrollTop - scroll.value.clientHeight < 56
}
function latest() { if (scroll.value) scroll.value.scrollTop = scroll.value.scrollHeight; following.value = true }
// 文本重排、软键盘和窗口尺寸变化也会改变底部位置。
watch(scroll, (element, _previous, cleanup) => {
    if (!element || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => { if (following.value) latest() })
    observer.observe(element)
    if (element.firstElementChild) observer.observe(element.firstElementChild)
    cleanup(() => observer.disconnect())
}, { flush: 'post' })
watch(conversationId, () => { following.value = true })
watch(entries, () => { if (following.value) latest() }, { flush: 'post' })

function closeNavigation() {
    mobileNav.value = false
    nextTick(() => navToggle.value?.focus())
}
function navigate(id: string, isProject: boolean) {
    mobileNav.value = false
    if (isProject) {
        if (projectId.value !== id) void selectProject(id)
    } else if (conversationId.value !== id) void selectConversation(id)
    if (narrow.value) nextTick(() => navToggle.value?.focus())
}
function form(mode: 'project' | 'conversation', fromFolder = false) {
    formName.value = ''; error.value = ''; importing.value = fromFolder
    mobileNav.value = false; panel.value = mode
}
async function finishForm(result: Promise<boolean>) {
    if (await result) { panel.value = ''; await nextTick(); composer.value?.focus() }
}
async function deliver() {
    const id = conversationId.value
    if (await send() && id === conversationId.value) composer.value?.focus()
}
function seed(text: string) { draft.value = text; composer.value?.focus() }
function backToRun() {
    if (elsewhere.value) void selectProject(elsewhere.value.projectId, elsewhere.value.conversationId)
}
const navProps = computed(() => ({ projects: projects.value, conversations: conversations.value, projectId: projectId.value,
    conversationId: conversationId.value, foregrounds: feed.value.foregrounds ?? [], settings: settings.value }))
const navEvents = { project: (id: string) => navigate(id, true), conversation: (id: string) => navigate(id, false),
    newProject: () => form('project'), newConversation: () => form('conversation'),
    model: () => { mobileNav.value = false; panel.value = 'model' }, collapse: () => { expanded.value = false; nextTick(() => navToggle.value?.focus()) } }
</script>
<template>
    <div class="workbench" :class="{ 'with-rail': expanded && !narrow }">
        <aside v-if="expanded && !narrow" class="rail"><WorkbenchNavigation v-bind="navProps" v-on="navEvents" /></aside>
        <main class="desk">
            <header class="topbar"><div class="breadcrumbs">
                <button v-if="narrow || !expanded" ref="navToggle" class="icon-btn nav-toggle" aria-label="展开项目与对话" title="展开项目与对话"
                    :aria-expanded="narrow ? mobileNav : expanded"
                    @click="narrow ? mobileNav = true : expanded = true"><Icon name="sidebar" /></button>
                <Icon class="crumb-icon" name="folder" /><span class="project-crumb" :title="project?.name">{{ project?.name || '项目工作台' }}</span>
                <template v-if="conversation"><span class="separator">/</span><h1 :title="conversation.title">{{ conversation.title }}</h1></template>
            </div>
            <div class="top-actions">
                <span v-if="run" class="state-chip" :data-state="run.state" role="status"><i aria-hidden="true" />{{ stateText[run.state] || run.state }}</span>
                <button v-if="project?.workcopy" class="workspace-btn" aria-label="查看工作副本" title="查看工作副本" @click="panel = 'workspace'"><Icon name="layers" /><span>工作副本</span></button>
            </div></header>
            <div v-if="error && !panel" class="error-banner" role="alert">{{ error }}<button class="icon-btn" aria-label="关闭提示" @click="error = ''"><Icon name="close" /></button></div>
            <section v-if="!conversation" class="welcome-page">
                <div v-if="loading" role="status" class="muted">正在载入工作台…</div>
                <div v-else class="welcome">
                    <span class="welcome-mark"><BrandSymbol /></span>
                    <h2>{{ project ? project.name : '从一个项目开始' }}</h2>
                    <p>{{ project ? (project.detail || '在这个项目中开始第一段开发对话。') : '导入本机代码，或创建一个空项目。对话和工作副本保存在你的电脑上。' }}</p>
                    <div v-if="!project" class="welcome-actions"><button class="primary" @click="form('project', true)"><Icon name="folder" />导入本机项目</button><button @click="form('project')"><Icon name="plus" />创建空项目</button></div>
                    <button v-else-if="project.state === 'READY'" class="primary" @click="form('conversation')"><Icon name="plus" />新建对话</button>
                </div>
            </section>
            <template v-else>
                <div class="conversation-body" :class="{ starting }">
                    <section v-if="!starting" ref="scroll" class="conversation-scroll" aria-label="对话记录" tabindex="0" @scroll.passive="trackScroll">
                        <div class="reading-column"><p v-if="loading" role="status" class="muted">正在载入对话…</p>
                            <Transcript :key="conversationId" :project-id="projectId" :conversation="conversationId"
                                :entries="entries" :runs="feed.runs" />
                        </div>
                    </section>
                    <div class="compose-region">
                        <div v-if="starting" class="greeting"><span class="welcome-mark"><BrandSymbol /></span>
                            <h2>想在这个项目里做什么？</h2></div>
                        <button v-if="!following && !starting" class="jump" @click="latest"><Icon name="down" />回到最新</button>
                        <ConversationComposer ref="composer" v-model="draft" v-model:limit="limit" :run="run" :active="active"
                            :pending="pending" :loading="loading" :exhausted="exhausted" :min-limit="minLimit" :valid-limit="validLimit"
                            :queued="queued" :away="!!elsewhere" :default-limit="settings?.defaultRequestLimit" :notice="notice"
                            @send="deliver" @control="control" @return-to-run="backToRun" />
                        <div v-if="starting && !draft" class="starters"><button @click="seed('请介绍这个项目的目录结构和启动方式。')"><Icon name="folder" />了解项目结构</button>
                            <button @click="seed('请检查工作副本中的近期改动，并告诉我需要验证哪些行为。')"><Icon name="git" />检查近期改动</button></div>
                    </div>
                </div>
            </template>
        </main>
        <WorkbenchDialog v-if="narrow && mobileNav" title="项目与对话" drawer class="navigation-dialog" @close="closeNavigation">
            <WorkbenchNavigation v-bind="navProps" mobile v-on="navEvents" />
        </WorkbenchDialog>
        <WorkbenchDialog v-if="panel" :key="panel" :drawer="panel === 'workspace' || panel === 'model'"
            :title="panel === 'project' ? '新建或导入项目' : panel === 'conversation' ? '新建对话' : panel === 'workspace' ? '工作副本' : '模型与用量'" @close="panel = ''">
            <p v-if="error" class="error-banner" role="alert">{{ error }}</p>
            <ProjectForm v-if="panel === 'project'" :from-folder="importing" :pending="pending" @cancel="panel = ''"
                @submit="(name, path) => finishForm(createProject(name, path))" />
            <form v-else-if="panel === 'conversation'" class="editor-form" @submit.prevent="formName.trim() && finishForm(createConversation(formName))">
                <label>对话名称<input v-model="formName" required maxlength="160" autofocus></label>
                <div class="form-actions"><button type="button" @click="panel = ''">取消</button>
                    <button class="primary" :disabled="pending || !formName.trim()">{{ pending ? '正在创建…' : '创建对话' }}</button></div>
            </form>
            <WorkbenchDetails v-else :mode="panel" :project="project" :changes="changes" :previews="links" :settings="settings" :run="run" :pending="pending" @open="action(() => request<void>('/projects/' + projectId + '/open', {}))" />
        </WorkbenchDialog>
    </div>
</template>
