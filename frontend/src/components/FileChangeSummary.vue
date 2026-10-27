<script setup lang="ts">
import { computed, ref } from "vue";
import type { PresentedFileChange } from "./agentResultPresentation";
const props = defineProps<{ files: readonly PresentedFileChange[] }>();
const expanded = ref(false);
const visible = computed(() => expanded.value ? props.files : props.files.slice(0, 6));
const labels = { ADDED: "新增", MODIFIED: "修改", DELETED: "删除", UNKNOWN: "待解析" };
const counts = computed(() => props.files.reduce((result, file) => {
    result[file.changeType]++; return result;
}, { ADDED: 0, MODIFIED: 0, DELETED: 0, UNKNOWN: 0 }));
</script>
<template>
    <div class="file-change-summary">
        <p class="change-counts"><strong>{{ files.length }} 个文件</strong>
            <span class="change-count" data-type="ADDED">新增 {{ counts.ADDED }}</span>
            <span class="change-count" data-type="MODIFIED">修改 {{ counts.MODIFIED }}</span>
            <span class="change-count" data-type="DELETED">删除 {{ counts.DELETED }}</span>
            <span v-if="counts.UNKNOWN" class="change-count">待解析 {{ counts.UNKNOWN }}</span></p>
        <ul v-if="files.length" class="final-file-list">
            <li v-for="file in visible" :key="file.path"><span class="file-change-type" :data-type="file.changeType">{{ labels[file.changeType] }}</span>
                <code class="file-path">{{ file.path }}</code></li>
        </ul>
        <button v-if="files.length > 6" class="text-button file-list-toggle" :aria-expanded="expanded" @click="expanded = !expanded">
            {{ expanded ? '收起文件列表' : `展开全部 ${files.length} 个文件` }}</button>
    </div>
</template>
<style scoped>
.file-change-summary { min-width: 0; }
.change-counts { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 14px; margin: 12px 0; font-size: 13px; }
.change-counts strong { color: var(--ink); font-size: 14px; }
.final-file-list { list-style: none; padding: 0; margin: 8px 0; }
.final-file-list li { display: grid; grid-template-columns: auto minmax(0, 1fr); align-items: start; gap: 10px; padding: 9px 0; border-top: 1px solid var(--line); }
.file-path { min-width: 0; overflow-wrap: anywhere; line-height: 1.6; font-size: 12px; color: var(--ink); }
.file-change-type { border-radius: 4px; padding: 2px 6px; font-size: 11px; line-height: 1.6; white-space: nowrap; color: var(--muted); background: var(--canvas); }
[data-type="ADDED"] { color: var(--success); }
.file-change-type[data-type="ADDED"] { background: var(--success-bg, var(--canvas)); }
[data-type="MODIFIED"] { color: var(--accent); }
.file-change-type[data-type="MODIFIED"] { background: var(--selection); }
[data-type="DELETED"] { color: var(--danger); }
.file-change-type[data-type="DELETED"] { background: var(--danger-bg); }
.file-list-toggle { font-size: 13px; margin-top: 6px; }
</style>
