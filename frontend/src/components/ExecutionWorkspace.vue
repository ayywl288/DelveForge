<script setup lang="ts">
defineProps<{ initial: boolean }>();
</script>

<template>
    <div class="operations-workspace" :class="{ 'is-initial': initial }" :data-task-state="initial ? 'prepare' : 'activity'">
        <template v-if="initial">
            <slot name="controls" />
            <slot name="activity" />
        </template>
        <template v-else>
            <slot name="activity" />
            <slot name="controls" />
        </template>
    </div>
</template>
