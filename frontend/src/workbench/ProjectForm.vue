<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import Icon from '../components/NavigationIcon.vue'
import { request } from './api'

const props = defineProps<{ fromFolder: boolean; pending: boolean }>()
const emit = defineEmits<{ submit: [name: string, source: string]; cancel: [] }>()
const importing = ref(props.fromFolder)
const name = ref(''), source = ref(''), picking = ref(false), error = ref('')
const manual = ref(false), chooseButton = ref<HTMLButtonElement>()
const valid = computed(() => name.value.trim() && (!importing.value || source.value.trim()))

function folderName(path: string) {
    return path.split(/[\\/]/).filter(Boolean).at(-1)?.slice(0, 160) ?? ''
}

async function chooseFolder() {
    if (picking.value || props.pending) return
    picking.value = true
    error.value = ''
    try {
        const selection = await request<{ path: string | null }>('/projects/choose-folder', {})
        if (selection.path) {
            if (!name.value.trim() || name.value === folderName(source.value)) name.value = folderName(selection.path)
            source.value = selection.path
            manual.value = false
        }
    } catch {
        error.value = '无法打开本机选择器。可以重试，或手动输入文件夹路径。'
        manual.value = true
    } finally {
        picking.value = false
        await nextTick()
        if (document.activeElement === document.body) chooseButton.value?.focus({ preventScroll: true })
    }
}

function submit() {
    if (valid.value && !picking.value && !props.pending) emit('submit', name.value, importing.value ? source.value : '')
}
</script>
<template>
    <form class="editor-form" @submit.prevent="submit">
        <fieldset class="project-mode" :disabled="picking || pending">
            <legend class="sr-only">项目来源</legend>
            <label><input v-model="importing" type="radio" :value="false">空项目</label>
            <label><input v-model="importing" type="radio" :value="true">从本机导入</label>
        </fieldset>
        <div v-if="importing" class="source-field">
            <span id="source-label">项目文件夹</span>
            <div class="folder-selection" role="group" aria-labelledby="source-label">
                <code v-if="source && !manual" class="selected-source" :title="source">{{ source }}</code>
                <button ref="chooseButton" type="button" :disabled="picking || pending" @click="chooseFolder">
                    <Icon name="folder" />{{ picking ? '正在选择…' : source ? '更换文件夹' : '选择文件夹…' }}
                </button>
            </div>
            <p v-if="error" role="alert">{{ error }}</p>
            <button v-if="!manual" class="manual-source" type="button" :disabled="picking || pending"
                @click="manual = true">手动输入路径</button>
            <label v-else class="manual-path"><span class="sr-only">本机源目录</span>
                <input v-model="source" aria-label="本机源目录" placeholder="文件夹的完整路径" :disabled="picking || pending">
            </label>
        </div>
        <label>项目名称<input v-model="name" required maxlength="160" :autofocus="!fromFolder"></label>
        <div class="form-actions">
            <button type="button" @click="emit('cancel')">取消</button>
            <button class="primary" :disabled="pending || picking || !valid">{{ pending ? '正在创建…' : '创建工作副本' }}</button>
        </div>
    </form>
</template>
