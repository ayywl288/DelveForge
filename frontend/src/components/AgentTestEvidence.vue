<script setup lang="ts">
import { computed } from "vue";
import { executionFacts, presentTestEvidence, presentCheckRows, presentSourceView } from "./agentResultPresentation";
const props = defineProps<{ evidence: string }>();
const result = computed(() => presentTestEvidence(props.evidence));
const checks = computed(() => presentCheckRows(props.evidence));
const report = computed(() => { try { const value = JSON.parse(props.evidence); return [7, 8].includes(value.evidenceVersion) ? value.agentReport : null; } catch { return null; } });
const generic = computed(() => { try { return [6, 7, 8].includes(JSON.parse(props.evidence).evidenceVersion); } catch { return false; } });
const facts = computed(() => executionFacts(props.evidence));
const finalFacts = computed(() => executionFacts(props.evidence, true));
const summary = computed(() => typeof report.value?.summary === "string"
    ? report.value.summary.split(/\n+|(?<=[。！？])\s*/u).map((line: string) => line.replace(/^[-*]\s*/, "").trim()).filter(Boolean) : []);
const limitations = computed(() => Array.isArray(report.value?.limitations)
    ? report.value.limitations.filter((item: unknown) => typeof item === "string")
    : typeof report.value?.limitations === "string" ? [report.value.limitations] : []);
const sourceView = computed(() => presentSourceView(props.evidence));
</script>
<template>
    <section class="candidate-evidence" aria-label="实现与验证结果" :data-tone="result.tone">
        <section v-if="summary.length" class="implementation-summary" aria-label="实现摘要">
            <h4>实现摘要 <span class="evidence-scope">Agent 报告</span></h4>
            <ul><li v-for="(item, index) in summary.slice(0, 5)" :key="index">{{ item }}</li></ul>
        </section>
        <div class="evidence-heading"><h4>验证</h4><span class="evidence-attribution">平台执行事实</span></div>
        <template v-if="finalFacts">
            <p v-if="finalFacts.total">最终源码上的 {{ finalFacts.total }} 次命令：成功 {{ finalFacts.succeeded }} · 失败 {{ finalFacts.failed }} · 超时 {{ finalFacts.timedOut }} · 取消 {{ finalFacts.cancelled }}</p>
            <p v-else>最终源码上没有已记录的命令结果。</p>
            <p class="evidence-scope">{{ finalFacts.reports }} 次带测试报告，{{ finalFacts.unknown }} 次无报告详情。退出零不证明测试或业务通过。</p>
            <p v-if="finalFacts.reports">报告累计执行 {{ finalFacts.executed }} 项，失败 / 错误 {{ finalFacts.failures }} 项，跳过 {{ finalFacts.skipped }} 项（含重复执行，不是唯一用例数）。</p>
            <p v-if="facts && facts.total > finalFacts.total" class="evidence-scope">另有 {{ facts.total - finalFacts.total }} 次执行的源码或视图与最终状态不匹配。</p>
        </template>
        <template v-else>
            <p class="verification-outcome"><strong class="evidence-status">{{ result.label }}</strong> · {{ result.outcome }}<span v-if="result.scope" class="evidence-scope"> · {{ result.scope }}</span></p>
            <dl v-if="result.counts" class="test-counts">
                <div><dt>已执行</dt><dd>{{ result.counts.executed }}</dd></div>
                <div><dt>通过</dt><dd>{{ result.counts.passed ?? '未记录' }}</dd></div>
                <div><dt>失败</dt><dd>{{ result.counts.failed ?? '未记录' }}</dd></div>
                <div><dt>错误</dt><dd>{{ result.counts.errored ?? '未记录' }}</dd></div>
                <div><dt>跳过</dt><dd>{{ result.counts.skipped ?? '未记录' }}</dd></div>
            </dl>
            <ul v-if="!generic && checks.length" class="check-evidence-list" aria-label="各项检查证据"><li v-for="(check, index) in checks" :key="index">{{ check.scope }} · {{ check.label }}<strong v-if="check.stale"> · 旧源码结果</strong></li></ul>
        </template>
        <p v-if="sourceView" class="evidence-scope">{{ sourceView }}</p>
        <section v-if="limitations.length" class="result-limitations" aria-label="剩余限制"><h4>剩余限制 <span class="evidence-scope">Agent 报告</span></h4>
            <ul><li v-for="(item, index) in limitations" :key="index">{{ item }}</li></ul>
        </section>
    </section>
</template>
<style scoped>
.implementation-summary, .result-limitations { margin-bottom: 24px; }
.result-limitations { margin-top: 24px; }
.candidate-evidence ul { padding-left: 20px; line-height: 1.8; }
.candidate-evidence p { font-size: 14px; line-height: 1.7; }
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
