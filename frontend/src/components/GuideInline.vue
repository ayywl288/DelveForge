<script setup lang="ts">
import { computed } from "vue";
const props = defineProps<{ text: string }>();
const tokens = computed(() => {
    const pattern = /(`[^`]+`|\*\*[^*]+\*\*|\[[^\]]+\]\(https?:\/\/[^\s)]+\))/g;
    return props.text.split(pattern).filter(Boolean).map(text => {
        if (text.startsWith("`")) return { kind: "code", text: text.slice(1, -1), href: "" };
        if (text.startsWith("**")) return { kind: "strong", text: text.slice(2, -2), href: "" };
        const link = /^\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)$/.exec(text);
        return link ? { kind: "link", text: link[1], href: link[2] } : { kind: "text", text, href: "" };
    });
});
</script>
<template><template v-for="(token, index) in tokens" :key="index"><code v-if="token.kind === 'code'">{{ token.text }}</code><strong v-else-if="token.kind === 'strong'">{{ token.text }}</strong><a v-else-if="token.kind === 'link'" :href="token.href" target="_blank" rel="noopener noreferrer">{{ token.text }}</a><template v-else>{{ token.text }}</template></template></template>
