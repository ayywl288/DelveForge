<script setup lang="ts">
import { onActivated, onDeactivated, onUnmounted, ref, watch } from "vue";
import { api, type QuickStart } from "../api/client";
import GuideMarkdown from "../components/GuideMarkdown.vue";
const props = defineProps<{ planId?: string; completed: boolean }>();
const guide = ref<QuickStart | null>(null);
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
        <div class="section-row"><h2>启动项目</h2><button v-if="completed" class="secondary" :disabled="loading || guide?.status === 'GENERATING'" @click="load(true)">{{ guide?.status === 'AVAILABLE' ? '重新生成指南' : '生成 / 重试指南' }}</button></div>
        <p v-if="!planId">打开一个演化项目后，在这里查看启动指南。</p>
        <p v-else-if="guide?.status === 'GENERATING'" role="status">正在根据最终交付文件生成指南…</p>
        <p v-else-if="guide?.status !== 'AVAILABLE'">{{ completed ? '启动指南暂不可用，代码交付已完成，可稍后重试生成。' : '全部步骤正式交付后，会在这里生成启动指南。' }}</p>
        <p v-if="error" role="alert">{{ error }} <button class="secondary compact-button" @click="load()">刷新</button></p>
        <template v-if="guide?.status === 'AVAILABLE'">
            <p v-if="guide.stale" class="stale-guide" role="status">此指南已过期：后续计划已改变交付项目。新计划全部交付后生成新指南。</p>
            <p class="meta">Plan {{ guide.planId }} · Delivery {{ guide.candidateId }}<br>版本 <code>{{ guide.revision }}</code></p>
            <p class="guide-verification">以下由模型依据交付文件整理；启动命令和配置尚未执行验证。</p>
            <GuideMarkdown :markdown="guide.markdown" />
        </template>
        <p v-if="completed" class="meta">重新生成会依据同一交付版本再次调用模型整理说明，不启动 Agent 容器，也不重新执行代码。</p>
    </section>
</template>
<style scoped>
.quick-start { min-width: 0; }.quick-start h2 { font-size: 18px; margin: 0; }
.quick-start .section-row { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 12px; }
.quick-start .meta { overflow-wrap: anywhere; }.guide-verification { font-size: 13px; color: var(--muted); }
.stale-guide { color: var(--warning); }
</style>
