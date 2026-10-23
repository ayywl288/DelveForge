<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import type { AgentEvent, AgentRun } from "../api/agentTypes";
import NavigationIcon, { type NavigationIconName } from "./NavigationIcon.vue";

const props = defineProps<{ events: AgentEvent[]; run: AgentRun; connection: string }>();
const viewport = ref<HTMLElement>();
const following = ref(true);
const unseen = ref(false);
const names: Record<string, string> = {
    CREATED: "运行已预约", PREPARING: "正在准备 Docker 沙箱", SANDBOX_READY: "隔离沙箱已就绪",
    RUNNING: "开始编码", MODEL_REQUEST: "正在请求模型", MODEL_COMPLETED: "模型已结束，等待冻结结果",
    COMPLETION_FORMAT_RETRY: "请求模型明确完成状态", TOOL_STARTED: "开始执行工具", TOOL_FINISHED: "工具执行结束",
    FINALIZING: "正在冻结 Candidate", SUCCEEDED: "Candidate 已冻结", FAILED: "运行失败",
    CANCELLED: "运行已取消", INTERRUPTED: "运行已中断", MATERIAL_RETAINED: "最终化材料已保留",
    CLEANUP_BLOCKED: "资源清理受阻，需核对现场", RECONCILIATION_BLOCKED: "资源协调受阻",
    DELIVERY_INTENT: "正式交付已预约", DELIVERY_APPLIED: "正式文件已核对",
    DELIVERY_COMMIT_READY: "正式提交已准备", DELIVERY_PUBLISHED: "Git 已发布，等待接受",
    DELIVERY_COMPLETED: "正式交付完成", DELIVERY_BLOCKED: "正式交付阻塞",
    DELIVERY_RECOVERY_REQUIRED: "正式副本需要核对恢复", DELIVERY_PENDING: "交付等待协调",
    DELIVERY_DISCOVERY_BLOCKED: "冻结结果的交付发现受阻",
};
const tools: Record<string, string> = {
    list_files: "浏览文件", read_file: "读取文件", search_code: "搜索代码", apply_patch: "应用代码修改",
    run_checks: "运行 Maven 检查",
};
function fields(event: AgentEvent): Record<string, unknown> {
    try {
        const value: unknown = JSON.parse(event.summary);
        if (value && typeof value === "object" && !Array.isArray(value)) return value as Record<string, unknown>;
    } catch { /* 非 JSON 事件保留原始诊断文本。 */ }
    return {};
}
function description(event: AgentEvent) {
    const data = fields(event);
    const tool = typeof data.tool === "string" ? data.tool : event.summary.match(/^tool=([^;]+)/)?.[1];
    const title = event.kind === "TOOL_FINISHED" && data.ok === false ? "工具执行失败" : names[event.kind] ?? "运行活动";
    return tool ? `${title} · ${tools[tool] ?? tool}` : title;
}
function detail(event: AgentEvent) {
    const data = fields(event);
    return Object.keys(data).length ? Object.entries(data)
        .map(([key, value]) => `${key}: ${typeof value === "object" || value === "" ? JSON.stringify(value) : value}`).join("\n")
        : event.summary;
}
function tone(event: AgentEvent) {
    if (event.kind.includes("BLOCKED") || event.kind.includes("RECOVERY_REQUIRED") || event.kind === "DELIVERY_PENDING") return "blocked";
    if (event.kind === "FAILED" || fields(event).ok === false) return "failed";
    if (["INTERRUPTED", "CANCELLED"].includes(event.kind)) return "interrupted";
    if (event.kind === "MATERIAL_RETAINED") return "retained";
    if (["TOOL_FINISHED", "MODEL_COMPLETED", "SANDBOX_READY", "SUCCEEDED", "DELIVERY_INTENT", "DELIVERY_APPLIED",
        "DELIVERY_COMMIT_READY", "DELIVERY_PUBLISHED", "DELIVERY_COMPLETED"].includes(event.kind)) return "completed";
    return "recorded";
}
// 仅末条持久化活动与当前 Run 状态一致时，才展示进行中动画；SSE 连接状态不代表运行状态。
const activeSequence = computed(() => {
    const last = props.events.at(-1);
    if (!last || props.run.cancelRequested) return null;
    const supported: Record<string, string[]> = {
        CREATED: ["CREATED"], PREPARING: ["PREPARING"], RUNNING: ["RUNNING", "MODEL_REQUEST", "TOOL_STARTED", "COMPLETION_FORMAT_RETRY"],
        FINALIZING: ["FINALIZING"],
    };
    return supported[props.run.state]?.includes(last.kind) ? last.sequence : null;
});
function icon(event: AgentEvent): NavigationIconName {
    const state = tone(event);
    if (state === "blocked" || state === "retained") return "lock";
    if (state === "failed") return "warning";
    if (state === "interrupted") return "close";
    if (state === "completed") return "check";
    return event.kind.startsWith("TOOL_") ? "code" : "activity";
}
function trackScroll() {
    const element = viewport.value;
    if (!element) return;
    following.value = element.scrollHeight - element.clientHeight - element.scrollTop < 40;
    if (following.value) unseen.value = false;
}
function latest() {
    const element = viewport.value;
    if (element) element.scrollTop = element.scrollHeight;
    following.value = true;
    unseen.value = false;
}
watch(() => props.events.at(-1)?.sequence, async () => {
    const follow = following.value;
    if (!follow) unseen.value = true;
    await nextTick();
    if (follow && following.value) latest();
});
watch(() => props.run.id, () => { following.value = true; unseen.value = false; });
</script>

<template>
    <section class="activity-section" aria-labelledby="agent-activity-title">
        <div class="section-row"><h3 id="agent-activity-title">实际活动时间线</h3>
            <p class="stream-status" role="status">事件连接：{{ connection }}</p></div>
        <p v-if="!events.length" class="meta">等待持久化事件。</p>
        <div ref="viewport" class="timeline-viewport" tabindex="0" aria-label="运行活动，可滚动查看历史" @scroll="trackScroll">
            <ol class="agent-timeline">
                <li v-for="event in events" :key="event.sequence" :data-sequence="event.sequence"
                    :data-tone="tone(event)" :class="{ 'is-active': activeSequence === event.sequence }">
                    <span class="timeline-node" aria-hidden="true"><NavigationIcon :name="icon(event)" /></span>
                    <div class="timeline-body"><div class="timeline-heading"><strong>{{ description(event) }}</strong>
                        <time :datetime="event.at" :title="new Date(event.at).toLocaleString()">{{ new Date(event.at).toLocaleTimeString() }}</time>
                        <span v-if="activeSequence === event.sequence" class="activity-current">进行中</span></div>
                        <p v-if="event.kind === 'CLEANUP_BLOCKED'" class="timeline-warning">需核对所属资源与保留材料，完成资源协调后再判断能否重试。</p>
                        <details><summary>技术详情 · {{ event.kind }}</summary><pre>{{ detail(event) }}</pre></details>
                    </div>
                </li>
            </ol>
        </div>
        <button v-if="!following" class="secondary compact-button latest-activity" @click="latest">{{ unseen ? '有新活动 · 回到最新' : '回到最新活动' }}</button>
        <p v-if="events.length === 1000" class="meta">显示最近 1000 条事件。</p>
    </section>
</template>

<style scoped>
.activity-section { min-width: 0; line-height: 1.6; }
.activity-section h3 { font-size: 17px; line-height: 1.5; margin: 0; }
.activity-section > .section-row { margin-bottom: 12px; }
.stream-status { font-size: 13px; color: var(--muted); margin: 0; }
.timeline-viewport { max-height: 34rem; overflow: auto; scrollbar-width: thin; overflow-anchor: none; padding: 6px 4px; }
.agent-timeline { list-style: none; margin: 0; padding: 0; }
.agent-timeline li { position: relative; display: flex; gap: 12px; padding: 0 0 12px; margin: 0; min-width: 0; }
.agent-timeline li:not(:last-child)::before { content: ""; position: absolute; left: 13px; top: 28px; bottom: 0; width: 1px; background: var(--line); }
.timeline-node { flex: 0 0 28px; height: 28px; border-radius: 50%; display: grid; place-items: center; background: var(--canvas); color: var(--muted); }
.timeline-node .navigation-icon { width: 15px; height: 15px; }
.timeline-body { min-width: 0; flex: 1; padding-top: 3px; overflow-wrap: anywhere; }
.timeline-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 4px 12px; }
.timeline-heading strong { font-size: 14px; font-weight: 600; line-height: 1.6; }
time, .activity-current { font-size: 12px; color: var(--muted); font-variant-numeric: tabular-nums; }
details { margin-top: 4px; }
summary { color: var(--muted); font-size: 12px; cursor: pointer; overflow-wrap: anywhere; padding: 4px 0; min-height: 28px; }
pre { width: fit-content; max-width: 100%; font-size: 13px; line-height: 1.55; max-height: 18rem; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; background: var(--canvas); padding: 8px 10px; border-radius: 6px; margin: 6px 0 0; }
[data-tone="failed"] .timeline-node { color: var(--danger); background: var(--danger-bg); }
[data-tone="blocked"] .timeline-node, [data-tone="retained"] .timeline-node { color: var(--warning); background: var(--warning-bg); }
[data-tone="completed"] .timeline-node { color: var(--success); }
[data-tone="interrupted"] .timeline-node { color: var(--muted); border: 1px dashed var(--muted); }
.is-active .timeline-node { color: var(--accent); background: var(--selection); animation: activity-pulse 1.8s ease-in-out infinite; }
.activity-current { color: var(--accent); }
.timeline-warning { font-size: 14px; line-height: 1.6; color: var(--warning); margin: 6px 0; }
.latest-activity { margin: 8px 0; }
@keyframes activity-pulse { 50% { box-shadow: 0 0 0 4px var(--selection); } }
@media (max-width: 640px) { summary { min-height: 44px; padding: 10px 0; } }
@media (prefers-reduced-motion: reduce) { .is-active .timeline-node { animation: none; } }
</style>
