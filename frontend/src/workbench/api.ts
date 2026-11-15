export interface Project { id: string; name: string; workcopy: string | null; state: string; detail: string | null }
export interface Conversation { id: string; title: string; project_id: string }
export interface Run { id: string; state: string; detail: string | null; requests: number; allowance: number; tokens: number }
export interface Message { seq: number; run_id?: string | null; role: string; text: string | null; calls: string; tool_name: string | null; provider_id: string | null; recorded_at: string }
export interface ActiveTool {
    id: string; run_id: string; assistant_seq: number; provider_id: string; name: string
    frames: { channel: string; text: string }[]
}
export interface Foreground { projectId: string; conversationId: string }
export interface Feed { messages: Message[]; runs: Run[]; activeTools?: ActiveTool[]; foreground?: Foreground | null }
export interface Ack { sequence: number; runId: string | null; newlyStarted: boolean }
export interface RuntimeSettings {
    model: { adapter: string; endpoint: string; requestedModel: string; thinking: string }
    defaultRequestLimit: number
    contextWindowTokens: number
    responseTokens: number
    commandSeconds: number
}
export interface OutputPage { chunks: { channel: string; base64: string }[]; bytes: number; next: number; gap: boolean }
export interface Receipt { invocation?: string; stdout?: string; stderr?: string; exit?: number | null; capturedBytes?: number; displayTruncated?: boolean; outputGap?: boolean; detail?: string; notStarted?: boolean }

export async function request<T>(path: string, body?: unknown): Promise<T> {
    const response = await fetch('/api/workbench' + path, body === undefined ? { method: 'GET' } : {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    })
    const text = await response.text()
    if (!response.ok) {
        let reason = '请求失败（' + response.status + '）'
        try { reason = JSON.parse(text).message || reason } catch { /* 非 JSON 错误保留 HTTP 状态。 */ }
        throw new Error(reason)
    }
    return text ? JSON.parse(text) as T : undefined as T
}

export function receipt(message: Message): Receipt {
    try { return JSON.parse(message.text || '{}') as Receipt } catch { return {} }
}

export function proposedTools(message: Message): { id: string; name: string; arguments: string }[] {
    try {
        const calls: unknown = JSON.parse(message.calls || '[]')
        return Array.isArray(calls) ? calls.filter(call => call && typeof call.id === 'string'
            && typeof call.name === 'string' && typeof call.arguments === 'string') : []
    } catch { return [] }
}

export function previews(text: string | null): string[] {
    return [...new Set(text?.match(/http:\/\/p-[0-9a-f]{24}\.localhost:\d{1,5}/g) || [])]
}

export const stateText: Record<string, string> = {
    RUNNING: '执行中', STOPPING: '正在停止', RECONCILING: '核对前台执行中', BLOCKED: '需要核对',
    PAUSED: '已暂停', COMPLETED: '已完成', STOPPED: '已停止', FAILED: '失败',
}
