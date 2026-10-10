<script setup lang="ts">
import { ref, watch, nextTick } from "vue";
import { profileSections, type ProfileSection } from "../api/types";
import type { WorkspaceSession } from "../state/session";
import StatusBadge from "../components/StatusBadge.vue";
import EvidenceList from "../components/EvidenceList.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
const props = defineProps<{ s: WorkspaceSession }>();
const emit = defineEmits<{ next: [] }>();
const input = ref("");
const reviewed = ref(false);
const editing = ref<ProfileSection | null>(null);
const editValue = ref("");
const reopen = ref(false);
const chat = ref<HTMLElement>();
watch(
  () => `${props.s.profile?.id}:${props.s.profile?.revision}:${props.s.profile?.status}`,
  () => {
    reviewed.value = false;
    editing.value = null;
    input.value = "";
  },
);
watch(
  () => props.s.messages.length,
  async () => {
    await nextTick();
    if (chat.value) chat.value.scrollTop = chat.value.scrollHeight;
  },
);
async function send() {
  if (!input.value.trim() || props.s.busy) return;
  await props.s.turn(input.value.trim());
  if (!props.s.error) input.value = "";
}
function edit(key: ProfileSection) {
  editing.value = key;
  editValue.value = props.s.profile?.[key].join("\n") ?? "";
}
async function save() {
  if (!editing.value) return;
  await props.s.patch({
    [editing.value]: editValue.value
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean),
  });
  if (!props.s.error) editing.value = null;
}
</script>
<template>
  <div v-if="!s.profile" class="welcome panel">
    <span class="eyebrow">PERSONAL BEFORE GENERIC</span>
    <h2>从你的日常开始。</h2>
    <p>
      说说你想改善什么、习惯怎样工作，以及想做成什么。Agent
      会逐轮提问，把需求整理为可审阅的画像。
    </p>
    <p class="muted">
      对话会调用配置的 AI Provider；请勿输入凭据或不必要的隐私信息。
    </p>
    <button :disabled="!!s.busy" @click="s.createProfile">
      开始需求探索 <span aria-hidden="true">↗</span>
    </button>
  </div>
  <div v-else class="discovery-layout">
    <section class="panel chat-panel" aria-labelledby="chat-heading">
      <div class="panel-header">
        <h2 id="chat-heading">需求对话</h2>
        <StatusBadge :status="s.profile.status" :show-code="false" />
      </div>
      <div
        ref="chat"
        class="chat-log"
        role="log"
        aria-label="本次页面会话的探索对话"
        aria-live="polite"
      >
        <div v-if="!s.messages.length" class="chat-intro">
          <span class="orbit" aria-hidden="true">d</span>
          <h3>你的下一步，从哪里开始？</h3>
          <p>
            可以从一个反复遇到的问题，或一个想尝试的项目说起。这是页面提示，实际
            Agent 问题将在提交后显示。
          </p>
          <p class="meta">
            刷新后画像可恢复，历史对话和充分性评估没有读取
            API，页面不会重构旧消息。
          </p>
        </div>
        <article
          v-for="(message, index) in s.messages"
          :key="index"
          class="message"
          :class="message.role"
        >
          <span class="message-author">{{
            message.role === "user" ? "你" : "DelveForge · 下一轮问题"
          }}</span>
          <p>{{ message.text }}</p>
          <span v-if="message.failed" class="meta"
            >该请求未收到成功响应，已尝试重新查询画像。不要将这条消息视为已接受的结果。</span
          >
        </article>
        <p v-if="s.busy" class="request-note" role="status">
          {{ s.busy }}… 正在等待实际响应。
        </p>
        <p
          v-else-if="s.sufficiency?.sufficient && s.profile.status === 'EXPLORING'"
          class="request-note"
        >
          已达到最低审阅条件，系统没有返回下一轮问题。你可以继续补充，或选择进入审阅。
        </p>
      </div>
      <form class="composer" @submit.prevent="send">
        <label for="discovery-input">你的想法</label>
        <textarea
          id="discovery-input" name="discovery-input" autocomplete="off"
          v-model="input"
          rows="3"
          placeholder="最近有什么事情，你希望软件能帮你做得更好？"
          :disabled="!!s.busy || s.profile.status !== 'EXPLORING'"
          @keydown.ctrl.enter.prevent="send"
        />
        <div class="composer-footer">
          <span class="meta">Ctrl + Enter 发送 · 不保存聊天到浏览器</span>
          <button
            :disabled="
              !!s.busy || !input.trim() || s.profile.status !== 'EXPLORING'
            "
          >
            发送
          </button>
        </div>
      </form>
      <div v-if="s.profile.status !== 'EXPLORING'" class="inline-note">
        <p>
          当前画像{{
            s.profile.status === "CONFIRMED" ? "已确认" : "进入审阅"
          }}，发送下一轮前需显式继续探索。
        </p>
        <button class="text-button" :disabled="!!s.busy" @click="reopen = true">
          {{ s.profile.status === "CONFIRMED" ? "重新开启探索" : "继续探索" }}
        </button>
      </div>
    </section>
    <aside class="panel profile-panel" aria-labelledby="profile-heading">
      <div class="panel-header">
        <div>
          <span class="eyebrow">LIVE PROFILE</span>
          <h2 id="profile-heading">你的六维画像</h2>
        </div>
        <span class="version">画像版本 {{ s.profile.revision }}</span>
      </div>
      <section
        v-for="section in profileSections"
        :key="section.key"
        class="profile-section"
      >
        <div class="section-row">
          <h3>{{ section.label }}</h3>
          <button
            class="text-button"
            :aria-label="`纠正${section.label}`"
            :disabled="!!s.busy || s.profile.status === 'CONFIRMED'"
            @click="edit(section.key)"
          >
            纠正
          </button>
        </div>
        <ul v-if="s.profile[section.key].length">
          <li v-for="(item, index) in s.profile[section.key]" :key="index">
            {{ item }}
          </li>
        </ul>
        <p v-else class="muted">{{ section.hint }} · 尚未提取</p>
      </section>
      <EvidenceList :items="s.profile.evidence" title="画像依据" friendly />
      <div class="review-box">
        <h3>充分性与审阅</h3>
        <template v-if="s.sufficiency"
          ><p>
            {{
              s.sufficiency.sufficient
                ? "本次评估：达到最低审阅条件"
                : "本次评估：仍需补充"
            }}
          </p>
          <ul>
            <li v-for="area in s.sufficiency.missingAreas" :key="area">
              {{ area }}
            </li>
          </ul>
        </template>
        <p v-else class="muted">
          本页没有当前画像版本的充分性结果。上方状态来自保存的画像。
        </p>
        <button
          v-if="s.profile.status === 'EXPLORING'"
          class="secondary"
          :disabled="!!s.busy"
          @click="s.assess"
        >
          进入审阅（先调用 AI 评估）
        </button>
        <template v-if="s.profile.status === 'REVIEWING'"
          ><label class="check"
            ><input
              v-model="reviewed"
              type="checkbox"
              :disabled="!!s.busy"
            />我已审阅画像版本 {{ s.profile.revision }}，内容准确</label
          >
          <button
            :disabled="!reviewed || !!s.busy"
            @click="s.confirmProfile(s.profile.revision)"
          >
            确认当前画像
          </button></template
        >
        <button v-if="s.profile.status === 'CONFIRMED'" @click="emit('next')">
          理解我的仓库 <span aria-hidden="true">→</span>
        </button>
      </div>
      <details class="object-id">
        <summary>画像 ID · 用于恢复</summary>
        <code>{{ s.profile.id }}</code>
        <p>原始状态：{{ s.profile.status }} · revision={{ s.profile.revision }}</p>
      </details>
    </aside>
  </div>
  <ConfirmDialog
    :open="!!editing"
    title="纠正画像条目"
    confirm-label="保存纠正"
    :disabled="!!s.busy"
    @close="editing = null"
    @confirm="save"
  >
    <p>
      每行一个条目，空白表示清空该维度。保存会更新画像
      版本，请重新审阅。当前接口没有版本冲突检查，请避免多处同时编辑。
    </p>
    <label for="profile-edit">{{
      profileSections.find((item) => item.key === editing)?.label
    }}</label
    ><textarea id="profile-edit" name="profile-edit" autocomplete="off" v-model="editValue" rows="7" />
  </ConfirmDialog>
  <ConfirmDialog
    :open="reopen"
    title="继续探索需求？"
    confirm-label="继续探索"
    @close="reopen = false"
    @confirm="
      reopen = false;
      s.resumeDiscovery();
    "
  >
    <p>
      画像将回到探索中，可以连续补充多轮，再自主选择审阅。后续方向与计划仍保留其原始画像版本；
      新生成操作需要再次确认更新后的画像。此操作不会生成或恢复历史 Agent 问题。
    </p>
  </ConfirmDialog>
</template>
