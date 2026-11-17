<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { effortLabel, request, type ModelCatalog, type ModelChoice } from './api'
const props = defineProps<{ catalog: ModelCatalog; initial: string; choice?: ModelChoice; refresh: () => Promise<void> }>()
const provider = ref(props.initial), preset = ref(''), workspace = ref(''), key = ref('')
const modelId = ref(''), effort = ref('default'), working = ref(false), status = ref(''), failed = ref(false)
const current = computed(() => props.catalog.providers.find(p => p.id === provider.value))
const models = computed(() => props.catalog.models.filter(m => m.provider === provider.value))
const selected = computed(() => models.value.find(m => m.id === modelId.value))
const compatibility = computed(() => selected.value?.rejectedEfforts.includes(effort.value) ? '提供方确认不支持'
    : selected.value?.verifiedEfforts.includes(effort.value) ? '工具回合已验证' : '尚未验证')
watch([provider, () => current.value?.id, () => current.value?.preset, () => current.value?.workspace], () => {
    preset.value = current.value?.preset ?? ''; workspace.value = current.value?.workspace ?? ''
    key.value = ''; status.value = ''
    const preferred = props.choice?.provider === provider.value ? props.choice : undefined
    modelId.value = models.value.find(m => m.id === preferred?.model)?.id ?? models.value[0]?.id ?? ''
    effort.value = preferred && selected.value?.efforts.includes(preferred.effort) ? preferred.effort : 'default'
}, { immediate: true })
watch(modelId, () => { if (!selected.value?.efforts.includes(effort.value)) effort.value = 'default' })
async function submit(op: 'save' | 'delete' | 'validate') {
    if (working.value) return
    working.value = true; status.value = ''; failed.value = false
    let usage = ''
    try {
        if (op === 'save') {
            await request('/providers/' + provider.value, { preset: preset.value,
                workspace: preset.value.endsWith('-workspace') ? workspace.value.trim() : '', key: key.value })
        } else if (op === 'delete') {
            await request('/providers/' + provider.value + '/delete', {})
        } else {
            const result = await request<{ totalTokens?: number }>('/models/validate',
                { provider: provider.value, model: modelId.value, effort: effort.value })
            if (result?.totalTokens) usage = '（' + result.totalTokens + ' tokens）'
        }
        await props.refresh()
        status.value = op === 'save' ? '凭据已保存到本机' : op === 'delete' ? '已删除保存的凭据' : '已完成工具调用与回执验证' + usage
    } catch (e) {
        failed.value = true
        status.value = e instanceof Error ? e.message : '操作失败'
        if (op === 'validate') {
            try { await props.refresh() }
            catch { status.value += '。模型目录暂未更新。' }
        }
    }
    finally { key.value = ''; working.value = false }
}
</script>
<template>
    <section class="detail-section provider-settings">
        <h3>模型提供方</h3>
        <form class="editor-form" @submit.prevent="submit('save')">
            <label>提供方<select v-model="provider" :disabled="working">
                <option v-for="p in catalog.providers" :key="p.id" :value="p.id">{{ p.name }} · {{ p.configured ? '已配置' : '未配置' }}</option>
            </select></label>
            <label v-if="(current?.presets.length ?? 0) > 1">区域或服务端点<select v-model="preset" :disabled="working">
                <option v-for="p in current?.presets" :key="p" :value="p">{{ p }}</option></select></label>
            <label v-if="preset.endsWith('-workspace')">工作空间 ID<input v-model="workspace" required maxlength="63" :disabled="working"></label>
            <label>{{ current?.saved ? '替换 API Key' : 'API Key' }}<input v-model="key" type="password" autocomplete="off"
                spellcheck="false" maxlength="4096" required :disabled="working" placeholder="输入提供方颁发的密钥"></label>
            <div class="form-actions"><button v-if="current?.saved" type="button" :disabled="working" @click="submit('delete')">删除保存的密钥</button>
                <button class="primary" :disabled="working || !key.trim()">保存凭据</button></div>
        </form>
        <form v-if="current?.configured" class="editor-form provider-validation" @submit.prevent="submit('validate')">
            <label>验证模型<select v-model="modelId" :disabled="working">
                <option v-for="m in models" :key="m.id" :value="m.id">{{ m.name }}</option></select></label>
            <label v-if="(selected?.efforts.length ?? 0) > 1">验证思考强度<select v-model="effort" :disabled="working">
                <option v-for="v in selected?.efforts" :key="v" :value="v">{{ effortLabel[v] ?? v }}</option></select></label>
            <p class="helper">此组合{{ compatibility }}。验证会发送工具调用和回执，按提供方价格计费。</p>
            <button :disabled="working || !modelId">验证连接与工具调用</button>
        </form>
        <p v-if="working || status" :role="failed ? 'alert' : 'status'" :class="failed ? 'error-banner' : 'helper'">{{ working ? '正在处理…' : status }}</p>
    </section>
</template>
