import { createApp, nextTick } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import App from '../src/App.vue'

const projects = [{ id: 'p', name: '本机项目', workcopy: 'D:\\projects\\p', state: 'READY', detail: null }]
const conversations = [{ id: 'c1', title: '第一次调查', project_id: 'p' }, { id: 'c2', title: '后续调整', project_id: 'p' }]
const feed = { messages: [], runs: [{ id: 'r1', state: 'RUNNING', requests: 1, allowance: 5, tokens: 12 }] }
const settings = { model: { adapter: '测试适配器', endpoint: 'https://example.org', requestedModel: 'fixture-model', thinking: 'HIGH' },
    contextWindowTokens: 10000, responseTokens: 500, commandSeconds: 30, defaultRequestLimit: 96 }
const dispose: (() => void)[] = []
afterEach(() => dispose.splice(0).forEach(f => f()))

async function settle() { for (let i = 0; i < 18; i++) { await Promise.resolve(); await nextTick() } }
function mount(post?: (url: string, body: Record<string, string>) => unknown) {
    const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
        if (options?.method === 'POST') return new Response(JSON.stringify(post?.(url, JSON.parse(String(options.body))) || {}))
        return new Response(JSON.stringify(url.endsWith('/settings') ? settings : url.endsWith('/projects') ? projects : url.endsWith('/conversations') ? conversations : feed))
    })
    vi.stubGlobal('fetch', fetcher)
    const root = document.createElement('div'); document.body.append(root)
    const app = createApp(App); app.mount(root); dispose.push(() => app.unmount())
    return { root, fetcher }
}

it('刷新只恢复选中的项目和对话，不触发 Run', async () => {
    localStorage.setItem('delveforge.workbench.selection', JSON.stringify(['p', 'c2']))
    const {root, fetcher} = mount(); await settle()
    expect(root.querySelector('h1')?.textContent).toBe('后续调整')
    expect(root.querySelector('.workcopy')?.textContent).toContain('D:\\projects\\p')
    expect(root.textContent).toContain('同一时间运行一个前台 Run')
    expect(root.querySelector('.model-info')?.textContent).toContain('fixture-model')
    expect(root.querySelector('.usage')?.textContent).toContain('已报告 12 tokens')
    expect(fetcher.mock.calls.every(([, options]) => options?.method === 'GET')).toBe(true)
})

it('活动 Run 中仍可发送纠正，网络应答丢失后用相同消息 ID 重试', async () => {
    const ids: string[] = []
    const {root} = mount((url,body) => {
        expect(url).toMatch(/conversations\/c1\/messages$/)
        ids.push(body.requestId)
        if (ids.length === 1) throw Error('应答丢失')
        return {sequence: 7, runId: 'r1', newlyStarted: false}
    })
    await settle()
    expect(root.querySelector('label[for=task]')?.textContent).toContain('纠正')
    const field = root.querySelector('textarea')!
    field.value = '保留现有接口'; field.dispatchEvent(new Event('input')); await nextTick()
    root.querySelector('.composer')!.dispatchEvent(new Event('submit')); await settle()
    expect(field.value).toBe('保留现有接口')
    root.querySelector('.composer')!.dispatchEvent(new Event('submit')); await settle()
    expect(ids.length).toBe(2); expect(ids[1]).toEqual(ids[0])
    expect(field.value).toBe(''); expect(root.textContent).toContain('消息已保存')
})

it('项目内切换对话保留各自草稿，并关闭移动导航', async () => {
    const {root} = mount(); await settle()
    const field = root.querySelector('textarea')!
    field.value = '只在调查里'; field.dispatchEvent(new Event('input')); await nextTick()
    ;(root.querySelector('.mobile-toggle') as HTMLButtonElement).click(); await nextTick()
    expect(root.querySelector('.mobile-toggle')?.getAttribute('aria-expanded')).toBe('true')
    const tabs = root.querySelectorAll<HTMLButtonElement>('.chat-item')
    tabs[1]!.click(); await settle()
    expect(root.querySelector('textarea')?.value).toBe('')
    expect(root.querySelector('.mobile-toggle')?.getAttribute('aria-expanded')).toBe('false')
    tabs[0]!.click(); await settle()
    expect(root.querySelector('textarea')?.value).toBe('只在调查里')
})
