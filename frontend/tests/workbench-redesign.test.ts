import { createApp, h, nextTick } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import { recordedChanges, timeline, toolStatus, type ToolActivity } from '../src/workbench/activity'
import { type Feed, type Message } from '../src/workbench/api'
import { useWorkbench } from '../src/workbench/useWorkbench'

function message(seq: number, run_id: string, role: string, name = 'Edit', exit = 0): Message {
    return { seq, run_id, role, provider_id: role === 'tool' ? 'repeat' : null, tool_name: role === 'tool' ? name : null,
        recorded_at: '', calls: role === 'assistant' ? JSON.stringify([{ id: 'repeat', name,
            arguments: JSON.stringify({ path: 'notes.md', old: 'A', new: 'B' }) }]) : '[]',
        text: role === 'tool' ? JSON.stringify({ exit, invocation: 'inv-' + seq }) : null }
}

it('相同提供方标识跨轮、跨 Run 仍配到正确的参数、回执和前台输出', () => {
    const feed: Feed = { runs: [], messages: [message(1, 'a', 'assistant'), message(2, 'a', 'tool'),
        message(3, 'a', 'assistant', 'Bash'), message(4, 'a', 'tool', 'Bash', 2),
        message(5, 'b', 'assistant', 'Write')], activeTools: [{ id: 'live', run_id: 'b', assistant_seq: 5,
        provider_id: 'repeat', name: 'Write', frames: [{ channel: 'stdout', text: '工作中' }] }] }
    const rows = timeline(feed) as ToolActivity[]
    expect(rows.map(r => toolStatus(r).label)).toEqual(['已完成', '退出码 2', '执行中'])
    expect(rows.map(r => r.result?.invocation)).toEqual(['inv-2','inv-4',undefined])
    expect(toolStatus(rows[2]!, 'BLOCKED').label).toBe('待核对')
    expect(toolStatus({ ...rows[2]!, result: { notStarted: true } }).label).toBe('未开始')
    expect(toolStatus({ ...rows[2]!, result: { exit: null } }).label).toBe('退出状态未知')
    expect(recordedChanges(rows).map(r => r.key)).toEqual([rows[0]!.key])
})

it('文件面板只列出有成功回执和原始路径的写入', () => {
    const rows = timeline({ runs: [], messages: [message(1, 'a','assistant','Write'), message(2, 'a','tool','Write',2),
        message(3,'a','assistant','Bash'), message(4,'a','tool','Bash'),
        message(5,'b','tool','Edit'), message(6,'b','assistant','Edit'), message(7,'b','tool','Edit')] })
    expect(recordedChanges(rows).map(r => [r.name,r.values.old,r.values.new])).toEqual([['Edit','A','B']])
})

const response = (v: unknown) => new Response(JSON.stringify(v))
const project = (id: string) => ({ id, name: id, workcopy: id, state: 'READY', detail: null })
const conv = (id: string) => ({ id, title: id, project_id: id[0] })
let cleanup: (() => void) | undefined
afterEach(() => { cleanup?.(); cleanup = undefined; vi.unstubAllGlobals(); localStorage.clear(); sessionStorage.clear() })

async function mount(receive: (body: { requestId: string; text: string }) => Promise<Response>) {
    vi.stubGlobal('fetch', vi.fn(async (address: string, init: RequestInit) => {
        const path = address.replace('/api/workbench', '')
        if (path === '/projects') return response([project('A'), project('B')])
        if (path === '/settings') return response({})
        if (path === '/projects/A/conversations') return response([conv('A1'),conv('A2')])
        if (path === '/projects/B/conversations') return response([conv('B1')])
        if (path.endsWith('/messages')) return receive(JSON.parse(String(init.body)))
        return response({ runs: [], messages: [] })
    }))
    let wb!: ReturnType<typeof useWorkbench>
    const app = createApp({ setup() { wb = useWorkbench(); return () => h('div') } })
    app.mount(document.createElement('div'))
    cleanup = () => app.unmount()
    await vi.waitFor(() => expect(wb.loading.value).toBe(false))
    return wb
}

it('切换项目后找回所选对话和草稿，刷新同一标签页仍可继续输入', async () => {
    let wb = await mount(async () => response({}))
    await wb.selectConversation('A2')
    wb.draft.value = 'A 的草稿'
    await wb.selectProject('B')
    wb.draft.value = 'B 的草稿'
    await wb.selectProject('A')
    expect([wb.conversationId.value, wb.draft.value]).toEqual(['A2','A 的草稿'])
    cleanup!()
    wb = await mount(async () => response({}))
    expect([wb.projectId.value, wb.conversationId.value, wb.draft.value]).toEqual(['A','A2','A 的草稿'])
})

it('在提交期间切换对话，迟到的确认只清理原草稿，同一次连按只发一条', async () => {
    let settle!: (value: Response) => void
    const receive = vi.fn(() => new Promise<Response>(resolve => { settle = resolve }))
    const wb = await mount(receive)
    wb.draft.value = '给 A'
    const sent = wb.send()
    expect(await wb.send()).toBe(false)
    await wb.selectProject('B')
    wb.draft.value = '给 B'
    settle(response({ sequence: 1, runId: 'run-A' }))
    expect(await sent).toBe(true)
    expect(receive).toHaveBeenCalledTimes(1)
    expect(wb.draft.value).toBe('给 B')
    await wb.selectProject('A')
    expect(wb.draft.value).toBe('')
})

it('确认丢失后刷新再提交沿用原请求标识，收到成功确认后清除草稿', async () => {
    const ids: string[] = []
    const receive = vi.fn(async (body: {requestId: string}) => {
        ids.push(body.requestId)
        if (ids.length === 1) throw Error('连接中断')
        return response({ sequence: 3, runId: 'one' })
    })
    let wb = await mount(receive)
    wb.draft.value = '请保留输入'
    expect(await wb.send()).toBe(false)
    expect(wb.error.value).toBe('连接中断')
    cleanup!()
    wb = await mount(receive)
    expect(await wb.send()).toBe(true)
    await nextTick()
    expect(ids).toHaveLength(2)
    expect(ids[1]).toBe(ids[0])
    expect(wb.draft.value).toBe('')
    expect(wb.notice.value).toContain('消息已保存')
})
