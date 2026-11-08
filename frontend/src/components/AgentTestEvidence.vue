<script setup lang="ts">
import { computed } from "vue";
import { executionFacts, presentTestEvidence, presentCheckRows, presentSourceView } from "./agentResultPresentation";
const props = defineProps<{ evidence: string }>();
const result = computed(() => presentTestEvidence(props.evidence));
const checks = computed(() => presentCheckRows(props.evidence));
const report = computed(() => { try { const value = JSON.parse(props.evidence); return [7, 8].includes(value.evidenceVersion) ? value.agentReport : null; } catch { return null; } });
const generic = computed(() => { try { return [6, 7, 8].includes(JSON.parse(props.evidence).evidenceVersion); } catch { return false; } });
const facts = computed(() => executionFacts(props.evidence));
const sourceView = computed(() => presentSourceView(props.evidence));
</script>
<template>
    <section class="candidate-evidence" aria-label="测试结果" :data-tone="result.tone">
        <div class="evidence-heading"><h4>测试结果</h4><strong class="evidence-status">{{ result.label }}</strong></div>
        <p class="verification-outcome">{{ result.outcome }}<span v-if="result.scope" class="evidence-scope"> · {{ result.scope }}</span></p>
        <p v-if="sourceView" class="evidence-scope">{{ sourceView }}</p>
        <dl v-if="result.counts" class="test-counts">
            <div><dt>已执行</dt><dd>{{ result.counts.executed }}</dd></div>
            <div><dt>通过</dt><dd>{{ result.counts.passed ?? '未记录' }}</dd></div>
            <div><dt>失败</dt><dd>{{ result.counts.failed ?? '未记录' }}</dd></div>
            <div><dt>错误</dt><dd>{{ result.counts.errored ?? '未记录' }}</dd></div>
            <div><dt>跳过</dt><dd>{{ result.counts.skipped ?? '未记录' }}</dd></div>
        </dl>
        <dl v-if="facts" class="test-counts" aria-label="全部执行记录汇总">
            <div><dt>命令总数</dt><dd>{{ facts.total }}</dd></div><div><dt>进程成功</dt><dd>{{ facts.succeeded }}</dd></div>
            <div><dt>进程失败</dt><dd>{{ facts.failed }}</dd></div><div><dt>超时</dt><dd>{{ facts.timedOut }}</dd></div>
            <div><dt>取消</dt><dd>{{ facts.cancelled }}</dd></div>
        </dl>
        <p v-if="facts" class="evidence-scope">{{ facts.reports }} 次有测试报告，{{ facts.unknown }} 次无报告详情；{{ facts.stale }} 次属于旧源码。报告累计执行 {{ facts.executed }} 项，失败或错误 {{ facts.failures }} 项，跳过 {{ facts.skipped }} 项，包含重复执行。未执行的检查见 Agent 限制说明。</p>
        <h4 v-if="report">Agent 判断</h4>
        <p v-if="report?.summary">{{ report.summary }}</p>
        <p v-if="report?.limitations">Agent 报告限制：{{ Array.isArray(report.limitations) ? report.limitations.join('；') : report.limitations }}</p>
        <p v-if="generic" class="evidence-scope">完整命令与日志见本次 Agent Run 的「命令与检查」。</p>
        <ul v-if="!generic && checks.length" class="check-evidence-list" aria-label="各项检查证据">
            <li v-for="(check, index) in checks" :key="index">
                <span>{{ check.scope }}</span> · {{ check.label }}
                <span v-if="check.executed !== null"> · 已执行 {{ check.executed }} 项测试</span>
                <strong v-if="check.stale"> · 旧源码结果</strong>
            </li>
        </ul>
    </section>
</template>
<style scoped>
.candidate-evidence { padding-top: 18px; margin-top: 18px; border-top: 1px solid var(--line); min-width: 0; }
.evidence-heading { display: flex; align-items: baseline; flex-wrap: wrap; gap: 12px; }
h4 { font-size: 15px; margin: 0; }
.evidence-status { font-size: 13px; color: var(--muted); }
[data-tone="success"] .evidence-status { color: var(--success); }
[data-tone="danger"] .evidence-status, [data-tone="danger"] .verification-outcome { color: var(--danger); }
[data-tone="warning"] .evidence-status { color: var(--warning); }
.verification-outcome { font-size: 14px; margin: 10px 0; line-height: 1.6; }
.evidence-scope { color: var(--muted); font-size: 12px; }
.test-counts { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; margin: 12px 0; }
.test-counts dt { font-size: 12px; color: var(--muted); }
.test-counts dd { margin: 4px 0 0; font-size: 16px; font-weight: 600; overflow-wrap: anywhere; }
.evidence-history { margin-top: 12px; font-size: 13px; }
.evidence-history summary { cursor: pointer; }
.current-evidence-list, .check-evidence-list { padding-left: 20px; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.check-evidence-list > li { content-visibility: auto; contain-intrinsic-size: auto 24px; }
@media (max-width: 640px) { .test-counts { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
