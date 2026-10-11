<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { standardLicenses, validLicenseDeclaration } from "../state/licenseDeclaration";
const props = defineProps<{ id: string; modelValue: string | null; disabled: boolean }>();
const emit = defineEmits<{ "update:modelValue": [value: string | null] }>();
const kind = ref("unknown");
watch(() => props.modelValue, value => {
  if (kind.value === "custom" && value !== null) return;
  if (value === null) kind.value = "unknown";
  else if (standardLicenses.some(item => item === value)) kind.value = value;
  else kind.value = "custom";
}, { immediate: true });
const valid = computed(() => validLicenseDeclaration(props.modelValue));
function choose(event: Event) {
  const selected = (event.target as HTMLSelectElement).value;
  kind.value = selected;
  emit("update:modelValue", selected === "unknown" ? null : selected === "custom" ? "" : selected);
}
</script>
<template>
  <div class="license-declaration stack">
    <label :for="id">许可证声明</label>
    <select :id="id" :name="id" :value="kind" :disabled="disabled" @change="choose"
      :aria-describedby="`${id}-help`">
      <option value="unknown">暂未知晓（只能用于注册、分析）</option>
      <option v-for="license in standardLicenses" :key="license" :value="license">{{ license }}</option>
      <option value="custom">其他 / 自定义 / 混合许可证</option>
    </select>
    <template v-if="kind === 'custom'">
      <label :for="`${id}-text`">适用条款、来源与范围</label>
      <textarea :id="`${id}-text`" :name="`${id}-text`" :value="modelValue" rows="4"
        :disabled="disabled" :aria-invalid="!valid" :aria-describedby="`${id}-help ${id}-error`"
        placeholder="代码：许可证名称及适用目录。&#10;文档：适用条款及范围。&#10;来源：LICENSE 文件或权利人的授权声明。"
        @input="emit('update:modelValue', ($event.target as HTMLTextAreaElement).value)" />
      <p :id="`${id}-error`" v-show="!valid" class="field-error">请填写许可证名称或适用条款文字，不能只有数字、符号或空白。</p>
    </template>
    <p :id="`${id}-help`" class="meta">
      这是你的声明。系统未独立验证真实性、权属或法律兼容性。混合许可请分别说明代码与文档的来源、适用范围；选择 SPDX 名称不授予读取或使用权限。
    </p>
  </div>
</template>
