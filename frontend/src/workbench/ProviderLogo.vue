<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ provider: string }>()
const icons = import.meta.glob<string>('../assets/providers/*.svg', { eager: true, query: '?raw', import: 'default' })
const svg = computed(() => {
    const name = ({ google: 'gemini', zai: 'zhipu' } as Record<string, string>)[props.provider] ?? props.provider
    return icons['../assets/providers/' + name + '.svg']
})
</script>
<template>
    <span v-if="svg" class="provider-logo" aria-hidden="true" v-html="svg" />
    <span v-else class="provider-initial" aria-hidden="true">{{ provider.slice(0, 1).toUpperCase() }}</span>
</template>
