<script setup lang="ts">
import { computed } from "vue";
import { activityFacts, commandPresentation, type AgentActivity } from "./agentEventPresentation";
const props = defineProps<{ activity: AgentActivity }>();
const command = computed(() => props.activity.tool === "exec_command" ? commandPresentation(props.activity) : null);
const facts = computed(() => activityFacts(props.activity));
</script>
<template>
    <div v-if="command" class="command-details">
        <p class="command-line" translate="no"><code>{{ command.data.command || '命令正文未记录' }}</code></p>
        <dl class="command-summary">
            <div><dt>工作目录</dt><dd translate="no">{{ command.data.directory === '.' ? '仓库根目录' : command.data.directory || '未记录或已隐藏' }}</dd></div>
            <div v-if="typeof command.data.exitCode === 'number'"><dt>退出码</dt><dd>{{ command.data.exitCode }}</dd></div>
            <div><dt>结果</dt><dd>{{ command.label }}</dd></div>
        </dl>
        <p class="command-explanation">{{ command.explanation }}</p>
        <p v-if="command.data.scope">验证要求：{{ command.data.scope }}</p>
        <p v-if="typeof command.data.exitCode === 'number'">{{ command.data.testDetails === "AVAILABLE" ? "已提供测试明细" : "测试明细未提供" }}<span v-if="command.data.sourceChanged"> · 源码已变化，需重验</span></p>
        <details v-if="command.data.stdout || command.data.stderr" class="command-output"><summary>查看命令输出<span v-if="command.data.outputTruncated">（已截断）</span></summary>
            <template v-for="stream in ['stdout', 'stderr']" :key="stream">
                <template v-if="command.data[stream]"><p class="output-label">{{ stream === 'stdout' ? '标准输出' : '错误输出' }}</p>
                    <pre tabindex="0" :aria-label="stream === 'stdout' ? '命令标准输出' : '命令错误输出'" translate="no">{{ command.data[stream] }}</pre>
                </template>
            </template>
        </details>
        <p v-else-if="typeof command.data.exitCode === 'number'" class="output-label">没有保留的文本输出。</p>
    </div>
    <dl v-if="facts.length" class="activity-facts"><div v-for="(fact, index) in facts" :key="index">
        <dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd>
    </div></dl>
</template>
<style scoped>
.command-details { min-width: 0; font-size: 13px; overflow-wrap: anywhere; }
.command-details p { margin: 4px 0; }
.command-summary { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 6px 0; font-variant-numeric: tabular-nums; }
.command-summary > div { display: flex; gap: 6px; min-width: 0; }
.command-explanation { color: var(--muted); line-height: 1.65; }
.command-output summary { cursor: pointer; padding: 6px 0; }
.output-label { color: var(--muted); font-size: 12px; }
.command-details pre { max-height: 260px; overflow: auto; white-space: pre-wrap; }
.activity-facts { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 5px 0; }
.activity-facts > div { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
dt { color: var(--muted); font-size: 12px; flex-shrink: 0; }
dd { font-size: 13px; line-height: 1.6; margin: 0; min-width: 0; overflow-wrap: anywhere; }
</style>
