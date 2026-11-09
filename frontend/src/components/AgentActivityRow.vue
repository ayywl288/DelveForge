<script setup lang="ts">
import { computed } from "vue";
import NavigationIcon, { type NavigationIconName } from "./NavigationIcon.vue";
import AgentEventDetails from "./AgentEventDetails.vue";
import { activityFacts, safePath, preflightProgress, providerDiagnosticTitle, commandPresentation, toolNames, type AgentActivity } from "./agentEventPresentation";

const props = defineProps<{ activity: AgentActivity; compact?: boolean }>();
const command = computed(() => props.activity.tool === "exec_command" ? commandPresentation(props.activity) : null);
const target = computed(() => command.value ? safePath(command.value.data.directory) : props.activity.target);
const names: Record<string, string> = {
    UNVERIFIED_WORK_RETAINED: "未验证的修改已保留，不能交付", PARTIAL_CAPTURE_UNAVAILABLE: "无法安全保存部分修改",
    WORKING_NOTES: "Agent 更新工作笔记", CONTEXT_COMPACTED: "较早上下文已压缩",
    MODEL_PROVIDER_DIAGNOSTIC: "模型请求尝试已记录",
    RESOURCES_RELEASED: "资源已核对并释放占用", ENVIRONMENT_PREFLIGHT: "环境预检结果已保存",
    CREATED: "运行已预约", PREPARING: "准备 Docker 沙箱", SANDBOX_READY: "隔离沙箱已就绪",
    RUNNING: "开始编码", MODEL_REQUEST: "请求模型", MODEL_COMPLETED: "模型已结束，等待冻结结果",
    MODEL_INCOMPLETE: "模型报告未完成", COMPLETION_CHECK_REJECTED: "完成报告未通过事实检查",
    MODEL_RESPONSE_RECOVERY: "丢弃截断响应，准备较小动作", MODEL_FAILED: "模型请求失败", COMPLETION_FORMAT_RETRY: "请求模型明确完成状态", TOOL_STARTED: "工具操作已开始", TOOL_FINISHED: "工具操作已返回",
    FINALIZING: "冻结 Candidate", SUCCEEDED: "编码结果已保存", FAILED: "运行失败",
    CANCELLED: "运行已取消", INTERRUPTED: "运行已中断", MATERIAL_RETAINED: "最终化材料已保留",
    CLEANUP_BLOCKED: "资源清理受阻，需核对现场", RECONCILIATION_BLOCKED: "资源协调受阻",
    DELIVERY_INTENT: "正式交付已预约", DELIVERY_APPLIED: "正式文件已核对", DELIVERY_COMMIT_READY: "正式提交已准备",
    DELIVERY_PUBLISHED: "Git 已发布，等待接受", DELIVERY_COMPLETED: "正式交付完成", PLAN_COMPLETED: "演化计划已完成", DELIVERY_BLOCKED: "正式交付阻塞",
    DELIVERY_RECOVERY_REQUIRED: "正式副本需要核对恢复", DELIVERY_PENDING: "交付等待协调",
    DELIVERY_DISCOVERY_BLOCKED: "冻结结果的交付发现受阻",
};
const outcomes = { running: "进行中", completed: "完成", failed: "失败", conflict: "冲突", unknown: "结果未记录或待核对",
    recorded: "已记录", blocked: "需要核对", interrupted: "已中断或取消" };
const toolIcons: Record<string, NavigationIconName> = {
    read_file: "file", list_files: "repository", search_code: "evidence", apply_patch: "code", edit_file: "code", exec_command: "check", run_checks: "check", run_frontend_checks: "check",
};
function icon(activity: AgentActivity): NavigationIconName {
    if (["blocked", "conflict"].includes(activity.outcome)) return "lock";
    if (activity.outcome === "failed") return "warning";
    if (activity.outcome === "interrupted") return "close";
    return activity.tool ? toolIcons[activity.tool] ?? "code" : activity.event.kind === "MODEL_REQUEST" ? "discovery"
        : activity.outcome === "completed" ? "check" : "activity";
}
function duration(value: number) {
    return value < 1 ? "<1 ms" : `${value.toLocaleString("zh-CN", { maximumFractionDigits: 2 })} ms`;
}
</script>

<template>
    <li :data-sequence="activity.event.sequence" :data-tone="activity.outcome" :data-kind="activity.tool ?? activity.event.kind"
        :class="{ 'is-active': activity.active }">
        <span class="timeline-node" aria-hidden="true"><NavigationIcon :name="icon(activity)" /></span>
        <div class="timeline-body">
            <div class="timeline-heading"><strong>{{ activity.title ?? preflightProgress(activity.event)?.title ?? (activity.tool ? toolNames[activity.tool] : activity.event.kind === 'MODEL_PROVIDER_DIAGNOSTIC' ? providerDiagnosticTitle(activity.event) : names[activity.event.kind] ?? '历史运行活动') }}</strong>
                <span v-if="target" class="activity-target">{{ command ? "目录 · " : "" }}{{ target }}</span>
                <span v-if="activity.targetWithheld" class="target-note">{{ target ? '部分目标已隐藏' : '目标已隐藏' }}</span>
                <span v-else-if="activity.tool && !target && !['apply_patch', 'edit_file'].includes(activity.tool)" class="target-note">目标未记录</span>
                <time :datetime="activity.event.at" :title="new Date(activity.event.at).toLocaleString()">{{ new Date(activity.event.at).toLocaleTimeString() }}</time>
            </div>
            <div class="activity-result"><span :role="activity.active ? 'status' : undefined">{{ command ? command.label : activity.active && activity.event.kind === 'MODEL_REQUEST' ? '等待模型响应' : outcomes[activity.outcome] }}</span>
                <span v-if="activity.duration !== undefined" class="activity-duration" :data-source="activity.durationSource"
                    :title="activity.durationSource === 'invocation' ? 'Java 到沙箱的调用总耗时' : '工具内部计时，不包含 Java 调用往返'">{{ activity.durationSource === 'internal' ? '内部 ' : '' }}{{ duration(activity.duration) }}</span>
                <span v-if="command && typeof command.data.exitCode === 'number'">退出码 {{ command.data.exitCode }}</span>
                <code v-else-if="!command && activity.classification && activity.tool">{{ activity.classification }}</code>
            </div>
            <p v-if="activity.resultUncertain" class="activity-uncertain">未取得完整工具结果，修改情况需核对。</p>
            <AgentEventDetails v-if="activity.tool && !compact" :activity="activity" />
            <details v-else-if="!compact && (activity.classification || ['WORKING_NOTES', 'MODEL_COMPLETED', 'CONTEXT_COMPACTED', 'SANDBOX_READY', 'MODEL_FAILED', 'MODEL_INCOMPLETE', 'COMPLETION_CHECK_REJECTED', 'PREFLIGHT_PROGRESS', 'MODEL_PROVIDER_DIAGNOSTIC'].includes(activity.event.kind))" class="activity-technical">
                <summary>{{ activity.event.kind === "MODEL_INCOMPLETE" ? "模型未完成说明" : "技术详情" }}</summary><code v-if="activity.classification">{{ activity.classification }}</code>
                <AgentEventDetails :activity="activity" />
            </details>
            <p v-if="compact && ['MODEL_INCOMPLETE', 'COMPLETION_CHECK_REJECTED', 'MODEL_FAILED'].includes(activity.event.kind)" class="timeline-warning">{{ activityFacts(activity).find(fact => ['模型说明（未独立验证）', '完成检查诊断', '诊断'].includes(fact.label))?.value.slice(0, 240) }}</p>
            <p v-if="activity.event.kind === 'CLEANUP_BLOCKED'" class="timeline-warning">此处记录当时的清理失败；当前是否仍需协调以运行状态提示为准。</p>
        </div>
    </li>
</template>

<style scoped>
.activity-technical { margin-top: 5px; font-size: 12px; color: var(--muted); }
.activity-technical summary { cursor: pointer; }
li { position: relative; display: flex; gap: 12px; padding: 0 0 16px; margin: 0; min-width: 0; }
li:not(:last-child)::before { content: ""; position: absolute; left: 13px; top: 28px; bottom: 0; width: 1px; background: var(--line); }
.timeline-node { flex: 0 0 28px; height: 28px; border-radius: 50%; display: grid; place-items: center; background: var(--canvas); color: var(--muted); }
.timeline-node .navigation-icon { width: 15px; height: 15px; }
.timeline-body { min-width: 0; flex: 1; padding-top: 3px; overflow-wrap: anywhere; }
.timeline-heading, .activity-result { display: flex; align-items: baseline; flex-wrap: wrap; gap: 4px 12px; }
.timeline-heading strong { font-size: 14px; font-weight: 600; }
time, .activity-target, .activity-result, .target-note { font-size: 12px; color: var(--muted); }
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
.activity-uncertain { font-size: 12px; color: var(--muted); margin: 4px 0; }
.timeline-warning { font-size: 14px; color: var(--warning); margin: 6px 0; }
@keyframes activity-pulse { 50% { box-shadow: 0 0 0 4px var(--selection); } }
@media (prefers-reduced-motion: reduce) { .is-active .timeline-node { animation: none; } }
</style>
