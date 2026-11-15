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
function mount(post?: (url: string, body: Record<string, string>) => unknown, narrow = false) {
    vi.stubGlobal('matchMedia', () => ({ matches: narrow, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    HTMLDialogElement.prototype.showModal = function () { this.open = true }
    HTMLDialogElement.prototype.close = function () { this.open = false; this.dispatchEvent(new Event('close')) }
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
    root.querySelector<HTMLButtonElement>('.workspace-btn')!.click(); await settle()
    expect(root.querySelector('.file-path')?.textContent).toContain('D:\\projects\\p')
    root.querySelector<HTMLButtonElement>('dialog [aria-label="关闭"]')!.click(); await settle()
    const status = root.querySelector<HTMLElement>('.state-chip')!
    expect(status.textContent).toContain('执行中')
    expect(status.tagName).toBe('SPAN')
    expect(status.tabIndex).toBe(-1)
    status.click(); await settle()
    expect(root.querySelector('dialog')).toBeNull()
    root.querySelector<HTMLButtonElement>('.model-button')!.click(); await settle()
    const details = root.querySelector('dialog')!.textContent
    expect(details).toContain('同一时间运行一个前台 Run')
    expect(details).toContain('fixture-model')
    expect(details).toContain('已报告用量12 tokens')
    expect(fetcher.mock.calls.every(([, options]) => options?.method === 'GET')).toBe(true)
})

it('工作副本面板向所选项目请求打开目录，并呈现操作错误', async () => {
    const { root, fetcher } = mount(() => { throw Error('无法打开文件夹') })
    await settle()
    root.querySelector<HTMLButtonElement>('.workspace-btn')!.click(); await settle()
    root.querySelector<HTMLButtonElement>('dialog .button-row .primary')!.click(); await settle()
    expect(fetcher).toHaveBeenCalledWith('/api/workbench/projects/p/open', expect.objectContaining({ method: 'POST' }))
    expect(root.querySelector('dialog [role=alert]')?.textContent).toContain('无法打开文件夹')
})

async function openImport(root: HTMLElement) {
    root.querySelector<HTMLButtonElement>('.new-project')!.click()
    await nextTick()
    root.querySelectorAll<HTMLInputElement>('.project-mode input')[1]!.click()
    await nextTick()
    return root.querySelector<HTMLFormElement>('.editor-form')!
}

it('选文件夹仅填入完整路径与项目名，显式提交才创建工作副本', async () => {
    const source = 'D:\\开发\\课设 项目'
    const operations: { url: string; body: Record<string, string> }[] = []
    const { root } = mount((url, body) => {
        operations.push({ url, body })
        return url.endsWith('/choose-folder') ? { path: source } : { id: 'new-id' }
    })
    await settle()
    const form = await openImport(root)
    form.querySelector<HTMLButtonElement>('.folder-selection button')!.click()
    await settle()
    expect(form.querySelector('.selected-source')?.textContent).toBe(source)
    expect(form.querySelector<HTMLInputElement>('input[maxlength]')?.value).toBe('课设 项目')
    expect(operations.map(op => op.url)).toEqual(['/api/workbench/projects/choose-folder'])
    form.dispatchEvent(new Event('submit'))
    await settle()
    expect(operations.filter(op => op.url === '/api/workbench/projects'))
        .toEqual([{ url: '/api/workbench/projects', body: { name: '课设 项目', source } }])
})

it('取消文件夹选择保留先前的路径和自定义项目名，换选也不覆盖自定义项目名', async () => {
    const paths = [null, 'D:\\甲', null, 'D:\\乙']
    const { root, fetcher } = mount(() => ({ path: paths.shift() }))
    await settle()
    const form = await openImport(root)
    const pick = form.querySelector<HTMLButtonElement>('.folder-selection button')!
    pick.click(); await settle()
    expect(form.querySelector('.selected-source')).toBeNull()
    expect(form.querySelector<HTMLButtonElement>('.primary')?.disabled).toBe(true)
    pick.click(); await settle()
    const name = form.querySelector<HTMLInputElement>('input[maxlength]')!
    name.value = '我的名字'; name.dispatchEvent(new Event('input'))
    pick.click(); await settle()
    expect(form.querySelector('.selected-source')?.textContent).toBe('D:\\甲')
    pick.click(); await settle()
    expect(name.value).toBe('我的名字')
    expect(form.querySelector('.selected-source')?.textContent).toBe('D:\\乙')
    expect(fetcher.mock.calls.filter(([url, opt]) => opt?.method === 'POST' && !url.endsWith('/choose-folder'))).toHaveLength(0)
})

it('本机选择器不可用时可以用路径导入，空项目仍可独立创建', async () => {
    const operations: Record<string, string>[] = []
    const { root } = mount((url, body) => {
        if (url.endsWith('/choose-folder')) throw Error('桌面不可用')
        if (url.endsWith('/projects')) operations.push(body)
        return { id: 'new-id' }
    })
    await settle()
    const form = await openImport(root)
    form.querySelector<HTMLButtonElement>('.folder-selection button')!.click()
    await settle()
    expect(form.querySelector('[role=alert]')?.textContent).toContain('手动输入')
    const source = form.querySelector<HTMLInputElement>('.manual-path input')!
    source.value = 'D:\\备选'; source.dispatchEvent(new Event('input'))
    const name = form.querySelector<HTMLInputElement>('input[maxlength]')!
    name.value = '新项目'; name.dispatchEvent(new Event('input'))
    form.dispatchEvent(new Event('submit'))
    await vi.waitFor(() => expect(root.querySelector('dialog')).toBeNull())
    root.querySelector<HTMLButtonElement>('.new-project')!.click(); await nextTick()
    const empty = root.querySelector<HTMLFormElement>('.editor-form')!
    const emptyName = empty.querySelector<HTMLInputElement>('input[maxlength]')!
    emptyName.value = '空项目'; emptyName.dispatchEvent(new Event('input')); await nextTick()
    empty.dispatchEvent(new Event('submit')); await settle()
    expect(operations).toEqual([{ name: '新项目', source: 'D:\\备选' }, { name: '空项目', source: null }])
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
    const {root} = mount(undefined, true); await settle()
    const field = root.querySelector('textarea')!
    field.value = '只在调查里'; field.dispatchEvent(new Event('input')); await nextTick()
    const toggle = root.querySelector<HTMLButtonElement>('.nav-toggle')!
    toggle.click(); await nextTick()
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    root.querySelectorAll<HTMLButtonElement>('dialog .conversation-node')[1]!.click(); await settle()
    expect(root.querySelector('textarea')?.value).toBe('')
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    toggle.click(); await nextTick()
    root.querySelectorAll<HTMLButtonElement>('dialog .conversation-node')[0]!.click(); await settle()
    expect(root.querySelector('textarea')?.value).toBe('只在调查里')
})
