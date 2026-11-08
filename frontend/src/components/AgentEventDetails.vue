<script setup lang="ts">
import { computed, ref } from "vue";
import { api } from "../api/client";
import { activityFacts, commandPresentation, type AgentActivity } from "./agentEventPresentation";
const props = defineProps<{ activity: AgentActivity }>();
const command = computed(() => props.activity.tool === "exec_command" ? commandPresentation(props.activity) : null);
const facts = computed(() => activityFacts(props.activity));
const stream = ref("stdout");
const page = ref<{ content: string; nextOffset: number; hasMore: boolean; logDiscarded: boolean } | null>(null);
const loading = ref(false);
const error = ref("");
async function load(offset = 0) {
    if (loading.value || !command.value?.data.logRef) return;
    loading.value = true;
    error.value = "";
    try {
        const result = await api.agentLog(props.activity.event.runId, String(command.value.data.logRef), stream.value, offset);
        page.value = result.result;
    } catch { error.value = "日志未能读取，请重试。"; }
    finally { loading.value = false; }
}
function changeStream(value: string) { stream.value = value; page.value = null; if (command.value?.data.logRef) void load(); }
</script>
<template>
    <div v-if="command" class="command-details">
        <pre class="command-preview" tabindex="0" aria-label="命令" translate="no">{{ stream === 'command' && page ? page.content : command.data.command || '命令正文未记录' }}</pre>
        <details class="command-disclosure">
            <summary>查看输出与诊断</summary>
            <p v-if="command.data.commandTruncated">命令预览已截断{{ command.data.logRef ? '，可选择完整命令读取保留内容。' : '，完整内容未保留。' }}</p>
            <p v-if="command.explanation" class="command-explanation">{{ command.explanation }}</p>
            <p v-if="typeof command.data.exitCode === 'number'">{{ command.data.testDetails === "AVAILABLE" ? "已提供测试明细" : "测试明细未提供；进程成功不代表测试通过" }}<span v-if="command.data.sourceChanged"> · 此命令修改了源码</span></p>
            <div class="log-controls" role="group" aria-label="输出流">
                <button v-for="name in ['stdout', 'stderr', ...(command.data.commandTruncated && command.data.logRef ? ['command'] : [])]" :key="name" type="button"
                    class="secondary compact-button" :aria-pressed="stream === name" :disabled="loading" @click="changeStream(name)">
                    {{ name === 'stdout' ? '标准输出' : name === 'stderr' ? '错误输出' : '完整命令' }}
                </button>
            </div>
            <pre v-if="stream !== 'command'" tabindex="0" aria-label="命令输出" translate="no">{{ page ? page.content || '此日志流没有输出。' : command.data[stream] || '此日志流没有输出。' }}</pre>
            <p v-if="command.data.outputTruncated && !page">预览已截断<span v-if="command.data.logRef">，可翻页读取保留日志</span>。</p>
            <div v-if="command.data.logRef" class="log-controls">
                <button type="button" :disabled="loading" @click="load(0)">{{ loading ? '读取中…' : '从头读取' }}</button>
                <button v-if="page?.hasMore" type="button" :disabled="loading" @click="load(page!.nextOffset)">下一页</button>
            </div>
            <p v-if="page" role="status">已读至 {{ page.nextOffset }} 字符{{ page.hasMore ? '，后面还有内容' : '，已到保留末尾' }}</p>
            <p v-if="command.data.logDiscarded || page?.logDiscarded">超出保留上限的输出已丢弃。</p>
            <p v-if="error" role="alert">{{ error }}</p>
        </details>
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
.log-controls button[aria-pressed="true"] { background: var(--canvas); color: var(--ink); border-color: var(--ink); }
.command-disclosure summary { cursor: pointer; padding: 6px 0; }
.output-label { color: var(--muted); font-size: 12px; }
.command-details pre { max-height: 260px; overflow: auto; white-space: pre-wrap; }
.log-controls { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 8px 0; }
.activity-facts { display: flex; flex-wrap: wrap; gap: 4px 16px; margin: 5px 0; }
.activity-facts > div { display: flex; align-items: baseline; gap: 6px; min-width: 0; }
.activity-facts > .explanation-fact { display: block; flex-basis: 100%; }
.explanation-fact dd { margin-top: 4px; white-space: pre-wrap; }
dt { color: var(--muted); font-size: 12px; flex-shrink: 0; }
dd { font-size: 13px; line-height: 1.6; margin: 0; min-width: 0; overflow-wrap: anywhere; }
</style>
