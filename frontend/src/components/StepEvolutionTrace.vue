<script setup lang="ts">
import { computed } from "vue";
import type { EvolutionStep } from "../api/types";
import { stepEvolutionTrace } from "../state/stepEvolutionPresentation";
const props = defineProps<{ step: EvolutionStep }>();
const rounds = computed(() => stepEvolutionTrace(props.step));
function time(value: string | null) {
  if (!value) return "时间未记录";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "记录时间不可用" : date.toLocaleString("zh-CN");
}
</script>
<template>
  <section id="step-evolution-trace" class="execution-section">
    <h3>Step 演化轨迹</h3>
    <p class="meta">按已保存事实与授权归属连接；没有记录的事件和时间不会补造。</p>
    <div v-for="round in rounds" :key="round.key" class="trace-round">
      <h4>{{ round.label }}</h4>
      <ol class="step-trace">
        <li v-for="node in round.nodes" :key="node.key" class="trace-node" :data-tone="node.tone">
          <strong>{{ node.label }}</strong><span class="trace-result">{{ node.result }}</span>
          <time v-if="node.time" :datetime="node.time">{{ time(node.time) }}</time><span v-else class="meta">时间未记录</span>
          <details class="trace-details"><summary>记录详情</summary><p v-for="(detail, index) in node.details" :key="index">{{ detail }}</p></details>
        </li>
      </ol>
    </div>
  </section>
</template>
