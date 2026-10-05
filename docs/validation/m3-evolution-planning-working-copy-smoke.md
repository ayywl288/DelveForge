# M3 — Evolution Planning & Working Copy Focused Smoke

**Status:** Validation Record / **Not Source of Truth**

**Date:** 2026-10-05（UTC+08:00）

**Verdict:** **M3 FOCUSED SMOKE: PASS**

**Validated code revision:** `912d50665cd17c5f5d703b542a1e4157675e6182`

**Documentation follow-up:** `6b5f7af` 补录实际接受的 Plan 输出，`fd9ba17` 补录实际 Profile
输入，均从保留的 smoke 数据库与 API 产物恢复。本次里程碑收尾仅澄清记录元数据；这些
文档修改没有重跑 smoke，也不代表验证了另一个代码 revision。

本文记录一次真实环境运行及人工质量判断，不新增领域或架构规则。所记录的 smoke 与
输出补录没有修改生产代码、Prompt、产品文档、领域文档或 Roadmap，没有启动 M4。

## 1. 环境与隔离

| 项目 | 实际值 |
|---|---|
| 应用 | 当前构建的 `delveforge-app-0.1.0-SNAPSHOT.jar`，Java 21 |
| API | `http://127.0.0.1:18783`，仅绑定 loopback |
| Provider | 真实 DeepSeek，直接访问 `https://api.deepseek.com`，无代理或 fake |
| Model | 既有配置 `deepseek-flash`；单次调用 timeout 保持 `60s` |
| 源仓库 | `E:\develop\local_repository\memos-main`，复用 M2 成功验证的本地快照 |
| 源 revision | `aea105e081c97dcd45eea25adcf2b69d89c8e4ef`，与 M2 记录相同 |
| 许可证 | 仓库现有 `LICENSE`：MIT |
| Smoke 根目录 | `C:\Users\28226\AppData\Local\Temp\delveforge-m3-smoke-20261005-210005` |
| SQLite | 上述目录的 `smoke.db`，全新数据库，正常 Flyway 迁移至 V9 |
| 托管根目录 | 上述目录的 `workspaces`，通过 `delveforge.workspace.root` 配置 |

凭据仅通过应用进程的 `DEEPSEEK_API_KEY` 环境变量提供，未写入文件或应用启动参数。
日志未启用 DEBUG。运行结束后应用已停止，数据库、副本及不含凭据的观测产物保留于
smoke 目录供检查。对顶层 JSON、日志和临时 Python 工具的凭据形状扫描无命中。

源仓库只读。观测工具位于 smoke 临时目录，不进入源码仓库；工具调用正常应用 API，
没有手工 clone、修改副本产品代码或手工写入数据库状态。

## 2. 有效输入链

本次使用明确的 **synthetic smoke fixture**，不将其描述为用户本人的画像：

- 每天记录开发学习与生活短笔记；担心笔记和附件迁移、备份困难。
- 希望周期性导出到本地归档并检查完整性。
- 有 Go / TypeScript 基础，前端只做必要调整，每周约六小时，优先增量复用。

通过 UserProfile create / PATCH 建立六个内容区和五条 USER_INPUT Evidence；
真实 sufficiency-assessment 返回 sufficient=true、REVIEWING，再经正常 confirm API
确认对应 revision。SoftwareAsset 通过注册 API 建立，readPermissionAllowed=true、
licenseInfo=MIT、usageAuthorization=ALLOWED。随后真实 Repository Analysis 和
Product Direction Discovery 建立其余输入，最后经 select API 选择 smoke 方向。

| 对象 | 持久化身份与状态 |
|---|---|
| UserProfile | `36db3a56-79bf-419d-a2f8-2d6db0299c65`，CONFIRMED，revision 12 |
| SoftwareAsset | `f136230c-2dde-463c-b96a-e5362b31ed85` |
| RepositoryProfile | `f01b1958-b9fc-4299-82aa-7da3708d1f58`，analyzedRevision 等于源 HEAD |
| ProductDirection | `1b067a1b-c71d-4c4a-af62-87cfdb20ce2d`，SELECTED |

### 2.1 Confirmed UserProfile：实际六区输入

以下为本次正式确认的 smoke fixture，并非用户本人的画像。数据来自保留的
`user-confirmed.json`，已以只读 SQLite 核对 `user_profile`、revision 12 的内容快照
及有序 Evidence；六个内容区按持久化原文完整展示，不用场景摘要替代：

```json
{
  "id": "36db3a56-79bf-419d-a2f8-2d6db0299c65",
  "status": "CONFIRMED",
  "revision": 12,
  "interests": [
    "个人知识管理",
    "自托管工具"
  ],
  "behaviors": [
    "每天使用短笔记记录开发学习与生活信息"
  ],
  "painPoints": [
    "笔记和附件需要自己掌控，担心迁移与备份困难"
  ],
  "technicalCapabilities": [
    "有 Go 和 TypeScript 基础，能维护已有后端，前端只做必要调整"
  ],
  "projectGoals": [
    "希望定期导出笔记和附件到本地归档并检查完整性"
  ],
  "constraints": [
    "每周最多投入六小时，希望增量复用已有笔记应用"
  ]
}
```

**代表性 UserProfile Evidence：** 该 revision 共五条依据，均为
`sourceType=USER_INPUT`、`sourceRef=m3-smoke-scenario`、`confidence=null`、`confirmed=false`。
下面保留三条实际 claim，覆盖痛点、目标与投入约束；其所属快照是上方 UserProfile ID @ revision 12。
Profile 的 CONFIRMED 状态不改变单条 Evidence.confirmed 的值。

| 原 Evidence 位置（从 1 开始） | sourceRef | 实际 claim |
|---|---|---|
| 2 | `m3-smoke-scenario` | 笔记和附件需要自己掌控，担心迁移与备份困难 |
| 3 | `m3-smoke-scenario` | 希望定期导出笔记和附件到本地归档并检查完整性 |
| 5 | `m3-smoke-scenario` | 每周最多投入六小时，希望增量复用已有笔记应用 |

这些用户依据随后由 Direction 的 userNeed / userFit 支持槽引用，最终以
USER_PROFILE origin（同一 ID、revision 12）进入 Plan 的 EvidenceBasis。
下文的 Direction 与 Plan 仍是同一次 smoke 产出，没有重新确认画像或发起 AI 调用。

### 2.2 RepositoryProfile：实际完整分析输入

以下为真实 Repository Analysis 接受并保存的分析快照，来自 `profile.json`，
已逐字段、逐有序列表及全部 Evidence 与只读 SQLite 核对。
保留当次分析的原文与技术版本陈述，不把本次文档补充当作重新分析或修正仓库结论。

```json
{
  "id": "f01b1958-b9fc-4299-82aa-7da3708d1f58",
  "assetId": "f136230c-2dde-463c-b96a-e5362b31ed85",
  "analyzedRevision": "aea105e081c97dcd45eea25adcf2b69d89c8e4ef",
  "purpose": "Memos 是一个自托管的短笔记（memo）应用后端与前端一体化仓库，围绕 memo 及其附件、空间、反应、分享等资源提供多协议 API、存储层与 Web 界面。",
  "techStack": [
    "Go 1.27.0",
    "Echo v5",
    "Connect RPC (connectrpc.com/connect)",
    "gRPC / gRPC-Gateway",
    "Protocol Buffers",
    "React 19",
    "TypeScript 7",
    "Vite 8",
    "Tailwind CSS v4",
    "TanStack React Query v5",
    "Biome",
    "Vitest",
    "pnpm 11 / Node 24",
    "SQLite (modernc.org/sqlite)",
    "MySQL (go-sql-driver/mysql)",
    "PostgreSQL (lib/pq)",
    "AWS SDK v2 S3",
    "CEL (google/cel-go)",
    "goldmark (Markdown)",
    "JWT (golang-jwt/jwt/v5)",
    "Cobra + Viper CLI",
    "testcontainers-go",
    "Model Context Protocol Go SDK",
    "OpenAI / Google GenAI SDKs",
    "Docker (Alpine 3.21 运行时)"
  ],
  "modules": [
    "cmd/memos：Cobra/Viper CLI 与服务启动入口",
    "server：HTTP 进程、Echo 引导、所有传输层",
    "server/api/v1：Connect/gRPC-Gateway 服务、ACL、SSE 枢纽、拦截器",
    "server/fileserver：原生 HTTP 文件服务、缩略图、Range 请求",
    "server/frontend：go:embed 打包的静态 SPA",
    "server/mcp：Model Context Protocol 服务器",
    "server/auth：JWT 访问令牌、刷新令牌、PAT",
    "core：无 HTTP 无 SQL 的业务规则（access、notification、memopayload、memoexport）",
    "store：Store 门面、缓存、迁移与 Driver 接口（sqlite/mysql/postgres 驱动）",
    "markdown：Markdown 引擎（解析、AST、memos 语法扩展、渲染器、memo payload）",
    "filter：CEL 过滤器编译器（解析为 IR、按驱动渲染 SQL、可过滤字段模式）",
    "provider：由实例设置配置的后端（ai、idp、storage）",
    "internal：无 memos 词汇的私有基础组件（identifier、email、webhook、ratelimit 等）",
    "proto/api/v1、proto/store：公共 API 与内部存储 proto 源，proto/gen 为生成产物",
    "web/src：React SPA（connect.ts 客户端、auth-state、hooks、contexts、components、themes）"
  ],
  "capabilities": [
    "创建、列出、查询、更新、删除 memo，支持置顶、可见性（PUBLIC/PROTECTED/PRIVATE/SPACE）与归档状态",
    "memo 关系、评论、反应（reaction）、分享链接（memo_share，支持过期时间）",
    "Space 协作边界：空间、成员（ADMIN/USER）、邀请的创建/接受/拒绝/撤销",
    "附件管理与上传（含分块上传、缩略图、图片处理并发信号量、存储用量查询）",
    "多数据库支持：SQLite、MySQL、PostgreSQL 的迁移与 LATEST.sql 全新安装脚本",
    "Markdown 解析与元数据抽取：标签、提及、图片/附件引用、标题、代码/链接/任务列表属性、摘要生成、标签重命名",
    "CEL 过滤器编译为跨数据库方言的 SQL 片段（比较、in、contains/startsWith/endsWith、matches、集合操作、exists/all/exists_one 推导）",
    "认证与授权：JWT、刷新令牌、PAT、IdP 身份联合、实例管理员与 memo 级访问策略（core/access）",
    "AI 能力：转录（OpenAI whisper-1 / Gemini gemini-2.5-flash）与 AI provider 解析",
    "多协议 API：Connect RPC 与 gRPC-Gateway 双通道，共享 Authorizer 与限流",
    "限流、人机挑战、注册策略、请求体大小分级限制",
    "SSE 事件推送、webhook、通知与收件箱（inbox）",
    "Memo 导出格式（ZIP 容器携带 memo 与附件，用于实例间迁移）",
    "实例设置与用户设置管理、实例统计（带缓存）",
    "原生文件服务（/file/*）与单页应用静态资源服务",
    "MCP（Model Context Protocol）服务端集成"
  ],
  "reusableAssets": [
    "markdown 包（markdown.Service）：Markdown 解析/元数据抽取/渲染/摘要/标签重命名，可独立复用",
    "markdown/renderer 的 MarkdownRenderer：将 goldmark AST 渲染回 Markdown 文本",
    "filter 包：CEL 表达式到 IR 再到 SQL 的编译器，含 Schema 字段定义",
    "core/access：传输无关的资源授权策略（memo 读决策、memo/attachment 管理权限、实例管理员判定）",
    "core/memoexport：Memos Export Format 的 ZIP 容器读写与 JSON 记录定义",
    "store.Driver 接口与 store/db/{sqlite,mysql,postgres} 驱动实现",
    "store 门面中的内存缓存与对象存储客户端缓存",
    "provider/ai：AI provider 类型/配置/错误定义与模型解析",
    "server/auth：JWT 访问令牌、刷新令牌与 PAT 处理",
    "server/api/v1 的 Connect 拦截器（metadata、logging、recovery、auth）与错误码转换",
    "internal 下的通用组件（identifier、email、webhook、ratelimit、linkmeta、profile）"
  ],
  "limitations": [
    "给出的材料仅包含 go.mod、部分脚本、部分 store/markdown/filter/provider/server/core 文件与配置，未包含完整的业务实现，无法据此判断全量行为",
    "仓库无 README 内容被提供，未见到功能全景、部署与配置的官方说明",
    "前端仅见 package.json 与 AGENTS.md 描述，未见实际源码，无法核实组件与页面实现",
    "MCP 服务器、文件服务器、SSE 枢纽、IdP 与存储 provider 的具体实现未在材料中出现",
    "导出格式中 1.0 版本仅写入 USER 作用域与 REFERENCE 关系类型，未涉及其他关系或作用域",
    "导出格式明确不在容器内存储 mimetype 条目（为兼容 macOS Archive Utility）",
    "CEL 过滤器的部分能力受限：时间戳访问器不接受时区参数、按 UTC 提取；set 操作要求字面量字符串列表；不支持顶层非布尔表达式",
    "core/access 中 Space 邀请在授权判定上不授予成员资格或访问权，直到用户接受"
  ],
  "risks": [
    "AGENTS.md 明确警告不要手工编辑生成产物（proto/gen、web/src/types/proto），误改会与 buf generate 结果不一致",
    "数据库模式变更需同时更新 SQLite、MySQL、PostgreSQL 迁移与各自 LATEST.sql，遗漏会导致不同驱动行为不一致",
    "分层依赖由 depguard 强制（cmd→server→core→store→{provider,markdown,filter}→internal），违反会破坏架构约束但当前仅靠 lint 拦截",
    "请求体上限分级（16MB 通用、256MB 内联文件、分块上传更低）若配置理解错误可能导致大文件上传失败或内存压力",
    "内存限流器（ratelimit.NewMemoryLimiter）为进程内实现，多实例部署下不共享限流状态",
    "Entry point 以 root 启动后会 chown 数据目录并降权为 UID/GID 10001，权限修复失败被静默忽略（`|| true`）",
    "权威性：AGENTS.md 声明若与源码或 CI 配置冲突应以源文件为准，说明该文档可能滞后",
    "依赖面很宽（AWS SDK、Docker/testcontainers、OpenAI/GenAI、CEL、grpc-gateway 等），升级与供应链维护成本较高",
    "认证/令牌行为与 Docker/发布流程的改动被要求先征询，说明这些区域改动风险高"
  ]
}
```

**代表性 RepositoryProfile Evidence：** 共 36 条，均为 `sourceType=REPOSITORY`、
`confidence=null`、`confirmed=false`。下表保留六条实际 sourceRef / claim，覆盖技术基础、
前端依赖、导出格式、数据读取、Markdown/附件引用及运行边界。
这些 sourceRef 是分析版本中的仓库相对文件引用；其所属快照为上方 RepositoryProfile ID，
analyzedRevision 固定为 `aea105e081c97dcd45eea25adcf2b69d89c8e4ef`。

| 原 Evidence 位置（从 1 开始） | sourceRef | 实际 claim |
|---|---|---|
| 1 | `go.mod` | 项目模块路径与 Go 版本、后端框架、数据库、AI、CEL、Markdown 等主要依赖来自 go.mod |
| 30 | `web/package.json` | web/package.json 展示前端依赖（React 19、React Query、Tailwind、CodeMirror、Connect、maplibre、mermaid 等）与脚本（dev/build/release/lint/test） |
| 29 | `core/memoexport/format.go` | core/memoexport/format.go 定义导出格式常量、manifest/memo/attachment 等记录结构与路径规则 |
| 13 | `store/driver.go` | store/driver.go 定义 Driver 接口，涵盖 attachment、memo、space、memo relation、instance setting、user、user setting、idp、inbox、reaction、memo share、user identity 等方法 |
| 17 | `markdown/markdown.go` | markdown/markdown.go 实现标签/提及抽取、属性计算（标题、链接、代码、任务列表）、渲染、摘要生成、内容校验、标签重命名与受管附件 URL 解析 |
| 32 | `scripts/entrypoint.sh` | scripts/entrypoint.sh 在 root 启动时修正数据目录权限并降权至 MEMOS_UID/MEMOS_GID，并支持 *_FILE 形式的 MEMOS_DSN |

其中导出格式、Driver 和 Markdown 依据被选入 Direction.reusableCapability，
又可在 Plan 的 REPOSITORY_PROFILE origin 中逐值追溯；下文 Evidence 示例保留了正式 origin。
输入快照的 limitations 明确承认分析材料不完整，不应把未覆盖的实现当作已经证明不存在。

### 2.3 本次持久化对象的完整因果链

```text
Confirmed UserProfile 36db3a56-79bf-419d-a2f8-2d6db0299c65 @ revision 12
+
RepositoryProfile f01b1958-b9fc-4299-82aa-7da3708d1f58 @ aea105e081c97dcd45eea25adcf2b69d89c8e4ef
    ↓
Selected ProductDirection 1b067a1b-c71d-4c4a-af62-87cfdb20ce2d
    ↓
EvolutionPlan 941caea3-9164-4dd5-9cb7-da42a7c3f06c
    ↓
WorkingCopy 3fd15e06-aad8-448c-b878-30508d842dc8
```

Direction 保存上述 UserProfile ID / revision 与 RepositoryProfile ID，Plan 保存选定
Direction ID 和 Base Asset / Profile ID，WorkingCopy 保存相同 Asset 与 analyzedRevision。
这些关系已与保留的数据库核对；下面继续展示该方向及其实际规划输出。

选择的方向是「增量归档守护进程与归档完整性校验」：复用 Memos Export Format，
提供周期性增量归档、外部哈希清单和缺失/损坏/未覆盖内容报告；不承担迁移导入职责，
不新增笔记编辑界面。Asset 属于该方向的 Candidate Assets，Profile 属于该 Asset。

前置真实仓库分析 HTTP 201，80.48 s；生成 Profile 有 16 项 capabilities、15 项 modules、
8 项 limitations、36 条 Evidence。方向发现 HTTP 201，27.80 s，返回四个候选方向。

## 3. 真实 Planning 与持久化结果

`POST /api/evolution-plans/planning` 使用以上 Direction / Asset / Profile ID，
HTTP **201**，约 **31.97 s**，创建 Plan：

`941caea3-9164-4dd5-9cb7-da42a7c3f06c`

随后 GET HTTP 200，确认：PROPOSED、workingCopyId=null、五个 Step 全部
PENDING_CONFIRMATION、baselineRevision=null。只读 SQLite 查询确认一个 Plan、零个 WorkingCopy。

### 3.1 Planning 请求 / 结果

以下请求体按本次保留的 `m3_flow.py` 与 `state.json` 还原；HTTP 状态及耗时沿用运行时观测，
响应值与 `proposed-plan.json`、`proposed-plan-read.json` 一致。

```http
POST /api/evolution-plans/planning
Content-Type: application/json

{
  "productDirectionId": "1b067a1b-c71d-4c4a-af62-87cfdb20ce2d",
  "baseAssetId": "f136230c-2dde-463c-b96a-e5362b31ed85",
  "baseRepositoryProfileId": "f01b1958-b9fc-4299-82aa-7da3708d1f58"
}
```

结果：HTTP **201 Created**，约 **31.97 s**；关键响应字段：

```json
{
  "id": "941caea3-9164-4dd5-9cb7-da42a7c3f06c",
  "productDirectionId": "1b067a1b-c71d-4c4a-af62-87cfdb20ce2d",
  "baseAssetId": "f136230c-2dde-463c-b96a-e5362b31ed85",
  "baseRepositoryProfileId": "f01b1958-b9fc-4299-82aa-7da3708d1f58",
  "status": "PROPOSED",
  "workingCopyId": null
}
```

五个 Step 均为 `PENDING_CONFIRMATION`，`baselineRevision=null`。此时数据库尚无 WorkingCopy。

### 3.2 Prepare 请求 / 结果

```http
POST /api/evolution-plans/941caea3-9164-4dd5-9cb7-da42a7c3f06c/prepare
```

请求无业务请求体。结果：HTTP **200 OK**，约 **4.31 s**，随后 GET Plan 与 GET WorkingCopy
均 HTTP 200；以下为 `prepare-result.json` 和两个独立回读产物的关键结果：

```json
{
  "planId": "941caea3-9164-4dd5-9cb7-da42a7c3f06c",
  "planStatus": "ACTIVE",
  "workingCopyId": "3fd15e06-aad8-448c-b878-30508d842dc8",
  "workingCopyStatus": "READY",
  "sourceRevision": "aea105e081c97dcd45eea25adcf2b69d89c8e4ef",
  "currentRevision": "aea105e081c97dcd45eea25adcf2b69d89c8e4ef",
  "lastVerifiedRevision": "aea105e081c97dcd45eea25adcf2b69d89c8e4ef",
  "allFiveStepStatuses": "PENDING_CONFIRMATION",
  "allFiveStepBaselineRevisions": null
}
```

## 4. Real Evolution Planning Output

以下是本次真实 LLM 规划经过 Parser / Resolver、领域接受与持久化后的**实际正式内容**。
除展示缩进与段落外不改写字段值，不将评估结论替代模型实际产出。

数据来源为 smoke 根目录下的 `smoke.db`（本次仅以 `mode=ro` 打开），并逐项核对
`active-plan-read.json`、`proposed-plan-read.json`、`selected-direction.json`、
`ready-copy-read.json`。准备前后正式内容相同，仅 Plan status 与 workingCopyId 改变；
以下内容与已激活 Plan 的持久化状态一致，生命周期身份由系统管理。

本次没有保留 raw provider response；上述 JSON 是正常应用 API 的正式回读结果，
不是原始模型输出。`R-E*` / `D-E*` 临时引用在还原后不作为持久化字段保存，
此处以真实 Evidence + origin 以及 Direction 支持槽位展示追溯，不猜测共享依据使用的临时引用。

### 4.1 Selected ProductDirection：实际输入

```json
{
  "id": "1b067a1b-c71d-4c4a-af62-87cfdb20ce2d",
  "title": "增量归档守护进程与归档完整性校验",
  "status": "SELECTED",
  "problem": "用户每天用短笔记记录开发学习与生活信息，担心自托管实例里的笔记与附件无法自主掌控、迁移与备份困难；仓库现有的导出能力面向实例间迁移场景的一次性 ZIP 导出，缺少周期性、可增量执行、可校验完整性并指出附件缺失的本地归档流程。",
  "targetProduct": "一个以 CLI/守护进程形式运行的归档工具：按固定周期从 Memos 实例或直接读取 store 增量拉取 memo 与附件，按 Memos Export Format 写出本地归档批次，生成哈希清单并对既有归档做完整性校验，输出缺失附件、损坏条目与未覆盖内容的报告。",
  "differentiation": "与 Memos 内置导出不同：内置导出是为实例间迁移设计的一次性 ZIP 容器，本方向面向长期本地留存，强调周期性调度、增量写入与校验报告，不承担迁移与导入职责，也不新增笔记编辑界面。"
}
```

### 4.2 CurrentState：实际摘要与事实选择

```json
{
  "summary": "从增量归档方向审视，仓库已有 Memos Export Format 的 ZIP 容器与记录定义、Store/Driver 多驱动持久化抽象、markdown 元数据抽取与附件管理能力；但现有导出是为实例间迁移设计的一次性 ZIP，不提供周期调度、增量归档、哈希清单或完整性报告，且材料未覆盖完整附件 provider 与业务实现。",
  "capabilities": [
    "创建、列出、查询、更新、删除 memo，支持置顶、可见性（PUBLIC/PROTECTED/PRIVATE/SPACE）与归档状态",
    "附件管理与上传（含分块上传、缩略图、图片处理并发信号量、存储用量查询）",
    "多数据库支持：SQLite、MySQL、PostgreSQL 的迁移与 LATEST.sql 全新安装脚本",
    "Markdown 解析与元数据抽取：标签、提及、图片/附件引用、标题、代码/链接/任务列表属性、摘要生成、标签重命名",
    "Memo 导出格式（ZIP 容器携带 memo 与附件，用于实例间迁移）",
    "原生文件服务（/file/*）与单页应用静态资源服务"
  ],
  "modules": [
    "cmd/memos：Cobra/Viper CLI 与服务启动入口",
    "server/api/v1：Connect/gRPC-Gateway 服务、ACL、SSE 枢纽、拦截器",
    "server/fileserver：原生 HTTP 文件服务、缩略图、Range 请求",
    "core：无 HTTP 无 SQL 的业务规则（access、notification、memopayload、memoexport）",
    "store：Store 门面、缓存、迁移与 Driver 接口（sqlite/mysql/postgres 驱动）",
    "markdown：Markdown 引擎（解析、AST、memos 语法扩展、渲染器、memo payload）",
    "provider：由实例设置配置的后端（ai、idp、storage）"
  ],
  "limitations": [
    "给出的材料仅包含 go.mod、部分脚本、部分 store/markdown/filter/provider/server/core 文件与配置，未包含完整的业务实现，无法据此判断全量行为",
    "仓库无 README 内容被提供，未见到功能全景、部署与配置的官方说明",
    "导出格式中 1.0 版本仅写入 USER 作用域与 REFERENCE 关系类型，未涉及其他关系或作用域",
    "导出格式明确不在容器内存储 mimetype 条目（为兼容 macOS Archive Utility）",
    "MCP 服务器、文件服务器、SSE 枢纽、IdP 与存储 provider 的具体实现未在材料中出现"
  ]
}
```

### 4.3 TargetState：实际持久化字段

以下三个字段与上方 Selected Direction 完全相等，未用方向摘要替代实际 Plan 值：

```json
{
  "problem": "用户每天用短笔记记录开发学习与生活信息，担心自托管实例里的笔记与附件无法自主掌控、迁移与备份困难；仓库现有的导出能力面向实例间迁移场景的一次性 ZIP 导出，缺少周期性、可增量执行、可校验完整性并指出附件缺失的本地归档流程。",
  "targetProduct": "一个以 CLI/守护进程形式运行的归档工具：按固定周期从 Memos 实例或直接读取 store 增量拉取 memo 与附件，按 Memos Export Format 写出本地归档批次，生成哈希清单并对既有归档做完整性校验，输出缺失附件、损坏条目与未覆盖内容的报告。",
  "differentiation": "与 Memos 内置导出不同：内置导出是为实例间迁移设计的一次性 ZIP 容器，本方向面向长期本地留存，强调周期性调度、增量写入与校验报告，不承担迁移与导入职责，也不新增笔记编辑界面。"
}
```

### 4.4 reusableCapabilities：实际完整列表

```json
{
  "reusableCapabilities": [
    "core/memoexport：Memos Export Format 的 ZIP 容器读写与 JSON 记录定义",
    "store.Driver 接口与 store/db/{sqlite,mysql,postgres} 驱动实现",
    "markdown 包（markdown.Service）：Markdown 解析/元数据抽取/渲染/摘要/标签重命名，可独立复用",
    "Memo 导出格式（ZIP 容器携带 memo 与附件，用于实例间迁移）",
    "附件管理与上传（含分块上传、缩略图、图片处理并发信号量、存储用量查询）",
    "多数据库支持：SQLite、MySQL、PostgreSQL 的迁移与 LATEST.sql 全新安装脚本"
  ]
}
```

### 4.5 changes：实际完整有序列表

保留原顺序及原文，包括新增、复用、保持兼容与明确排除的责任：

```json
{
  "changes": [
    "新增独立的归档 CLI/守护进程运行形态与配置，不修改现有 Memos API 行为",
    "新增可切换的归档源读取路径：直接读取 store 或通过 API 获取 memo 与附件元数据",
    "新增周期调度与增量游标，记录已归档 memo 与附件的进度",
    "新增按 Memos Export Format 写出本地归档批次，并生成批次外部哈希清单",
    "新增完整性校验流程，输出缺失附件、损坏条目与未覆盖内容报告",
    "复用 core/memoexport 的容器与记录定义以保持导出兼容性，不改变既有迁移导出语义",
    "在文档与配置中说明 USER 作用域、REFERENCE 关系与 mimetype 缺失等格式覆盖边界",
    "不新增笔记编辑界面，不承担迁移与导入职责"
  ]
}
```

### 4.6 全部五个 EvolutionSteps：实际定义

以下按持久化 position 排列。每个 Step 的 `planId` 均为
`941caea3-9164-4dd5-9cb7-da42a7c3f06c`，状态均 `PENDING_CONFIRMATION`，baselineRevision 均 null。

#### Step 1

```json
{
  "id": "24258273-36fc-40d2-a012-b87a17f02795",
  "goal": "验证从既有数据源读取 memo 与附件并按 Memos Export Format 写出本地批次的可行性。",
  "scope": "单次全量归档读取与批次写出，不涉及周期调度与历史批次校验。",
  "plannedChanges": [
    "新增归档源读取适配，可从 store.Driver 或 API 获取 memo 与附件元数据",
    "复用 core/memoexport 容器与记录定义写出本地归档批次",
    "为批次生成外部哈希清单",
    "提供一次性归档运行的配置与退出报告"
  ],
  "preconditions": [
    "归档源的访问配置（数据库连接或 API 令牌）可提供",
    "Memos Export Format 的记录与路径规则可复用"
  ],
  "verificationCriteria": [
    "能证明单次运行产出符合现有 Memos Export Format 的归档容器",
    "哈希清单与归档内条目一致",
    "在无附件或空实例下行为明确且不产生损坏归档"
  ]
}
```

#### Step 2

```json
{
  "id": "e272e1e9-d589-40f8-ab19-fb43437311d0",
  "goal": "让归档工具按固定周期增量执行，避免重复写入已归档内容。",
  "scope": "周期调度、增量游标与批次边界，不改变单次归档的格式写出逻辑。",
  "plannedChanges": [
    "引入守护进程式周期触发机制",
    "基于 memo/附件更新时间或稳定标识维护增量游标",
    "按批次编号管理本地归档目录与清单",
    "使重复运行具备幂等性并在游标不可用时回落到全量"
  ],
  "preconditions": [
    "单次归档读取与写出闭环已验证",
    "存在可用于增量判断的稳定时间或标识字段"
  ],
  "verificationCriteria": [
    "连续周期只写入新增或变更的 memo 与附件",
    "重复执行不产生重复归档条目",
    "游标丢失或重置后能安全执行全量回落"
  ]
}
```

#### Step 3

```json
{
  "id": "e592f8fe-6b46-4176-9175-ce4d21b5d409",
  "goal": "对既有归档执行完整性校验并输出问题报告。",
  "scope": "校验已有批次、哈希清单与附件引用，输出缺失附件、损坏条目与未覆盖内容报告。",
  "plannedChanges": [
    "增加归档批次与哈希清单的校验流程",
    "检测缺失附件、损坏条目与未被归档覆盖的内容",
    "利用 markdown 元数据抽取定位附件引用与标签信息",
    "生成面向用户的问题报告而不修改源数据"
  ],
  "preconditions": [
    "已存在至少一个本地归档批次及其哈希清单",
    "memo 的附件引用与元数据可被解析"
  ],
  "verificationCriteria": [
    "报告能区分缺失附件、损坏条目与未覆盖内容",
    "问题条目可定位到对应 memo 或附件引用",
    "对导出容器不含 mimetype 的情况有明确且不误报的处理说明"
  ]
}
```

#### Step 4

```json
{
  "id": "804d1498-51bc-4ea4-8379-e57d59efacdd",
  "goal": "使归档源可在直读 store 与调用 Memos 实例 API 之间切换。",
  "scope": "两种归档源的等价读取与附件存储路径核实，不改变归档格式与校验报告职责。",
  "plannedChanges": [
    "抽象归档源的读取职责并支持两种来源配置",
    "核实附件遍历路径与存储 provider 行为",
    "对齐两种来源在格式限制内的输出差异",
    "为源配置错误提供明确失败反馈"
  ],
  "preconditions": [
    "单次归档与增量归档读取路径已稳定",
    "可访问源码以核实附件与存储 provider 行为"
  ],
  "verificationCriteria": [
    "在格式限制内两种源产出等价的归档内容",
    "附件读取不依赖未核实或隐含的 provider 行为",
    "源切换配置错误时任务明确失败而非静默产出不完整归档"
  ]
}
```

#### Step 5

```json
{
  "id": "6022517e-29ca-43d0-987b-7ddf320ea610",
  "goal": "将归档工具作为可长期运行的守护进程交付，并明确格式覆盖边界。",
  "scope": "运行集成、配置说明与格式边界文档，不承担迁移导入职责，不新增笔记编辑界面。",
  "plannedChanges": [
    "提供守护进程运行配置与日志/错误报告",
    "补充 USER 作用域、REFERENCE 关系与 mimetype 缺失的覆盖说明",
    "说明归档工具不负责导入与迁移",
    "在文档中标注多驱动模式变更对归档读取的影响"
  ],
  "preconditions": [
    "周期增量归档与完整性校验流程已验证",
    "格式覆盖范围与限制已从现有导出定义确认"
  ],
  "verificationCriteria": [
    "可按固定周期无人值守运行并产出可理解的状态与错误报告",
    "文档明确不覆盖的关系/作用域与不承担迁移导入职责",
    "不修改现有 Memos API 行为与前端笔记编辑界面"
  ]
}
```

### 4.7 risks：实际完整有序列表

```json
{
  "risks": [
    "导出格式 1.0 仅写入 USER 作用域与 REFERENCE 关系类型，增量归档无法覆盖其他关系或作用域，需要在工具文档中说明或自行扩展格式",
    "材料未包含完整的业务实现，附件遍历路径与存储 provider 行为需要先在源码中核实",
    "导出容器不记录 mimetype 条目，校验与恢复时不能依赖归档内的类型信息",
    "数据库与存储驱动的模式变更需要同时跟进多驱动迁移，归档读取逻辑可能随之调整",
    "增量归档对删除、关系变化和可见性变化的覆盖需要明确定义，否则可能产生未覆盖内容",
    "长期运行的守护进程与多实例限流/缓存交互可能影响读取一致性与附件读取结果",
    "分层依赖由 depguard 强制（cmd→server→core→store→{provider,markdown,filter}→internal），新增归档组件需遵守架构约束",
    "AGENTS.md 声明若与源码或 CI 配置冲突应以源文件为准，说明该文档可能滞后"
  ]
}
```

### 4.8 Evidence：代表性实际值与来源

“来自 Direction”表示该依据存在于本次选定 Direction 的 evidenceSupport，
并非新增一种 `PRODUCT_DIRECTION` origin。最终 origin 仍是 RepositoryProfile，
或带 revision 的 UserProfile。下面选取 Plan 的第 1、9、13、15 条（从 1 开始计数）：

**Plan Evidence #1：Profile 直接提供的仓库依据；不在该 Direction 的支持槽中**

```json
{
  "evidence": {
    "sourceType": "REPOSITORY",
    "sourceRef": "go.mod",
    "claim": "项目模块路径与 Go 版本、后端框架、数据库、AI、CEL、Markdown 等主要依赖来自 go.mod",
    "confidence": null,
    "confirmed": false
  },
  "origin": {
    "kind": "REPOSITORY_PROFILE",
    "userProfileId": null,
    "userProfileRevision": null,
    "repositoryProfileId": "f01b1958-b9fc-4299-82aa-7da3708d1f58"
  }
}
```

**Plan Evidence #9：Profile 的导出格式依据，同时位于 Direction.reusableCapability**

```json
{
  "evidence": {
    "sourceType": "REPOSITORY",
    "sourceRef": "core/memoexport/doc.go",
    "claim": "core/memoexport/doc.go 说明该包实现 Memos Export Format（ZIP 容器携带用户 memo 与附件）",
    "confidence": null,
    "confirmed": false
  },
  "origin": {
    "kind": "REPOSITORY_PROFILE",
    "userProfileId": null,
    "userProfileRevision": null,
    "repositoryProfileId": "f01b1958-b9fc-4299-82aa-7da3708d1f58"
  }
}
```

**Plan Evidence #13：Direction.userNeed 携带的用户痛点依据，原始出处是 UserProfile revision 12**

```json
{
  "evidence": {
    "sourceType": "USER_INPUT",
    "sourceRef": "m3-smoke-scenario",
    "claim": "笔记和附件需要自己掌控，担心迁移与备份困难",
    "confidence": null,
    "confirmed": false
  },
  "origin": {
    "kind": "USER_PROFILE",
    "userProfileId": "36db3a56-79bf-419d-a2f8-2d6db0299c65",
    "userProfileRevision": 12,
    "repositoryProfileId": null
  }
}
```

**Plan Evidence #15：Direction.userFit 携带的用户能力依据，原始出处是 UserProfile revision 12**

```json
{
  "evidence": {
    "sourceType": "USER_INPUT",
    "sourceRef": "m3-smoke-scenario",
    "claim": "有 Go 和 TypeScript 基础，能维护已有后端，前端只做必要调整",
    "confidence": null,
    "confirmed": false
  },
  "origin": {
    "kind": "USER_PROFILE",
    "userProfileId": "36db3a56-79bf-419d-a2f8-2d6db0299c65",
    "userProfileRevision": 12,
    "repositoryProfileId": null
  }
}
```

这四条的 sourceType、sourceRef、claim、confidence、confirmed 与 origin 均逐值来自数据库，
没有把未确认的 Evidence 改为 confirmed=true；UserProfile 的 CONFIRMED 状态与单条
Evidence.confirmed 是不同字段。

**重复数量单独统计：** 21 条 EvidenceBasis，按完整 evidence + origin 比较有 16 条唯一值；
五组依据各出现两次，即五个额外重复项，无其他重复组：

| sourceRef | Plan 位置（从 1 开始） | 出处 |
|---|---|---|
| `store/store.go` | 4 / 19 | RepositoryProfile `f01b1958-b9fc-4299-82aa-7da3708d1f58` |
| `store/driver.go` | 5 / 20 | RepositoryProfile `f01b1958-b9fc-4299-82aa-7da3708d1f58` |
| `markdown/markdown.go` | 7 / 21 | RepositoryProfile `f01b1958-b9fc-4299-82aa-7da3708d1f58` |
| `core/memoexport/doc.go` | 9 / 17 | RepositoryProfile `f01b1958-b9fc-4299-82aa-7da3708d1f58` |
| `core/memoexport/format.go` | 10 / 18 | RepositoryProfile `f01b1958-b9fc-4299-82aa-7da3708d1f58` |

这五组均存在于 Base Profile，同时也位于 Direction.reusableCapability；还原后值相同。
本次未捕获原始引用序列，因此不凭重复位置断言每个副本使用的是哪个 `R-E*` 或 `D-E*`。

## 5. 人工质量评估

| 内容 | 观察与判断 |
|---|---|
| CurrentState | 选择 6/16 项 capabilities、7/15 项 modules、5/8 项 limitations，全部逐项存在于 Profile；重点是 memo、附件、导出格式、store、多驱动和 Markdown，没有复制整个 Profile。 |
| TargetState | problem / targetProduct / differentiation 与选定 Direction 完全相等；保留 CLI/守护进程、周期增量归档和完整性报告目标，没有因缺少实现而削弱目标。 |
| ReusableCapabilities | 六项均来自 Profile 的 capabilities 或 reusableAssets，包括 `core/memoexport`、`store.Driver`、Markdown、附件管理和多数据库能力。 |
| changes | 有明确的复用与新增：复用 ZIP/记录格式；新增源读取、周期调度、增量游标、批次、外部哈希清单和校验报告；保留既有导出语义，排除编辑 UI 与导入职责。 |
| Steps | 五个有顺序、有 scope、有前置条件和验收目标的工程增量；没有要求编辑某文件、创建某类或执行构建命令。 |
| VerificationCriteria | 验收归档格式、哈希对应、空输入行为、增量幂等、游标回落、缺失/损坏分类、双源等价及长期运行报告，均表达 WHAT。 |
| Evidence | 21 条 EvidenceBasis，16 条唯一值；每条完整 evidence + origin 均精确匹配本次 Profile 或 Direction 的已有依据，包含 Base Profile 依据。未生成新的来源、confidence 或 confirmed 值。 |

**结论：本次规划语义可用，足以通过聚焦 smoke。** 以下质量观察不作为正确性阻塞：

- Step 1 已提及 store/API 来源选择，Step 4 再完成双源等价支持，存在局部职责重叠。
  用户执行前应明确 Step 1 先支持哪个来源；本次不改 Prompt 或实现。
- CurrentState 摘要将 Direction 的「缺少周期/增量/校验」缺口判断组织进摘要。
  Profile 已明确材料不完整，因此不能把这段摘要当作全仓不存在相关实现的独立证明。
- 部分材料覆盖限制及文档边界被保留；执行前仍需核实源码。Steps 的前置条件和 risks
  明确承认了附件 provider、增量删除/关系变化等尚待核实的问题。
- Evidence 有五条重复值，但均为已有事实，追溯仍完整；本次不扩大到去重或 Evidence 重设计。

## 6. 真实 WorkingCopy 准备与回读

`POST /api/evolution-plans/941caea3-9164-4dd5-9cb7-da42a7c3f06c/prepare`
返回 HTTP **200**，约 **4.31 s**。目录由实际 WorkingCopyProvisioningPort →
GitWorkspaceAdapter 创建，未手工创建或 clone。

WorkingCopy ID：`3fd15e06-aad8-448c-b878-30508d842dc8`

实际位置：

```text
C:\Users\28226\AppData\Local\Temp\delveforge-m3-smoke-20261005-210005\workspaces\3fd15e06-aad8-448c-b878-30508d842dc8
```

GET Plan 和 GET WorkingCopy 均 HTTP 200，独立回读确认：

```text
Plan.status                    = ACTIVE
Plan.workingCopyId              = 3fd15e06-aad8-448c-b878-30508d842dc8
WorkingCopy.status              = READY
WorkingCopy.sourceAssetId       = f136230c-2dde-463c-b96a-e5362b31ed85
WorkingCopy.sourceRevision      = aea105e081c97dcd45eea25adcf2b69d89c8e4ef
WorkingCopy.currentRevision     = aea105e081c97dcd45eea25adcf2b69d89c8e4ef
WorkingCopy.lastVerifiedRevision= aea105e081c97dcd45eea25adcf2b69d89c8e4ef
all five Steps.status           = PENDING_CONFIRMATION
all five Steps.baselineRevision = null
```

只读 SQLite 查询再次确认相同的 Plan / WorkingCopy / Step 状态。准备前后的 Plan
除 status 与 workingCopyId 外，其余内容、Step 身份、顺序、Evidence 完全一致。
数据库只有一个 Plan 和一个 WorkingCopy；当前迁移中没有执行/验证结果表，本次也未调用
任何 Step 确认或执行流程，没有产生 M4 Candidate State 或 Verification Result。

## 7. Git 与源仓库完整性

- 副本是配置托管根的直接子目录，与源仓库物理分离，双方没有父子目录重叠。
- 副本 `rev-parse HEAD` 等于 analyzedRevision；`rev-parse --abbrev-ref HEAD` 为 `HEAD`，
  即 detached；`status --porcelain=v1 --untracked-files=all` 为空。
- 副本没有 `.git/objects/info/alternates`。
- 检查源的 1466 个 Git object 文件和副本的 3 个 object/pack 文件，文件系统身份集合
  无交集，没有共享同一物理文件；两边 pack 布局不同，未仅靠同名文件判断隔离。
- 准备前后源仓库 HEAD 与 porcelain 状态一致；递归快照的 2807 个文件
  （包含 `.git`、index、refs、对象和工作树文件）路径、SHA-256、长度、mtime 全部一致。
  没有新增、删除或修改源文件，index 也未改变。
- 未在副本里做产品代码修改；初始 source revision 被采用为可信基线，没有执行构建或测试
  来制造 Step Verification 结果。

## 8. 信任边界、范围与结论

运行使用真实配置的 Provider 和最终中文 Prompt，正常经过
raw String → Parser → AiPlanningProposal → Resolver → PlanningProposal → Domain Service
→ 正式 Plan/Step → SQLite。可观察到的事实成员关系、目标一致性、完整 Evidence 值匹配、
服务器身份和初始状态均符合该边界，没有发现模型输出获得生命周期或授权权限。
本次没有对每个内部阶段插桩，因此不将 happy-path 观察声称为所有拒绝路径的独立证明。

没有扩大负向矩阵；事务失败、补偿、授权拒绝与 R1→R2 等确定性路径沿用既有自动化覆盖。
本次仅新增验证记录，未重跑全套自动化；此前 focused boundary refactor 的全量 verify 为
1127 tests、0 failure/error/skipped。单仓库、单方向、单次模型输出不能证明普遍规划质量。

十项 smoke PASS 条件均满足。**未发现应阻塞本轮 M3 freeze 的正确性问题。**
上述局部规划质量观察留给用户审阅，不自动扩大修复。Step 授权、代码修改、执行、
Verification 和 revision 演化仍留给 M4。
