<script setup lang="ts">
import { ref, watch } from "vue";
import type { SoftwareAsset, AssetAuthorizationInput } from "../api/types";
const props = defineProps<{ asset: SoftwareAsset; busy: boolean }>();
const emit = defineEmits<{ submit: [input: AssetAuthorizationInput] }>();
const read = ref(false);
const license = ref("");
const usage = ref<SoftwareAsset["usageAuthorization"]>("UNCLEAR");
const acknowledged = ref(false);
watch(() => props.asset, value => {
  read.value = value.readPermissionAllowed;
  license.value = value.licenseInfo ?? "";
  usage.value = value.usageAuthorization;
  acknowledged.value = false;
}, { immediate: true });
function submit() {
  if (!acknowledged.value || props.busy) return;
  emit("submit", {
    expectedAuthorizationRevision: props.asset.authorizationRevision,
    readPermissionAllowed: read.value,
    licenseInfo: license.value.trim() || null,
    usageAuthorization: usage.value,
  });
}
</script>
<template>
  <details class="authorization-editor">
    <summary>完善许可证与授权</summary>
    <form class="stack" @submit.prevent="submit">
      <p class="meta">保留已有资产、分析和方向。授权版本 {{ asset.authorizationRevision }}。</p>
      <label class="check"><input type="checkbox" v-model="read" :disabled="busy" />允许读取和分析此仓库</label>
      <label :for="`asset-license-${asset.id}`">许可证信息及适用范围</label>
      <textarea :id="`asset-license-${asset.id}`" v-model="license" rows="3" :disabled="busy"
        placeholder="按实际文件记录代码、文字等各自的许可与限制；未知可留空" />
      <p class="meta">演化规划要求明确记录许可证；非空文字不代表系统认定许可合法、兼容或可商用。发现 LICENSE 文件不会自动产生使用授权。</p>
      <label :for="`asset-usage-${asset.id}`">复用 / 二次开发授权</label>
      <select :id="`asset-usage-${asset.id}`" v-model="usage" :disabled="busy" required>
        <option value="ALLOWED">已明确允许</option>
        <option value="DENIED">明确不允许</option>
        <option value="UNCLEAR">尚未确认</option>
      </select>
      <label class="check"><input type="checkbox" v-model="acknowledged" :disabled="busy" />我已核对适用条款，明确提交以上读取许可与使用授权</label>
      <button :disabled="busy || !acknowledged">保存本次授权信息</button>
    </form>
  </details>
</template>
