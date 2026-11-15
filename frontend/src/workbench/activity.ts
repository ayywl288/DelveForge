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
export interface MessageEntry {
    kind: 'message'
    key: string
    message: Message
    accompaniesTools: boolean
}
export type Entry = ToolActivity | MessageEntry

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
        const calls = proposedTools(m)
        if (m.text) {
            entries.push({ kind: 'message', key: m.role + '-' + m.seq, message: m,
                accompaniesTools: m.role === 'assistant' && calls.length > 0 })
        }
        for (const call of calls) {
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

export function projectRelativePath(path: string, projectId: string) {
    const root = '/projects/' + projectId
    if (!projectId || (path !== root && !path.startsWith(root + '/'))) return path
    const relative = path.slice(root.length).replace(/^\/+/, '')
    if (relative.split('/').includes('..')) return path
    return relative || '.'
}

export function toolTarget(row: ToolActivity, projectId: string) {
    if (row.name !== 'Bash') {
        return typeof row.values.path === 'string' ? projectRelativePath(row.values.path, projectId) : ''
    }
    const command = row.values.command
    if (typeof command !== 'string') return ''
    // Bash 本就在项目根目录启动。只简写开头可明确识别的 cd，原命令完整保存在回执详情。
    const cd = /^[ \t]*cd[ \t]+(?:--[ \t]+)?("[^"$`\\\r\n]*"|'[^'\r\n]*'|[\p{L}\p{N}._/@+-]+)[ \t]*(?:(&&)[ \t]*|$)/u.exec(command)
    if (!cd) return command
    const operand = cd[1]!
    const quoted = operand.startsWith('"') || operand.startsWith("'")
    const path = quoted ? operand.slice(1, -1) : operand
    const local = projectRelativePath(path, projectId)
    if (local === path) return command
    const rest = command.slice(cd[0].length)
    if (local === '.' && cd[2] && rest.trim()) return rest.trimStart()
    const relative = local.startsWith('-') ? './' + local : local
    const display = quoted ? operand[0] + relative + operand[0] : relative
    return 'cd ' + display + (cd[2] ? ' && ' + rest : '')
}

export function recordedChanges(entries: Entry[]) {
    return entries.filter((row): row is ToolActivity => row.kind === 'tool' && ['Edit','Write'].includes(row.name)
        && row.result?.exit === 0 && !row.result.notStarted && typeof row.values.path === 'string')
}

export function previewLinks(messages: Message[]) {
    const mentioned = messages.flatMap(m => m.role === 'tool' || m.role === 'assistant' ? previews(m.text) : [])
    return [...new Set(mentioned.reverse())]
}
