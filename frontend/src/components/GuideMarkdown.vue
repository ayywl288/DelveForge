<script setup lang="ts">
import { computed, onUnmounted, ref } from "vue";
import GuideInline from "./GuideInline.vue";
import NavigationIcon from "./NavigationIcon.vue";
const props = defineProps<{ markdown: string }>();
type Block = { kind: "code" | "heading" | "paragraph" | "list"; text: string; level?: number; items?: string[] };
// 小型 Markdown 展示子集：全部内容由 Vue 文本节点转义，不接受 HTML 或远程资源。
const blocks = computed(() => {
    const result: Block[] = [];
    const lines = props.markdown.replace(/\r/g, "").split("\n");
    for (let i = 0; i < lines.length; i++) {
        const line = lines[i]!;
        if (line.startsWith("```")) {
            const content: string[] = [];
            while (++i < lines.length && !lines[i]!.startsWith("```")) content.push(lines[i]!);
            result.push({ kind: "code", text: content.join("\n") });
        } else if (/^#{1,6} /.test(line)) {
            const level = line.indexOf(" ");
            result.push({ kind: "heading", text: line.slice(level + 1), level: Math.min(level + 1, 6) });
        } else if (/^\s*(?:[-*]|\d+\.) /.test(line)) {
            const items = [line.replace(/^\s*(?:[-*]|\d+\.) /, "")];
            while (i + 1 < lines.length && /^\s*(?:[-*]|\d+\.) /.test(lines[i + 1]!)) items.push(lines[++i]!.replace(/^\s*(?:[-*]|\d+\.) /, ""));
            result.push({ kind: "list", text: "", items });
        } else if (line.trim()) {
            result.push({ kind: "paragraph", text: line });
        }
    }
    return result;
});
const copied = ref(-1);
const error = ref("");
let resetCopy: ReturnType<typeof setTimeout> | undefined;
onUnmounted(() => clearTimeout(resetCopy));
async function copy(text: string, index: number) {
    try {
        await navigator.clipboard.writeText(text);
        clearTimeout(resetCopy);
        copied.value = index;
        error.value = "";
        resetCopy = setTimeout(() => { copied.value = -1; }, 1800);
    }
    catch { error.value = "未能复制，请选中命令后复制。"; }
}
</script>
<template>
    <article class="guide-markdown">
        <template v-for="(block, index) in blocks" :key="index">
            <div v-if="block.kind === 'code'" class="guide-command">
                <button class="guide-copy" type="button" :aria-label="copied === index ? 'Copied' : 'Copy'"
                    :data-copied="copied === index" @click="copy(block.text, index)">
                    <NavigationIcon :name="copied === index ? 'check' : 'copy'" />
                    <span class="copy-tooltip" aria-hidden="true">{{ copied === index ? 'Copied' : 'Copy' }}</span>
                </button>
                <pre tabindex="0"><code>{{ block.text }}</code></pre>
            </div>
            <component v-else-if="block.kind === 'heading'" :is="`h${block.level}`"><GuideInline :text="block.text" /></component>
            <ul v-else-if="block.kind === 'list'"><li v-for="(item, itemIndex) in block.items" :key="itemIndex"><GuideInline :text="item" /></li></ul>
            <p v-else><GuideInline :text="block.text" /></p>
        </template>
        <p v-if="error" role="alert">{{ error }}</p><span class="sr-only" role="status">{{ copied >= 0 ? '命令已复制' : '' }}</span>
    </article>
</template>
<style scoped>
.guide-markdown { max-width: 74ch; line-height: 1.8; overflow-wrap: anywhere; }
.guide-markdown :is(h2,h3,h4) { margin: 28px 0 10px; line-height: 1.4; }
.guide-markdown h2 { font-size: 21px; }.guide-markdown h3 { font-size: 17px; }
.guide-command { position: relative; margin: 16px 0; padding: 12px; background: var(--canvas); border: 1px solid var(--line); border-radius: 8px; }
.guide-command pre { margin: 0; padding-right: 32px; min-height: 28px; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 13px; }
.guide-copy { position: absolute; top: 8px; right: 8px; display: grid; place-items: center; width: 28px; height: 28px; min-height: 0; padding: 0; border: 0; border-radius: 5px; background: transparent; color: var(--muted); }
.guide-copy:hover:not(:disabled), .guide-copy:focus-visible { background: var(--line); color: var(--ink); }
.guide-copy .navigation-icon { width: 15px; height: 15px; }
.guide-copy[data-copied="true"] { color: var(--success); }
.copy-tooltip { position: absolute; bottom: calc(100% + 6px); right: 0; padding: 3px 7px; border-radius: 4px; background: var(--nav); color: white; font-size: 11px; line-height: 1.5; white-space: nowrap; pointer-events: none; opacity: 0; }
.guide-copy:hover .copy-tooltip, .guide-copy:focus-visible .copy-tooltip { opacity: 1; }
</style>
