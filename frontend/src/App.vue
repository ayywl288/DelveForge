<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { fetchBackendConnectivity, type BackendConnectivity } from './api/system'

type ConnectivityStatus = 'checking' | 'connected' | 'failed'

const status = ref<ConnectivityStatus>('checking')
const result = ref<BackendConnectivity | null>(null)
const errorMessage = ref('')

async function checkConnectivity(): Promise<void> {
  status.value = 'checking'
  result.value = null
  errorMessage.value = ''

  try {
    result.value = await fetchBackendConnectivity()
    status.value = 'connected'
  } catch (error) {
    status.value = 'failed'
    errorMessage.value = error instanceof Error ? error.message : String(error)
  }
}

onMounted(checkConnectivity)
</script>

<template>
  <main>
    <h1>DelveForge</h1>
    <p class="subtitle">Frontend ↔ Local Backend 连通性验证</p>

    <section class="panel">
      <p class="status" :class="`status--${status}`">
        <template v-if="status === 'checking'">检查中…</template>
        <template v-else-if="status === 'connected'">Backend 已连接</template>
        <template v-else>Backend 连接失败</template>
      </p>

      <dl v-if="result" class="detail">
        <dt>service</dt>
        <dd>{{ result.service }}</dd>
        <dt>status</dt>
        <dd>{{ result.status }}</dd>
        <dt>timestamp</dt>
        <dd>{{ result.timestamp }}</dd>
      </dl>

      <p v-if="errorMessage" class="error">{{ errorMessage }}</p>

      <button type="button" :disabled="status === 'checking'" @click="checkConnectivity">
        重新检测
      </button>
    </section>
  </main>
</template>
