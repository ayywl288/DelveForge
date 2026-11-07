<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import type { AgentEvent, AgentRun } from "../api/agentTypes";
import NavigationIcon from "./NavigationIcon.vue";
import AgentActivityRow from "./AgentActivityRow.vue";
import { verificationLabel } from "./agentResultPresentation";
import { summaryFields, routineProviderDiagnostic, commandPresentation } from "./agentEventPresentation";
import type { RunPresentation } from "./agentResultPresentation";
import { activityState, buildActivities, currentActivity, checkSummary, explorationSummary, isExploration, isPatchTool, isCheckTool, patchSummary, toolNames } from "./agentEventPresentation";

const props = withDefaults(defineProps<{ events: AgentEvent[]; run: AgentRun; connection: string; presentation?: RunPresentation; showUsage?: boolean }>(), { showUsage: true });
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
    if (props.presentation && props.run.state === "SUCCEEDED" && result.title === "Candidate 已冻结") {
        return { ...result, title: props.presentation.label, detail: props.presentation.detail, tone: props.presentation.tone };
    }
    return result;
});
const checks = computed(() => checkSummary(activities.value, props.run.id));
const checksExpanded = ref(false);
const checkCount = ref(40);
const visibleChecks = computed(() => checks.value.activities.slice(0, checkCount.value));
const chronological = computed(() => activities.value.filter(activity => !isExploration(activity) && !isPatchTool(activity.tool) && !isCheckTool(activity.tool) && activity.event.kind !== "MODEL_REQUEST" && !routineProviderDiagnostic(activity)));
const providerExpanded = ref(false);
const providerCount = ref(40);
const providerDiagnostics = computed(() => activities.value.filter(activity => activity.event.kind === "MODEL_PROVIDER_DIAGNOSTIC"));
const visibleProviders = computed(() => [...providerDiagnostics.value].reverse().slice(0, providerCount.value));
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
watch(() => props.run.id, () => { following.value = true; unseen.value = false; visibleCount.value = 40; previousCount = 0; filesExpanded.value = false; fileCount.value = 40; patchesExpanded.value = false; patchCount.value = 40; checksExpanded.value = false; checkCount.value = 40; providerExpanded.value = false; providerCount.value = 40; });
</script>

<template>
    <section class="activity-section" :data-terminal="state.terminal" aria-labelledby="agent-activity-title">
        <div class="section-row"><h3 id="agent-activity-title">执行活动</h3>
            <p class="stream-status" role="status">事件连接：{{ connection }}</p></div>
        <p v-if="state.snapshotBehind" class="snapshot-note" role="status">已收到较新的持久化活动，运行概览待同步。</p>
        <p v-if="!events.length" class="meta">等待持久化事件。</p>
        <div class="current-activity" :data-active="current.active" :data-tone="current.tone" role="status" aria-live="polite" aria-atomic="true">
            <NavigationIcon :name="current.tone === 'failed' ? 'warning' : current.tone === 'blocked' ? 'lock' : current.tone === 'completed' ? 'check' : 'activity'" />
            <div class="current-copy"><p class="current-label">当前活动</p><Transition name="current-change" @leave="removeImmediately"><div :key="current.title + current.detail" class="current-content"><p class="current-title">{{ current.title }}
                <span v-if="current.waiting" class="model-waiting" aria-hidden="true"><i></i><i></i><i></i></span></p>
                <p v-if="current.detail" class="current-detail">{{ current.detail }}</p></div></Transition></div>
        </div>
        <div v-if="current.active" class="activity-bar" aria-hidden="true"></div>
        <p v-if="showUsage !== false" class="model-statistics">模型调用 {{ run.modelCalls ?? '待同步' }}{{ run.modelCalls === undefined ? '' : ' 次' }}</p>
        <p v-if="patches.blocked || patches.uncertain || patches.failed" class="patch-alert" role="alert">代码修改需核对：{{ patches.blocked }} 次安全拒绝 · {{ patches.uncertain }} 次结果不明 · {{ patches.failed }} 次失败。详见代码修改记录；不代表 Candidate 或交付成功。</p>
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
        <section v-if="checks.count" class="current-checks" aria-label="当前源码验证">
            <h4>当前源码验证</h4>
            <p v-if="checks.stale" class="check-blocker" role="status">源码已变化，部分必要检查需要重新运行。</p>
            <ol v-if="checks.current.length" class="current-check-list">
                <li v-for="activity in checks.current" :key="activity.key">
                    <code translate="no">{{ summaryFields(activity.finish ?? activity.event).command ?? activity.target ?? toolNames[activity.tool!] }}</code>
                    · {{ activity.tool === "exec_command" ? commandPresentation(activity).label : verificationLabel(activity.classification) }}
                    <span v-if="activity.tool === 'exec_command'"> · {{ summaryFields(activity.finish ?? activity.event).scope }}</span>
                </li>
            </ol>
            <p v-else class="meta">尚无可确认的当前范围验证结果。命令执行成功不等于业务验收通过。</p>
        </section>
        <details v-if="checks.count" :key="checks.key" class="check-summary" :open="checksExpanded">
            <summary @click.prevent="checksExpanded = !checksExpanded"><NavigationIcon name="check" />命令与检查 · 历史
                <strong class="check-latest" :data-tone="checks.latest?.outcome">{{ checks.label }}</strong>
                <span class="check-count">{{ checks.count }} 次尝试<span v-if="checks.failed"> · 历史 {{ checks.failed }} 次未通过</span></span>
                <span v-if="checks.detail" class="check-detail">{{ checks.detail }}</span>
            </summary>
            <template v-if="checksExpanded"><p class="exploration-note">当前结果绑定最近已记录的源码版本；以下为全部命令尝试，最近记录在前。缺失的历史不会推断为通过。</p>
                <ol class="check-history" aria-label="本 Run 已加载命令历史，最近在前" tabindex="0">
                    <AgentActivityRow v-for="activity in visibleChecks" :key="activity.key" :activity="activity" />
                </ol><button v-if="checkCount < checks.count" class="secondary compact-button more-checks" @click="checkCount += 40">显示更多检查记录</button>
            </template>
        </details>
        <p v-if="checks.blocked" class="check-blocker" role="alert">{{ checks.sourceBlocked ? '检查期间检测到文件变化，本次检查已停止。' : '检查被安全规则停止，请核对运行记录。' }}</p>
        <details :key="patches.key" class="patch-summary" :open="patchesExpanded">
            <summary @click.prevent="patchesExpanded = !patchesExpanded"><NavigationIcon name="code" />代码修改 · 执行历史
                <span class="patch-count"><span :key="`${patches.count}:${patches.applied}:${patches.conflicts}:${patches.blocked}:${patches.uncertain}`" class="counter-values">已加载 {{ patches.count }} 次尝试 · 已应用 {{ patches.applied }} · 冲突 {{ patches.conflicts }} · 安全拒绝 {{ patches.blocked }} · 结果不明 {{ patches.uncertain }} · 失败 {{ patches.failed }}</span></span>
                <span v-if="patches.active" class="patch-current" role="status">正在应用代码修改</span>
            </summary>
            <p v-if="patchesExpanded" class="exploration-note">以下为逐次修改尝试；最终交付文件见下方变更清单。冲突、安全拒绝和结果不明均保留原记录。</p>
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
        <details v-if="providerDiagnostics.length" class="provider-diagnostics" :open="providerExpanded">
            <summary @click.prevent="providerExpanded = !providerExpanded">模型请求技术诊断 · 已加载 {{ providerDiagnostics.length }} 条</summary>
            <template v-if="providerExpanded">
                <p class="exploration-note">常规成功请求收在此处；失败和重试仍保留在主时间线。诊断不改变调用及用量统计。</p>
                <ol class="exploration-operations" aria-label="模型请求技术诊断，最近在前" tabindex="0">
                    <AgentActivityRow v-for="activity in visibleProviders" :key="activity.key" :activity="activity" />
                </ol>
                <button v-if="providerCount < providerDiagnostics.length" class="secondary compact-button" @click="providerCount += 40">显示更多请求诊断</button>
            </template>
        </details>
        <p v-if="events.length === 1000" class="meta">已加载最近 1000 条事件；更早事件受现有保留范围限制。</p>
    </section>
</template>

<style scoped>
.provider-diagnostics { border-top: 1px solid var(--line); padding: 12px 0; font-size: 13px; }
.provider-diagnostics > summary { cursor: pointer; overflow-wrap: anywhere; }
.current-checks { border-bottom: 1px solid var(--line); padding: 12px 0; }
.current-checks h4 { font-size: 14px; margin: 0 0 8px; }
.current-check-list { list-style: none; margin: 0; padding: 0; overflow-wrap: anywhere; }
.check-summary { min-width: 0; margin: 10px 0; padding: 12px 0 16px; border-bottom: 1px solid var(--line); }
.check-summary summary { cursor: pointer; font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.check-summary summary > .navigation-icon { width: 15px; height: 15px; vertical-align: middle; margin-right: 8px; color: var(--muted); }
.check-latest, .check-count, .check-detail { display: block; margin: 4px 0 0 28px; font-size: 12px; line-height: 1.6; }
.check-count, .check-detail { color: var(--muted); font-weight: 400; }
.check-latest[data-tone="completed"] { color: var(--success); }
.check-latest[data-tone="failed"] { color: var(--danger); }
.check-latest[data-tone="blocked"], .check-blocker { color: var(--warning); }
.check-blocker { margin: 10px 0 0 28px; font-size: 13px; line-height: 1.6; }
.check-history { list-style: none; margin: 14px 0 10px; padding: 0; max-height: 26rem; overflow: auto; scrollbar-width: thin; }

.activity-section { min-width: 0; line-height: 1.6; }
.activity-section h3 { font-size: 17px; line-height: 1.5; margin: 0; }
.activity-section > .section-row { margin-bottom: 12px; }
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
.patch-alert { font-size: 13px; color: var(--warning); margin: 8px 0 14px; }
.patch-summary { border-bottom: 1px solid var(--line); padding: 10px 0 16px; }
.patch-summary summary { cursor: pointer; font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.patch-summary summary .navigation-icon { width: 15px; height: 15px; vertical-align: middle; margin-right: 8px; color: var(--muted); }
.patch-count, .patch-current { display: block; font-size: 12px; font-weight: 400; color: var(--muted); margin: 4px 0 0 28px; overflow-wrap: anywhere; }
.patch-current { color: var(--accent); }
.patch-count { display: grid; }
.counter-values { grid-area: 1/1; animation: counter-update 220ms ease-out; }
@keyframes counter-update { from { color: var(--accent); } }
.patch-operations { list-style: none; padding: 12px 8px 0 0; max-height: 24rem; overflow: auto; overflow-anchor: none; scrollbar-width: thin; }
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
@media (prefers-reduced-motion: reduce) { .activity-bar::after, .counter-values { animation: none; } .activity-bar::after { width: 100%; transform: none; opacity: .5; } .current-change-enter-active, .current-change-leave-active { transition: none; } .current-change-enter-from { transform: none; opacity: 1; } }
</style>
