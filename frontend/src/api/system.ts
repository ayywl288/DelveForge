/**
 * Backend 连通性检查。
 *
 * 该调用只用于验证 Frontend → Local Backend 的 HTTP 链路，不承载业务语义。
 *
 * 路径始终使用相对形式：开发环境由 Vite dev server 代理到本地 Backend，
 * 前端代码不持有任何环境相关的 Backend 地址。
 */

export interface BackendConnectivity {
  service: string
  status: string
  timestamp: string
}

const CONNECTIVITY_PATH = '/api/system/connectivity'

export async function fetchBackendConnectivity(): Promise<BackendConnectivity> {
  const response = await fetch(CONNECTIVITY_PATH)
  if (!response.ok) {
    throw new Error(`Backend 返回 HTTP ${response.status}`)
  }
  return (await response.json()) as BackendConnectivity
}
