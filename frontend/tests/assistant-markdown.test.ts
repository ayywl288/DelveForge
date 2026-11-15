import { createApp, nextTick } from 'vue'
import { expect, it, vi } from 'vitest'
import AssistantMarkdown from '../src/workbench/AssistantMarkdown.vue'
import { renderAssistant } from '../src/workbench/markdown'

function rendered(text: string) {
    const root = document.createElement('div')
    root.innerHTML = renderAssistant(text)
    return root
}

it('渲染标题、强调、列表、表格和安全的外部链接', () => {
    const root = rendered(['## 摘要', '**已完成**、*说明*、`inline`', '', '- 苹果', '', '1. 运行',
        '', '| 项 | 值 |', '| --- | --- |', '| A | 1 |', '', '[文档](https://example.org/guide)'].join('\n'))
    for (const tag of ['h2', 'strong', 'em', 'code', 'ul li', 'ol li', 'table td']) expect(root.querySelector(tag)).toBeTruthy()
    const link = root.querySelector('a')!
    expect(link.href).toBe('https://example.org/guide')
    expect(link.rel).toContain('noreferrer')
})

it('HTML 保持为文字，外部图片、危险协议和未闭合围栏不产生外部资源', () => {
    const root = rendered(['<iframe src="https://example.org/frame"></iframe>', '![图示](https://example.org/file)',
        '[本机](file:///test)', '[数据](data:text/plain,hello)', '[不可导航](javascript:void(0))',
        '```unknown', '<b>未闭合'].join('\n\n'))
    expect(root.querySelector('iframe, img, script, object, a, [src]')).toBeNull()
    expect(root.textContent).toContain('图示')
    expect(root.querySelector('pre code')?.textContent).toContain('<b>未闭合')
})

it('高亮代码并复制原始换行和特殊字符', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const root = document.createElement('div')
    const code = 'const less = a < b\n'
    const app = createApp(AssistantMarkdown, { text: '```js\n' + code + '```' })
    app.mount(root)
    try {
        expect(root.querySelector('pre code .hljs-keyword')).toBeTruthy()
        root.querySelector('button')!.click()
        await Promise.resolve()
        await nextTick()
        expect(writeText).toHaveBeenCalledWith(code)
        expect(root.querySelector('[role=status]')?.textContent).toBe('代码已复制')
    } finally {
        app.unmount()
        vi.unstubAllGlobals()
    }
})
