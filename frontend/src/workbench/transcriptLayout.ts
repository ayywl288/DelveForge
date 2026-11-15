import { toolStatus, toolTarget, type Entry, type MessageEntry, type ToolActivity } from './activity'
import type { Run } from './api'

interface ToolBatch {
    kind: 'batch'
    key: string
    tools: ToolActivity[]
}
interface WorkSection {
    kind: 'work'
    key: string
    runId?: string | null
    steps: (MessageEntry | ToolBatch)[]
    tools: ToolActivity[]
    batches: number
}

// 工具附带的过程说明可以折叠。用户输入、独立回复和 Run 边界始终分开。
export function transcriptSections(entries: Entry[]) {
    const sections: (MessageEntry | WorkSection)[] = []
    let work: WorkSection | undefined
    for (const entry of entries) {
        if (entry.kind === 'message' && !entry.accompaniesTools) {
            sections.push(entry)
            work = undefined
            continue
        }
        const runId = entry.kind === 'tool' ? entry.runId : entry.message.run_id
        if (!work || work.runId !== runId) {
            work = { kind: 'work', key: 'work-' + entry.key, runId, steps: [], tools: [], batches: 0 }
            sections.push(work)
        }
        if (entry.kind === 'message') {
            work.steps.push(entry)
            continue
        }
        const last = work.steps.at(-1)
        if (last?.kind === 'batch') {
            last.tools.push(entry)
        } else {
            work.steps.push({ kind: 'batch', key: 'batch-' + entry.key, tools: [entry] })
            work.batches++
        }
        work.tools.push(entry)
    }
    return sections
}

export function batchTitle(rows: ToolActivity[]) {
    const name = rows[0]?.name
    if (name && rows.every(row => row.name === name)) {
        switch (name) {
            case 'Bash': return '运行 ' + rows.length + ' 条命令'
            case 'Read': return '读取 ' + rows.length + ' 次'
            case 'Edit': return '编辑 ' + rows.length + ' 次'
            case 'Write': return '写入 ' + rows.length + ' 次'
        }
    }
    return rows.length + ' 次工具调用'
}

export function activitySummary(rows: ToolActivity[], projectId: string, runState?: string) {
    let failures = 0, unknown = 0, skipped = 0, gaps = 0
    let unresolved = 0
    let pending: ToolActivity | undefined
    for (const row of rows) {
        const result = row.result
        if (!result) {
            unresolved++
            if (!pending || row.live) pending = row
            continue
        }
        if (result.notStarted) skipped++
        else if (result.exit == null) unknown++
        else if (result.exit !== 0) failures++
        if (result.outputGap) gaps++
    }
    const flags: string[] = []
    if (failures) flags.push(failures + ' 次失败')
    if (unknown) flags.push(unknown + ' 次退出状态未知')
    if (unresolved && (runState === 'BLOCKED' || runState === 'RECONCILING')) flags.push(unresolved + ' 次待核对')
    if (skipped) flags.push(skipped + ' 次未开始')
    if (gaps) flags.push(gaps + ' 次输出有缺口')
    const liveStatus = pending ? toolStatus(pending, runState).label : ''
    const target = pending ? toolTarget(pending, projectId).split('\n')[0] : ''
    return {
        warnings: flags.join(' · '),
        tone: failures ? 'bad' : 'warn',
        current: pending ? [liveStatus, target || pending.name].join(' · ') : '',
    }
}

// 轮询能确认前台 Run 和工具回执。提示表示用户正在等下一条回复。
export function awaitingReply(entries: Entry[], run?: Run) {
    if (run?.state !== 'RUNNING') return false
    return !entries.some(entry => entry.kind === 'tool'
        ? entry.runId === run.id && !entry.result
        : entry.message.run_id === run.id && entry.message.role === 'assistant' && !entry.accompaniesTools)
}
