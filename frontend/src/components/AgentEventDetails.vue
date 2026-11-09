<script setup lang="ts">
import { computed, ref } from "vue";
import { api } from "../api/client";
import { activityFacts, commandPresentation, type AgentActivity } from "./agentEventPresentation";
const props = defineProps<{ activity: AgentActivity }>();
const command = computed(() => props.activity.tool === "exec_command" ? commandPresentation(props.activity) : null);
const facts = computed(() => activityFacts(props.activity));
const stream = ref("stdout");
const logsOpen = ref(false);
const page = ref<{ content: string; nextOffset: number; hasMore: boolean; logDiscarded: boolean } | null>(null);
const loading = ref(false);
const error = ref("");
async function load(offset = 0) {
    if (loading.value || !command.value?.data.logRef) return;
    loading.value = true;
    error.value = "";
    try {
        const result = await api.agentLog(props.activity.event.runId, String(command.value.data.logRef), stream.value, offset);
        page.value = { ...result.result, content: offset > 0 ? (page.value?.content ?? "") + result.result.content : result.result.content };
    } catch { error.value = "日志未能读取，请重试。"; }
    finally { loading.value = false; }
}
function changeStream(value: string) { stream.value = value; page.value = null; if (command.value?.data.logRef) void load(); }
function toggleLogs() {
    logsOpen.value = !logsOpen.value;
    if (logsOpen.value && !page.value) void load();
}
</script>
<template>
    <div v-if="command" class="command-details">
        <pre class="command-preview" tabindex="0" aria-label="命令" translate="no">{{ logsOpen && stream === 'command' && page ? page.content : command.data.command || '命令正文未记录' }}</pre>
        <p v-if="command.explanation" class="command-explanation">{{ command.explanation }}</p>
        <p v-if="command.data.sourceChanged">此命令修改了源码</p>
        <p v-if="command.data.commandTruncated">命令预览已截断{{ command.data.logRef ? '，可读取完整命令。' : '，完整内容未保留。' }}</p>
        <p v-if="command.data.logDiscarded && !logsOpen">超出保留上限的输出已丢弃。</p>
        <button type="button" class="text-button" :aria-expanded="logsOpen" @click="toggleLogs">{{ logsOpen ? '收起日志' : command.data.logRef ? '查看完整日志' : '查看已保存输出' }}</button>
        <section v-if="logsOpen" class="command-disclosure" aria-label="保留命令日志">
            <label>日志流 <select v-model="stream" :disabled="loading" @change="changeStream(stream)">
                <option value="stdout">stdout · 标准输出</option><option value="stderr">stderr · 错误输出</option>
                <option v-if="command.data.commandTruncated && command.data.logRef" value="command">完整命令</option>
            </select></label>
            <pre v-if="stream !== 'command'" tabindex="0" aria-label="命令输出" translate="no">{{ page ? page.content || '此日志流没有输出。' : command.data[stream] || '此日志流没有输出。' }}</pre>
            <p v-if="command.data.outputTruncated && !page">预览已截断{{ command.data.logRef ? '，可读取保留日志。' : '，完整内容未保留。' }}</p>
            <button v-if="page?.hasMore" class="text-button" type="button" :disabled="loading" @click="load(page!.nextOffset)">加载更多</button>
            <p v-if="loading" role="status">读取中…</p>
            <p v-if="page" role="status">已读至 {{ page.nextOffset }} 字符{{ page.hasMore ? '，后面还有内容' : '，已到保留末尾' }}</p>
            <p v-if="command.data.logDiscarded || page?.logDiscarded">超出保留上限的输出已丢弃。</p>
            <p v-if="error" role="alert">{{ error }} <button class="text-button" @click="load()">重试读取</button></p>
        </section>
    </div>
    <dl v-if="facts.length" class="activity-facts"><div v-for="(fact, index) in facts" :key="index"
        :class="{ 'explanation-fact': ['模型说明（未独立验证）', '完成检查诊断', 'Agent 工作笔记', 'Agent 完成报告', 'Agent 报告限制'].includes(fact.label) }">
        <dt>{{ fact.label }}</dt><dd>{{ fact.value }}</dd>
    </div></dl>
</template>
<style scoped>
.command-details { min-width: 0; font-size: 13px; overflow-wrap: anywhere; }
.command-details p { margin: 4px 0; }
.command-explanation { color: var(--muted); line-height: 1.65; }
.command-preview { white-space: pre-wrap; overflow-wrap: anywhere; }

.command-disclosure { margin-top: 12px; }
.command-disclosure select { width: auto; margin-left: 8px; font-size: 13px; }
.output-label { color: var(--muted); font-size: 12px; }
.command-details pre { white-space: pre-wrap; }
.command-disclosure pre { max-height: 28rem; overflow: auto; }
.log-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 8px 0; }
.activity-facts { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 5px 0; }
.activity-facts > div { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
.activity-facts > .explanation-fact { display: block; flex-basis: 100%; }
.explanation-fact dd { margin-top: 4px; white-space: pre-wrap; }
dt { color: var(--muted); font-size: 12px; flex-shrink: 0; }
dd { font-size: 13px; line-height: 1.6; margin: 0; min-width: 0; overflow-wrap: anywhere; }
</style>
