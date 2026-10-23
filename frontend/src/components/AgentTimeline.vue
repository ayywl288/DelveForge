<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import type { AgentEvent, AgentRun } from "../api/agentTypes";
import NavigationIcon from "./NavigationIcon.vue";
import AgentActivityRow from "./AgentActivityRow.vue";
import { activityState, buildActivities, currentActivity, explorationSummary, isExploration, toolNames } from "./agentEventPresentation";

const props = defineProps<{ events: AgentEvent[]; run: AgentRun; connection: string }>();
const viewport = ref<HTMLElement>();
const following = ref(true);
const unseen = ref(false);
const visibleCount = ref(40);
const activities = computed(() => buildActivities(props.events, props.run));
const exploration = computed(() => explorationSummary(activities.value, props.run.id));
const current = computed(() => currentActivity(activities.value, props.events, props.run));
const chronological = computed(() => activities.value.filter(activity => !isExploration(activity) && activity.event.kind !== "MODEL_REQUEST"));
const visible = computed(() => chronological.value.slice(-visibleCount.value));
const fileCount = ref(40);
const filesExpanded = ref(false);
const visibleFiles = computed(() => exploration.value.activities.slice(0, fileCount.value));
const latestFile = computed(() => exploration.value.activities.at(-1));
const state = computed(() => activityState(props.events, props.run));
let previousCount = chronological.value.length;
function removeImmediately(_element: Element, done: () => void) { done(); }
function trackScroll() {
    const element = viewport.value;
    if (!element) return;
    following.value = element.scrollHeight - element.clientHeight - element.scrollTop < 40;
    if (following.value) unseen.value = false;
}
function latest() {
    visibleCount.value = 40;
    following.value = true;
    unseen.value = false;
    void nextTick(() => { if (viewport.value) viewport.value.scrollTop = viewport.value.scrollHeight; });
}
async function earlier() {
    visibleCount.value += 40;
    following.value = false;
    await nextTick();
    if (viewport.value) viewport.value.scrollTop = 0;
}
watch(() => props.events.at(-1)?.sequence, async () => {
    const follow = following.value;
    if (!follow) {
        unseen.value = true;
        visibleCount.value += Math.max(0, chronological.value.length - previousCount);
    }
    previousCount = chronological.value.length;
    await nextTick();
    if (follow && following.value && viewport.value) viewport.value.scrollTop = viewport.value.scrollHeight;
});
watch(() => props.run.id, () => { following.value = true; unseen.value = false; visibleCount.value = 40; previousCount = 0; filesExpanded.value = false; fileCount.value = 40; });
</script>

<template>
    <section class="activity-section" aria-labelledby="agent-activity-title">
        <div class="section-row"><h3 id="agent-activity-title">执行活动</h3>
            <p class="stream-status" role="status">事件连接：{{ connection }}</p></div>
        <p v-if="state.snapshotBehind" class="snapshot-note" role="status">已收到较新的持久化活动，运行概览待同步。</p>
        <p v-if="!events.length" class="meta">等待持久化事件。</p>
        <div class="current-activity" :data-active="current.active" :data-tone="current.tone" role="status" aria-live="polite" aria-atomic="true">
            <NavigationIcon :name="current.tone === 'failed' ? 'warning' : current.tone === 'blocked' ? 'lock' : current.tone === 'completed' ? 'check' : 'activity'" />
            <div><p class="current-label">当前活动</p><p class="current-title">{{ current.title }}
                <span v-if="current.waiting" class="model-waiting" aria-hidden="true"><i></i><i></i><i></i></span></p>
                <p v-if="current.detail" class="current-detail">{{ current.detail }}</p></div>
        </div>
        <p class="model-statistics">模型调用 {{ run.modelCalls ?? '待同步' }}{{ run.modelCalls === undefined ? '' : ' 次' }}</p>
        <h4 class="operations-title">重要操作</h4>
        <button v-if="chronological.length > visible.length" class="text-button earlier-activity" @click="earlier">查看更早活动（已加载 {{ chronological.length }} 项）</button>
        <div ref="viewport" class="timeline-viewport" tabindex="0" aria-label="运行活动，可滚动查看历史" @scroll="trackScroll">
            <TransitionGroup name="activity-entry" tag="ol" class="agent-timeline" :data-terminal="state.terminal" @leave="removeImmediately">
                <AgentActivityRow v-for="row in visible" :key="row.key" :activity="row" />
            </TransitionGroup>
            <p v-if="!visible.length" class="meta">暂无已记录的重要操作。</p>
        </div>
        <button v-if="!following" class="secondary compact-button latest-activity" @click="latest">{{ unseen ? '有新活动 · 回到最新' : '回到最新活动' }}</button>
        <p v-if="chronological.length > 40" class="meta">显示 {{ visible.length }} / {{ chronological.length }} 项已加载时间线活动。</p>
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
        <p v-if="events.length === 1000" class="meta">已加载最近 1000 条事件；更早事件受现有保留范围限制。</p>
    </section>
</template>

<style scoped>
.activity-section { min-width: 0; line-height: 1.6; }
.activity-section h3 { font-size: 17px; line-height: 1.5; margin: 0; }
.activity-section > .section-row { margin-bottom: 12px; }
.stream-status { font-size: 13px; color: var(--muted); margin: 0; }
.current-activity { display: flex; align-items: flex-start; gap: 12px; padding: 14px 0; border-block: 1px solid var(--line); }
.current-activity > .navigation-icon { width: 20px; height: 20px; color: var(--accent); flex-shrink: 0; margin-top: 3px; }
.current-activity > div { min-width: 0; }
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
.timeline-viewport { max-height: 34rem; overflow: auto; scrollbar-width: thin; overflow-anchor: none; padding: 6px 4px; }
.agent-timeline { list-style: none; margin: 0; padding: 0; }
.exploration-summary { margin: 0 0 18px; padding: 10px 0 14px; min-width: 0; border-bottom: 1px solid var(--line); }
.exploration-summary summary { cursor: pointer; font-size: 14px; font-weight: 600; line-height: 1.6; overflow-wrap: anywhere; padding: 5px 0; }
.exploration-summary summary .navigation-icon { width: 15px; height: 15px; vertical-align: middle; margin-right: 8px; color: var(--muted); }
.exploration-count, .exploration-previews { display: block; margin: 3px 0 0 28px; font-size: 12px; color: var(--muted); font-weight: 400; }
.exploration-operations { list-style: none; padding: 12px 8px 0 0; margin: 10px 0; max-height: 24rem; overflow: auto; overflow-anchor: none; scrollbar-width: thin; }
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
</style>
