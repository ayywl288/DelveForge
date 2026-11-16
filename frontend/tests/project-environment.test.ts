import { createApp, nextTick } from 'vue'
import { afterEach, expect, it, vi } from 'vitest'
import ProjectEnvironment from '../src/workbench/ProjectEnvironment.vue'

const pkg = { name: 'figlet', version: '2.2.5-3', arch: 'amd64', manual: true, included: false }
async function settle() { for (let i = 0; i < 16; i++) { await Promise.resolve(); await nextTick() } }
afterEach(() => { vi.unstubAllGlobals(); document.body.replaceChildren() })

it('预览受信包后仍须确认，取消不会构建全局镜像', async () => {
    const inventory = { initialized: true, current: 0, latest: 1, latestImage: 'new', image: 'old', installed: [pkg],
        packages: [], manualFiles: ['local-script'], rollback: false }
    const requests: string[] = []
    vi.stubGlobal('fetch', async (path: string) => {
        requests.push(path)
        return new Response(JSON.stringify(path.endsWith('/maintenance') ? {operation:''} : path.endsWith('/preview')
            ? { package: {...pkg, origin:'archive.ubuntu.com'}, plan:'approved', dependencies:['figlet'] }
            : path.endsWith('/publish') ? { version: 2 } : inventory))
    })
    const root = document.createElement('div'); document.body.append(root)
    const app = createApp(ProjectEnvironment, {project: 'a'}); app.mount(root)
    const panel = root.querySelector('details')!; panel.open = true; panel.dispatchEvent(new Event('toggle')); await settle()
    expect(root.textContent).toContain('local-script')
    const button = (label: string) => [...root.querySelectorAll<HTMLButtonElement>('button')].find(b => b.textContent === label)!
    button('推广…').click(); await settle()
    expect(root.textContent).toContain('archive.ubuntu.com')
    expect(document.activeElement).toBe(button('取消'))
    button('取消').click(); await settle()
    expect(document.activeElement).toBe(button('推广…'))
    expect(requests.filter(p=>p.endsWith('/publish'))).toHaveLength(0)
    button('推广…').click(); await settle()
    button('构建全局镜像').click(); await settle()
    expect(requests.filter(p=>p.endsWith('/publish'))).toHaveLength(1)
    expect(root.textContent).toContain('v2 已就绪')
    button('升级到 v1').click(); await settle()
    expect(root.textContent).toContain('后台服务会停止')
    button('取消').click(); await settle()
    expect(requests.filter(p=>p.endsWith('/environment'))).toHaveLength(0)
    app.unmount()
})
