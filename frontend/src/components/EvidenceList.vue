<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import type { Evidence, EvidenceBasis } from "../api/types";
import NavigationIcon from "./NavigationIcon.vue";

const props = defineProps<{
    items?: Evidence[];
    bases?: EvidenceBasis[];
    groups?: { label: string; bases: EvidenceBasis[] }[];
    title?: string;
    friendly?: boolean;
    expanded?: boolean;
}>();
const pageSize = 6;
const page = ref(1);
const openRecord = ref<number | null>(null);
const pageStatus = ref<HTMLElement>();
const records = computed(() => {
    const entries = props.groups
        ? props.groups.flatMap(group => group.bases.map(basis => ({ ...basis, categories: [group.label] })))
        : (props.bases ?? (props.items ?? []).map(evidence => ({ evidence, origin: null })))
            .map(basis => ({ ...basis, categories: [] as string[] }));
    const unique = new Map<string, typeof entries[number]>();
    for (const entry of entries) {
        const { evidence, origin } = entry;
        const key = JSON.stringify([evidence.sourceType, evidence.sourceRef, evidence.claim,
            evidence.confidence, evidence.confirmed, origin?.kind, origin?.userProfileId,
            origin?.userProfileRevision, origin?.repositoryProfileId]);
        const existing = unique.get(key);
        if (existing) existing.categories = [...new Set([...existing.categories, ...entry.categories])];
        else unique.set(key, { ...entry });
    }
    return [...unique.values()];
});
const pageCount = computed(() => Math.max(1, Math.ceil(records.value.length / pageSize)));
const firstIndex = computed(() => (page.value - 1) * pageSize);
const visibleRecords = computed(() => records.value.slice(firstIndex.value, firstIndex.value + pageSize)
        .map((item, offset) => ({ ...item, index: firstIndex.value + offset })));
const sources: Record<string, string> = {
    USER_INPUT: "来自用户描述",
    REPOSITORY: "来自仓库文件",
};

// Evidence 没有独立 ID；集合或原文变化时重置本地页码与展开项，避免复用另一份依据的查看状态。
watch(records, () => {
    page.value = 1;
    openRecord.value = null;
}, { deep: true });

async function changePage(value: number) {
    page.value = Math.max(1, Math.min(pageCount.value, value));
    openRecord.value = null;
    await nextTick();
    pageStatus.value?.focus({ preventScroll: true });
    pageStatus.value?.scrollIntoView?.({ block: "start" });
}
function selectPage(event: Event) {
    changePage(Number((event.target as HTMLSelectElement).value));
}
function toggleRecord(index: number, event: Event) {
    const detail = event.currentTarget as HTMLDetailsElement;
    if (!detail.isConnected) return;
    if (detail.open) openRecord.value = index;
    else if (openRecord.value === index) openRecord.value = null;
}
</script>
<template>
    <details class="evidence" :open="expanded">
        <summary>
            <NavigationIcon name="evidence" />{{ title ?? "查看依据" }}
            <span class="count-pill">{{ records.length }}</span>
        </summary>
        <p v-if="!records.length" class="muted">此项没有可追溯的依据。</p>
        <template v-else>
            <p ref="pageStatus" class="evidence-page-status meta" tabindex="-1" aria-live="polite">
                <template v-if="pageCount > 1">第 {{ page }} / {{ pageCount }} 页 ·
                    第 {{ firstIndex + 1 }}–{{ Math.min(firstIndex + pageSize, records.length) }} 条 · </template>
                共 {{ records.length }} 条
            </p>
            <article v-for="item in visibleRecords" :key="item.index" class="evidence-entry"
                    :data-record-index="item.index + 1">
                <div class="evidence-source meta">
                    <NavigationIcon :name="item.evidence.sourceType === 'REPOSITORY' ? 'file' : 'discovery'" />
                    {{ sources[item.evidence.sourceType] ?? "其他来源" }}
                    <span v-for="category in item.categories" :key="category" class="evidence-category">{{ category }}</span>
                </div>
                <details class="evidence-record" :open="openRecord === item.index"
                        @toggle="toggleRecord(item.index, $event)">
                    <summary>
                        <span class="evidence-record-index">{{ item.index + 1 }}</span>
                        <span class="evidence-preview evidence-claim">{{ item.evidence.claim }}</span>
                    </summary>
                    <p class="meta">{{ item.evidence.sourceType === 'USER_INPUT' ? '原始描述' : '来源引用' }}</p>
                    <blockquote v-if="item.evidence.sourceType === 'USER_INPUT'" class="source-reference">{{ item.evidence.sourceRef }}</blockquote>
                    <code v-else class="source-reference">{{ item.evidence.sourceRef }}</code>
                    <p v-if="item.origin" class="meta">
                        {{ item.origin.kind === 'USER_PROFILE' ? '用户画像' : '仓库分析' }}
                        <span v-if="item.origin.userProfileRevision != null"> · 版本 {{ item.origin.userProfileRevision }}</span>
                    </p>
                </details>
            </article>
            <nav v-if="pageCount > 1" class="evidence-pagination" :aria-label="`${title ?? '依据'}分页`">
                <button class="secondary" type="button" :disabled="page === 1" @click="changePage(page - 1)">上一页</button>
                <label>页码
                    <select :value="page" @change="selectPage">
                        <option v-for="number in pageCount" :key="number" :value="number">{{ number }} / {{ pageCount }}</option>
                    </select>
                </label>
                <button class="secondary" type="button" :disabled="page === pageCount" @click="changePage(page + 1)">下一页</button>
            </nav>
        </template>
    </details>
</template>
