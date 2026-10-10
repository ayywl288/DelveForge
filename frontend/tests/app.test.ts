import { createApp, nextTick } from 'vue'
import { expect, it, vi } from 'vitest'
import App from '../src/App.vue'
import { profile } from './fixtures'
import { sessionKey } from '../src/state/session'

it('切换阶段保留未发送草稿，刷新恢复不自动发送业务 POST', async () => {
  localStorage.setItem(sessionKey, JSON.stringify({ profileId: 'profile-1', assetIds: [], repositoryIds: [], directionIds: [], planId: '' }))
  const fetcher = vi.fn(async (url: string, _options?: RequestInit) => new Response(JSON.stringify(url.includes('user-profiles') ? profile() : { status: 'UP' })))
  vi.stubGlobal('fetch', fetcher)
  const root = document.createElement('div'); document.body.append(root); const app = createApp(App); app.mount(root)
  try {
    for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick() }
    const input = root.querySelector('#discovery-input') as HTMLTextAreaElement
    input.value = '尚未发送的草稿'; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
    const nav = root.querySelectorAll('.nav-stage'); (nav[1] as HTMLButtonElement).click(); await nextTick(); (nav[0] as HTMLButtonElement).click(); await nextTick()
    expect((root.querySelector('#discovery-input') as HTMLTextAreaElement).value).toBe('尚未发送的草稿')
    expect(fetcher.mock.calls.every(([, options]) => options?.method === 'GET')).toBe(true)
    expect(localStorage.getItem(sessionKey)).not.toContain('尚未发送的草稿')
  } finally { app.unmount() }
})
