<script setup lang="ts">
import { computed } from "vue";
import type { EvolutionPlan, EvolutionStep, WorkingCopy } from "../api/types";
import StatusBadge from "./StatusBadge.vue";
const props = defineProps<{ plan: EvolutionPlan; step: EvolutionStep; copy: WorkingCopy | null; execution?: boolean }>();
const previous = computed(() => props.plan.steps.slice(0, props.plan.steps.indexOf(props.step)));
</script>
<template>
  <section class="step-prerequisites">
    <h3>操作前提</h3>
    <p class="meta">按当前记录核对顺序与副本状态，最终以后端校验为准。</p>
    <ul>
      <li>计划需处于进行中：<StatusBadge :status="plan.status" :show-code="false" /></li>
      <li>隔离副本需就绪或演化中：<StatusBadge v-if="copy" :status="copy.status" :show-code="false" /><span v-else>尚未创建或读取</span></li>
      <li v-if="!previous.length">当前是第一步，无前序 Step。</li>
      <li v-for="(item, index) in previous" :key="item.id">
        前序步骤 {{ index + 1 }} 需验证通过：{{ item.goal }} <StatusBadge :status="item.status" :show-code="false" />
      </li>
      <li v-if="execution">执行前当前 revision 需等于最近验证 revision：
        {{ !copy ? '尚未读取' : copy.currentRevision === copy.lastVerifiedRevision ? '当前记录一致' : '当前记录不一致，禁止执行' }}
      </li>
    </ul>
  </section>
</template>
