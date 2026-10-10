<script setup lang="ts">
import { nextTick, onMounted, ref, useId, watch } from "vue";
const props = defineProps<{
  open: boolean;
  title: string;
  confirmLabel: string;
  disabled?: boolean;
  cancelLabel?: string;
}>();
const emit = defineEmits<{ close: []; confirm: [] }>();
const dialog = ref<HTMLDialogElement>();
const titleId = useId();
let returnFocus: HTMLElement | null = null;
function sync() {
  if (props.open && !dialog.value?.open) {
    returnFocus =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    dialog.value?.showModal();
  }
  if (!props.open && dialog.value?.open) {
    dialog.value.close();
    nextTick(() => {
      if (returnFocus?.isConnected) returnFocus.focus();
    });
  }
}
watch(() => props.open, sync);
onMounted(sync);
</script>
<template>
  <dialog
    ref="dialog"
    :aria-labelledby="titleId"
    @cancel.prevent="emit('close')"
  >
    <h2 :id="titleId">{{ title }}</h2>
    <div class="dialog-body"><slot /></div>
    <div class="actions">
      <button class="secondary" autofocus @click="emit('close')">
        {{ cancelLabel ?? "返回审阅" }}
      </button>
      <button :disabled="disabled" @click="emit('confirm')">
        {{ confirmLabel }}
      </button>
    </div>
  </dialog>
</template>
