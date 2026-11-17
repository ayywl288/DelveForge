<script setup lang="ts">
import { computed } from 'vue'
import Icon from '../components/NavigationIcon.vue'
import BrandSymbol from '../components/BrandSymbol.vue'
import { stateText, type Conversation, type Foreground, type Project, type RuntimeSettings } from './api'
const props = defineProps<{ projects: Project[]; conversations: Conversation[]; projectId: string; conversationId: string;
    foregrounds?: Foreground[]; settings?: RuntimeSettings; mobile?: boolean }>()
const projectRows = computed(() => {
    const owners = new Map(props.foregrounds?.map(owner => [owner.projectId, owner]))
    return props.projects.map(project => ({ project, owner: owners.get(project.id) }))
})
const emit = defineEmits<{ project: [id: string]; conversation: [id: string]; newProject: []; newConversation: []; model: []; collapse: [] }>()
</script>
<template>
    <div class="navigation-content">
        <div v-if="!mobile" class="rail-brand"><span class="emblem"><BrandSymbol /></span><b>DelveForge</b>
            <button class="icon-btn" type="button" aria-label="收起侧栏" title="收起侧栏" @click="emit('collapse')"><Icon name="sidebar" /></button></div>
        <button class="new-project" type="button" @click="emit('newProject')"><Icon name="plus" />新建或导入项目</button>
        <nav class="project-tree" aria-label="项目与对话">
            <h2>项目 <span>{{ projects.length }}</span></h2>
            <p v-if="!projects.length" class="nav-empty">尚无项目</p>
            <div v-for="{ project: p, owner } in projectRows" :key="p.id" class="tree-group">
                <button class="project-node" :class="{ selected: p.id === projectId }" :title="p.name"
                    :aria-expanded="p.id === projectId" @click="emit('project', p.id)">
                    <Icon name="folder" /><span>{{ p.name }}</span>
                    <span v-if="p.id !== projectId && owner?.state === 'RUNNING'" class="run-indicator"
                        role="img" aria-label="任务正在执行" title="任务正在执行"></span>
                    <small v-else-if="owner && owner.state !== 'RUNNING'">{{ stateText[owner.state] }}</small>
                    <Icon class="disclosure" name="chevron" />
                </button>
                <div v-if="p.id === projectId" class="branches">
                    <button v-for="c in conversations" :key="c.id" class="conversation-node"
                        :title="c.title" :aria-current="c.id === conversationId ? 'page' : undefined"
                        @click="emit('conversation', c.id)">
                        <Icon name="conversation" /><span>{{ c.title }}</span>
                        <span v-if="owner?.state === 'RUNNING' && owner.conversationId === c.id" class="run-indicator"
                            role="img" aria-label="任务正在执行" title="任务正在执行"></span>
                    </button>
                    <button v-if="p.state === 'READY'" class="add-conversation" @click="emit('newConversation')"><Icon name="plus" />新建对话</button>
                    <small v-if="p.state !== 'READY'">{{ p.detail || p.state }}</small>
                </div>
            </div>
        </nav>
        <div class="nav-bottom"><Icon name="terminal" /><div><strong>本机工作台</strong><small>各项目独立的 Linux 环境</small></div></div>
        <button v-if="settings" class="model-button" @click="emit('model')"><Icon name="sliders" /><span>模型与用量</span><Icon name="chevron" /></button>
    </div>
</template>
