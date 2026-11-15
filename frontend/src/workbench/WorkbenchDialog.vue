<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import Icon from '../components/NavigationIcon.vue'
defineProps<{ title: string; drawer?: boolean }>()
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => { if (dialog.value?.open) dialog.value.close() })
function dismiss() { dialog.value?.close() }
function backdrop(event: MouseEvent) {
    const el = dialog.value
    if (!el || event.target !== el) return
    const r = el.getBoundingClientRect()
    if (event.clientX < r.left || event.clientX > r.right || event.clientY < r.top || event.clientY > r.bottom) dismiss()
}
</script>
<template>
    <dialog ref="dialog" class="wb-dialog" :class="{ drawer }" aria-labelledby="dialog-title"
        @close="emit('close')" @click="backdrop">
        <header><h2 id="dialog-title">{{ title }}</h2><button type="button" class="icon-btn" aria-label="关闭" @click="dismiss"><Icon name="close" /></button></header>
        <div class="dialog-body"><slot /></div>
    </dialog>
</template>
