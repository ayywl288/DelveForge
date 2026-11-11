<script setup lang="ts">
import { computed } from "vue";
import { profileSections, type ProfileUpdateProposal } from "../api/types";

const props = defineProps<{ proposal: ProfileUpdateProposal; busy: boolean }>();
defineEmits<{ accept: [continueAfter: boolean]; dismiss: []; edit: [] }>();
const sections = computed(() => profileSections.filter(section => props.proposal.proposedSections[section.key]));
</script>

<template>
  <section class="profile-update-card" aria-labelledby="profile-update-heading">
    <h3 id="profile-update-heading">核对这次画像更新</h3>
    <p>建议尚未应用。接受会一起更新这些分区，忽略会保留当前内容。</p>
    <section v-for="section in sections" :key="section.key" class="profile-update-section"
      :aria-labelledby="`proposal-${section.key}`">
      <h4 :id="`proposal-${section.key}`">{{ section.label }}</h4>
      <div class="profile-update-comparison">
        <div><strong>当前内容</strong>
          <ul v-if="proposal.currentSections[section.key]?.length">
            <li v-for="(item, index) in proposal.currentSections[section.key]" :key="index">{{ item }}</li>
          </ul>
          <p v-else class="muted">未记录</p>
        </div>
        <div><strong>建议更新为</strong>
          <ul v-if="proposal.proposedSections[section.key]?.length">
            <li v-for="(item, index) in proposal.proposedSections[section.key]" :key="index">{{ item }}</li>
          </ul>
          <p v-else class="muted">清空该分区</p>
        </div>
      </div>
    </section>
    <div class="action-row" role="group" aria-label="处理画像更新建议">
      <button :disabled="busy" @click="$emit('accept', true)">接受更新并继续</button>
      <button class="secondary" :disabled="busy" @click="$emit('accept', false)">仅接受更新</button>
      <button class="text-button" :disabled="busy" @click="$emit('dismiss')">忽略建议</button>
      <button class="text-button" :disabled="busy" @click="$emit('edit')">去画像审阅中编辑</button>
    </div>
    <p class="meta">继续时会先恢复待答问题；没有待答问题才请 AI 提问，可能计费。仅接受更新不调用 AI。</p>
  </section>
</template>
