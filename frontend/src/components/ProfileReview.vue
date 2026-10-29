<script setup lang="ts">
import { profileSections, type ProfileSection, type Sufficiency, type UserProfile } from "../api/types";
import OriginalDescriptions from "./OriginalDescriptions.vue";
defineProps<{ profile: UserProfile; sufficiency: Sufficiency | null; busy: boolean }>();
defineEmits<{ edit: [section: ProfileSection] }>();
</script>
<template>
  <section class="profile-review" aria-labelledby="review-heading">
    <header class="profile-review-heading">
      <div><h2 id="review-heading" tabindex="-1">审阅你的项目需求</h2>
        <p>看看这些内容是否准确。你可以逐项修改，也可以继续聊。</p></div>
      <span class="tag">版本 {{ profile.revision }}</span>
    </header>
    <div class="profile-review-grid">
      <section v-for="section in profileSections" :key="section.key" class="profile-card"
        :aria-labelledby="`review-${section.key}`">
        <div class="section-row"><h3 :id="`review-${section.key}`">{{ section.label }}</h3>
          <button class="text-button" :disabled="busy" :aria-label="`审阅时纠正${section.label}`"
            @click="$emit('edit', section.key)">编辑</button></div>
        <ul v-if="profile[section.key].length"><li v-for="(item, index) in profile[section.key]" :key="index">{{ item }}</li></ul>
        <p v-else class="muted">暂未记录，可以留空。</p>
      </section>
    </div>
    <OriginalDescriptions :items="profile.evidence" />
  </section>
</template>
