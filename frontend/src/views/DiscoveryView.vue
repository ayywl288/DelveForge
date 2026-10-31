<script setup lang="ts">
import { computed, ref, watch, nextTick } from "vue";
import { profileSections, type ProfileSection } from "../api/types";
import type { WorkspaceSession } from "../state/session";
import StatusBadge from "../components/StatusBadge.vue";
import OriginalDescriptions from "../components/OriginalDescriptions.vue";
import ConfirmDialog from "../components/ConfirmDialog.vue";
import NavigationIcon, { type NavigationIconName } from "../components/NavigationIcon.vue";
import ProfileReview from "../components/ProfileReview.vue";
const props = defineProps<{ s: WorkspaceSession }>();
const emit = defineEmits<{ next: [] }>();
const input = ref("");
const canSend = computed(() => props.s.profile?.status === "EXPLORING" && !!input.value.trim() &&
  !props.s.busy && !props.s.discoveryUncertain && !props.s.conversationLoading);
const reviewed = ref(false);
const editing = ref<ProfileSection | null>(null);
const editValue = ref("");
const reopen = ref(false);
const chat = ref<HTMLElement>();
const inspectorOpen = ref(true);
const dimensionIcons: Record<ProfileSection, NavigationIconName> = {
  interests: "heart", behaviors: "activity", painPoints: "warning", technicalCapabilities: "code",
  projectGoals: "target", constraints: "lock",
};
const activeTab = ref<"chat" | "review">(
  props.s.profile?.status === "CONFIRMED" || props.s.profile?.status === "REVIEWING" ? "review" : "chat",
);
watch(() => props.s.profile?.status, (status, previous) => {
  if (status === "REVIEWING") activeTab.value = "review";
  if (status === "CONFIRMED") activeTab.value = "review";
  if (status === "EXPLORING" && previous !== "EXPLORING") activeTab.value = "chat";
});
watch(() => props.s.discoveryPending, (pending) => {
  if (pending) activeTab.value = "chat";
  else if (props.s.profile?.status === "CONFIRMED") activeTab.value = "review";
  else if (props.s.profile?.status === "REVIEWING") activeTab.value = "review";
});
async function enterReview() {
  await props.s.assess();
}
watch(
  () => `${props.s.profile?.id}:${props.s.profile?.revision}:${props.s.profile?.status}`,
  () => {
    reviewed.value = false;
    editing.value = null;
  },
);
watch(() => props.s.profile?.id, () => { input.value = ""; });
watch(
  () => [props.s.messages.length, props.s.discoveryPending],
  async () => {
    await nextTick();
    if (chat.value) chat.value.scrollTop = chat.value.scrollHeight;
  },
);
watch(activeTab, async value => {
  await nextTick();
  document.getElementById(value === "review" ? "review-heading" : "discovery-input")?.focus();
});
async function loadOlder() {
  const height = chat.value?.scrollHeight ?? 0;
  await props.s.loadConversation(true);
  await nextTick();
  if (chat.value) {
    chat.value.scrollTop = chat.value.scrollHeight - height;
    chat.value.focus({ preventScroll: true });
  }
}
async function send() {
  if (!canSend.value) return;
  const count = props.s.messages.length;
  const request = props.s.turn(input.value.trim());
  if (props.s.messages.length > count) input.value = "";
  await request;
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
  <div v-if="!s.profile" class="welcome panel discovery-welcome">
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
  <div v-else class="discovery-layout discovery-workspace" :class="{ 'inspector-collapsed': !inspectorOpen, 'review-layout': activeTab === 'review' }">
    <section class="panel chat-panel transcript-surface" :class="{ 'review-dossier': activeTab === 'review' }" aria-labelledby="chat-heading">
      <div class="transcript-toolbar">
      <div class="panel-header">
        <h2 id="chat-heading">{{ s.profile.status === 'CONFIRMED' ? '已确认的需求' : activeTab === 'review' ? '项目需求' : '需求对话' }}</h2>
        <StatusBadge :status="s.profile.status" :show-code="false" />
      </div>
      <div class="discovery-tabs" role="group" aria-label="探索与画像视图">
        <button v-if="s.profile.status !== 'CONFIRMED' || s.messages.length" class="secondary" :aria-pressed="activeTab === 'chat'" @click="activeTab = 'chat'">本次对话</button>
        <button v-if="s.profile.status !== 'CONFIRMED'" class="secondary" :aria-pressed="activeTab === 'review'" @click="activeTab = 'review'">画像审阅</button>
        <button v-else class="secondary" :aria-pressed="activeTab === 'review'" @click="activeTab = 'review'">查看已确认需求</button>
      </div>
      </div>
      <ProfileReview v-if="activeTab === 'review'" :profile="s.profile" :sufficiency="s.sufficiency"
        :busy="!!s.busy || s.profile.status === 'CONFIRMED'" @edit="edit" />
      <div v-if="activeTab === 'review'" class="review-actions">
        <template v-if="s.profile.status === 'REVIEWING'">
          <label class="check"><input v-model="reviewed" type="checkbox" :disabled="!!s.busy" />
            我已审阅画像版本 {{ s.profile.revision }}，内容准确</label>
          <div class="action-row">
            <button class="secondary" :disabled="!!s.busy" @click="reopen = true">继续探索</button>
            <button :disabled="!reviewed || !!s.busy" @click="s.confirmProfile(s.profile.revision)">确认当前画像</button>
          </div>
        </template>
        <template v-else-if="s.profile.status === 'CONFIRMED'">
          <p>需求已确认，可以继续理解仓库。</p>
          <button class="secondary" :disabled="!!s.busy" @click="reopen = true">重新开启探索</button>
          <button @click="emit('next')">理解我的仓库</button>
        </template>
        <template v-else>
          <p v-if="s.sufficiency && !s.sufficiency.sufficient" class="muted">{{ s.sufficiency.missingAreas.join('；') }}</p>
          <button class="secondary" @click="activeTab = 'chat'">继续对话</button>
          <button :disabled="!!s.busy || s.discoveryUncertain" @click="enterReview">进入画像审阅</button>
        </template>
      </div>
      <p v-if="s.conversationLoading && activeTab === 'chat'" class="request-note" role="status">正在读取已保存的对话…</p>
      <p v-if="s.conversationError && activeTab === 'chat'" class="inline-note" role="alert">{{ s.conversationError }}
        <button class="text-button" :disabled="!!s.busy" @click="s.loadConversation()">重新读取对话</button></p>
      <p v-if="s.discoveryUncertain && !s.discoveryPending && activeTab === 'chat'" class="request-note">
        上一轮结果待核对。<button class="text-button" :disabled="!!s.busy" @click="s.checkDiscoveryStatus">查询本轮结果</button>
        <button class="text-button" :disabled="!!s.busy" @click="s.abandonDiscovery">放弃未完成的本轮</button>
      </p>
      <div
        v-show="activeTab === 'chat'"
        ref="chat"
        class="chat-log"
        :class="{ 'empty-conversation': !s.messages.length && !s.discoveryPending }"
        tabindex="0"
        role="log"
        aria-label="已保存的需求对话"
        aria-live="polite"
      >
        <button v-if="s.conversationBefore" class="text-button" :disabled="s.conversationLoading" @click="loadOlder">查看更早对话</button>
        <div v-if="!s.messages.length && !s.discoveryPending && !s.conversationLoading && !s.conversationError" class="chat-intro">
          <span class="feature-symbol"><NavigationIcon name="discovery" /></span>
          <h3>从一个想改善的事情说起</h3>
          <p>
            这份需求还没有保存的对话。可以直接描述想法，或主动请 Agent 提问。
          </p>
          <button v-if="s.profile.status === 'EXPLORING'" class="secondary" :disabled="!!s.busy" @click="continueExploring">请 Agent 提出下一问</button>
          <p class="meta">
            已接受的对话会保存在本地服务中，重新打开项目后可以继续。
          </p>
        </div>
        <article
          v-for="(message, index) in s.messages"
          :key="index"
          class="message"
          :class="[message.role, message.state]"
          :data-state="message.state"
        >
          <header class="message-turn-header">
          <span class="message-author sr-only">{{
            message.role === "user" ? "你说：" : "DelveForge："
          }}</span>
          <span class="message-speaker" aria-hidden="true">{{ message.role === 'user' ? '你' : 'DelveForge' }}</span>
          <time v-if="message.createdAt" class="meta" :datetime="message.createdAt">{{ new Date(message.createdAt).toLocaleString('zh-CN') }}</time>
          </header>
          <p style="white-space: pre-wrap">{{ message.text }}</p>
          <span v-if="message.state === 'pending'" class="message-state">
            <span class="message-spinner" aria-hidden="true"></span>正在提交
          </span>
          <span v-else-if="message.state === 'accepted'" class="message-state accepted-state" role="img"
            aria-label="本轮请求已获服务端接受，不表示送达或已读回执" title="服务端已接受本轮请求">
            <svg viewBox="0 0 24 16" aria-hidden="true"><path d="m1 8 4 4L15 2M10 9l3 3L23 2" /></svg>
          </span>
          <div v-else-if="message.failed" class="message-error">
            <p>{{ message.state === 'failed'
              ? '本轮未被接受。可以核对画像后编辑并重新发送。'
              : '未收到成功响应，结果尚不确定。请核对画像；不会自动重发。' }}</p>
            <button type="button" class="text-button" :disabled="!!s.busy" @click="s.checkDiscoveryStatus">查询画像状态</button>
          </div>
        </article>
        <div v-if="s.discoveryPending" class="message agent assistant-waiting">
          <span class="sr-only">DelveForge 正在整理你的想法…</span>
          <span class="waiting-label" aria-hidden="true">正在整理你的想法</span>
          <span class="waiting-dots" aria-hidden="true"><i></i><i></i><i></i></span>
        </div>
        <p v-else-if="s.busy" class="request-note" role="status">
          {{ s.busy }}… 正在等待实际响应。
        </p>
        <p
          v-else-if="s.sufficiency?.sufficient && s.profile.status === 'EXPLORING' && !s.nextQuestion"
          class="request-note"
        >
          已达到最低审阅条件。你可以继续补充想法、主动请 Agent 提问，或选择进入画像审阅。
        </p>
      </div>
      <form v-show="activeTab === 'chat' && s.profile.status === 'EXPLORING'" class="composer" @submit.prevent="send">
        <label for="discovery-input">你的想法</label>
        <textarea
          id="discovery-input" name="discovery-input" autocomplete="off"
          v-model="input"
          rows="3"
          placeholder="最近有什么事情，你希望软件能帮你做得更好？"
          :disabled="s.profile.status !== 'EXPLORING'"
          @keydown.ctrl.enter.prevent="send"
        />
        <div class="composer-footer">
          <span class="meta">{{ s.discoveryPending ? '可继续写草稿，当前轮结束后再发送' : s.discoveryUncertain ? '上一轮结果待核对，暂不发送新一轮' : 'Ctrl + Enter 发送 · 对话保存在本地服务中' }}</span>
          <button
            :disabled="!canSend"
          >
            发送
          </button>
        </div>
      </form>
      <div v-if="activeTab === 'chat' && s.profile.status !== 'EXPLORING'" class="discovery-resume">
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
    <aside v-if="activeTab === 'chat'" class="panel profile-panel revision-notebook" aria-labelledby="profile-heading">
      <div class="notebook-heading">
      <div class="panel-header">
        <h2 id="profile-heading">需求摘要</h2>
        <button class="text-button" :aria-expanded="inspectorOpen" aria-controls="profile-inspector"
          @click="inspectorOpen = !inspectorOpen">{{ inspectorOpen ? '收起画像' : '展开画像' }}</button>
      </div>
      <p class="meta">画像版本 {{ s.profile.revision }} · {{ s.profile.status === 'CONFIRMED' ? '逐项展开查看已确认内容' : '逐项展开查看与纠正' }}</p>
      <div class="review-box notebook-review">
        <div v-if="s.profile.status === 'EXPLORING' && s.sufficiency?.sufficient" class="readiness-banner" role="status">
          已有足够信息供你审阅。可以继续补充想法，也可随时进入画像审阅。
        </div>
        <p v-if="s.sufficiency && !s.sufficiency.sufficient" class="muted">{{ s.sufficiency.missingAreas.join('；') }}</p>
        <button v-if="s.profile.status === 'EXPLORING'" :disabled="!!s.busy || s.discoveryUncertain" @click="enterReview">进入画像审阅</button>
        <button v-if="s.profile.status === 'EXPLORING'" class="secondary" :disabled="!!s.busy || s.discoveryUncertain" @click="continueExploring">
          {{ s.nextQuestion ? '继续回答已有问题' : '请 Agent 提出下一问' }}</button>
      </div>
      </div>
      <div id="profile-inspector" v-show="inspectorOpen">
      <details
        v-for="section in profileSections"
        :key="section.key"
        class="profile-section"
      >
        <summary><NavigationIcon :name="dimensionIcons[section.key]" />
          <span class="dimension-name">{{ section.label }} <span class="dimension-count">{{ s.profile[section.key].length }} 项</span></span>
          <span class="dimension-preview">{{ s.profile[section.key][0] ?? '尚未记录' }}</span>
        </summary>
        <div class="section-row">
          <span class="meta">{{ section.hint }}</span>
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
      </details>
      <OriginalDescriptions :items="s.profile.evidence" />
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
      版本；如其他页面已修改，会提示重新核对。
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
      新生成操作需要再次确认更新后的画像。已保存的对话与未回答问题会保留。
    </p>
  </ConfirmDialog>
</template>
