<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from '../components/NavigationIcon.vue'
import { effortLabel, type ModelCatalog, type ModelChoice } from './api'

const props = defineProps<{ catalog: ModelCatalog; choice: ModelChoice; locked: boolean; busy: boolean }>()
const emit = defineEmits<{ choose: [choice: ModelChoice]; settings: [provider: string]; refresh: [] }>()
const menu = ref<'model' | 'effort' | ''>(''), query = ref('')
const host = ref<HTMLElement>(), opener = ref<HTMLButtonElement>(), search = ref<HTMLInputElement>()
const chosen = computed(() => props.catalog.models.find(m => m.provider === props.choice.provider && m.id === props.choice.model))
const sections = computed(() => {
    const term = query.value.trim().toLowerCase()
    return props.catalog.providers.map(p => ({ provider: p, models: props.catalog.models.filter(m =>
        m.provider === p.id && m.compatibility !== 'incompatible' && (m.id + ' ' + m.name + ' ' + p.name).toLowerCase().includes(term)) }))
        .filter(s => s.models.length).sort((a, b) => Number(b.provider.configured) - Number(a.provider.configured))
})
function close(returnFocus = true) {
    menu.value = ''
    if (returnFocus) nextTick(() => opener.value?.focus())
}
function open(which: 'model' | 'effort', event: MouseEvent) {
    if (menu.value === which) { close(); return }
    opener.value = event.currentTarget as HTMLButtonElement
    menu.value = which; query.value = ''; emit('refresh')
    nextTick(() => which === 'model' ? search.value?.focus() : host.value?.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus())
}
function select(choice: ModelChoice) { close(); emit('choose', choice) }
function outside(e: PointerEvent) { if (!host.value?.contains(e.target as Node)) close(false) }
function keys(e: KeyboardEvent) {
    if (!menu.value) return
    if (e.key === 'Escape') { e.preventDefault(); close(); return }
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
    const items = [...(host.value?.querySelectorAll<HTMLButtonElement>('.model-pop button:enabled') ?? [])]
    if (!items.length) return
    e.preventDefault()
    const i = items.indexOf(document.activeElement as HTMLButtonElement)
    items[(i + (e.key === 'ArrowDown' ? 1 : -1) + items.length) % items.length]?.focus()
}
watch([() => props.locked, () => props.choice.provider, () => props.choice.model, () => props.choice.effort], () => close(false))
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>
<template>
    <div ref="host" class="model-picker" @keydown="keys" @focusout="e => { if (e.relatedTarget && !host?.contains(e.relatedTarget as Node)) close(false) }">
        <button type="button" :disabled="locked || busy" :aria-expanded="menu === 'model'" aria-label="选择模型"
            :title="locked ? '本次任务已锁定模型' : choice.model" @click="open('model', $event)">
            <span>{{ chosen?.name ?? choice.model }}</span><Icon name="down" /></button>
        <button type="button" class="effort-button" :disabled="locked || busy || (chosen?.efforts.length ?? 0) < 2"
            :aria-expanded="menu === 'effort'" :title="locked ? '本次任务已锁定思考强度' : '思考强度'" aria-label="选择思考强度"
            @click="open('effort', $event)">{{ effortLabel[choice.effort] ?? choice.effort }}<Icon name="down" /></button>
        <div v-if="menu === 'model'" class="model-pop" role="group" aria-label="模型列表">
            <input ref="search" v-model="query" type="search" placeholder="搜索模型或提供方" aria-label="搜索模型或提供方">
            <div class="model-list">
                <section v-for="s in sections" :key="s.provider.id"><h3>{{ s.provider.name }}<small>{{ s.provider.configured ? '已配置' : '未配置' }}</small></h3>
                    <button v-for="m in s.models" :key="m.id" type="button" :disabled="!s.provider.configured"
                        :aria-pressed="choice.provider === m.provider && choice.model === m.id"
                        @click="select({ provider: m.provider, model: m.id, effort: m.efforts.find(v => !m.rejectedEfforts.includes(v))! })">
                        <span>{{ m.name }}</span><small>{{ m.verifiedEfforts.length === m.efforts.length ? '已验证'
                            : m.verifiedEfforts.length ? '部分强度已验证' : '待实测' }}</small></button>
                </section>
                <p v-if="!sections.length" class="helper">没有匹配的工具调用模型</p>
            </div>
            <button class="model-settings-link" type="button" @click="close(); emit('settings', choice.provider)">管理模型提供方</button>
        </div>
        <div v-if="menu === 'effort'" class="model-pop efforts" role="group" aria-label="思考强度">
            <button v-for="v in chosen?.efforts" :key="v" type="button" :disabled="chosen?.rejectedEfforts.includes(v)" :aria-pressed="choice.effort === v"
                @click="select({ ...choice, effort: v })">{{ effortLabel[v] ?? v }}
                <small>{{ chosen?.rejectedEfforts.includes(v) ? '不支持' : chosen?.verifiedEfforts.includes(v) ? '已验证' : '待实测' }}</small></button>
        </div>
    </div>
</template>
