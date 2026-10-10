<script setup lang="ts">
withDefaults(defineProps<{ status: string; showCode?: boolean }>(), {
  showCode: true,
});
const labels: Record<string, string> = {
  EXPLORING: "探索中",
  REVIEWING: "待审阅",
  CONFIRMED: "已确认",
  CANDIDATE: "候选",
  SELECTED: "已选择",
  REJECTED: "已拒绝",
  SUPERSEDED: "已替代",
  PROPOSED: "待审阅计划",
  ACTIVE: "进行中",
  COMPLETED: "已完成",
  PENDING_CONFIRMATION: "待 Step 授权",
  READY: "就绪",
  EXECUTING: "正在执行",
  VERIFYING: "正在验证",
  SUCCEEDED: "验证通过",
  FAILED: "失败",
  VERIFICATION_FAILED: "验证失败",
  RECOVERY_REQUIRED: "需要恢复",
  CANCELLED: "已取消",
  CREATING: "创建中",
  EVOLVING: "演化中",
  CLOSED: "已关闭",
  UNRECOVERABLE: "不可恢复",
  ABANDONED: "已放弃",
  ROLLING_BACK: "正在回滚",
};
</script>
<template>
  <span class="badge" :data-status="status"
    >{{ labels[status] ?? status }} <small v-if="showCode">{{ status }}</small></span
  >
</template>
