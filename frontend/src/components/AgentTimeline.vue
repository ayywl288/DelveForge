<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import type { AgentEvent, AgentRun } from "../api/agentTypes";
import NavigationIcon from "./NavigationIcon.vue";
import AgentActivityRow from "./AgentActivityRow.vue";
import { activityState, buildActivities, groupExploration } from "./agentEventPresentation";

const props = defineProps<{ events: AgentEvent[]; run: AgentRun; connection: string }>();
const viewport = ref<HTMLElement>();
const following = ref(true);
const unseen = ref(false);
const visibleCount = ref(40);
const activities = computed(() => buildActivities(props.events, props.run));
const visible = computed(() => activities.value.slice(-visibleCount.value));
const rows = computed(() => groupExploration(visible.value));
const state = computed(() => activityState(props.events, props.run));
const expanded = ref(new Set<string>());
let previousCount = activities.value.length;
function toggle(key: string) {
    if (expanded.value.has(key)) expanded.value.delete(key);
    else expanded.value.add(key);
    void nextTick(trackScroll);
}
function removeImmediately(_element: Element, done: () => void) { done(); }
function trackScroll() {
    const element = viewport.value;
    if (!element) return;
    following.value = element.scrollHeight - element.clientHeight - element.scrollTop < 40;
    if (following.value) unseen.value = false;
}
function latest() {
    visibleCount.value = 40;
    expanded.value.clear();
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
        visibleCount.value += Math.max(0, activities.value.length - previousCount);
    }
    previousCount = activities.value.length;
    await nextTick();
    if (follow && following.value && viewport.value) viewport.value.scrollTop = viewport.value.scrollHeight;
});
watch(() => props.run.id, () => { following.value = true; unseen.value = false; visibleCount.value = 40; previousCount = 0; expanded.value.clear(); });
</script>

<template>
    <section class="activity-section" aria-labelledby="agent-activity-title">
        <div class="section-row"><h3 id="agent-activity-title">实际活动时间线</h3>
            <p class="stream-status" role="status">事件连接：{{ connection }}</p></div>
        <p v-if="state.snapshotBehind" class="snapshot-note" role="status">已收到较新的持久化活动，运行概览待同步。</p>
        <p v-if="!events.length" class="meta">等待持久化事件。</p>
        <button v-if="activities.length > visible.length" class="text-button earlier-activity" @click="earlier">查看更早活动（已加载 {{ activities.length }} 项）</button>
        <div ref="viewport" class="timeline-viewport" tabindex="0" aria-label="运行活动，可滚动查看历史" @scroll="trackScroll">
            <TransitionGroup name="activity-entry" tag="ol" class="agent-timeline" :data-terminal="state.terminal" @leave="removeImmediately">
                <template v-for="row in rows" :key="row.key">
                    <li v-if="'activities' in row" class="exploration-group">
                        <details :open="expanded.has(row.key)">
                            <summary @click.prevent="toggle(row.key)"><NavigationIcon name="repository" />探索仓库文件
                                <span class="exploration-count">{{ row.count }} 次文件操作 · 已记录完成</span>
                                <span class="exploration-previews">{{ row.previews.join(' · ') || '目标未记录或已隐藏' }}</span>
                            </summary>
                            <ol v-if="expanded.has(row.key)" aria-label="仓库探索原始活动，按时间排序">
                                <AgentActivityRow v-for="activity in row.activities" :key="activity.key" :activity="activity" />
                            </ol>
                        </details>
                    </li>
                    <AgentActivityRow v-else :activity="row" />
                </template>
            </TransitionGroup>
        </div>
        <button v-if="!following" class="secondary compact-button latest-activity" @click="latest">{{ unseen ? '有新活动 · 回到最新' : '回到最新活动' }}</button>
        <p v-if="activities.length > 40" class="meta">显示 {{ visible.length }} / {{ activities.length }} 项已加载活动。</p>
        <p v-if="events.length === 1000" class="meta">已加载最近 1000 条事件；更早事件受现有保留范围限制。</p>
    </section>
</template>

<style scoped>
.activity-section { min-width: 0; line-height: 1.6; }
.activity-section h3 { font-size: 17px; line-height: 1.5; margin: 0; }
.activity-section > .section-row { margin-bottom: 12px; }
.stream-status { font-size: 13px; color: var(--muted); margin: 0; }
.timeline-viewport { max-height: 34rem; overflow: auto; scrollbar-width: thin; overflow-anchor: none; padding: 6px 4px; }
.agent-timeline { list-style: none; margin: 0; padding: 0; }
.exploration-group { margin: 0 0 14px; min-width: 0; }
.exploration-group summary { cursor: pointer; font-size: 14px; font-weight: 600; line-height: 1.6; overflow-wrap: anywhere; padding: 5px 0; }
.exploration-group summary .navigation-icon { width: 15px; height: 15px; vertical-align: middle; margin-right: 8px; color: var(--muted); }
.exploration-count, .exploration-previews { display: block; margin: 3px 0 0 28px; font-size: 12px; color: var(--muted); font-weight: 400; }
.exploration-group ol { list-style: none; padding: 12px 0 0 12px; margin: 0 0 0 13px; border-left: 1px solid var(--line); }
.snapshot-note { font-size: 12px; color: var(--muted); margin: 0 0 12px; }
.latest-activity { margin: 8px 0; }
.earlier-activity { margin: 0 0 8px; }
.activity-entry-enter-active { transition: opacity 220ms ease-out, transform 220ms cubic-bezier(.22, 1, .36, 1); }
.activity-entry-enter-from { opacity: 0; transform: translateY(5px); }
.agent-timeline[data-terminal="true"] > .activity-entry-enter-active { transition: none; }
@media (prefers-reduced-motion: reduce) { .activity-entry-enter-active { transition: none; } .activity-entry-enter-from { opacity: 1; transform: none; } }
</style>
