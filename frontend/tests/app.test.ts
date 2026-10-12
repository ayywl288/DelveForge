import { createApp, nextTick } from 'vue'
import { expect, it, vi } from 'vitest'
import App from '../src/App.vue'
import { copy, plan, profile } from './fixtures'
import { api, ApiError } from '../src/api/client'
import * as sessionModule from '../src/state/session'
import { sessionKey } from '../src/state/session'

it.each([
  [422, 'EXECUTION_CRITERIA_UNSUPPORTED', '重复点击执行不会解决'],
  [502, 'EXECUTION_AI_FAILED', 'Provider 响应处理失败'],
  [422, 'CODING_PROPOSAL_INVALID', '未通过解析或契约校验'],
])('执行拒绝 %s/%s 在全局提示中显示具体原因，查询按钮不重放执行', async (status, code, text) => {
  const s = sessionModule.createSession()
  s.ids.planId = 'plan-1'
  s.plan.value = plan()
  s.copy.value = copy()
  vi.spyOn(s, 'initialize').mockResolvedValue(null)
  vi.spyOn(s, 'restore').mockResolvedValue(undefined)
  vi.spyOn(sessionModule, 'createSession').mockReturnValue(s)
  vi.spyOn(api, 'plan').mockResolvedValue(plan())
  vi.spyOn(api, 'workingCopy').mockResolvedValue(copy())
  const execute = vi.spyOn(api, 'execute').mockRejectedValue(new ApiError(Number(status), String(code)))
  const root = document.createElement('div')
  document.body.append(root)
  const app = createApp(App)
  app.mount(root)
  try {
    await s.execute('step-1')
    await nextTick()
    const alert = root.querySelector('.alert[role="alert"]')!
    expect(alert.textContent).toContain(String(text))
    expect(alert.textContent).not.toContain('重试即可')
    expect(alert.textContent).not.toContain('AI 调用未获得可用结果')
    alert.querySelector('button')!.click()
    await nextTick()
    expect(s.restore).toHaveBeenCalledOnce()
    expect(execute).toHaveBeenCalledOnce()
  } finally {
    app.unmount()
  }
})

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
