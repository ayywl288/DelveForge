import { createApp, h, nextTick, reactive } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { preferredEffort, rememberModel } from '../src/workbench/modelPreferences'
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

it('只显示已配置模型，锁定时可查看，键盘可退回触发按钮', async () => {
    const state = reactive({ locked: false, choice: { ...choice } })
    const choose = vi.fn()
    const root = document.createElement('div')
    document.body.append(root)
    const app = createApp(() => h(ModelPicker, { catalog, choice: state.choice, locked: state.locked, busy: false, onChoose: choose }))
    app.mount(root); cleanups.push(() => { app.unmount(); root.remove() })
    const button = (s: string) => root.querySelector<HTMLButtonElement>(s)!
    const pick = button('.model-capsule')
    pick.click(); await nextTick()
    expect(root.querySelector('.binary-effort')?.textContent).toBe('高')
    expect(root.querySelector('.model-pop')?.textContent).not.toContain('待实测')
    button('.current-model').click(); await nextTick(); await nextTick()
    const search = root.querySelector<HTMLInputElement>('input[type=search]')!
    expect(document.activeElement).toBe(search)
    search.value = 'Qwen'; search.dispatchEvent(new Event('input')); await nextTick()
    expect(root.querySelector('.model-row')).toBeNull()
    search.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }))
    await nextTick(); await nextTick()
    expect(document.activeElement).toBe(button('.current-model'))
    button('.reset-effort').click(); await nextTick(); await nextTick()
    expect(choose).toHaveBeenCalledWith({ ...choice, effort: 'default' })
    expect(document.activeElement).toBe(pick)
    state.locked = true; await nextTick(); pick.click(); await nextTick()
    expect(pick.disabled).toBe(false)
    expect(button('.current-model').disabled).toBe(true)
    expect(root.querySelector('.level-control')).toBeNull()
    expect(root.textContent).toContain('任务结束后')
})

it('快速名单限八个，完整搜索与收藏、离散档、默认、失效偏好分别处理', async () => {
    localStorage.clear()
    const database = structuredClone(catalog)
    const template = database.models[0]!
    database.models = Array.from({length: 14}, (_, i) => ({...template, id: 'm' + i, name: 'Model ' + i,
        efforts: ['default', 'off', 'low', 'high', 'max'], rejectedEfforts: ['low']}))
    const state = reactive({ ...choice, model: 'm0' })
    const root = document.createElement('div')
    const choose = (value: ModelChoice) => { rememberModel(value); Object.assign(state, value) }
    const app = createApp(() => h(ModelPicker, {catalog: database, choice: state, locked: false, busy: false, onChoose: choose}))
    app.mount(root); cleanups.push(() => { app.unmount(); localStorage.clear() })
    const click = async (selector: string) => { root.querySelector<HTMLButtonElement>(selector)!.click(); await nextTick() }
    await click('.model-capsule')
    const range = root.querySelector<HTMLInputElement>('input[type=range]')!
    expect(range.max).toBe('1')
    expect(root.querySelector('.level-labels')?.textContent).toBe('高最高')
    range.value = '1'; range.dispatchEvent(new Event('change')); await nextTick()
    expect(state.effort).toBe('max')
    await click('.current-model')
    expect(root.querySelectorAll('.model-row')).toHaveLength(8)
    await click('.model-settings-link'); expect(root.querySelectorAll('.model-row')).toHaveLength(14)
    await click('.model-row:nth-child(2) .favorite')
    await click('.model-row:nth-child(2) button')
    expect(state).toMatchObject({model: 'm1', effort: 'default'})
    await click('.model-capsule'); await click('.current-model')
    const previous = [...root.querySelectorAll<HTMLButtonElement>('.model-row > button:first-child')].find(b => b.querySelector('span:last-child')?.textContent === 'Model 0')!
    previous.click(); await nextTick()
    expect(state.effort).toBe('max')
    expect(preferredEffort(database.models[0]!, 'low')).toBe('default')
    expect(preferredEffort(database.models[0]!, 'obsolete')).toBe('default')
    expect(preferredEffort(database.models[0]!, 'off')).toBe('default')
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

it('凭据删除后切换到当前端点的模型和验证状态', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(null, { status: 204 })))
    const state = reactive(structuredClone(catalog))
    Object.assign(state.providers[0]!, { saved: true, preset: 'saved', presets: ['saved', 'global'] })
    const root = document.createElement('div')
    const refresh = async () => {
        Object.assign(state.providers[0]!, { saved: false, preset: 'global' })
        Object.assign(state.models[0]!, { id: 'replacement', compatibility: 'catalog', verifiedEfforts: [], rejectedEfforts: [] })
    }
    const app = createApp(() => h(ProviderSettings, { catalog: state, initial: 'deepseek', choice, refresh }))
    app.mount(root); cleanups.push(() => app.unmount())
    expect(root.querySelector('.provider-validation .helper')?.textContent).toBe('验证会发送工具调用和回执，按提供方价格计费。')
    expect(root.querySelector('.provider-validation option[value=high]')?.textContent).toContain('已验证')
    root.querySelector<HTMLButtonElement>('button[type=button]')!.click()
    await vi.waitFor(() => expect(root.querySelector<HTMLSelectElement>('.provider-validation select')?.value).toBe('replacement'))
    expect(root.querySelector('.provider-validation option[value=high]')?.textContent).not.toContain('已验证')
})

it('普通测试显示后端实测耗时，兼容性验证独立折叠', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({ latencyMs: 127 })))
    vi.stubGlobal('fetch', fetcher)
    const root = document.createElement('div')
    const app = createApp(() => h(ProviderSettings, { catalog, choice, initial: 'deepseek', refresh: async () => {} }))
    app.mount(root); cleanups.push(() => app.unmount())
    expect(root.querySelector('details')!.open).toBe(false)
    const test = [...root.querySelectorAll('button')].find(b => b.textContent === '测试连接')!
    test.click()
    await vi.waitFor(() => expect(root.querySelector('[role=status]')?.textContent).toContain('127 ms'))
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(fetcher.mock.calls[0]).toContain('/api/workbench/providers/deepseek/connection')
})

it('未保存的临时密钥可以测试，保留输入并且不触发保存', async () => {
    const fetcher = vi.fn(async () => new Response(JSON.stringify({ latencyMs: 56 })))
    vi.stubGlobal('fetch', fetcher)
    const root = document.createElement('div')
    const app = createApp(() => h(ProviderSettings, { catalog, initial: 'qwen', refresh: async () => {} }))
    app.mount(root); cleanups.push(() => app.unmount())
    const test = [...root.querySelectorAll('button')].find(b => b.textContent === '测试连接')!
    expect(test.disabled).toBe(true)
    const input = root.querySelector<HTMLInputElement>('input[type=password]')!
    input.value = 'draft-test-key'; input.dispatchEvent(new Event('input')); await nextTick()
    expect(test.disabled).toBe(false)
    test.click()
    await vi.waitFor(() => expect(root.textContent).toContain('56 ms'))
    expect(fetcher).toHaveBeenCalledExactlyOnceWith('/api/workbench/providers/qwen/connection', expect.objectContaining({
        body: JSON.stringify({ preset: 'beijing', workspace: '', key: 'draft-test-key' }),
    }))
    expect(input.value).toBe('draft-test-key')
    expect(JSON.stringify(localStorage)).not.toContain('draft-test-key')
})

it('已被拒绝的默认档由用户明确选择替代强度', async () => {
    const available = structuredClone(catalog)
    available.models.push({ ...available.models[0]!, id: 'explicit', name: 'Explicit', rejectedEfforts: ['default'] })
    const choose = vi.fn()
    const root = document.createElement('div')
    const app = createApp(() => h(ModelPicker, { catalog: available, choice, locked: false, busy: false, onChoose: choose }))
    app.mount(root); cleanups.push(() => app.unmount())
    const click = async (selector: string) => { root.querySelector<HTMLButtonElement>(selector)!.click(); await nextTick() }
    await click('.model-capsule'); await click('.current-model')
    const target = [...root.querySelectorAll<HTMLButtonElement>('.model-row > button:first-child')].find(b => b.textContent?.includes('Explicit'))!
    target.click(); await nextTick()
    expect(choose).not.toHaveBeenCalled()
    expect(root.querySelector('.model-pop strong')?.textContent).toBe('选择思考强度')
    await click('.level-labels button:last-child')
    expect(choose).toHaveBeenCalledWith({ provider: 'deepseek', model: 'explicit', effort: 'max' })
})
