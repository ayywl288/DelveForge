<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import Icon from '../components/NavigationIcon.vue'
import type { Run } from './api'
const props = defineProps<{ run?: Run; active: boolean; pending: boolean; loading: boolean; exhausted: boolean;
    minLimit: number; validLimit: boolean; queued: boolean; away: boolean; defaultLimit?: number; notice: string }>()
const draft = defineModel<string>({ required: true })
const limit = defineModel<number | ''>('limit', { required: true })
const emit = defineEmits<{ send: []; control: [op: 'stop' | 'continue' | 'reconcile' | 'cancel-queue']; returnToRun: [] }>()
const area = ref<HTMLTextAreaElement>()
const steering = computed(() => props.run?.state === 'RUNNING')
const canSend = computed(() => draft.value.trim() && !props.pending && !props.loading
    && (props.active || props.run?.state === 'PAUSED' || props.validLimit))
const showContinue = computed(() => props.run?.state === 'PAUSED')
function submit() { if (canSend.value) emit('send') }
function shortcut(e: KeyboardEvent) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !e.isComposing) { e.preventDefault(); submit() }
}
function focus() { nextTick(() => area.value?.focus()) }
defineExpose({ focus })
</script>
<template>
    <div class="compose-stack">
        <div v-if="away" class="run-action"><Icon name="activity" /><span>本项目的另一段对话正在运行</span>
            <button type="button" @click="emit('returnToRun')">回到该对话</button></div>
        <div v-if="queued || showContinue || run?.state === 'BLOCKED' || run?.state === 'RECONCILING' || run?.state === 'STOPPING' || run?.state === 'FAILED'" class="run-action" :data-state="run?.state">
            <div class="run-reason"><strong>{{ exhausted ? '提高请求上限后可继续' : run?.state === 'BLOCKED' ? '前台执行待核对' : queued ? (away ? '等待本项目的前一项任务' : '等待空闲执行名额') : '任务状态' }}</strong>
                <span v-if="run?.detail">{{ run.detail }}</span></div>
            <label v-if="run?.state === 'PAUSED'" class="resume-limit">总上限<input v-model.number="limit" type="number" :min="minLimit" max="10000" :placeholder="String(run.allowance)"></label>
            <button v-if="queued" type="button" :disabled="pending" @click="emit('control','cancel-queue')">取消排队</button>
            <button v-if="showContinue" type="button" :disabled="pending" @click="emit('control','stop')">结束此任务</button>
            <button v-if="showContinue" type="button" :disabled="pending || !validLimit" @click="emit('control','continue')">继续此对话</button>
            <button v-if="run?.state === 'BLOCKED'" type="button" :disabled="pending" @click="emit('control','reconcile')">重新核对执行</button>
        </div>
        <form class="composer" novalidate @submit.prevent="submit">
            <label for="task" class="sr-only">{{ active ? '补充或纠正当前任务' : '给 DelveForge 的消息' }}</label>
            <textarea id="task" ref="area" v-model="draft" rows="2" maxlength="64000" @keydown="shortcut"
                :placeholder="steering ? '补充要求，或纠正正在执行的任务…' : active ? '补充内容会先保存在此对话…' : '描述任务，或询问项目中的代码…'" />
            <div class="composer-tools">
                <slot name="models" />
                <details v-if="!active && !queued && run?.state !== 'PAUSED'" class="budget-menu">
                    <summary><Icon name="sliders" />任务额度</summary>
                    <label>任务请求总上限<input v-model.number="limit" type="number" min="1" max="10000" :placeholder="String(defaultLimit ?? '')"><small>1 至 10,000 次请求</small></label>
                </details>
                <span v-else class="composer-context"><Icon name="conversation" />{{ steering ? '任务中可随时补充' : active ? '等待执行状态确认' : '继续当前任务' }}</span>
                <span class="shortcut">Ctrl / ⌘ + Enter</span>
                <button v-if="active && run?.state !== 'STOPPING'" class="stop-btn" type="button" title="停止任务" aria-label="停止任务" :disabled="pending" @click="emit('control','stop')"><Icon name="stop" /></button>
                <button class="send-btn" type="submit" :disabled="!canSend" :aria-label="active ? '发送补充' : '发送消息'" :title="active ? '发送补充' : '发送消息'"><Icon name="send" /></button>
            </div>
        </form>
        <div v-if="notice" class="ack" role="status"><Icon name="check" />{{ notice }}</div>
    </div>
</template>
