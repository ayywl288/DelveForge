import html from '../index.html?raw'
import { createApp, nextTick } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import AppearanceSwitcher from '../src/workbench/AppearanceSwitcher.vue'

const boot = html.match(/<script>([\s\S]*?)<\/script>/)![1]!
const key = 'delveforge.appearance'
const cleanup: (() => void)[] = []
afterEach(() => {
    cleanup.splice(0).forEach(fn => fn())
    delete document.documentElement.dataset.appearance
})

function mount(dark = false) {
    let listener: ((e: { matches: boolean }) => void) | undefined
    vi.stubGlobal('matchMedia', () => ({ matches: dark,
        addEventListener: (_: string, fn: typeof listener) => { listener = fn },
        removeEventListener: () => { listener = undefined } }))
    const root = document.createElement('div')
    document.body.append(root)
    const app = createApp(AppearanceSwitcher)
    app.mount(root)
    cleanup.push(() => app.unmount())
    const toggle = root.querySelector<HTMLButtonElement>('.theme-toggle')!
    const trigger = root.querySelector<HTMLButtonElement>('[aria-haspopup=menu]')!
    const items = () => [...root.querySelectorAll<HTMLButtonElement>('[role=menuitemradio]')]
    async function menu() { trigger.click(); await nextTick(); await nextTick() }
    return { toggle, trigger, menu, items, system: (matches: boolean) => listener?.({ matches }) }
}

it.each([true, false])('系统模式显示相反动作，点击后显式覆盖系统，并可从菜单恢复（深色=%s）', async dark => {
    const ui = mount(dark)
    expect(ui.toggle.title).toBe(dark ? '切换到浅色' : '切换到深色')
    ui.system(!dark); await nextTick()
    expect(ui.toggle.getAttribute('aria-label')).toBe(dark ? '切换到深色' : '切换到浅色')
    ui.toggle.click(); await nextTick()
    const chosen = dark ? 'dark' : 'light'
    expect(localStorage.getItem(key)).toBe(chosen)
    ui.system(dark); await nextTick()
    expect(ui.toggle.title).toBe(dark ? '切换到浅色' : '切换到深色')
    ui.system(!dark); await nextTick()
    expect(ui.toggle.title).toBe(dark ? '切换到浅色' : '切换到深色')
    delete document.documentElement.dataset.appearance
    new Function(boot)()
    expect(document.documentElement.dataset.appearance).toBe(chosen)
    await ui.menu()
    expect(ui.items().find(b => b.getAttribute('aria-checked') === 'true')?.textContent).toBe(dark ? '深色' : '浅色')
    ui.items()[2]!.click(); await nextTick(); await nextTick()
    expect(localStorage.getItem(key)).toBeNull()
    expect(document.documentElement.dataset.appearance).toBeUndefined()
    expect(ui.toggle.title).toBe(dark ? '切换到深色' : '切换到浅色')
    expect(document.activeElement).toBe(ui.trigger)
})

it('方向键、Home、End、Escape 和点击外部控制小菜单', async () => {
    const ui = mount()
    const press = async (el: Element, key: string) => {
        el.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }))
        await nextTick(); await nextTick()
    }
    await press(ui.trigger, 'ArrowDown')
    expect(document.activeElement).toBe(ui.items()[0])
    await press(document.activeElement!, 'End')
    expect(document.activeElement).toBe(ui.items()[2])
    await press(document.activeElement!, 'Home')
    await press(document.activeElement!, 'ArrowDown')
    expect(document.activeElement).toBe(ui.items()[1])
    ui.items()[1]!.click(); await nextTick()
    expect(document.documentElement.dataset.appearance).toBe('dark')
    await ui.menu(); await press(document.activeElement!, 'Escape')
    expect(ui.trigger.getAttribute('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(ui.trigger)
    await ui.menu()
    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true })); await nextTick()
    expect(ui.items()).toHaveLength(0)
})

it('损坏的本机设置回退到系统配色', () => {
    localStorage.setItem(key, 'unrecognized')
    new Function(boot)()
    expect(document.documentElement.dataset.appearance).toBeUndefined()
})
