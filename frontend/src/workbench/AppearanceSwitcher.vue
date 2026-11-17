<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import Icon from '../components/NavigationIcon.vue'

const options = [{ mode: 'light', label: '浅色' }, { mode: 'dark', label: '深色' }, { mode: 'system', label: '跟随系统' }] as const
type Mode = typeof options[number]['mode']
const stored = document.documentElement.dataset.appearance
const mode = ref<Mode>(stored === 'light' || stored === 'dark' ? stored : 'system')
const media = window.matchMedia('(prefers-color-scheme: dark)')
const systemDark = ref(media.matches)
const dark = computed(() => mode.value === 'dark' || (mode.value === 'system' && systemDark.value))
const action = computed(() => dark.value ? '切换到浅色' : '切换到深色')
const open = ref(false)
const host = ref<HTMLElement>()
const trigger = ref<HTMLButtonElement>()
function select(value: Mode, restoreFocus = true) {
    mode.value = value
    const root = document.documentElement
    if (value === 'system') delete root.dataset.appearance
    else root.dataset.appearance = value
    try {
        if (value === 'system') localStorage.removeItem('delveforge.appearance')
        else localStorage.setItem('delveforge.appearance', value)
    } catch { /* 当前页面仍可使用所选外观。 */ }
    if (open.value) close(restoreFocus)
}
function close(restoreFocus = false) {
    open.value = false
    if (restoreFocus) nextTick(() => trigger.value?.focus())
}
function items() { return Array.from(host.value?.querySelectorAll<HTMLButtonElement>('[role=menuitemradio]') ?? []) }
async function show(index = options.findIndex(option => option.mode === mode.value)) {
    open.value = true
    await nextTick()
    items()[index]?.focus()
}
function navigate(event: KeyboardEvent) {
    const buttons = items()
    const index = buttons.indexOf(document.activeElement as HTMLButtonElement)
    switch (event.key) {
        case 'Escape': close(true); break
        case 'Tab': close(); return
        case 'Home': buttons[0]?.focus(); break
        case 'End': buttons.at(-1)?.focus(); break
        case 'ArrowDown': buttons[(index + 1) % buttons.length]?.focus(); break
        case 'ArrowUp': buttons[(index - 1 + buttons.length) % buttons.length]?.focus(); break
        default: return
    }
    event.preventDefault()
}
function changed(event: MediaQueryListEvent) { systemDark.value = event.matches }
function outside(event: PointerEvent) { if (!host.value?.contains(event.target as Node)) close() }
onMounted(() => {
    media.addEventListener('change', changed)
    document.addEventListener('pointerdown', outside)
})
onUnmounted(() => {
    media.removeEventListener('change', changed)
    document.removeEventListener('pointerdown', outside)
})
</script>

<template>
    <div ref="host" class="appearance-switcher"
        @focusout="e => { if (e.relatedTarget && !host?.contains(e.relatedTarget as Node)) close() }">
        <button type="button" class="theme-toggle" :aria-label="action" :title="action"
            @click="select(dark ? 'light' : 'dark', false)">
            <Transition name="theme-icon" mode="out-in">
                <Icon :key="dark ? 'sun' : 'moon'" :name="dark ? 'sun' : 'moon'" />
            </Transition>
        </button>
        <button ref="trigger" type="button" class="theme-chevron" aria-label="选择外观偏好" title="选择外观偏好"
            aria-haspopup="menu" :aria-expanded="open" :aria-controls="open ? 'workbench-appearance-menu' : undefined"
            @click="open ? close() : show()" @keydown.down.prevent="show(0)" @keydown.up.prevent="show(options.length - 1)">
            <Icon name="chevron" />
        </button>
        <div v-if="open" id="workbench-appearance-menu" class="theme-menu" role="menu" aria-label="外观偏好" @keydown="navigate">
            <button v-for="option in options" :key="option.mode" type="button" role="menuitemradio" tabindex="-1"
                :aria-checked="mode === option.mode" @click="select(option.mode)">
                {{ option.label }}<Icon v-if="mode === option.mode" name="check" />
            </button>
        </div>
    </div>
</template>
