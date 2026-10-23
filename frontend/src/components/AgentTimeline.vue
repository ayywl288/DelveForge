<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import type { AgentEvent, AgentRun } from "../api/agentTypes";
import NavigationIcon, { type NavigationIconName } from "./NavigationIcon.vue";
import AgentEventDetails from "./AgentEventDetails.vue";
import { buildActivities, toolNames, type AgentActivity } from "./agentEventPresentation";

const props = defineProps<{ events: AgentEvent[]; run: AgentRun; connection: string }>();
const viewport = ref<HTMLElement>();
const following = ref(true);
const unseen = ref(false);
const visibleCount = ref(40);
const activities = computed(() => buildActivities(props.events, props.run));
const visible = computed(() => activities.value.slice(-visibleCount.value));
let previousCount = activities.value.length;
const names: Record<string, string> = {
    CREATED: "运行已预约", PREPARING: "准备 Docker 沙箱", SANDBOX_READY: "隔离沙箱已就绪",
    RUNNING: "开始编码", MODEL_REQUEST: "请求模型", MODEL_COMPLETED: "模型已结束，等待冻结结果",
    COMPLETION_FORMAT_RETRY: "请求模型明确完成状态", TOOL_STARTED: "工具操作已开始", TOOL_FINISHED: "工具操作已返回",
    FINALIZING: "冻结 Candidate", SUCCEEDED: "Candidate 已冻结", FAILED: "运行失败",
    CANCELLED: "运行已取消", INTERRUPTED: "运行已中断", MATERIAL_RETAINED: "最终化材料已保留",
    CLEANUP_BLOCKED: "资源清理受阻，需核对现场", RECONCILIATION_BLOCKED: "资源协调受阻",
    DELIVERY_INTENT: "正式交付已预约", DELIVERY_APPLIED: "正式文件已核对",
    DELIVERY_COMMIT_READY: "正式提交已准备", DELIVERY_PUBLISHED: "Git 已发布，等待接受",
    DELIVERY_COMPLETED: "正式交付完成", DELIVERY_BLOCKED: "正式交付阻塞",
    DELIVERY_RECOVERY_REQUIRED: "正式副本需要核对恢复", DELIVERY_PENDING: "交付等待协调",
    DELIVERY_DISCOVERY_BLOCKED: "冻结结果的交付发现受阻",
};
const outcomes = { running: "进行中", completed: "完成", failed: "失败", conflict: "冲突", unknown: "结果未记录或待核对",
    recorded: "已记录", blocked: "需要核对", interrupted: "已中断或取消" };
function title(activity: AgentActivity) {
    return activity.tool ? toolNames[activity.tool] : names[activity.event.kind] ?? "历史运行活动";
}
function icon(activity: AgentActivity): NavigationIconName {
    if (["blocked", "conflict"].includes(activity.outcome)) return "lock";
    if (activity.outcome === "failed") return "warning";
    if (activity.outcome === "interrupted") return "close";
    if (activity.outcome === "completed") return "check";
    return activity.tool ? "code" : "activity";
}
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
        visibleCount.value += Math.max(0, activities.value.length - previousCount);
    }
    previousCount = activities.value.length;
    await nextTick();
    if (follow && following.value && viewport.value) viewport.value.scrollTop = viewport.value.scrollHeight;
});
watch(() => props.run.id, () => { following.value = true; unseen.value = false; visibleCount.value = 40; previousCount = 0; });
</script>

<template>
    <section class="activity-section" aria-labelledby="agent-activity-title">
        <div class="section-row"><h3 id="agent-activity-title">实际活动时间线</h3>
            <p class="stream-status" role="status">事件连接：{{ connection }}</p></div>
        <p v-if="!events.length" class="meta">等待持久化事件。</p>
        <button v-if="activities.length > visible.length" class="text-button earlier-activity" @click="earlier">查看更早活动（已加载 {{ activities.length }} 项）</button>
        <div ref="viewport" class="timeline-viewport" tabindex="0" aria-label="运行活动，可滚动查看历史" @scroll="trackScroll">
            <ol class="agent-timeline">
                <li v-for="activity in visible" :key="activity.key" :data-sequence="activity.event.sequence"
                    :data-tone="activity.outcome" :class="{ 'is-active': activity.active }">
                    <span class="timeline-node" aria-hidden="true"><NavigationIcon :name="icon(activity)" /></span>
                    <div class="timeline-body"><div class="timeline-heading"><strong>{{ title(activity) }}</strong>
                        <span v-if="activity.target" class="activity-target">{{ activity.target }}</span>
                        <time :datetime="activity.event.at" :title="new Date(activity.event.at).toLocaleString()">{{ new Date(activity.event.at).toLocaleTimeString() }}</time></div>
                        <div class="activity-result"><span :role="activity.active ? 'status' : undefined">{{ outcomes[activity.outcome] }}</span>
                            <span v-if="activity.duration !== undefined" class="activity-duration">{{ activity.duration.toLocaleString('zh-CN') }} ms</span>
                            <code v-if="activity.classification">{{ activity.classification }}</code></div>
                        <AgentEventDetails :activity="activity" />
                        <p v-if="activity.event.kind === 'CLEANUP_BLOCKED'" class="timeline-warning">需核对所属资源与保留材料，完成资源协调后再判断能否重试。</p>
                    </div>
                </li>
            </ol>
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
.agent-timeline li { position: relative; display: flex; gap: 12px; padding: 0 0 16px; margin: 0; min-width: 0; }
.agent-timeline li:not(:last-child)::before { content: ""; position: absolute; left: 13px; top: 28px; bottom: 0; width: 1px; background: var(--line); }
.timeline-node { flex: 0 0 28px; height: 28px; border-radius: 50%; display: grid; place-items: center; background: var(--canvas); color: var(--muted); }
.timeline-node .navigation-icon { width: 15px; height: 15px; }
.timeline-body { min-width: 0; flex: 1; padding-top: 3px; overflow-wrap: anywhere; }
.timeline-heading, .activity-result { display: flex; align-items: baseline; flex-wrap: wrap; gap: 4px 12px; }
.timeline-heading strong { font-size: 14px; font-weight: 600; }
time, .activity-target, .activity-result { font-size: 12px; color: var(--muted); }
.activity-target { color: var(--ink); overflow-wrap: anywhere; }
.activity-result { margin-top: 3px; font-variant-numeric: tabular-nums; }
.activity-result code { font-size: 12px; color: inherit; }
[data-tone="failed"] .timeline-node { color: var(--danger); background: var(--danger-bg); }
[data-tone="blocked"] .timeline-node, [data-tone="conflict"] .timeline-node { color: var(--warning); background: var(--warning-bg); }
[data-tone="failed"] .activity-result { color: var(--danger); }
[data-tone="blocked"] .activity-result, [data-tone="conflict"] .activity-result { color: var(--warning); }
[data-tone="completed"] .timeline-node { color: var(--success); }
[data-tone="interrupted"] .timeline-node { border: 1px dashed var(--muted); }
.is-active .timeline-node { color: var(--accent); background: var(--selection); animation: activity-pulse 1.8s ease-in-out infinite; }
.timeline-warning { font-size: 14px; color: var(--warning); margin: 6px 0; }
.latest-activity { margin: 8px 0; }
.earlier-activity { margin: 0 0 8px; }
@keyframes activity-pulse { 50% { box-shadow: 0 0 0 4px var(--selection); } }
@media (prefers-reduced-motion: reduce) { .is-active .timeline-node { animation: none; } }
</style>
