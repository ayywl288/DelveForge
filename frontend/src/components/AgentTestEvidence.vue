<script setup lang="ts">
import { computed } from "vue";
import { presentTestEvidence, presentCheckRows, presentSourceView } from "./agentResultPresentation";
const props = defineProps<{ evidence: string }>();
const result = computed(() => presentTestEvidence(props.evidence));
const checks = computed(() => presentCheckRows(props.evidence));
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
        <ul v-if="checks.length" class="check-evidence-list" aria-label="各项检查证据">
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
.check-evidence-list { padding-left: 20px; font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
@media (max-width: 640px) { .test-counts { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
</style>
