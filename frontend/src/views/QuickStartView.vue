<script setup lang="ts">
import { computed, onActivated, onDeactivated, onUnmounted, ref, watch } from "vue";
import { api, type QuickStart } from "../api/client";
import GuideMarkdown from "../components/GuideMarkdown.vue";
const props = defineProps<{ planId?: string; completed: boolean }>();
const guide = ref<QuickStart | null>(null);
const document = computed(() => {
    const text = guide.value?.markdown ?? "";
    const title = text.match(/^# ([^\n]+)\n?/);
    const parts = text.replace(/^# [^\n]+\n?/, "").split(/^## 生成依据\s*$/m);
    return { title: title?.[1] ?? "项目", body: parts[0]?.trim() ?? "", sources: parts.slice(1).join("\n").trim() };
});
const loading = ref(false);
const error = ref("");
let epoch = 0;
let timer: ReturnType<typeof setTimeout> | undefined;
let active = true;
function stop() { active = false; epoch++; clearTimeout(timer); }
async function load(generate = false) {
    if (!props.planId) return;
    clearTimeout(timer);
    const id = props.planId;
    const version = ++epoch;
    loading.value = true; error.value = "";
    try {
        const result = await (generate ? api.generateQuickStart(id) : api.quickStart(id));
        if (version !== epoch) return;
        guide.value = result;
        if (active && result.status === "GENERATING") timer = setTimeout(() => void load(), 2000);
    } catch (failure) { if (version === epoch) error.value = failure instanceof Error ? failure.message : "指南暂不可用，请重试。"; }
    finally { if (version === epoch) loading.value = false; }
}
watch(() => [props.planId, props.completed], () => { guide.value = null; if (active) void load(); }, { immediate: true });
onActivated(() => { active = true; void load(); });
onDeactivated(stop); onUnmounted(stop);
</script>
<template>
    <section class="panel quick-start" aria-label="项目 Quick Start">
        <div class="section-row"><h2>{{ guide?.status === 'AVAILABLE' ? document.title : '启动项目' }}<span v-if="guide?.status === 'AVAILABLE'" class="quick-start-label">Quick Start</span></h2><button v-if="completed" class="secondary" :disabled="loading || guide?.status === 'GENERATING'" @click="load(true)">{{ guide?.status === 'AVAILABLE' ? '重新生成指南' : '生成 / 重试指南' }}</button></div>
        <p v-if="!planId">打开一个演化项目后，在这里查看启动指南。</p>
        <p v-else-if="guide?.status === 'GENERATING'" role="status">正在根据最终交付文件生成指南…</p>
        <p v-else-if="guide?.status !== 'AVAILABLE'">{{ completed ? '启动指南暂不可用，代码交付已完成，可稍后重试生成。' : '全部步骤正式交付后，会在这里生成启动指南。' }}</p>
        <p v-if="error" role="alert">{{ error }} <button class="secondary compact-button" @click="load()">刷新</button></p>
        <template v-if="guide?.status === 'AVAILABLE'">
            <p v-if="guide.stale" class="stale-guide" role="status">此指南已过期：后续计划已改变交付项目。新计划全部交付后生成新指南。</p>
            <p class="guide-version">最终交付 <code>{{ guide.revision?.slice(0, 12) }}</code></p>
            <p class="guide-verification">依据交付文件整理，启动命令和配置尚未执行验证。</p>
            <GuideMarkdown :markdown="document.body" />
            <details class="guide-sources"><summary>生成依据</summary>
                <p>Plan <code>{{ guide.planId }}</code> · Delivery <code>{{ guide.candidateId }}</code></p>
                <p>版本 <code>{{ guide.revision }}</code></p>
                <!-- 来源路径可能含 Markdown 标记，始终按文本显示，不能重新解析为链接。 -->
                <pre v-if="document.sources" class="guide-source-text"><code>{{ document.sources }}</code></pre>
                <p v-else>旧版指南未单独保存来源清单，可重新生成。</p>
            </details>
        </template>
        <p v-if="completed" class="meta">重新生成会调用模型并产生 API 费用。</p>
    </section>
</template>
<style scoped>
.quick-start { min-width: 0; padding: clamp(20px, 3vw, 36px); }
.quick-start-label { margin-left: 12px; color: var(--muted); font-size: 14px; font-weight: 400; }
.guide-version { font-size: 13px; color: var(--muted); margin: 12px 0; }
.guide-sources { margin-top: 32px; padding-top: 16px; border-top: 1px solid var(--line); font-size: 13px; overflow-wrap: anywhere; }
.guide-sources summary { cursor: pointer; font-weight: 600; }.quick-start h2 { font-size: 18px; margin: 0; }
.guide-source-text { white-space: pre-wrap; overflow-wrap: anywhere; }
.quick-start .section-row { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; }
.quick-start .meta { overflow-wrap: anywhere; }.guide-verification { font-size: 13px; color: var(--muted); }
.stale-guide { color: var(--warning); }
</style>
