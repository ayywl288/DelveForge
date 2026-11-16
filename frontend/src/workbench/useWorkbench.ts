import { computed, onMounted, onUnmounted, ref } from 'vue'
import { request, type Ack, type Conversation, type Feed, type Foreground, type Project, type RuntimeSettings } from './api'

const selectionKey = 'delveforge.workbench.selection'
const key = (kind: string, id: string) => 'delveforge.workbench.' + kind + '.' + id
const blankFeed = (): Feed => ({ messages: [], runs: [] })
interface Delivery { id: string; text: string; requestLimit?: number }

export function useWorkbench() {
    const projects = ref<Project[]>([])
    const conversations = ref<Conversation[]>([])
    const projectId = ref(''), conversationId = ref('')
    const project = computed(() => projects.value.find(p => p.id === projectId.value))
    const conversation = computed(() => conversations.value.find(c => c.id === conversationId.value))
    const settings = ref<RuntimeSettings>()
    const feed = ref<Feed>(blankFeed()), foregrounds = computed<Foreground[]>(() => feed.value.foregrounds ?? [])
    const run = computed(() => feed.value.runs[0])
    const active = computed(() => ['RUNNING', 'RECONCILING', 'BLOCKED', 'STOPPING'].includes(run.value?.state ?? ''))
    const elsewhere = computed(() => foregrounds.value.find(owner => owner.projectId === projectId.value
        && owner.conversationId !== conversationId.value))
    const loading = ref(true), pending = ref(false), error = ref(''), notice = ref('')
    const input = ref('')
    const draft = computed({
        get: () => input.value,
        set(value: string) {
            input.value = value
            if (conversationId.value) {
                const k = key('draft', conversationId.value)
                if (value) sessionStorage.setItem(k, value)
                else sessionStorage.removeItem(k)
            }
        },
    })
    const limit = ref<number | ''>('')
    const exhausted = computed(() => run.value?.state === 'PAUSED' && run.value.requests >= run.value.allowance)
    const minLimit = computed(() => run.value?.state === 'PAUSED'
        ? Math.max(run.value.allowance, run.value.requests + (exhausted.value ? 1 : 0)) : 1)
    const validLimit = computed(() => limit.value === '' ? !exhausted.value
        : Number.isInteger(limit.value) && limit.value >= minLimit.value && limit.value <= 10000)
    let navigation = 0, revision = 0, disposed = false
    let timer: ReturnType<typeof setTimeout>
    function tell(e: unknown) { error.value = e instanceof Error ? e.message : String(e) }
    function remember() { localStorage.setItem(selectionKey, JSON.stringify([projectId.value, conversationId.value])) }

    async function refresh() {
        const id = conversationId.value, sequence = ++revision
        if (!id) return
        try {
            const next = await request<Feed>('/conversations/' + id)
            if (disposed || sequence !== revision || id !== conversationId.value) return
            feed.value = next
            loading.value = false
        } catch (e) {
            if (!disposed && sequence === revision && id === conversationId.value) {
                loading.value = false
                tell(e)
            }
        }
    }
    async function selectConversation(id: string) {
        conversationId.value = id
        input.value = id ? sessionStorage.getItem(key('draft', id)) ?? '' : ''
        feed.value = blankFeed()
        notice.value = ''; error.value = ''; limit.value = ''; loading.value = !!id
        if (id) sessionStorage.setItem(key('last', projectId.value), id)
        remember()
        await refresh()
    }
    async function selectProject(id: string, preferred = '') {
        const turn = ++navigation
        projectId.value = id; conversationId.value = ''; conversations.value = []
        input.value = ''; feed.value = blankFeed(); loading.value = !!id
        notice.value = ''; error.value = ''; limit.value = ''
        if (!id) { remember(); return }
        try {
            const list = await request<Conversation[]>('/projects/' + id + '/conversations')
            if (turn !== navigation || disposed) return
            conversations.value = list
            const last = preferred || sessionStorage.getItem(key('last', id))
            await selectConversation(list.find(c => c.id === last)?.id || list[0]?.id || '')
        } catch (e) { if (turn === navigation && !disposed) { loading.value = false; tell(e) } }
    }
    async function load() {
        let selected: string[] = []
        try { selected = JSON.parse(localStorage.getItem(selectionKey) ?? '[]') || [] } catch { /* 使用默认项目。 */ }
        try {
            projects.value = await request<Project[]>('/projects')
            const ready = projects.value.filter(p => p.state === 'READY')
            await selectProject(ready.find(p => p.id === selected[0])?.id || ready[0]?.id || '', selected[1])
        } catch (e) { loading.value = false; tell(e) }
    }
    async function poll() {
        await refresh()
        if (!disposed) timer = setTimeout(poll, active.value || run.value?.state === 'QUEUED' || elsewhere.value ? 800 : 2500)
    }
    onMounted(async () => {
        await Promise.all([load(), request<RuntimeSettings>('/settings').then(s => { settings.value = s }).catch(tell)])
        if (!disposed) timer = setTimeout(poll, 800)
    })
    onUnmounted(() => { disposed = true; clearTimeout(timer) })

    async function action(f: () => Promise<void>, scope = conversationId.value): Promise<boolean> {
        if (pending.value) return false
        pending.value = true; error.value = ''; notice.value = ''
        try { await f(); return true }
        catch (e) { if (scope === conversationId.value) tell(e); return false }
        finally { pending.value = false }
    }
    function createProject(name: string, source: string) {
        return action(async () => {
            const p = await request<{id: string}>('/projects', { name: name.trim(), source: source.trim() || null })
            projects.value = await request<Project[]>('/projects')
            let conversation = ''
            try {
                const created = await request<{id: string}>('/projects/' + p.id + '/conversations', { title: '项目对话' })
                conversation = created.id
            } catch (e) {
                await selectProject(p.id)
                tell(e)
                return
            }
            await selectProject(p.id, conversation)
        })
    }
    function createConversation(title: string) {
        const project = projectId.value
        return action(async () => {
            const c = await request<{id: string}>('/projects/' + project + '/conversations', { title: title.trim() })
            await selectProject(project, c.id)
        })
    }
    async function send() {
        const id = conversationId.value, text = draft.value.trim()
        if (!id || !text || loading.value || (!active.value && run.value?.state !== 'PAUSED' && !validLimit.value)) return false
        return action(async () => {
            const out = key('outgoing', id)
            let previous: Delivery | null = null
            try { previous = JSON.parse(sessionStorage.getItem(out) ?? 'null') } catch { /* 重新生成请求标识。 */ }
            const requestLimit = !active.value && run.value?.state !== 'PAUSED' && typeof limit.value === 'number'
                ? limit.value : undefined
            const delivery = previous?.text === text ? previous : { id: crypto.randomUUID(), text, requestLimit }
            sessionStorage.setItem(out, JSON.stringify(delivery))
            const ack = await request<Ack>('/conversations/' + id + '/messages', {
                requestId: delivery.id, text, requestLimit: delivery.requestLimit,
            })
            sessionStorage.removeItem(out)
            const draftKey = key('draft', id)
            if (sessionStorage.getItem(draftKey)?.trim() === text) sessionStorage.removeItem(draftKey)
            if (id !== conversationId.value) return
            if (draft.value.trim() === text) draft.value = ''
            if (delivery.requestLimit !== undefined) limit.value = ''
            notice.value = ack.runId ? '消息已保存' : '消息已保存，等待此对话继续'
            await refresh()
        }, id)
    }
    function control(op: 'continue' | 'stop' | 'reconcile' | 'cancel-queue') {
        const id = conversationId.value, current = run.value
        if (!id || (op !== 'continue' && !current) || (op === 'continue' && !validLimit.value)) return
        return action(async () => {
            const path = op === 'continue' ? '/conversations/' + id + '/continue' : '/runs/' + current!.id + '/' + op
            await request(path, op === 'continue' && typeof limit.value === 'number' ? { requestLimit: limit.value } : {})
            if (id === conversationId.value) { limit.value = ''; await refresh() }
        })
    }
    return { projects, conversations, projectId, conversationId, project, conversation, feed, run, settings,
        active, elsewhere, loading, pending, error, notice, draft, limit, exhausted, minLimit, validLimit,
        selectProject, selectConversation, createProject, createConversation, send, control, action }
}
