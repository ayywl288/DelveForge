<script setup lang="ts">
import type { AgentCandidate, AgentRun, Delivery } from "../api/agentTypes";
import type { EvolutionStep } from "../api/types";
import type { PresentedFileChange, RunPresentation } from "./agentResultPresentation";
import StepFinalChanges from "./StepFinalChanges.vue";
import AgentTestEvidence from "./AgentTestEvidence.vue";
import FileChangeSummary from "./FileChangeSummary.vue";
import RevisionText from "./RevisionText.vue";

// 仅组合冻结结果与正式交付的阅读面；接受条件与重试操作仍由原执行页拥有。
defineProps<{
    planId: string;
    step?: EvolutionStep;
    run: AgentRun | null;
    candidate: AgentCandidate | null;
    delivery: Delivery | null;
    runPresentation: RunPresentation | null;
    officialStep: boolean;
    completedDelivery: boolean;
    candidateFiles: PresentedFileChange[];
    filesLoading: boolean;
    filesUnavailable: boolean;
    pending: boolean;
}>();
const emit = defineEmits<{ deliver: [] }>();
</script>
<template>
    <section class="panel agent-changes" aria-labelledby="agent-changes-title">
      <div class="section-row"><h3 id="agent-changes-title" tabindex="-1">代码变更与交付</h3><span v-if="candidate && runPresentation" class="meta">{{ runPresentation.label }}</span></div>
      <p v-if="!candidate" class="workbench-empty">{{ run?.candidateId
        ? '此 Run 已记录冻结 Candidate，但详情尚未读回。请刷新记录核对，暂不展示文件列表。'
        : run ? '此 Run 尚无冻结 Candidate，因此没有 Candidate 文件列表。'
          : '选择或开始运行后，可在此查看冻结 Candidate 的文件、检查证据与交付结果。' }}</p>
      <template v-else>
      <StepFinalChanges v-if="officialStep && step" :plan-id="planId" :step="step" />
      <div v-if="officialStep && step" class="accepted-revisions" aria-label="正式接受的版本">
        <RevisionText label="正式接受版本 · deliveryRevision" :value="step.deliveryRevision" />
        <RevisionText label="本步骤基线 · baselineRevision" :value="step.baselineRevision" />
      </div>
      <p v-else-if="completedDelivery" role="status">交付已完成，正在同步步骤的正式版本与文件清单。</p>
      <div v-else class="candidate-overview">
        <h4>候选变更</h4><p class="meta">以下是本次保存的编码结果，尚未完成正式交付。</p>
        <details class="candidate-identity"><summary>冻结版本与完整性</summary>
          <p>Candidate <code>{{ candidate.id }}</code></p>
          <RevisionText label="冻结基线 · baseline" :value="candidate.baseline" />
          <RevisionText label="预期文件树 · expectedTree" :value="candidate.expectedTree" />
          <p>SHA-256 <code>{{ candidate.sha256 }}</code></p>
        </details>
        <p v-if="filesLoading" role="status" class="meta">正在读取候选文件分类…</p>
        <FileChangeSummary :key="candidate.id" :files="candidateFiles" />
        <p v-if="filesUnavailable" class="meta" role="status">文件分类暂未读回，可刷新记录重试。</p>
      </div>
      <p v-if="delivery?.state === 'RECOVERY_REQUIRED'" class="resource-warning" role="alert">正式副本状态需要核对，后续执行已停止。可重新核对已有结果；只有版本、文件内容和交付历史均通过安全检查才会继续，不会重新编码。</p>
      <p v-else-if="delivery?.state === 'DELIVERY_BLOCKED'" class="resource-warning" role="alert">正式交付受阻，编码结果已保留。</p>
      <p v-if="delivery?.reason" class="delivery-diagnostic">后端记录的交付原因：<code>{{ delivery.reason }}</code></p>
      <button v-if="!delivery || ['DELIVERY_BLOCKED', 'PUBLISHED', 'COMMIT_READY', 'APPLIED'].includes(delivery.state)
        || delivery.state === 'RECOVERY_REQUIRED'" :disabled="pending"
        @click="emit('deliver')">重新核对并交付已有结果</button>
      <AgentTestEvidence :key="candidate.id" :evidence="candidate.testEvidence" />
      <p class="meta">{{ completedDelivery ? '可在项目导航的「快速启动」查看已完成计划的启动指南。' : '全部步骤正式交付后，可在项目导航的「快速启动」查看启动指南。' }}</p>
      </template>
    </section>
</template>
