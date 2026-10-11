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
const inspectorOpen = ref(true);
const activeTab = ref<"chat" | "review" | "confirmed">(
  props.s.profile?.status === "CONFIRMED" ? "confirmed"
    : props.s.profile?.status === "REVIEWING" ? "review" : "chat",
);
watch(() => props.s.profile?.status, (status, previous) => {
  if (status === "REVIEWING") activeTab.value = "review";
  if (status === "CONFIRMED") activeTab.value = "confirmed";
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
  <div v-else class="discovery-layout" :class="{ 'inspector-collapsed': !inspectorOpen }">
    <section class="panel chat-panel" aria-labelledby="chat-heading">
      <div class="panel-header">
        <h2 id="chat-heading">{{ s.profile.status === 'CONFIRMED' ? '已确认的需求' : activeTab === 'review' ? '画像审阅' : '需求对话' }}</h2>
        <StatusBadge :status="s.profile.status" :show-code="false" />
      </div>
      <div class="discovery-tabs" role="group" aria-label="探索与画像视图">
        <button v-if="s.profile.status !== 'CONFIRMED' || s.messages.length" class="secondary" :aria-pressed="activeTab === 'chat'" @click="activeTab = 'chat'">本次对话</button>
        <button v-if="s.profile.status !== 'CONFIRMED'" class="secondary" :aria-pressed="activeTab === 'review'" @click="activeTab = 'review'">画像审阅</button>
        <button v-else class="secondary" :aria-pressed="activeTab === 'confirmed'" @click="activeTab = 'confirmed'">确认信息</button>
      </div>
      <div v-if="activeTab === 'confirmed'" class="confirmed-profile">
        <span class="tag">画像版本 {{ s.profile.revision }}</span>
        <h3>画像已确认，可以继续理解仓库</h3>
        <p>这份画像将作为后续方向的输入。六维内容和原始依据可在右侧逐项展开查看。</p>
        <button @click="emit('next')">理解我的仓库 →</button>
        <p class="meta">当前显示的是保存的画像；刷新不会恢复历史对话。</p>
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
        :class="{ 'empty-conversation': !s.messages.length }"
        tabindex="0"
        role="log"
        aria-label="本次页面会话的探索对话"
        aria-live="polite"
      >
        <div v-if="!s.messages.length" class="chat-intro">
          <h3>从一个想改善的事情说起</h3>
          <p>
            本次会话没有聊天记录。可以直接描述一个想改善的事情，或主动请 Agent 提问。
          </p>
          <button v-if="s.profile.status === 'EXPLORING'" class="secondary" :disabled="!!s.busy" @click="continueExploring">请 Agent 提出下一问</button>
          <p class="meta">
            刷新可恢复画像与本版审阅就绪提示，聊天记录不会恢复。
          </p>
        </div>
        <article
          v-for="(message, index) in s.messages"
          :key="index"
          class="message"
          :class="message.role"
        >
          <span class="message-author">{{
            message.role === "user" ? "你" : "DelveForge"
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
      <div v-if="s.profile.status !== 'EXPLORING'" class="discovery-resume">
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
        <h2 id="profile-heading">六维画像</h2>
        <button class="text-button" :aria-expanded="inspectorOpen" aria-controls="profile-inspector"
          @click="inspectorOpen = !inspectorOpen">{{ inspectorOpen ? '收起画像' : '展开画像' }}</button>
      </div>
      <div id="profile-inspector" v-show="inspectorOpen">
      <p class="meta">画像版本 {{ s.profile.revision }} · {{ activeTab === 'review' ? '完整内容位于左侧审阅区' : s.profile.status === 'CONFIRMED' ? '逐项展开查看已确认内容' : '逐项展开查看与纠正' }}</p>
      <template v-if="activeTab !== 'review'">
      <details
        v-for="section in profileSections"
        :key="section.key"
        class="profile-section"
      >
        <summary>{{ section.label }} <span class="dimension-count">{{ s.profile[section.key].length }} 项</span></summary>
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
      <EvidenceList :items="s.profile.evidence" title="画像依据" friendly />
      </template>
      <div v-else class="inspector-index">
        <a v-for="section in profileSections" :key="section.key" :href="`#review-${section.key}`">
          {{ section.label }} <span class="meta">{{ s.profile[section.key].length }} 项</span>
        </a>
      </div>
      </div>
      <div class="review-box">
        <h3>{{ s.profile.status === 'CONFIRMED' ? '确认状态' : '充分性与审阅' }}</h3>
        <template v-if="s.sufficiency && s.profile.status !== 'CONFIRMED'"
          ><p>
            {{
              s.sufficiency.sufficient
                ? "本次评估：达到最低审阅条件"
                : "本次评估：仍需补充"
            }}
          </p>
          <ul v-if="s.sufficiency.missingAreas.length">
            <li v-for="area in s.sufficiency.missingAreas" :key="area">
              {{ area }}
            </li>
          </ul>
        </template>
        <p v-else-if="s.profile.status !== 'CONFIRMED'" class="muted">
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
            ? "审阅复用本版已保存的充分结果。"
            : "没有本版通过结果时，进入审阅会调用一次 AI 评估。" }}
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
        <p v-if="s.profile.status === 'CONFIRMED'" class="meta">当前版本已确认。修改前需显式重新开启探索。</p>
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
