import { createApp, nextTick } from 'vue'
import { expect, it, vi } from 'vitest'
import Transcript from '../src/workbench/Transcript.vue'
import { previews, type Message } from '../src/workbench/api'

it('显示模型原始调用，并在按字节翻页后还原跨页中文输出', async () => {
    const call = { id: 'original', name: 'Bash', arguments: '{"command": "pwd"}' }
    const base = { recorded_at: '', tool_name: null, provider_id: null }
    const messages: Message[] = [
        { ...base, seq: 1, role: 'assistant', text: '', calls: JSON.stringify([call]) },
        { ...base, seq: 2, role: 'tool', tool_name: 'Bash', calls: '[]',
            text: JSON.stringify({ invocation: 'inv', capturedBytes: 3, exit: 0 }) },
    ]
    const pages = [[0xe6], [0x9d, 0xbe]]
    vi.stubGlobal('fetch', vi.fn(async () => {
        const bytes = pages.shift()!
        return new Response(JSON.stringify({ chunks: [{ channel: 'stdout', base64: btoa(String.fromCharCode(...bytes)) }],
            bytes: 3, next: pages.length ? 1 : 3, gap: false }))
    }))
    const root = document.createElement('div')
    const app = createApp(Transcript, { conversation: 'example', messages })
    app.mount(root)
    try {
        expect(root.textContent).toContain(call.arguments)
        for (const label of ['查看已捕获输出', '加载下一页']) {
            Array.from(root.querySelectorAll('button')).find(button => button.textContent === label)!.click()
            for (let i = 0; i < 12; i++) { await Promise.resolve(); await nextTick() }
        }
        expect(root.textContent).toContain('[stdout] 松')
    } finally { app.unmount() }
})

it('只将网关生成的本机域名显示为预览，并分别标记显示截断、捕获缺口和未知退出码', async () => {
    const url = 'http://p-00112233445566778899aabb.localhost:43456'
    expect(previews('https://example.org http://127.0.0.1:1234')).toEqual([])
    const message: Message = {seq: 2, role: 'tool', calls: '[]', provider_id: 'provider-1',tool_name:'Bash',recorded_at:'',
        text: JSON.stringify({invocation: 'call', stdout: url, exit: null, capturedBytes: 10, displayTruncated: true, outputGap: true})}
    const fetcher = vi.fn(async () => new Response(JSON.stringify({chunks: [{channel:'stderr',base64:btoa('hello')}],bytes:5,next:5,gap:true})))
    vi.stubGlobal('fetch',fetcher)
    const root = document.createElement('div')
    const app = createApp(Transcript,{conversation:'conv',messages:[message]});app.mount(root)
    try {
        expect(root.querySelector('a')?.href).toBe(url + '/')
        expect(root.textContent).toContain('退出状态未知')
        expect(root.textContent).toContain('部分输出未捕获')
        expect(root.textContent).toContain('部分已捕获输出')
        root.querySelector('button')!.click()
        for(let i=0;i<12;i++){await Promise.resolve();await nextTick()}
        expect(fetcher).toHaveBeenCalledWith('/api/workbench/conversations/conv/outputs/call?offset=0',{method:'GET'})
        expect(root.textContent).toContain('[stderr] hello')
    } finally {app.unmount()}
})
