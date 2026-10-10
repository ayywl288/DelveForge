<script setup lang="ts">
import { ref, watch, nextTick } from "vue";
import { profileSections, type ProfileSection } from "../api/types";
import type { WorkspaceSession } from "../state/session";
import StatusBadge from "../components/StatusBadge.vue";
import EvidenceList from "../components/EvidenceList.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import ProfileReview from "../components/ProfileReview.vue";
const props = defineProps<{ s: WorkspaceSession }>();
const emit = defineEmits<{ next: [] }>();
const input = ref("");
const reviewed = ref(false);
const editing = ref<ProfileSection | null>(null);
const editValue = ref("");
const reopen = ref(false);
const chat = ref<HTMLElement>();
const activeTab = ref<"chat" | "review">(props.s.profile?.status === "REVIEWING" ? "review" : "chat");
watch(() => props.s.profile?.status, (status, previous) => {
  if (status === "REVIEWING") activeTab.value = "review";
  if (status === "EXPLORING" && previous !== "EXPLORING") activeTab.value = "chat";
});
async function enterReview() {
  props.s.dismissReviewChoice();
  await props.s.assess();
}
async function keepExploring() {
  props.s.dismissReviewChoice();
  await continueExploring();
}
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
async function continueExploring() {
  await props.s.continueExploring();
  await nextTick();
  if (!props.s.error && chat.value) {
    chat.value.scrollTop = chat.value.scrollHeight;
    document.getElementById("discovery-input")?.focus();
  }
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
      <div class="discovery-tabs" role="group" aria-label="探索与画像视图">
        <button class="secondary" :aria-pressed="activeTab === 'chat'" @click="activeTab = 'chat'">本次对话</button>
        <button class="secondary" :aria-pressed="activeTab === 'review'" @click="activeTab = 'review'">画像审阅</button>
      </div>
      <div v-if="s.profile.status === 'EXPLORING' && s.sufficiency?.sufficient" class="readiness-banner" role="status">
        已有足够信息供你审阅。可以继续回答下一问，也可随时进入画像审阅。
        <button class="text-button" :disabled="!!s.busy" @click="enterReview">进入画像审阅</button>
      </div>
      <ProfileReview v-if="activeTab === 'review'" :profile="s.profile" :sufficiency="s.sufficiency"
        :busy="!!s.busy || s.profile.status === 'CONFIRMED'" @edit="edit" />
      <div
        v-show="activeTab === 'chat'"
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
            Agent 问题将在提交后显示，也可以主动索取下一问。
          </p>
          <button v-if="s.profile.status === 'EXPLORING'" class="secondary" :disabled="!!s.busy" @click="continueExploring">请 Agent 提出下一问</button>
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
          v-else-if="s.sufficiency?.sufficient && s.profile.status === 'EXPLORING' && !s.nextQuestion"
          class="request-note"
        >
          已达到最低审阅条件，本页没有可继续回答的下一问。可主动请 Agent 提问，或选择进入画像审阅。
        </p>
      </div>
      <form v-show="activeTab === 'chat' && s.profile.status === 'EXPLORING'" class="composer" @submit.prevent="send">
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
          {{ s.profile.status === "CONFIRMED" ? "重新开启探索" : "返回探索并提问" }}
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
      <template v-if="activeTab === 'chat'">
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
      </template>
      <p v-else class="muted">六维内容与依据已在画像审阅视图展开；请逐项核对后确认。</p>
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
        <button v-if="s.profile.status === 'EXPLORING' && s.sufficiency?.sufficient" class="secondary" :disabled="!!s.busy" @click="continueExploring">继续深入探索</button>
        <button v-else-if="s.profile.status === 'EXPLORING' && s.messages.length && !s.nextQuestion" class="secondary" :disabled="!!s.busy" @click="continueExploring">请 Agent 提出下一问</button>
        <button
          v-if="s.profile.status === 'EXPLORING'"
          class="secondary"
          :disabled="!!s.busy"
          @click="enterReview"
        >
          进入画像审阅
        </button>
        <p v-if="s.profile.status === 'EXPLORING'" class="meta">
          {{ s.sufficiency?.sufficient
            ? "审阅优先复用后端保存的本版充分结果，不重复提问。"
            : "没有本版通过结果时，审阅会调用一次 AI 评估；若仍需补充，只显示缺失信息，不向聊天插入新问题。" }}
        </p>
        <template v-if="s.profile.status === 'REVIEWING'"
          ><label class="check"
            ><input
              v-model="reviewed"
              type="checkbox"
              :disabled="!!s.busy"
            />我已审阅画像版本 {{ s.profile.revision }}，内容准确</label
          >
          <button
            :disabled="!reviewed || !!s.busy || activeTab !== 'review'"
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
  <ConfirmDialog :open="s.reviewChoicePending && s.profile?.status === 'EXPLORING'"
    title="现在可以审阅你的画像了" confirm-label="审阅并确认画像" cancel-label="继续深入探索"
    :disabled="!!s.busy" @close="keepExploring" @confirm="enterReview">
    <p>系统已对当前画像给出充分结果。你可以继续回答已有问题，或进入审阅后逐项核对并显式确认；此按钮不会自动确认。</p>
  </ConfirmDialog>
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
      新生成操作需要再次确认更新后的画像。返回探索后将调用 AI 提出一个新的问题，不恢复历史消息。
    </p>
  </ConfirmDialog>
</template>
