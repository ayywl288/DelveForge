<script setup lang="ts">
import {
  profileSections,
  type ProfileSection,
  type Sufficiency,
  type UserProfile,
} from "../api/types";
import EvidenceList from "./EvidenceList.vue";
const props = defineProps<{
  profile: UserProfile;
  sufficiency: Sufficiency | null;
  busy: boolean;
}>();
defineEmits<{ edit: [section: ProfileSection] }>();
</script>
<template>
  <section class="profile-review" aria-labelledby="review-heading">
    <h3 id="review-heading">审阅你的画像 · 画像版本 {{ profile.revision }}</h3>
    <p>逐项核对系统整理的内容与下方原始输入依据。发现误解可以纠正，也可以返回探索；确认只针对当前版本。</p>
    <p v-if="sufficiency">
      {{ sufficiency.sufficient
        ? "本次评估认为已有足够信息供你审阅，这不表示每项提取结论已核实。"
        : "本次评估仍有缺失信息，请结合下方条目检查。" }}
    </p>
    <p v-else class="muted">已保存状态为审阅中；本页未保存当时的充分性结论，不会重构评估结果。</p>
    <section v-for="section in profileSections" :key="section.key" class="profile-section">
      <div class="section-row">
        <h4>{{ section.label }}</h4>
        <button class="text-button" :disabled="busy"
          :aria-label="`审阅时纠正${section.label}`"
          @click="$emit('edit', section.key)">纠正</button>
      </div>
      <ul v-if="profile[section.key].length">
        <li v-for="(item, index) in profile[section.key]" :key="index">{{ item }}</li>
      </ul>
      <p v-else class="muted">尚未记录；空白不代表不存在，痛点也不是每个人的必填条件。</p>
    </section>
    <h4>画像依据</h4>
    <p>请结合原始描述核对内容。整体画像确认与单条依据核对不同；依据原始字段位于技术详情。</p>
    <p class="meta">当前数据没有逐条画像内容与依据的关联字段；这里完整展示本版依据，不能推断每条内容对应哪一条依据。纠正画像不会删除旧依据。</p>
    <EvidenceList :items="profile.evidence" title="本版画像与原始输入依据" friendly />
  </section>
</template>
