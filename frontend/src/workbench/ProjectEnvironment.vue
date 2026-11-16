<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { request } from './api'

interface Package { name: string; version: string; arch: string; manual?: boolean; included?: boolean; origin?: string; command?: string }
interface Inventory { initialized: boolean; stopped?: boolean; missing?: boolean; current?: number; latest: number; image?: string; latestImage?: string
    distribution?: string; rollback?: boolean; installed?: Package[]; packages?: Package[]; manualFiles?: string[] }
interface Preview { package: Package; current: number; plan: string; dependencies: string[] }
type Action = 'Restart' | 'Rebuild' | 'Rollback' | 'Check'
const props = defineProps<{ project: string }>()
const inventory = ref<Inventory>(), maintenance = ref(''), choice = ref<Action>(), preview = ref<Preview>()
const busy = ref(''), notice = ref(''), confirmation = ref<HTMLButtonElement>(), panel = ref<HTMLDetailsElement>()
const triggerLabel = ref('')
const extra = computed(() => inventory.value?.installed?.filter(pkg => !pkg.included) ?? [])
const base = computed(() => inventory.value?.installed?.filter(pkg => pkg.included) ?? [])
const url = computed(() => '/projects/' + props.project)
const names: Record<Action, string> = { Restart: '重启此项目', Rebuild: '更换开发容器', Rollback: '回退到上一代', Check: '核对上次维护' }
const revision = (n?: number) => n ? 'v' + n : '基础镜像'
async function load() {
    const [packages, state] = await Promise.all([request<Inventory>(url.value + '/tools'),
        request<{operation: string}>(url.value + '/maintenance')])
    inventory.value = packages
    maintenance.value = state.operation
}
async function perform(message: string, work: () => Promise<void>) {
    if (busy.value) return
    busy.value = message
    notice.value = ''
    try { await work() } catch (err) { notice.value = err instanceof Error ? err.message : String(err) }
    finally { busy.value = '' }
}
function checkMaintenance() {
    void perform('正在核对维护…', async () => {
        await request(url.value + '/environment', { action: 'Check' })
        await load()
    })
}
function opened(event: Event) {
    if ((event.currentTarget as HTMLDetailsElement).open) void perform('正在读取环境…', load)
}
async function select(pkg: Package, event: Event) {
    const button = event.currentTarget as HTMLButtonElement
    triggerLabel.value = button.getAttribute('aria-label') || button.textContent || ''
    await perform('正在核对签名软件源…', async () => {
        preview.value = await request<Preview>(url.value + '/tools/preview', { name: pkg.name })
    })
    await nextTick()
    confirmation.value?.focus()
}
function choose(action: Action, event: Event) {
    triggerLabel.value = (event.currentTarget as HTMLElement).textContent || ''
    choice.value = action
    nextTick(() => confirmation.value?.focus())
}
function cancel() {
    choice.value = undefined
    preview.value = undefined
    nextTick(() => {
        const buttons = Array.from(panel.value?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [])
        const target = buttons.find(button => (button.getAttribute('aria-label') || button.textContent) === triggerLabel.value)
        const fallback = panel.value?.querySelector('summary')
        if (target) target.focus()
        else fallback?.focus()
    })
}
function confirm() {
    const plan = preview.value, action = choice.value
    if (!plan && !action) return
    void perform(plan ? '正在构建并验证新镜像…' : '正在维护项目环境…', async () => {
        if (plan) {
            const result = await request<{version:number}>(url.value + '/tools/publish', {name:plan.package.name, plan:plan.plan})
            notice.value = '全局工具镜像 v' + result.version + ' 已就绪'
        } else {
            await request(url.value + '/environment', {action})
            notice.value = '项目环境已就绪'
        }
        await load()
        cancel()
    })
}
</script>
<template>
    <details ref="panel" class="detail-section project-environment" @toggle="opened">
        <summary>项目开发环境</summary>
        <button v-if="notice && !inventory && !busy" @click="perform('正在读取环境…', load)">重新读取</button>
        <button v-if="maintenance && !inventory?.initialized" :disabled="!!busy" @click="checkMaintenance">核对上次维护</button>
        <p v-if="inventory && !inventory.initialized" class="helper">首次执行项目工具时，将使用{{ revision(inventory.latest) }}创建环境。</p>
        <template v-if="inventory?.initialized">
            <dl class="facts"><dt>当前镜像</dt><dd :title="inventory.image">{{ revision(inventory.current) }}</dd>
                <dt>可用镜像</dt><dd :title="inventory.latestImage">{{ revision(inventory.latest) }}</dd></dl>
            <p v-if="inventory.missing" class="helper">上次登记的开发容器已缺失，工作副本和项目卷仍可用于显式重建。</p>
            <p v-else-if="inventory.stopped" class="helper">容器已停止，重启后可查看已安装的软件。</p>
            <template v-if="!choice && !preview">
                <div class="button-row">
                    <button v-if="maintenance" :disabled="!!busy" @click="choose('Check', $event)">核对上次维护</button>
                    <template v-else>
                        <button v-if="!inventory.missing" :disabled="!!busy" @click="choose('Restart', $event)">重启此项目</button>
                        <button :disabled="!!busy" @click="choose('Rebuild', $event)">{{ inventory.image === inventory.latestImage ? '重建此项目' : '升级到 ' + revision(inventory.latest) }}</button>
                        <button v-if="inventory.rollback" :disabled="!!busy" @click="choose('Rollback', $event)">回退到上一代</button>
                    </template>
                </div>
                <template v-if="inventory.installed">
                    <h4>项目额外安装的 APT 包 <span class="count">{{ extra.length }}</span></h4>
                    <p v-if="!extra.length" class="helper">当前包清单与项目镜像一致。</p>
                    <ul v-else class="env-packages">
                        <li v-for="pkg in extra" :key="pkg.name + pkg.arch"><span><code>{{ pkg.name }}</code>
                            <small>{{ pkg.version }} · {{ pkg.arch }} · {{ pkg.manual ? '手动安装' : '依赖' }}</small></span>
                            <button v-if="pkg.manual && !inventory.packages?.some(p => p.name === pkg.name && p.version === pkg.version)"
                                :aria-label="'核对并推广 ' + pkg.name" :disabled="!!busy" @click="select(pkg, $event)">推广…</button></li>
                    </ul>
                    <details v-if="base.length"><summary>镜像自带的包（{{ base.length }}）</summary>
                        <ul class="env-packages base-packages" tabindex="0" aria-label="镜像自带的包"><li v-for="pkg in base" :key="pkg.name + pkg.arch"><code>{{ pkg.name }}</code><small>{{ pkg.version }}</small></li></ul></details>
                    <p v-if="inventory.manualFiles?.length" class="helper">/usr/local/bin 中另有手动文件，来源无法核实：{{ inventory.manualFiles.join('、') }}</p>
                </template>
            </template>
            <section v-else class="env-confirm">
                <h4>{{ preview ? '推广 ' + preview.package.name + ' 到全局镜像' : names[choice!] }}</h4>
                <template v-if="preview"><p>{{ preview.package.version }} · {{ preview.package.arch }} · {{ preview.package.origin }}</p>
                    <p>验证通过后，新项目会使用这个版本。已有项目可自行选择升级。</p>
                    <details><summary>本次使用的归档（{{ preview.dependencies.length }}）</summary><p>{{ preview.dependencies.join('、') }}</p></details></template>
                <p v-else-if="choice === 'Rebuild'">本项目的后台服务会停止。新容器使用{{ revision(inventory.latest) }}，继续使用同一工作副本和数据卷。
                    <template v-if="inventory.packages?.length">可用的推广工具有 {{ inventory.packages.map(p => p.name).join('、') }}。</template>
                    <template v-if="extra.length">旧容器额外安装了 {{ extra.map(p => p.name).join('、') }}。</template>只装在旧容器系统层的软件会留在上一代。</p>
                <p v-else-if="choice === 'Rollback'">停止本项目现有服务，恢复上一代容器及其系统软件。工作副本和持久数据继续使用当前内容。</p>
                <p v-else-if="choice === 'Restart'">此项目的后台服务会停止。已安装的软件和文件保留。</p>
                <p v-else>核对原容器和维护记录后，恢复这个项目的任务调度。</p>
                <div class="button-row"><button ref="confirmation" :disabled="!!busy" @click="cancel">取消</button>
                    <button :disabled="!!busy" @click="confirm">{{ preview ? '构建全局镜像' : '确认' + names[choice!] }}</button></div>
            </section>
        </template>
        <small role="status" aria-live="polite">{{ busy || notice }}</small>
    </details>
</template>
<style scoped>
.project-environment > summary { cursor: pointer; font-weight: 650; }
.project-environment h4 { font-size: 14px; margin: 16px 0 6px; }
.project-environment > details, .project-environment .button-row { margin: 10px 0; }
.env-packages { padding: 0; margin: 0; list-style: none; }
.env-packages li { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 6px 0; border-bottom: 1px solid var(--line); }
.env-packages li > span { min-width: 0; }
.env-packages small { display: block; color: var(--muted); overflow-wrap: anywhere; }
.base-packages { max-height: 16rem; overflow: auto; }
.env-confirm, .env-packages code { overflow-wrap: anywhere; }
.project-environment [role=status] { display: block; color: var(--muted); }
</style>
