import { createApp, h, nextTick, reactive } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import ModelPicker from '../src/workbench/ModelPicker.vue'
import ProviderSettings from '../src/workbench/ProviderSettings.vue'
import type { ModelCatalog, ModelChoice } from '../src/workbench/api'

const catalog: ModelCatalog = {
    providers: [
        { id: 'deepseek', name: 'DeepSeek', presets: ['global'], preset: 'global', workspace: '', configured: true, saved: false },
        { id: 'qwen', name: 'Qwen', presets: ['beijing', 'singapore'], preset: 'beijing', workspace: '', configured: false, saved: false },
    ],
    models: [
        { provider: 'deepseek', id: 'deepseek-flash', name: 'DeepSeek Flash', window: 1000000, input: 990000,
            output: 8192, reasoning: true, efforts: ['default', 'high', 'max'], compatibility: 'live', verifiedEfforts: ['high'], rejectedEfforts: ['max'] },
        { provider: 'qwen', id: 'qwen3.5-plus', name: 'Qwen Plus', window: 1000000, input: 990000, output: 8192,
            reasoning: true, efforts: ['default', 'off'], compatibility: 'catalog', verifiedEfforts: [], rejectedEfforts: [] },
    ],
}
const choice: ModelChoice = { provider: 'deepseek', model: 'deepseek-flash', effort: 'high' }
const cleanups: (() => void)[] = []
afterEach(() => { cleanups.splice(0).forEach(fn => fn()); vi.unstubAllGlobals() })

it('搜索模型、键盘返回焦点、禁用未配置模型和锁定任务', async () => {
    const state = reactive({ locked: false, choice: { ...choice } })
    const choose = vi.fn()
    const root = document.createElement('div')
    document.body.append(root)
    const app = createApp(() => h(ModelPicker, { catalog, choice: state.choice, locked: state.locked, busy: false, onChoose: choose }))
    app.mount(root); cleanups.push(() => { app.unmount(); root.remove() })
    const pick = root.querySelector<HTMLButtonElement>('[aria-label="选择模型"]')!
    pick.click(); await nextTick(); await nextTick()
    const search = root.querySelector<HTMLInputElement>('input[type=search]')!
    expect(document.activeElement).toBe(search)
    state.choice = { ...choice }
    await nextTick()
    expect(root.querySelector('input[type=search]')).toBe(search)
    search.value = 'qwen'; search.dispatchEvent(new Event('input')); await nextTick()
    expect(root.querySelector<HTMLButtonElement>('.model-list button')?.disabled).toBe(true)
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await nextTick(); await nextTick()
    expect(document.activeElement).toBe(pick)
    root.querySelector<HTMLButtonElement>('[aria-label="选择思考强度"]')!.click(); await nextTick()
    const levels = [...root.querySelectorAll<HTMLButtonElement>('.efforts button')]
    expect(levels.at(-1)?.disabled).toBe(true)
    levels[0]!.click()
    expect(choose).toHaveBeenCalledWith({ ...choice, effort: 'default' })
    state.locked = true; await nextTick()
    expect(pick.disabled).toBe(true)
})

it('保存凭据后清空密码输入且不写入浏览器存储', async () => {
    const fetcher = vi.fn(async () => new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetcher)
    const root = document.createElement('div')
    const app = createApp(() => h(ProviderSettings, { catalog, choice, initial: 'qwen', refresh: async () => {} }))
    app.mount(root); cleanups.push(() => app.unmount())
    const key = root.querySelector<HTMLInputElement>('input[type=password]')!
    key.value = 'test-only-key-value'; key.dispatchEvent(new Event('input')); await nextTick()
    root.querySelector('form')!.dispatchEvent(new Event('submit', { cancelable: true }))
    await vi.waitFor(() => expect(key.value).toBe(''))
    expect(fetcher).toHaveBeenCalledWith('/api/workbench/providers/qwen', expect.objectContaining({
        body: JSON.stringify({ preset: 'beijing', workspace: '', key: 'test-only-key-value' }),
    }))
    for (const storage of [localStorage, sessionStorage]) {
        expect(JSON.stringify(storage)).not.toContain('test-only-key-value')
    }
})
