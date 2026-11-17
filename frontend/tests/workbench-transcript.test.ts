import { createApp, h, nextTick, ref } from 'vue'
import { describe, expect, it } from 'vitest'
import Transcript from '../src/workbench/Transcript.vue'
import WorkbenchDetails from '../src/workbench/WorkbenchDetails.vue'
import { projectRelativePath, recordedChanges, timeline, toolTarget, type ToolActivity } from '../src/workbench/activity'
import { activitySummary, awaitingReply, transcriptSections } from '../src/workbench/transcriptLayout'
import type { Feed, Message, Run } from '../src/workbench/api'

const rootPath = '/projects/project-a'
const run: Run = { id: 'run-1', state: 'RUNNING', detail: null, requests: 1, allowance: 96, tokens: 0 }
const call = (id: string, name: string, values: Record<string, string>) => ({ id, name, arguments: JSON.stringify(values) })
function message(seq: number, role: string, text: string, calls: ReturnType<typeof call>[] = []): Message {
    return { seq, role, text, calls: JSON.stringify(calls), tool_name: null, provider_id: null,
        run_id: run.id, recorded_at: '2026-11-15T00:00:00Z' }
}
function result(seq: number, tool: ReturnType<typeof call>, exit: number | null = 0): Message {
    return { ...message(seq, 'tool', JSON.stringify({ exit })), tool_name: tool.name, provider_id: tool.id }
}

const write = call('new-file', 'Write', { path: rootPath + '/.gitignore', content: 'target/' })
const read = call('read-file', 'Read', { path: rootPath + '/src/App.java' })
const bash = call('compile', 'Bash', { command: 'cd ' + rootPath + ' && mvn test' })
const history = [
    message(1, 'user', '构建项目'),
    message(2, 'assistant', '先准备文件。', [write, read]), result(3, write), result(4, read, 2),
    message(5, 'assistant', '再执行测试。', [bash]), result(6, bash),
    message(7, 'assistant', '测试完成。'),
]

describe('项目路径的显示', () => {
    it('只从当前项目的文件标签中移除完整根目录，保留层级、外部路径和父目录引用', () => {
        for (const [input, display] of [
            [rootPath, '.'], [rootPath + '/.gitignore', '.gitignore'],
            [rootPath + '/src/a/Main.java', 'src/a/Main.java'], [rootPath + '/tests/a/Main.java', 'tests/a/Main.java'],
            ['/projects/project-abc/file', '/projects/project-abc/file'], ['/etc/hosts', '/etc/hosts'],
            ['src/Main.java', 'src/Main.java'], [rootPath + '/../other', rootPath + '/../other'],
        ]) expect(projectRelativePath(input!, 'project-a')).toBe(display)
    })

    it('简写开头明确的项目 cd 并保留复合命令、引号内的数据和原始调用', () => {
        for (const [command, expected] of [
            ['cd ' + rootPath + ' && mvn test', 'mvn test'],
            ["cd -- '" + rootPath + "/my src' && npm test", "cd 'my src' && npm test"],
            ['cd "' + rootPath + '"', 'cd "."'],
            ['cd ' + rootPath + '/-P && ls', 'cd ./-P && ls'],
            ['cd "' + rootPath + '/$WORK" && pwd', 'cd "' + rootPath + '/$WORK" && pwd'],
            ['cd /projects/elsewhere && pwd', 'cd /projects/elsewhere && pwd'],
            ['cd ' + rootPath + ' || exit 1', 'cd ' + rootPath + ' || exit 1'],
            ["echo 'cd " + rootPath + " && pwd'", "echo 'cd " + rootPath + " && pwd'"],
        ]) {
            const tool = call('command', 'Bash', { command: command! })
            const [row] = timeline({ messages: [message(1, 'assistant', '', [tool])], runs: [] }) as ToolActivity[]
            expect(toolTarget(row!, 'project-a')).toBe(expected)
            expect(row!.arguments).toBe(tool.arguments)
        }
    })

    it('工作副本显示相对文件名、当时的写入内容和可复制的 Windows 目录', () => {
        const project = { id: 'project-a', name: '示例', workcopy: 'E:/work/project-a', state: 'READY', detail: null }
        const root = document.createElement('div')
        const app = createApp(WorkbenchDetails, { project, mode: 'workspace', previews: [], pending: false,
            changes: recordedChanges(timeline({ messages: history, runs: [run] })) })
        app.mount(root)
        try {
            expect(root.querySelector('.file-change summary code')?.textContent).toBe('.gitignore')
            expect(root.querySelector('.file-change pre')?.textContent).toBe('target/')
            expect(root.querySelector('.file-path')?.textContent).toBe(project.workcopy)
        } finally { app.unmount() }
    })
})

describe('对话中的工作过程', () => {
    it('折叠多段工作、保留最终回复和失败提示，展开选择在轮询刷新后仍有效', async () => {
        const feed = ref<Feed>({ messages: history, runs: [{ ...run, state: 'COMPLETED' }] })
        const root = document.createElement('div')
        const app = createApp(() => h(Transcript, { projectId: 'project-a', conversation: 'c',
            entries: timeline(feed.value), runs: feed.value.runs }))
        app.mount(root)
        try {
            const group = root.querySelector<HTMLButtonElement>('.work-history > .activity-heading button')!
            expect(group.textContent).toContain('3 次工具调用')
            expect(group.textContent).not.toContain('1 次失败')
            expect(group.getAttribute('aria-expanded')).toBe('false')
            expect(root.querySelectorAll('.transcript > .message')).toHaveLength(2)
            expect(root.textContent).toContain('测试完成。')
            expect(root.querySelector('.tool-event')).toBeNull()
            group.click()
            await nextTick()
            expect(group.textContent).toContain('1 次失败')
            const batch = root.querySelector<HTMLButtonElement>('.tool-batch .activity-toggle')!
            expect(batch.getAttribute('aria-expanded')).toBe('false')
            batch.click()
            await nextTick()
            expect(root.querySelectorAll('.tool-event')).toHaveLength(3)
            const item = root.querySelector('details')!
            expect(item.querySelector('summary code')?.textContent).toBe('.gitignore')
            expect(root.querySelectorAll('.tool-event summary code')[2]?.textContent).toBe('mvn test')
            item.querySelector('summary')!.click()
            expect(item.open).toBe(true)
            expect(item.querySelector('pre')?.textContent).toBe(write.arguments)
            group.click()
            await nextTick()
            feed.value = { ...feed.value, messages: [...history] }
            await nextTick()
            group.click()
            await nextTick()
            expect(root.querySelector('details')).toBe(item)
            expect(item.open).toBe(true)
            expect(batch.getAttribute('aria-expanded')).toBe('true')
        } finally { app.unmount() }
    })

    it('用户纠正、独立回复和不同任务分隔工具组，不把后续操作藏进先前的组', () => {
        const conversation = [
            history[1]!, { ...message(3, 'pending', '先核对文件。'), run_id: null },
            message(4, 'assistant', '', [read]), message(5, 'assistant', '已核对。'),
            message(6, 'assistant', '', [bash]), { ...message(7, 'assistant', '', [bash]), run_id: 'run-2' },
        ]
        const sections = transcriptSections(timeline({ messages: conversation, runs: [] }))
        expect(sections.map(s => s.kind === 'work' ? s.runId : s.message.role))
            .toEqual([run.id, 'pending', run.id, 'assistant', run.id, 'run-2'])
    })

    it('汇总收起时仍保留未知退出状态、输出缺口、待核对及未开始的次数', () => {
        const rows = timeline({ messages: [message(1, 'assistant', '', [write, read, bash])], runs: [] }) as ToolActivity[]
        rows[0]!.result = { exit: null, outputGap: true }
        rows[1]!.result = { notStarted: true }
        const summary = activitySummary(rows, 'project-a', 'BLOCKED')
        for (const label of ['1 次退出状态未知', '1 次输出有缺口', '1 次待核对', '1 次未开始']) {
            expect(summary.warnings).toContain(label)
        }
        expect(summary.current).toContain('mvn test')
    })

    it('只有运行中且在等下一条回复时显示提示，受理或执行工具时切换，回执后恢复，最终回复收起', async () => {
        const feed = ref<Feed>({ messages: [history[0]!], runs: [run] })
        const root = document.createElement('div')
        const app = createApp(() => h(Transcript, { projectId: 'project-a', conversation: 'c',
            entries: timeline(feed.value), runs: feed.value.runs }))
        app.mount(root)
        try {
            const indicator = () => root.querySelector('.reply-wait [role=status]')
            expect(indicator()?.textContent).toBe('等待回复')
            expect(indicator()?.querySelectorAll('[aria-hidden=true] i')).toHaveLength(3)
            feed.value.messages.push(message(2, 'assistant', '', [bash]))
            await nextTick()
            expect(indicator()).toBeNull()
            feed.value.activeTools = [{ id: 'inv', provider_id: bash.id, name: bash.name, run_id: run.id,
                assistant_seq: 2, frames: [{ channel: 'stdout', text: 'compiling' }] }]
            await nextTick()
            expect(root.querySelector('.tool-state')?.textContent).toBe('执行中')
            expect(indicator()).toBeNull()
            feed.value.activeTools = []
            feed.value.messages.push(result(3, bash))
            await nextTick()
            expect(indicator()).toBeTruthy()
            feed.value.messages.push(history[6]!)
            await nextTick()
            expect(indicator()).toBeNull()
        } finally { app.unmount() }
    })

    it('忽略其他任务的旧提议，并在暂停、停止、失败和核对时关闭等待提示', () => {
        const entries = timeline({ messages: [{ ...history[1]!, run_id: 'older' }], runs: [] })
        expect(awaitingReply(entries, run)).toBe(true)
        for (const state of ['PAUSED', 'STOPPING', 'STOPPED', 'RECONCILING', 'BLOCKED', 'FAILED', 'COMPLETED']) {
            expect(awaitingReply(entries, { ...run, state })).toBe(false)
        }
        expect(awaitingReply(entries)).toBe(false)
    })
})
