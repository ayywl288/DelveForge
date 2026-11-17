<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import Icon from '../components/NavigationIcon.vue'
import ProviderLogo from './ProviderLogo.vue'
import { effortLabel, type CatalogModel, type ModelCatalog, type ModelChoice } from './api'
import { modelPreferences, preferredEffort, rememberModel } from './modelPreferences'

const props = defineProps<{ catalog: ModelCatalog; choice: ModelChoice; locked: boolean; busy: boolean }>()
const emit = defineEmits<{ choose: [choice: ModelChoice]; settings: [provider: string]; refresh: [] }>()
const menu = ref<'' | 'effort' | 'models'>(''), query = ref(''), more = ref(false)
const prefs = ref(modelPreferences())
const host = ref<HTMLElement>(), opener = ref<HTMLButtonElement>(), modelButton = ref<HTMLButtonElement>()
const search = ref<HTMLInputElement>(), panel = ref<HTMLElement>()
const chosen = computed(() => props.catalog.models.find(m => m.provider === props.choice.provider && m.id === props.choice.model))
const pendingModel = ref<CatalogModel>()
const editing = computed(() => pendingModel.value ?? chosen.value)
const editingEffort = computed(() => pendingModel.value ? '' : props.choice.effort)
const name = computed(() => chosen.value?.name || props.choice.model)
const levels = computed(() => editing.value?.efforts.filter(v => v !== 'default' && v !== 'off' && !editing.value?.rejectedEfforts.includes(v)) ?? [])
const levelName = computed(() => props.choice.effort === 'default' ? '提供方默认' : effortLabel[props.choice.effort] ?? props.choice.effort)
const eligible = computed(() => {
    const providers = new Set(props.catalog.providers.filter(p => p.configured).map(p => p.id))
    return props.catalog.models.filter(m => providers.has(m.provider) && m.compatibility !== 'incompatible'
        && m.efforts.some(v => v !== 'off' && !m.rejectedEfforts.includes(v)))
})
function preference(m: CatalogModel) { return prefs.value.find(p => p.provider === m.provider && p.model === m.id) }
const results = computed(() => {
    const term = query.value.trim().toLowerCase()
    if (term || more.value) return eligible.value.filter(m => (m.provider + ' ' + m.id + ' ' + m.name + ' '
        + props.catalog.providers.find(p => p.id === m.provider)?.name).toLowerCase().includes(term))
    const quick = new Set<CatalogModel>()
    if (chosen.value && eligible.value.includes(chosen.value)) quick.add(chosen.value)
    for (const p of [...prefs.value].sort((a, b) => Number(b.favorite) - Number(a.favorite))) {
        const m = eligible.value.find(m => m.provider === p.provider && m.id === p.model)
        if (m) quick.add(m)
    }
    const recent = [...eligible.value].sort((a, b) => (b.version ?? '').localeCompare(a.version ?? '')
        || b.name.localeCompare(a.name, undefined, { numeric: true }))
    for (const p of props.catalog.providers) {
        const m = recent.find(m => m.provider === p.id)
        if (m) quick.add(m)
    }
    return [...new Set([...quick, ...recent])].slice(0, 8)
})
function close(focus = true) {
    pendingModel.value = undefined
    menu.value = ''
    if (focus) void nextTick(() => opener.value?.focus())
}
function open() {
    if (menu.value) { close(); return }
    menu.value = 'effort'
    prefs.value = modelPreferences()
    emit('refresh')
    void nextTick(() => panel.value?.focus())
}
function models() {
    if (props.locked) return
    menu.value = 'models'; query.value = ''; more.value = false
    void nextTick(() => search.value?.focus())
}
function select(m: CatalogModel, effort: string | undefined, dismiss = true) {
    if (props.locked || props.busy || !effort || effort === 'off' || !m.efforts.includes(effort) || m.rejectedEfforts.includes(effort)) return
    emit('choose', { provider: m.provider, model: m.id, effort })
    if (dismiss || pendingModel.value) close()
}
function chooseModel(m: CatalogModel) {
    const effort = preferredEffort(m, preference(m)?.effort)
    if (effort) { select(m, effort); return }
    // 默认组合已被提供方拒绝时，由用户明确选档后再提交。
    pendingModel.value = m
    back()
}
function toggleFavorite(m: CatalogModel) {
    const pref = preference(m)
    rememberModel({ provider: m.provider, model: m.id, effort: pref?.effort ?? 'default' }, !pref?.favorite)
    prefs.value = modelPreferences()
}
function back() {
    menu.value = 'effort'
    void nextTick(() => modelButton.value?.focus())
}
function escape(e: KeyboardEvent) {
    if (menu.value === 'models') back()
    else if (menu.value) close()
    else return
    e.preventDefault(); e.stopPropagation()
}
function outside(e: PointerEvent) { if (!host.value?.contains(e.target as Node)) close(false) }
watch(() => props.locked, () => { pendingModel.value = undefined; if (menu.value) menu.value = 'effort' })
onMounted(() => document.addEventListener('pointerdown', outside))
onBeforeUnmount(() => document.removeEventListener('pointerdown', outside))
</script>
<template>
    <div ref="host" class="model-picker" @keydown.esc="escape"
        @focusout="e => { if (e.relatedTarget && !host?.contains(e.relatedTarget as Node)) close(false) }">
        <button ref="opener" class="model-capsule" type="button" :aria-expanded="!!menu" aria-haspopup="dialog"
            :aria-label="'模型与思考强度，' + name + '，' + levelName" @click="open">
            <ProviderLogo :provider="choice.provider" /><span>{{ name }}</span><small>· {{ effortLabel[choice.effort] ?? choice.effort }}</small>
            <Icon name="chevron" />
        </button>
        <div v-if="menu" ref="panel" class="model-pop" role="dialog" aria-label="模型与思考强度" tabindex="-1">
            <template v-if="menu === 'effort'">
                <header><strong>{{ pendingModel ? '选择思考强度' : levelName }}</strong>
                    <button v-if="!locked && editing?.efforts.includes('default')" type="button" class="reset-effort"
                        :disabled="busy || editingEffort === 'default' || editing.rejectedEfforts.includes('default')"
                        @click="select(editing, 'default')">恢复默认</button></header>
                <button ref="modelButton" class="current-model" type="button" :disabled="locked || busy" @click="models">
                    <ProviderLogo :provider="editing?.provider ?? choice.provider" /><span>{{ editing?.name ?? name }}</span><Icon v-if="!locked" name="chevron" />
                </button>
                <p v-if="locked" class="helper">当前任务已锁定配置。任务结束后可更换模型和思考强度。</p>
                <div v-else-if="editing && levels.length" class="level-control">
                    <div v-if="levels.length === 1"
                        class="binary-effort" role="group" aria-label="思考强度">
                        <button v-for="v in levels" :key="v" type="button" :aria-pressed="editingEffort === v"
                            :disabled="busy" @click="select(editing, v)">{{ effortLabel[v] ?? v }}</button>
                    </div>
                    <template v-else>
                        <input type="range" min="0" :max="levels.length - 1" step="1" aria-label="思考强度"
                            :value="Math.max(0, levels.indexOf(editingEffort))" :aria-valuetext="pendingModel ? '尚未选择' : levelName"
                            :class="{ 'default-effort': !editingEffort || editingEffort === 'default' }" :disabled="busy"
                            @keydown.home.prevent="select(editing, levels[0], false)"
                            @keydown.end.prevent="select(editing, levels.at(-1), false)"
                            @keydown.enter.prevent="select(editing, levels[Math.max(0, levels.indexOf(editingEffort))], false)"
                            @change="select(editing, levels[Number(($event.target as HTMLInputElement).value)], false)">
                        <div class="level-labels"><button v-for="(v, index) in levels" :key="v" type="button" tabindex="-1"
                            :style="{ left: index * 100 / (levels.length - 1) + '%' }"
                            :disabled="busy" :aria-pressed="editingEffort === v" @click="select(editing, v)">{{ effortLabel[v] ?? v }}</button></div>
                    </template>
                </div>
                <p v-else-if="editing" class="helper">此模型使用提供方默认思考设置。</p>
            </template>
            <template v-else>
                <div class="model-search"><button type="button" aria-label="返回思考强度" @click="back"><Icon name="chevron" /></button>
                <input ref="search" v-model="query" type="search" placeholder="搜索模型或提供方" aria-label="搜索模型或提供方"></div>
                <div class="model-list">
                    <div v-for="m in results" :key="m.provider + '/' + m.id" class="model-row">
                        <button type="button" :disabled="busy" :aria-pressed="choice.provider === m.provider && choice.model === m.id"
                            @click="chooseModel(m)">
                            <ProviderLogo :provider="m.provider" /><span>{{ m.name }}</span></button>
                        <button type="button" class="favorite" :aria-pressed="!!preference(m)?.favorite"
                            :aria-label="'收藏 ' + m.name" :title="'收藏 ' + m.name" @click="toggleFavorite(m)"><Icon name="star" /></button>
                    </div>
                    <p v-if="!results.length" class="helper">{{ eligible.length ? '没有匹配的模型' : '请先配置提供方凭据' }}</p>
                </div>
                <button v-if="!more && !query && eligible.length > results.length" type="button" class="model-settings-link" @click="more = true">更多模型</button>
                <button type="button" class="model-settings-link" @click="close(); emit('settings', choice.provider)">管理提供方</button>
            </template>
        </div>
    </div>
</template>
