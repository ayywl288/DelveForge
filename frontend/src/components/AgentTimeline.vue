<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import type { AgentEvent, AgentRun } from "../api/agentTypes";
import NavigationIcon from "./NavigationIcon.vue";
import AgentActivityRow from "./AgentActivityRow.vue";
import AgentRunElapsed from "./AgentRunElapsed.vue";
import { networkCapability, routineProviderDiagnostic } from "./agentEventPresentation";
import { runStates, type RunPresentation } from "./agentResultPresentation";
import { activityState, buildActivities, currentActivity, checkSummary, explorationSummary, patchSummary, toolNames } from "./agentEventPresentation";

const props = withDefaults(defineProps<{ events: AgentEvent[]; run: AgentRun; connection: string; presentation?: RunPresentation; showUsage?: boolean }>(), { showUsage: true });
const developerExpanded = ref(false);
const network = computed(() => networkCapability(props.events, props.run.id));
const viewport = ref<HTMLElement>();
const following = ref(true);
const unseen = ref(false);
const visibleCount = ref(40);
const activities = computed(() => buildActivities(props.events, props.run));
const exploration = computed(() => explorationSummary(activities.value, props.run.id));
const patches = computed(() => patchSummary(activities.value, props.run.id));
const patchesExpanded = ref(false);
const patchCount = ref(40);
const visiblePatches = computed(() => patches.value.activities.slice(0, patchCount.value));
const current = computed(() => {
    const result = currentActivity(activities.value, props.events, props.run);
    if (activities.value.some(activity => activity.active && activity.event.kind === "MODEL_REQUEST")) {
        return { ...result, title: "Agent 正在规划下一步", detail: "" };
    }
    if (props.presentation && props.run.state === "SUCCEEDED" && result.title === "Candidate 已冻结") {
        return { ...result, title: props.presentation.label, detail: props.presentation.detail, tone: props.presentation.tone };
    }
    return result;
});
const checks = computed(() => checkSummary(activities.value, props.run.id));
const checksExpanded = ref(false);
const checkCount = ref(40);
const visibleChecks = computed(() => checks.value.activities.slice(0, checkCount.value));
const workingPlan = computed(() => {
    const latest = [...props.events].reverse().find(event => event.runId === props.run.id && event.kind === "WORKING_NOTES");
    if (!latest) return null;
    try {
        const value = JSON.parse(latest.summary);
        if (Array.isArray(value.tasks)) return { tasks: value.tasks.filter((task: { title?: unknown; status?: unknown }) =>
            typeof task.title === "string" && ["pending", "in_progress", "completed"].includes(String(task.status))) as { title: string; status: string }[], legacy: "" };
    } catch { /* 历史自由文本笔记只读展示。 */ }
    return { tasks: [], legacy: latest.summary };
});
const chronological = computed(() => activities.value.filter(activity => !activity.tool
    && activity.event.kind !== "MODEL_REQUEST" && activity.event.kind !== "WORKING_NOTES"
    && !routineProviderDiagnostic(activity)));
const visible = computed(() => chronological.value.slice(-visibleCount.value));
const fileCount = ref(40);
const filesExpanded = ref(false);
const visibleFiles = computed(() => exploration.value.activities.slice(0, fileCount.value));
const latestFile = computed(() => exploration.value.activities.at(-1));
const state = computed(() => activityState(props.events, props.run));
const checklistHeading = computed(() => state.value.terminal ? "最后记录的工作清单" : "当前工作清单");
let previousCount = chronological.value.length;
function removeImmediately(_element: Element, done: () => void) { done(); }
function trackScroll() {
    const element = viewport.value;
    if (!element) return;
    const bottom = element.getBoundingClientRect().bottom;
    following.value = bottom >= 0 && bottom <= window.innerHeight + 40;
    if (following.value) unseen.value = false;
}
function latest() {
    visibleCount.value = 40;
    following.value = true;
    unseen.value = false;
    void nextTick(() => viewport.value?.lastElementChild?.scrollIntoView?.({ block: "nearest" }));
}
async function earlier() {
    visibleCount.value += 40;
    following.value = false;
    await nextTick();
    viewport.value?.scrollIntoView?.({ block: "start" });
}
watch(() => props.events.at(-1)?.sequence, async () => {
    const hadActivities = previousCount > 0;
    const bottom = viewport.value?.getBoundingClientRect().bottom ?? 0;
    const follow = following.value && bottom >= 0 && bottom <= window.innerHeight + 40;
    following.value = follow;
    if (hadActivities && !follow) {
        unseen.value = true;
        visibleCount.value += Math.max(0, chronological.value.length - previousCount);
    }
    previousCount = chronological.value.length;
    await nextTick();
    if (hadActivities && follow && following.value) viewport.value?.lastElementChild?.scrollIntoView?.({ block: "nearest" });
});
onMounted(() => window.addEventListener("scroll", trackScroll, { passive: true }));
onUnmounted(() => window.removeEventListener("scroll", trackScroll));
watch(() => props.run.id, () => { following.value = true; unseen.value = false; visibleCount.value = 40; previousCount = 0; filesExpanded.value = false; fileCount.value = 40; developerExpanded.value = false; patchesExpanded.value = false; patchCount.value = 40; checksExpanded.value = false; checkCount.value = 40; });
</script>

<template>
    <section v-if="workingPlan" class="working-plan" :aria-label="checklistHeading">
            <div class="section-row"><h4>{{ checklistHeading }}</h4><span v-if="workingPlan.tasks.length">{{ workingPlan.tasks.filter(task => task.status === 'completed').length }} / {{ workingPlan.tasks.length }} 完成</span></div>
            <ul v-if="workingPlan.tasks.length"><li v-for="(task, index) in workingPlan.tasks" :key="index" :data-status="task.status"
                :class="{ 'active-task': task.status === 'in_progress' && !state.terminal }">
                <NavigationIcon :name="task.status === 'completed' ? 'check' : task.status === 'in_progress' && !state.terminal ? 'activity' : 'planning'" />
                <span>{{ task.title }}</span><small>{{ { pending: state.terminal ? '记录为待处理' : '待处理',
                    in_progress: state.terminal ? '运行结束前未更新' : '进行中', completed: '已完成' }[task.status] }}</small>
            </li></ul>
            <p v-else-if="workingPlan.legacy">{{ workingPlan.legacy }}</p>

        </section>
    <section class="activity-section" :data-terminal="state.terminal" aria-labelledby="agent-activity-title">
        <div class="section-row"><div class="activity-heading"><h3 id="agent-activity-title">执行活动</h3>
            <span class="run-state-badge" role="status" :data-state="run.state" :title="run.state">
                <NavigationIcon :name="runStates[run.state].icon" /><span>运行状态</span><strong>{{ runStates[run.state].label }}</strong>
            </span>
            <AgentRunElapsed :started-at="run.startedAt" :ended-at="run.endedAt" :terminal="state.terminal" /></div>
            <p class="stream-status" role="status">事件连接：{{ connection }}</p></div>
        <p v-if="state.snapshotBehind" class="snapshot-note" role="status">已收到较新的持久化活动，运行概览待同步。</p>
        <p v-if="!events.length" class="meta">等待持久化事件。</p>

        <div class="current-activity" :data-active="current.active" :data-tone="current.tone" role="status" aria-live="polite" aria-atomic="true">
            <NavigationIcon :name="current.tone === 'failed' ? 'warning' : current.tone === 'blocked' ? 'lock' : current.tone === 'completed' ? 'check' : 'activity'" />
            <div class="current-copy"><p v-if="!state.terminal" class="current-label">当前活动</p><Transition name="current-change" @leave="removeImmediately"><div :key="current.title + current.detail" class="current-content"><p class="current-title">{{ current.title }}
                <span v-if="current.waiting" class="model-waiting" aria-hidden="true"><i></i><i></i><i></i></span></p>
                <p v-if="current.detail" class="current-detail">{{ current.detail }}</p></div></Transition></div>
        </div>
        <div v-if="current.active" class="activity-bar" aria-hidden="true"></div>

        <h4 class="operations-title">重要操作</h4>
        <button v-if="chronological.length > visible.length" class="text-button earlier-activity" @click="earlier">查看更早活动（已加载 {{ chronological.length }} 项）</button>
        <div ref="viewport" class="timeline-viewport" tabindex="0" aria-label="运行活动，随页面查看历史">
            <TransitionGroup name="activity-entry" tag="ol" class="agent-timeline" :data-terminal="state.terminal" @leave="removeImmediately">
                <AgentActivityRow v-for="row in visible" :key="row.key" :activity="row" />
            </TransitionGroup>
            <p v-if="!visible.length" class="meta">暂无已记录的重要操作。</p>
        </div>
        <button v-if="!following" class="secondary compact-button latest-activity" @click="latest">{{ unseen ? '有新活动 · 回到最新' : '回到最新活动' }}</button>
        <p v-if="chronological.length > 40" class="meta">显示 {{ visible.length }} / {{ chronological.length }} 项已加载时间线活动。</p>
        <p v-if="checks.blocked" class="check-blocker" role="alert">{{ checks.sourceBlocked ? '检查期间检测到文件变化，本次检查已停止。' : '检查被安全规则停止，请核对开发者详情。' }}</p>
        <details class="developer-details" :open="developerExpanded">
            <summary @click.prevent="developerExpanded = !developerExpanded">开发者详情</summary>
            <div class="developer-content">
            <slot name="developer" />
            <p v-if="network" class="meta">{{ network }}</p>
            <p v-if="showUsage !== false" class="model-statistics">模型调用 {{ run.modelCalls ?? '待同步' }} 次</p>
        <details v-if="checks.count" :key="checks.key" class="check-summary" :open="checksExpanded">
            <summary @click.prevent="checksExpanded = !checksExpanded"><NavigationIcon name="check" />命令与检查 · 历史
                <strong class="check-latest" :data-tone="checks.latest?.outcome">{{ checks.label }}</strong>
                <span class="check-count">{{ checks.count }} 次尝试<span v-if="checks.failed"> · 历史命令尝试 {{ checks.failed }} 次未通过（不等同于当前失败测试）</span></span>
                <span v-if="checks.detail" class="check-detail">{{ checks.detail }}</span>
            </summary>
            <template v-if="checksExpanded"><p class="exploration-note">当前结果绑定最近已记录的源码版本；以下为全部命令尝试，最近记录在前。缺失的历史不会推断为通过。</p>
                <ol class="check-history" aria-label="本 Run 已加载命令历史，最近在前" tabindex="0">
                    <AgentActivityRow v-for="activity in visibleChecks" :key="activity.key" :activity="activity" />
                </ol><button v-if="checkCount < checks.count" class="secondary compact-button more-checks" @click="checkCount += 40">显示更多检查记录</button>
            </template>
        </details>

        <details :key="patches.key" class="patch-summary" :open="patchesExpanded">
            <summary @click.prevent="patchesExpanded = !patchesExpanded"><NavigationIcon name="code" />精确编辑 · 执行历史
                <span class="patch-count"><span :key="`${patches.count}:${patches.applied}:${patches.conflicts}:${patches.blocked}:${patches.uncertain}`" class="counter-values">已加载 {{ patches.count }} 次尝试 · 已应用 {{ patches.applied }} · 冲突 {{ patches.conflicts }} · 安全拒绝 {{ patches.blocked }} · 结果不明 {{ patches.uncertain }} · 失败 {{ patches.failed }}</span></span>
                <span v-if="patches.active" class="patch-current" role="status">正在应用代码修改</span>
            </summary>
            <p v-if="patchesExpanded" class="exploration-note">以下为编辑工具的逐次尝试；Shell 也可修改文件，最终变更以实际差异清单为准。冲突、安全拒绝和结果不明均保留原记录。</p>
            <template v-if="patchesExpanded"><ol class="patch-operations" aria-label="本 Run 补丁尝试，按时间排序" tabindex="0">
                <AgentActivityRow v-for="activity in visiblePatches" :key="activity.key" :activity="activity" />
            </ol><button v-if="patchCount < patches.count" class="secondary compact-button more-patches" @click="patchCount += 40">显示更多补丁尝试</button></template>
        </details>
        <details :key="exploration.key" class="exploration-summary" :open="filesExpanded">
            <summary @click.prevent="filesExpanded = !filesExpanded"><NavigationIcon name="repository" />探索仓库文件 · 汇总
                <span class="exploration-count">已加载 {{ exploration.count }} 次文件操作 · 读取 {{ exploration.counts.read_file }} · 列目录 {{ exploration.counts.list_files }} · 搜索 {{ exploration.counts.search_code }}</span>
                <span class="exploration-previews">{{ exploration.previews.join(' · ') || '尚无可展示的目标' }}</span>
                <span v-if="latestFile?.active" class="exploration-current" role="status">{{ toolNames[latestFile.tool!] }}进行中{{ latestFile.target ? ' · ' + latestFile.target : latestFile.targetWithheld ? ' · 目标已隐藏' : ' · 目标未记录' }}</span>
                <span v-if="exploration.failed || exploration.uncertain" class="exploration-attention" role="status">{{ exploration.failed }} 次失败 · {{ exploration.uncertain }} 次结果待核对</span>
            </summary>
            <p class="exploration-note">当前 Run 的文件探索汇总；各次操作按原顺序列出，不代表一次执行。模型请求计入实际调用统计。</p>
            <template v-if="filesExpanded">
                <ol class="exploration-operations" aria-label="本 Run 文件探索操作，按时间排序" tabindex="0">
                    <AgentActivityRow v-for="activity in visibleFiles" :key="activity.key" :activity="activity" />
                </ol>
                <p class="meta">显示 {{ visibleFiles.length }} / {{ exploration.count }} 次已加载文件操作。</p>
                <button v-if="fileCount < exploration.count" class="secondary compact-button more-exploration" @click="fileCount += 40">显示更多文件操作</button>
            </template>
        </details>
            </div>
        </details>
    </section>
</template>

<style scoped>
.working-plan { padding: 20px; margin-bottom: 24px; background: var(--canvas); border: 1px solid var(--line); border-radius: 12px; }
.developer-details { border-top: 1px solid var(--line); margin-top: 20px; padding-top: 16px; }
.developer-details > summary { cursor: pointer; font-weight: 600; font-size: 14px; }
.working-plan h4 { margin: 0; font-size: 14px; }
.working-plan ul { list-style: none; padding: 0; }
.working-plan li { display: flex; align-items: center; gap: 10px; padding: 8px 0; font-size: 14px; }
.working-plan li .navigation-icon { width: 16px; height: 16px; flex-shrink: 0; }
.working-plan li span { flex: 1; overflow-wrap: anywhere; }
.working-plan li small { color: var(--muted); flex-shrink: 0; }
.working-plan li.active-task { color: var(--accent); font-weight: 600; }
.working-plan li[data-status="pending"] { color: var(--muted); }
.working-plan li[data-status="completed"] .navigation-icon { color: var(--success); }
.working-plan > p { white-space: pre-wrap; }

.check-summary { min-width: 0; margin: 10px 0; padding: 12px 0 16px; border-bottom: 1px solid var(--line); }
.check-summary summary { cursor: pointer; font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.check-summary summary > .navigation-icon { width: 15px; height: 15px; vertical-align: middle; margin-right: 8px; color: var(--muted); }
.check-latest, .check-count, .check-detail { display: block; margin: 4px 0 0 28px; font-size: 12px; line-height: 1.6; }
.check-count, .check-detail { color: var(--muted); font-weight: 400; }
.check-latest[data-tone="completed"] { color: var(--success); }
.check-latest[data-tone="failed"] { color: var(--danger); }
.check-latest[data-tone="blocked"], .check-blocker { color: var(--warning); }
.check-blocker { margin: 10px 0 0 28px; font-size: 13px; line-height: 1.6; }
.check-history { list-style: none; margin: 14px 0 10px; padding: 0;  scrollbar-width: thin; }

.activity-section { min-width: 0; line-height: 1.6; }
.activity-section h3 { font-size: 17px; line-height: 1.5; margin: 0; }
.activity-section > .section-row { margin-bottom: 12px; }
.activity-heading { display: flex; align-items: center; flex-wrap: wrap; gap: 4px 12px; }
.run-state-badge { display: inline-flex; align-items: center; gap: 6px; padding: 3px 9px; border-radius: 5px;
    color: var(--accent); background: color-mix(in srgb, currentColor 12%, transparent); font-size: 13px; white-space: nowrap; }
.run-state-badge .navigation-icon { width: 15px; height: 15px; }
.run-state-badge[data-state="SUCCEEDED"] { color: var(--success); }
.run-state-badge[data-state="FAILED"] { color: var(--danger); }
.run-state-badge[data-state="INTERRUPTED"] { color: var(--warning); }
.run-state-badge[data-state="CANCELLED"] { color: var(--muted); }
.stream-status { font-size: 13px; color: var(--muted); margin: 0; }
.current-activity { display: flex; align-items: flex-start; gap: 12px; padding: 14px 0; border-block: 1px solid var(--line); }
.current-activity > .navigation-icon { width: 20px; height: 20px; color: var(--accent); flex-shrink: 0; margin-top: 3px; }
.current-activity > div { min-width: 0; }
.current-copy { display: grid; }
.current-copy > .current-label { grid-row: 1; }
.current-content { grid-area: 2 / 1; }
.current-change-enter-active, .current-change-leave-active { transition: opacity 180ms ease, transform 180ms ease; }
.current-change-enter-from { opacity: 0; transform: translateY(3px); }
.current-change-leave-to { opacity: 0; }
.activity-bar { height: 3px; position: relative; overflow: hidden; background: var(--selection); }
.activity-bar::after { content: ''; position: absolute; inset: 0 auto 0 0; width: 30%; background: var(--accent); animation: monitor-activity 1.6s ease-in-out infinite; }
@keyframes monitor-activity { from { transform: translateX(-100%); } to { transform: translateX(340%); } }
.patch-summary { border-bottom: 1px solid var(--line); padding: 10px 0 16px; }
.patch-summary summary { cursor: pointer; font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.patch-summary summary .navigation-icon { width: 15px; height: 15px; vertical-align: middle; margin-right: 8px; color: var(--muted); }
.patch-count, .patch-current { display: block; font-size: 12px; font-weight: 400; color: var(--muted); margin: 4px 0 0 28px; overflow-wrap: anywhere; }
.patch-current { color: var(--accent); }
.patch-count { display: grid; }
.counter-values { grid-area: 1/1; animation: counter-update 220ms ease-out; }
@keyframes counter-update { from { color: var(--accent); } }
.patch-operations { list-style: none; padding: 12px 8px 0 0;  overflow-anchor: none; scrollbar-width: thin; }
.activity-section[data-terminal="true"] .current-activity { padding: 10px 12px; }
.activity-section[data-terminal="true"] .counter-values { animation: none; }
.activity-section[data-terminal="true"] .current-change-enter-active, .activity-section[data-terminal="true"] .current-change-leave-active { transition: none; }
.current-label, .current-detail { font-size: 13px; color: var(--muted); margin: 0; overflow-wrap: anywhere; }
.current-title { font-size: 16px; font-weight: 600; line-height: 1.6; margin: 2px 0; overflow-wrap: anywhere; }
.current-activity[data-tone="failed"] > .navigation-icon { color: var(--danger); }
.current-activity[data-tone="blocked"] > .navigation-icon { color: var(--warning); }
.current-activity[data-tone="completed"] > .navigation-icon { color: var(--success); }
.model-statistics { font-size: 13px; color: var(--muted); margin: 8px 0 16px; font-variant-numeric: tabular-nums; }
.operations-title { font-size: 14px; margin: 0 0 10px; }
.model-waiting { display: inline-flex; gap: 4px; vertical-align: middle; margin-left: 8px; color: var(--accent); }
.model-waiting i { width: 4px; height: 4px; border-radius: 50%; background: currentColor; animation: model-wait 1.2s ease-in-out infinite; }
.model-waiting i:nth-child(2) { animation-delay: 150ms; }
.model-waiting i:nth-child(3) { animation-delay: 300ms; }
@keyframes model-wait { 50% { opacity: .35; transform: translateY(-2px); } }
.timeline-viewport { padding: 6px 4px; }
.agent-timeline { list-style: none; margin: 0; padding: 0; }
.exploration-summary { margin: 0 0 18px; padding: 10px 0 14px; min-width: 0; border-bottom: 1px solid var(--line); }
.exploration-summary summary { cursor: pointer; font-size: 14px; font-weight: 600; line-height: 1.6; overflow-wrap: anywhere; padding: 5px 0; }
.exploration-summary summary .navigation-icon { width: 15px; height: 15px; vertical-align: middle; margin-right: 8px; color: var(--muted); }
.exploration-count, .exploration-previews { display: block; margin: 3px 0 0 28px; font-size: 12px; color: var(--muted); font-weight: 400; }
.exploration-operations { list-style: none; padding: 12px 8px 0 0; margin: 10px 0;  overflow-anchor: none; scrollbar-width: thin; }
.exploration-note { font-size: 12px; line-height: 1.6; color: var(--muted); margin: 6px 0; }
.exploration-current, .exploration-attention { display: block; font-size: 13px; font-weight: 400; line-height: 1.6; margin: 6px 0; overflow-wrap: anywhere; }
.exploration-current { color: var(--accent); }
.exploration-attention { color: var(--warning); }
.snapshot-note { font-size: 12px; color: var(--muted); margin: 0 0 12px; }
.latest-activity { margin: 8px 0; }
.earlier-activity { margin: 0 0 8px; }
.activity-entry-enter-active { transition: opacity 220ms ease-out, transform 220ms cubic-bezier(.22, 1, .36, 1); }
.activity-entry-enter-from { opacity: 0; transform: translateY(5px); }
.agent-timeline[data-terminal="true"] > .activity-entry-enter-active { transition: none; }
@media (prefers-reduced-motion: reduce) { .model-waiting i { animation: none; } .activity-entry-enter-active { transition: none; } .activity-entry-enter-from { opacity: 1; transform: none; } }
@media (prefers-reduced-motion: reduce) { .activity-bar::after, .counter-values { animation: none; } .activity-bar::after { width: 100%; transform: none; opacity: .5; } .current-change-enter-active, .current-change-leave-active { transition: none; } .current-change-enter-from { transform: none; opacity: 1; } }
</style>
