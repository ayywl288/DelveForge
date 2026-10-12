<script setup lang="ts">
import { nextTick, onBeforeUnmount, onDeactivated, onMounted, ref, useId, watch } from "vue";
import NavigationIcon from "./NavigationIcon.vue";

const props = defineProps<{ open: boolean; title: string; description?: string; fallbackFocus?: HTMLElement | null }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
const reading = ref<HTMLElement>();
const titleId = useId();
let returnFocus: HTMLElement | null = null;

function close() {
    if (!dialog.value?.open) return;
    dialog.value.close();
    if (returnFocus?.isConnected) returnFocus.focus({ preventScroll: true });
    else if (props.fallbackFocus?.isConnected) props.fallbackFocus.focus({ preventScroll: true });
}
async function sync() {
    if (!props.open) {
        close();
        return;
    }
    if (dialog.value?.open) return;
    returnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    dialog.value?.showModal();
    await nextTick();
    if (reading.value) reading.value.scrollTop = 0;
}
watch(() => props.open, sync);
onMounted(sync);
onDeactivated(() => { close(); emit("close"); });
onBeforeUnmount(close);
</script>
<template>
    <dialog ref="dialog" class="detail-panel" :aria-labelledby="titleId"
        @cancel.prevent="emit('close')">
        <header class="detail-panel-heading">
            <div><span class="meta">详情阅读</span><h2 :id="titleId">{{ title }}</h2></div>
            <button class="secondary detail-panel-close" autofocus aria-label="关闭详情" @click="emit('close')">
                <NavigationIcon name="close" /><span>关闭</span>
            </button>
        </header>
        <div ref="reading" class="detail-panel-reading" tabindex="0" role="region" :aria-label="title">
            <p v-if="description" class="detail-panel-description meta">{{ description }}</p>
            <slot />
        </div>
    </dialog>
</template>
