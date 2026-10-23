import { createApp, nextTick } from 'vue'
import { expect, it, vi } from 'vitest'
import App from '../src/App.vue'
import { profile } from './fixtures'
import { sessionKey } from '../src/state/session'
import * as sessions from '../src/state/session'

it('切换阶段保留未发送草稿，刷新恢复不自动发送业务 POST', async () => {
  localStorage.setItem(sessionKey, JSON.stringify({ profileId: 'profile-1', assetIds: [], repositoryIds: [], directionIds: [], planId: '' }))
  const fetcher = vi.fn(async (url: string, _options?: RequestInit) => new Response(JSON.stringify(url.includes('user-profiles') ? profile() : { status: 'UP' })))
  vi.stubGlobal('fetch', fetcher)
  const root = document.createElement('div'); document.body.append(root); const app = createApp(App); app.mount(root)
  try {
    for (let i = 0; i < 20; i++) { await Promise.resolve(); await nextTick() }
    const input = root.querySelector('#discovery-input') as HTMLTextAreaElement
    input.value = '尚未发送的草稿'; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
    const nav = root.querySelectorAll('.workflow-navigation .nav-stage'); (nav[1] as HTMLButtonElement).click(); await nextTick(); (nav[0] as HTMLButtonElement).click(); await nextTick()
    expect(root.querySelector('.app-navigation .project-entry')).not.toBeNull()
    expect(root.querySelectorAll('.workflow-navigation .navigation-icon')).toHaveLength(5)
    expect(root.querySelector('.workflow-navigation [aria-current="step"]')?.textContent).toContain('需求探索')
    expect((root.querySelector('#discovery-input') as HTMLTextAreaElement).value).toBe('尚未发送的草稿')
    expect(fetcher.mock.calls.every(([, options]) => options?.method === 'GET')).toBe(true)
    expect(localStorage.getItem(sessionKey)).not.toContain('尚未发送的草稿')
  } finally { app.unmount() }
})

it('当前项目名称保留完整可访问文本，长名称可聚焦查看且不增加导航动作', async () => {
  const session = sessions.createSession()
  const name = '非常长的项目名称用于验证两行显示与完整名称的可访问性'.repeat(6)
  session.projectContext.value = { id: 'context-1', name, userProfileId: null, assetIds: [],
    repositoryProfileIds: [], directionIds: [], planId: null, createdAt: '', updatedAt: '', version: 1,
    flowId: 'flow-1', previousContextId: null, deletedAt: null }
  vi.spyOn(session, 'initialize').mockResolvedValue(null)
  vi.spyOn(session, 'loadProjects').mockResolvedValue(undefined)
  vi.spyOn(sessions, 'createSession').mockReturnValue(session)
  vi.stubGlobal('fetch', vi.fn(async () => new Response('[]')))
  const root = document.createElement('div'); document.body.append(root); const app = createApp(App); app.mount(root)
  try {
    await nextTick()
    const block = root.querySelector('.project-navigation-heading')!
    const title = block.querySelector('#current-project-name')!
    expect(title.textContent).toBe(name)
    expect(title.getAttribute('title')).toBe(name)
    expect(title.getAttribute('tabindex')).toBe('0')
    expect(block.getAttribute('aria-labelledby')).toBe('current-project-name')
    expect(block.querySelector('.navigation-icon[aria-hidden="true"]')).not.toBeNull()
    expect(block.querySelectorAll('button, a')).toHaveLength(0)
  } finally { app.unmount() }
})
