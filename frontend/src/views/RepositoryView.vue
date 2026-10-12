<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { WorkspaceSession } from "../state/session";
import type { AssetAuthorizationInput, UsageAuthorization } from "../api/types";
import ContentList from "../components/ContentList.vue";
import EvidenceList from "../components/EvidenceList.vue";
import RevisionText from "../components/RevisionText.vue";
import AssetAuthorizationForm from "../components/AssetAuthorizationForm.vue";
import LicenseDeclarationField from "../components/LicenseDeclarationField.vue";
import ProjectResourcePicker from "../components/ProjectResourcePicker.vue";
import { validLicenseDeclaration } from "../state/licenseDeclaration";
import { repositoryLocationKey } from "../state/repositoryLocation";
import NavigationIcon, { type NavigationIconName } from "../components/NavigationIcon.vue";
const props = defineProps<{ s: WorkspaceSession; authorizationTargetId?: string }>();
const emit = defineEmits<{ next: []; returnPlanning: [] }>();
const savedAuthorizationId = ref("");
watch(() => props.authorizationTargetId, () => { savedAuthorizationId.value = ""; });
async function saveAuthorization(id: string, input: AssetAuthorizationInput) {
  savedAuthorizationId.value = "";
  if (await props.s.updateAssetAuthorization(id, input)) savedAuthorizationId.value = id;
}
const location = ref("");
const permission = ref("");
const license = ref<string | null>(null);
const authorization = ref<UsageAuthorization | "">("");
const registered = computed(() => props.s.assets.some(asset =>
  repositoryLocationKey(asset.location) === repositoryLocationKey(location.value)));
const absolute = computed(
  () =>
    /^[a-zA-Z]:[\\/]/.test(location.value.trim()) ||
    (location.value.trim().startsWith("/") && !location.value.trim().startsWith("//")),
);
const repositoryId = ref("");
const sectionId = ref("overview");
const repository = computed(() => props.s.repositories.find(item => item.id === repositoryId.value)
  ?? props.s.repositories[0]);
const repositoryAsset = computed(() => props.s.assets.find(item => item.id === repository.value?.assetId));
const sections = [
  { id: "overview", label: "概览", icon: "file" },
  { id: "structure", label: "结构与技术", icon: "code" },
  { id: "capabilities", label: "已有能力", icon: "layers" },
  { id: "reuse", label: "可复用资产", icon: "reuse" },
  { id: "risks", label: "限制与风险", icon: "warning" },
  { id: "evidence", label: "分析依据", icon: "evidence" },
] satisfies { id: string; label: string; icon: NavigationIconName }[];
function viewRepository(id: string) {
  repositoryId.value = id;
  sectionId.value = "overview";
  document.getElementById("repository-explorer")?.scrollIntoView?.({ block: "start" });
}
</script>
<template>
  <section v-if="repository" id="repository-explorer" class="panel repository-explorer">
    <div class="panel-header explorer-heading">
      <div class="repository-identity"><span class="feature-symbol"><NavigationIcon name="repository" /></span><div>
        <h2>{{ repository.purpose }}</h2>
        <p class="path">{{ repositoryAsset?.location ?? '未读取到关联资产路径' }}</p>
      </div></div>
      <span class="tag">只读分析快照</span>
    </div>
    <div class="snapshot-context">
      <label v-if="s.repositories.length > 1" for="repository-snapshot">查看分析快照</label>
      <select v-if="s.repositories.length > 1" id="repository-snapshot" :value="repository.id"
        @change="viewRepository(($event.target as HTMLSelectElement).value)">
        <option v-for="item in s.repositories" :key="item.id" :value="item.id">
          {{ item.purpose }} · {{ item.analyzedRevision.slice(0, 12) }} · {{ item.id }}
        </option>
      </select>
      <RevisionText label="分析版本 · analyzedRevision" :value="repository.analyzedRevision" />
      <p class="meta">保存的分析不代表仓库当前代码；完整授权信息位于下方「仓库登记与授权」。</p>
    </div>
    <div class="explorer-layout">
      <nav class="local-navigation" aria-label="仓库分析分区">
        <button v-for="section in sections" :key="section.id" class="local-nav-button"
          :aria-pressed="sectionId === section.id" :aria-controls="`repository-section-${section.id}`"
          @click="sectionId = section.id"><NavigationIcon :name="section.icon" />{{ section.label }}</button>
      </nav>
      <div class="explorer-reading" :id="`repository-${repository.id}`">
        <section id="repository-section-overview" v-show="sectionId === 'overview'">
          <h3 class="section-heading"><NavigationIcon name="file" />仓库用途</h3><p class="reading-text">{{ repository.purpose }}</p>
          <ContentList title="技术栈" :items="repository.techStack" variant="tags" icon="code" />
          <ContentList title="已有能力" :items="repository.capabilities" icon="layers" />
          <details class="object-id">
            <summary>快照与资产绑定</summary>
            <p>RepositoryProfile <code>{{ repository.id }}</code></p>
            <p>SoftwareAsset <code>{{ repository.assetId }}</code></p>
          </details>
        </section>
        <section id="repository-section-structure" v-show="sectionId === 'structure'">
          <ContentList title="技术栈" :items="repository.techStack" variant="tags" icon="code" />
          <ContentList title="模块" :items="repository.modules" variant="modules" icon="repository" />
        </section>
        <section id="repository-section-capabilities" v-show="sectionId === 'capabilities'">
          <ContentList title="已有能力" :items="repository.capabilities" icon="layers" />
        </section>
        <section id="repository-section-reuse" v-show="sectionId === 'reuse'">
          <ContentList title="可复用资产" :items="repository.reusableAssets" icon="reuse" />
        </section>
        <section id="repository-section-risks" v-show="sectionId === 'risks'">
          <ContentList title="限制" :items="repository.limitations" icon="lock" />
          <ContentList title="风险" :items="repository.risks" variant="risks" icon="warning" />
        </section>
        <section id="repository-section-evidence" v-show="sectionId === 'evidence'">
          <h3>原始分析依据</h3>
          <p class="meta">保存结果没有逐条结论与依据的关联字段；以下按原文完整展示。</p>
          <EvidenceList :items="repository.evidence" title="源码与分析依据" expanded />
        </section>
      </div>
    </div>
  </section>
  <ProjectResourcePicker :s="s" />
  <details class="panel repository-setup" :open="!s.repositories.length || !!authorizationTargetId">
    <summary>{{ s.repositories.length ? '仓库登记与授权' : '连接仓库并明确授权' }} <span class="meta">{{ s.assets.length }} 个已登记资产</span></summary>
  <div class="two-column">
    <section>
      <h2>连接已有代码</h2>
      <p class="muted">
        后端在本机读取路径，浏览器不访问目录。分析只读；执行阶段会创建独立
        WorkingCopy。
      </p>
      <form
        class="stack"
        @submit.prevent="
          s.register({
            location: location.trim(),
            readPermissionAllowed: permission === 'true',
            licenseInfo: license?.trim() ?? null,
            usageAuthorization: authorization as UsageAuthorization,
          })
        "
      >
        <label for="repo-path">本地 Git 仓库绝对路径</label
        ><input
          id="repo-path" name="repo-path" autocomplete="off"
          v-model="location"
          placeholder="例如 C:\projects\my-java-app"
          required
          :disabled="!!s.busy"
          aria-describedby="path-help"
        />
        <p id="path-help" class="meta">
          注册会只读规范化路径以防重复；分析时才检查 Git 和源码。请输入受信任的小型 Java
          / Maven 项目；本版登记不接收 UNC 共享或设备路径。
        </p>
        <p v-if="location && !absolute" class="field-error">请输入绝对路径。</p>
        <label for="read-permission">读取与分析权限</label
        ><select
          id="read-permission" name="read-permission" autocomplete="off"
          v-model="permission"
          required
          :disabled="!!s.busy"
        >
          <option disabled value="">请明确选择</option>
          <option value="true">我允许系统读取和分析此仓库</option>
          <option value="false">不允许读取</option>
        </select>
        <LicenseDeclarationField id="license" v-model="license" :disabled="!!s.busy" />
        <label for="usage-auth">复用 / 二次开发授权</label
        ><select
          id="usage-auth" name="usage-auth" autocomplete="off"
          v-model="authorization"
          required
          :disabled="!!s.busy"
        >
          <option disabled value="">请明确选择</option>
          <option value="ALLOWED">已明确允许 · ALLOWED</option>
          <option value="DENIED">明确不允许 · DENIED</option>
          <option value="UNCLEAR">尚未确认 · UNCLEAR</option>
        </select>
        <button
          :disabled="!!s.busy || registered || !absolute || !permission || !authorization || !validLicenseDeclaration(license)"
        >
          {{ registered ? "已注册" : "注册此仓库" }}
        </button>
        <p class="meta">许可证可以暂时留空以注册、分析；演化规划前必须在已有记录上补齐明确的许可证及适用范围。系统不会判断法律兼容性或自动授予权限。</p>
        <p v-if="registered" class="inline-note">此路径已注册，请复用已有资产，不必再次分析。若缺少许可证，请展开「完善许可证与授权」。</p>
      </form>
    </section>
    <section>
      <h2>已登记仓库</h2>
      <p v-if="!s.assets.length" class="empty">
        尚未注册。填写路径和授权后，单独启动分析。
      </p>
      <article v-for="asset in s.assets" :key="asset.id" class="asset-card"
        :id="`asset-${asset.id}`" :class="{ 'authorization-target': authorizationTargetId === asset.id }">
        <h3>{{ asset.location }}</h3>
        <p>
          读取：{{ asset.readPermissionAllowed ? "允许" : "不允许" }} · 使用：{{
            asset.usageAuthorization
          }}
        </p>
        <p class="meta">
          许可证：{{ asset.licenseInfo || "未知" }} · {{ asset.type }} /
          {{ asset.source }}
        </p>
        <p v-if="s.selected?.candidateAssetIds.includes(asset.id)" class="repository-info meta">
          当前已选方向引用此资产；补充授权后可沿用原方向。
        </p>
        <p v-for="snapshot in s.repositories.filter(item => item.assetId === asset.id)" :key="snapshot.id">
          <button class="text-button" @click="viewRepository(snapshot.id)">查看已有分析 · {{ snapshot.analyzedRevision.slice(0, 12) }}</button>
        </p>
        <button
          class="secondary"
          :disabled="!!s.busy || !asset.readPermissionAllowed"
          @click="s.analyze(asset.id)"
        >
          分析仓库（调用 AI）
        </button>
        <details class="object-id">
          <summary>资产 ID</summary>
          <code>{{ asset.id }}</code>
        </details>
        <p v-if="authorizationTargetId === asset.id" class="inline-note">
          演化规划指定的资产：<code>{{ asset.id }}</code>。本页按 ID 定位，不替换相同路径的其他记录。
        </p>
        <AssetAuthorizationForm :asset="asset" :busy="!!s.busy" :expanded="authorizationTargetId === asset.id"
          @submit="saveAuthorization(asset.id, $event)" />
        <button v-if="authorizationTargetId === asset.id && savedAuthorizationId === asset.id"
          class="secondary" :disabled="!!s.busy" @click="emit('returnPlanning')">返回演化规划 →</button>
      </article>
      <div class="inline-note">
        分析结果不代表任意仓库可安全执行。M4 验证只支持既有 Java / Maven
        场景；Windows 宿主执行没有系统级沙箱。
      </div>
    </section>
  </div>
  </details>
  <div v-if="s.repositories.length" class="stage-footer">
    <p>用已确认画像与这些分析快照，探索候选产品方向。</p>
    <button :disabled="s.profile?.status !== 'CONFIRMED'" @click="emit('next')">
      探索产品方向 →
    </button>
  </div>
</template>
