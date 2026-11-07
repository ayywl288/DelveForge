<script setup lang="ts">
import { computed } from "vue";
import { activityFacts, summaryFields, type AgentActivity } from "./agentEventPresentation";
const props = defineProps<{ activity: AgentActivity }>();
const command = computed(() => props.activity.tool === "exec_command" ? summaryFields(props.activity.finish ?? props.activity.event) : null);
const facts = computed(() => activityFacts(props.activity));
</script>
<template>
    <div v-if="command" class="command-details">
        <p translate="no">{{ command.command }} <span>· {{ command.directory }}</span></p>
        <p v-if="command.scope">验证要求：{{ command.scope }}</p>
        <p v-if="typeof command.exitCode === 'number'">退出码 {{ command.exitCode }} · {{ command.testDetails === "AVAILABLE" ? "已提供测试明细" : "测试明细未提供" }}<span v-if="command.sourceChanged"> · 源码已变化，需重验</span></p>
        <p v-else-if="command.resultRecorded">{{ command.mutationNotStarted ? '命令未执行，请按反馈调整后重试。' : '退出结果未记录，不能确认执行成功。' }}</p>
        <details v-if="command.stdout || command.stderr"><summary>查看命令输出<span v-if="command.outputTruncated">（已截断）</span></summary>
            <pre tabindex="0" aria-label="命令输出" translate="no">{{ command.stdout }}{{ command.stderr }}</pre>
        </details>
    </div>
    <dl v-if="facts.length" class="activity-facts"><div v-for="(fact, index) in facts" :key="index">
        <dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd>
    </div></dl>
</template>
<style scoped>
.command-details { min-width: 0; font-size: 13px; overflow-wrap: anywhere; }
.command-details p { margin: 4px 0; }
.command-details pre { max-height: 260px; overflow: auto; white-space: pre-wrap; }
.activity-facts { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 5px 0; }
.activity-facts > div { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
dt { color: var(--muted); font-size: 12px; flex-shrink: 0; }
dd { font-size: 13px; line-height: 1.6; margin: 0; min-width: 0; overflow-wrap: anywhere; }
</style>
