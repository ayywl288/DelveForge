<script setup lang="ts">
import { computed } from "vue";
import { activityFacts, commandPresentation, type AgentActivity } from "./agentEventPresentation";
const props = defineProps<{ activity: AgentActivity }>();
const command = computed(() => props.activity.tool === "exec_command" ? commandPresentation(props.activity) : null);
const facts = computed(() => activityFacts(props.activity));
</script>
<template>
    <div v-if="command" class="command-details">
        <p class="command-preview" translate="no"><code>{{ command.data.command || '命令正文未记录' }}</code></p>
        <p v-if="command.explanation" class="command-explanation">{{ command.explanation }}</p>
        <div class="command-disclosures">
            <details class="command-definition"><summary>完整命令与验证范围</summary>
                <pre tabindex="0" aria-label="完整命令" translate="no">{{ command.data.command || '命令正文未记录' }}</pre>
                <p v-if="command.data.scope">验证要求：{{ command.data.scope }}</p>
                <p v-else>探索命令，未声明验证范围。</p>
                <p v-if="typeof command.data.exitCode === 'number'">{{ command.data.testDetails === "AVAILABLE" ? "已提供测试明细" : "测试明细未提供" }}<span v-if="command.data.sourceChanged"> · 源码已变化，需重验</span></p>
            </details>
            <details v-if="command.data.stdout || command.data.stderr" class="command-output"><summary>查看命令输出<span v-if="command.data.outputTruncated">（已截断）</span></summary>
                <template v-for="stream in ['stdout', 'stderr']" :key="stream">
                    <template v-if="command.data[stream]"><p class="output-label">{{ stream === 'stdout' ? '标准输出' : '错误输出' }}</p>
                        <pre tabindex="0" :aria-label="stream === 'stdout' ? '命令标准输出' : '命令错误输出'" translate="no">{{ command.data[stream] }}</pre>
                    </template>
                </template>
            </details>
        </div>
    </div>
    <dl v-if="facts.length" class="activity-facts"><div v-for="(fact, index) in facts" :key="index"
        :class="{ 'explanation-fact': ['模型说明（未独立验证）', '完成检查诊断'].includes(fact.label) }">
        <dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd>
    </div></dl>
</template>
<style scoped>
.command-details { min-width: 0; font-size: 13px; overflow-wrap: anywhere; }
.command-details p { margin: 4px 0; }
.command-explanation { color: var(--muted); line-height: 1.65; }
.command-preview code { white-space: nowrap; }
.command-disclosures { display: flex; flex-wrap: wrap; align-items: flex-start; column-gap: 20px; }
.command-disclosures details { min-width: 0; }
.command-disclosures details[open] { flex-basis: 100%; }
.command-preview { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.command-output summary, .command-definition summary { cursor: pointer; padding: 6px 0; }
.output-label { color: var(--muted); font-size: 12px; }
.command-details pre { max-height: 260px; overflow: auto; white-space: pre-wrap; }
.activity-facts { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 5px 0; }
.activity-facts > div { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
.activity-facts > .explanation-fact { display: block; flex-basis: 100%; }
.explanation-fact dd { margin-top: 4px; }
dt { color: var(--muted); font-size: 12px; flex-shrink: 0; }
dd { font-size: 13px; line-height: 1.6; margin: 0; min-width: 0; overflow-wrap: anywhere; }
</style>
