import { previews, proposedTools, receipt, type ActiveTool, type Feed, type Message, type Receipt } from './api'

export interface ToolActivity {
    key: string
    kind: 'tool'
    name: string
    arguments: string
    values: Record<string, unknown>
    result?: Receipt
    live?: ActiveTool
    runId?: string | null
}
export type Entry = ToolActivity | { kind: 'message'; key: string; message: Message }

function argumentsOf(raw: string): Record<string, unknown> {
    try {
        const value = JSON.parse(raw)
        return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
    } catch { return {} }
}

// 回执匹配此前同一 Run 的提议。提供方可在后续轮次重复使用调用 ID。
export function timeline(feed: Feed): Entry[] {
    const entries: Entry[] = []
    const waiting = new Map<string, ToolActivity[]>()
    const live = new Map((feed.activeTools ?? []).map(tool =>
        [JSON.stringify([tool.run_id, tool.assistant_seq, tool.provider_id]), tool]))
    for (const m of feed.messages) {
        if (m.role === 'tool') {
            const queue = waiting.get(JSON.stringify([m.run_id, m.provider_id]))
            const activity = queue?.shift()
            if (activity) {
                activity.result = receipt(m)
            } else {
                entries.push({ kind: 'tool', key: 'receipt-' + m.seq, name: m.tool_name || '工具',
                    runId: m.run_id, values: {}, arguments: '', result: receipt(m) })
            }
            continue
        }
        if (m.text) entries.push({ kind: 'message', key: m.role + '-' + m.seq, message: m })
        for (const call of proposedTools(m)) {
            const key = JSON.stringify([m.run_id, m.seq, call.id])
            const row: ToolActivity = { kind: 'tool', key, name: call.name, arguments: call.arguments,
                values: argumentsOf(call.arguments), live: live.get(key), runId: m.run_id }
            live.delete(key)
            const lookup = JSON.stringify([m.run_id, call.id])
            if (!waiting.has(lookup)) waiting.set(lookup, [])
            waiting.get(lookup)!.push(row)
            entries.push(row)
        }
    }
    for (const tool of live.values()) {
        entries.push({ kind: 'tool', key: tool.id, name: tool.name, runId: tool.run_id,
            arguments: '', values: {}, live: tool })
    }
    return entries
}

export function toolStatus(row: ToolActivity, state?: string) {
    const r = row.result
    if (r?.notStarted) return { label: '未开始', tone: 'muted' }
    if (r) {
        if (r.exit == null) return { label: '退出状态未知', tone: 'warn' }
        return r.exit === 0 ? { label: '已完成', tone: 'good' } : { label: '退出码 ' + r.exit, tone: 'bad' }
    }
    if (state === 'BLOCKED' || state === 'RECONCILING') return { label: '待核对', tone: 'warn' }
    if (state === 'STOPPING') return { label: '正在停止', tone: 'warn' }
    if (row.live) return { label: row.live.frames.length ? '执行中' : '已准入', tone: 'active' }
    return { label: '已提议', tone: 'muted' }
}

export function toolTarget(row: ToolActivity) {
    const value = row.values[row.name === 'Bash' ? 'command' : 'path']
    return typeof value === 'string' ? value : ''
}

export function recordedChanges(entries: Entry[]) {
    return entries.filter((row): row is ToolActivity => row.kind === 'tool' && ['Edit','Write'].includes(row.name)
        && row.result?.exit === 0 && !row.result.notStarted && typeof row.values.path === 'string')
}

export function previewLinks(messages: Message[]) {
    const mentioned = messages.flatMap(m => m.role === 'tool' || m.role === 'assistant' ? previews(m.text) : [])
    return [...new Set(mentioned.reverse())]
}
