# M4 本地后端使用

M0–M4 后端已完成并冻结；M5 Repair / Retry / Recovery 暂缓。已有一次真实直接 DeepSeek → CodingProposal → Candidate → Maven Verification → Verified B 成功，详见 [真实案例](validation/m4-real-provider-verified-evolution-smoke.md)。本文提供可迁移的运行方式，不承诺其它仓库一次成功。

## 1. 适用仓库与运行环境

当前写入与验证路径面向**用户明确授权、受信任、小型 Java/Maven Git 仓库**。要求 JDK 21、Git CLI，以及仓库根目录已提交的 `pom.xml`、`mvnw.cmd`（Windows）或 `mvnw`（其它平台）、`.mvn/wrapper/maven-wrapper.properties`。原仓库应已提交、工作区干净；分析和规划固定到实际 Git revision，执行只修改系统创建的 WorkingCopy。

生产 Maven 动作使用离线模式。先由用户在原仓库自行运行 Wrapper 构建与测试，准备本机 Maven 缓存，并确认项目不依赖在线服务、交互输入、额外凭据或未准备的插件。Wrapper 分发本身也应已缓存。存在复杂 profile、Surefire 选择器或其它无法确定全套测试语义的配置时，系统可能拒绝执行；有可运行 Maven 构建并不保证满足所有 M4 守卫。

Python 命名提示是首次案例修改的业务内容；这不等于已有 Python Verification 执行器。当前不支持任意语言、任意 shell 命令或自然语言业务验收的自动证明。

**Windows 宿主边界：** WorkingCopy 路径、Git revision、净化禁写和进程环境筛选不能隔离 Maven 插件、测试与 Git 本机程序对宿主文件系统的访问。本地后端没有面向公网的认证部署方案；本 profile 绑定 `127.0.0.1`，仅用于受控本机使用。不要把未知或不可信仓库送入执行流程。这是功能验证，不是 OS 沙箱或安全认证。

## 2. 默认配置与显式成功配置

[`application.yml`](../backend/delveforge-app/src/main/resources/application.yml) 保留普通默认值；[`application-m4-local.yml`](../backend/delveforge-app/src/main/resources/application-m4-local.yml) 通过 `--spring.profiles.active=m4-local` 显式启用首次 PASS 使用的预算。

| 项目 | 默认配置 | `m4-local` |
|---|---|---|
| 内部 Provider SSE | false | true |
| connect / 非流式读期限 / SSE idle | 60s | connect / idle 90s |
| SSE absolute total | 300s（SSE 未启用） | 300s |
| Provider `max_tokens` | 16,384 | 98,304 |
| 原始 HTTP 响应上限 | 8 MiB | 32 MiB |
| 单 SSE 事件上限 | 64 KiB | 64 KiB |
| Verification 单动作 / 整次调用 | 120s / 300s | 900s / 1200s |
| Verification 输出 / XML 报告上限 | 1 MiB / 100 | 8 MiB / 160 |
| Verification 最大动作数 | 16 | 16（继承） |
| SQLite | `./data/delveforge.db` | `./data/m4-local/delveforge.db` |
| Workspace | `${user.home}/.delveforge/workspaces` | `${user.home}/.delveforge/m4-local/workspaces` |
| 文件日志 | 未配置 | `./logs/m4-local/delveforge.log` |

`timeout` 在当前装配中同时设置连接期限与 SSE 字节空闲期限；`stream-timeout` 由 JDK request factory 约束绝对读取期限，增量或 keep-alive 不能延长它。SSE 仅在完整 `stop` 和 `[DONE]` 后向 Application 返回完整文本，REST 业务接口本身不提供流式返回。超时、截断、预算耗尽都会拒绝响应，不应用部分提案。

当前只有一个 Gateway 配置，`AiRequest` 没有阶段预算字段，**启用 profile 后 User Discovery、Repository Analysis、Direction、Planning、CodingProposal 都使用这套 Provider 上限**。98,304 是总生成上限，包含 reasoning，不表示每次必然用完。它有较高成本和延迟；首次成功的 execute 请求实际耗时约 702 秒，包含多次 AI 调用与 Maven，不能用 300 秒当整个 API 的期限。HTTP 调用方应能等待这一量级的同步请求；客户端断开不能当作服务端已取消，也不能因此重发 execute。

成功预算不是已证明最优默认值；默认非流式配置尚未取得同等真实 M4 成功验证。可以在服务空闲、停止后切换 profile，并保持同一对 DB / Workspace 路径；不要在执行中换配置或开第二个实例。本文不增加阶段路由、自动网络重试或新的恢复行为。

## 3. 配置并从全新数据环境启动

以下为 PowerShell 7（Windows）。运行目录由用户选择，避免直接复用历史 Smoke 路径。第一次启动请选择尚不存在的数据库文件；已有数据继续使用时保留它，不能为了得到“干净环境”删库。

```powershell
# 仓库根目录；已有最终 verify 产物时可跳过 package。
.\mvnw.cmd -DskipTests package

$localRunRoot = Join-Path $env:LOCALAPPDATA 'DelveForge\m4-local'
$env:DELVEFORGE_PERSISTENCE_DATABASE_FILE = Join-Path $localRunRoot 'data\delveforge.db'
$env:DELVEFORGE_WORKSPACE_ROOT = Join-Path $localRunRoot 'workspaces'
$env:DELVEFORGE_LOG_DIR = Join-Path $localRunRoot 'logs'

# 仅在当前进程环境注入；不要把真实 key 写进命令、文件或聊天。
$env:DEEPSEEK_API_KEY = Read-Host 'DeepSeek API Key' -MaskInput

# 这些属于 Provider 配置，不属于业务或 Domain。
$env:DELVEFORGE_AI_DEEPSEEK_BASEURL = 'https://api.deepseek.com'
$env:DELVEFORGE_AI_DEEPSEEK_MODEL = 'deepseek-flash'

java -jar backend/delveforge-app/target/delveforge-app-0.1.0-SNAPSHOT.jar `
    --spring.profiles.active=m4-local
```

Endpoint 与 model 是现有默认配置示例，不保证 Provider 将来保持该型号或接口；应使用自己账号实际可用的配置。也可通过 `--delveforge.ai.deepseek.base-url=...`、`--delveforge.ai.deepseek.model=...` 覆盖。预算同样可用各自的配置字段覆盖，但改过的预算不能冒称已真实验证。API Key 不应放在启动参数中。

默认端口 8080，可用 `SERVER_PORT` 修改。启动前确认没有其它应用实例占用所选数据库、Workspace 和端口。缺少父数据目录会在启动时创建；Flyway 迁移新库。服务可无 Key 启动以检查装配，但 AI 请求会拒绝。停止服务后可移除当前终端的 `DEEPSEEK_API_KEY` 环境变量。

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/system/connectivity
```

只应看到服务连通结果，不会因此调用 Provider。文件日志写在指定日志目录，构建日志也应直接重定向到外部交付证据或该日志目录，不放在项目根目录。保持现有 INFO 限制；禁止为了诊断开启完整请求体、模型响应或 MyBatis 参数日志。

## 4. 用正常 API 完成 M1–M4

所有 ID、revision、状态都从本次 API 返回读取。以下占位符不是历史 UUID，也不能向数据库直接填入。各请求保存必要的结构化结果，敏感原件放在受控目录；不要将完整画像、源码或模型正文提交到 Git。

| 阶段 | 正常 API 与实际输入 | 下一阶段得到什么 / 用户决策 |
|---|---|---|
| User Discovery | `POST /api/user-profiles`；对返回 ID 调用 `POST /api/user-profiles/{id}/discovery-turn`，请求 `{"input":"自己的需求、技能与约束"}` | 回答 `nextQuestion`；`GET` 画像，必要时 `PATCH` 纠正 |
| 确认画像 | `POST /api/user-profiles/{id}/confirm`，`{"revision":N}` | **用户审阅并确认实际版本 N**，不能用预设数字 |
| 登记资产 | `POST /api/software-assets`，见下方 JSON | 保存真实位置、读取权限、许可证与使用授权；登记本身不验证 Git 或构建 |
| Repository Analysis | `POST /api/software-assets/{assetId}/analysis`（无请求体） | 只读固定 revision，返回 RepositoryProfile；`GET /api/repository-profiles/{id}` 可查看 |
| Direction Discovery | `POST /api/product-directions/discovery`，见下方 JSON | 返回候选；用户阅读匹配依据、风险与限制 |
| 选择方向 | `POST /api/product-directions/{directionId}/select`（无请求体） | **用户明确选择**；不选择就不能规划；也可 `/reject` |
| Evolution Planning | `POST /api/evolution-plans/planning`，见下方 JSON | PROPOSED Plan，包含真实 Steps、Scope、plannedChanges、Verification Criteria；尚未授权修改 |
| Plan preparation | 审阅 Plan 后 `POST /api/evolution-plans/{planId}/prepare` | 隔离 WorkingCopy，Plan ACTIVE / WorkingCopy READY / Step PENDING_CONFIRMATION；**不等于 Step 授权** |
| Step confirmation | `POST /api/evolution-plans/{planId}/steps/{stepId}/confirm` | **用户明确确认当前步骤**，Step READY；顺序与前序可信状态仍需通过检查 |
| 可选只读提案预览 | `POST /api/evolution-plans/{planId}/steps/{stepId}/execution-preparation` | Step 专属理解与受校验 CodingProposal，额外消耗 Provider；不修改文件，也不保存可执行提案 |
| 执行与验证 | `POST /api/evolution-plans/{planId}/steps/{stepId}/execute`（无请求体） | 重新理解和生成提案，不接受客户端上传的“可信提案”；校验、隔离修改、Candidate、Verification 按正常链路执行 |
| 查看结果 | `GET /api/evolution-plans/{planId}`、`GET /api/working-copies/{workingCopyId}` | 核对 ExecutionResult、VerificationResult、Step、物理 Git 和可信 revision |

登记另一个实际授权仓库的请求示例（将路径与许可证替换为真实情况）：

```json
{
  "location": "D:/projects/authorized-java-project",
  "readPermissionAllowed": true,
  "licenseInfo": "实际许可证或用户拥有该代码的授权说明",
  "usageAuthorization": "ALLOWED"
}
```

不能因示例写了 `true` / `ALLOWED` 就替他人制造授权；未知或受限授权不能用于演化。注册接口不替你检测目录是否存在，实际 Git 资格检查在后续流程中完成。

Direction Discovery 请求：

```json
{
  "userProfileId": "<本次已确认画像的 id>",
  "expectedRevision": 1,
  "repositoryProfileIds": ["<本次分析返回的 profile id>"]
}
```

把 `1` 替换为用户刚审阅并确认的整数 revision。Planning 请求：

```json
{
  "productDirectionId": "<本次 SELECTED 方向 id>",
  "baseAssetId": "<选定基础资产 id>",
  "baseRepositoryProfileId": "<该基础资产当前有效分析快照 id>"
}
```

以上流程会调用付费 Provider；本交付任务只进行了离线测试与新库启动检查，没有再次运行它。生成的方向和 Plan 每次可能不同，必须检查业务内容及范围，不能因为字段完整便认为规划必然可执行。

## 5. 选材与提案预算保持不变

`m4-local` 只覆盖传输、Verification 和运行位置，不扩大源码读取或提案许可：

| 既有预算 | 上限 |
|---|---|
| Repository Analysis flat / region catalog | 各 64 KiB |
| 整次 Scout / Region Scout | 18 / 12 次；每分支最多 8 轮，每轮最多 6 区域 |
| Foundation | 12 文件，单文件 32 KiB，总量 96 KiB |
| Targeted source | 24 文件，单文件 64 KiB，总量 224 KiB |
| Step Understanding / 扩展累计 | 2 轮扩展、24 Scout、48 文件、单文件 64 KiB、总量 512 KiB |
| Step AI request / Proposal | 768 KiB / 256 KiB |
| affectedPaths / verificationChecks | 各 16 |

更高的 Provider token / HTTP 响应上限不扩大上述 Scope、路径白名单或净化禁写规则。Coding 输入使用单份 `numberedLines + endsWithLf`；严格 hunk 行号、delta 与原文匹配仍然成立。

## 6. Verification 与失败后边界

当前只自动验证 DOMAIN_MODEL §8.11 的技术标准：`BUILD_SUCCEEDS`、`FULL_TEST_SUITE_PASSES`、`SELECTED_TESTS_PASS`、`DIFF_CHECK_PASSES`。保留其它业务标准为 `UNSUPPORTED`；不能改成“构建通过”以制造成功。

- BUILD：根 Wrapper 离线 `-DskipTests package`。
- TEST：根 Wrapper 离线、显式不跳测试；同会话 effective POM 与 Surefire 配置审查，解析真实 XML，要求实际有测试且没有失败。完整套件不能用被选择器缩小的测试代替。
- FOCUSED_TEST：仅支持受校验的 Java 测试目标与对应报告，不是任意命令。
- DIFF_CHECK：实际基线 A 到 Candidate B 的 Git 空白 / 冲突标记检查，不能证明业务效果。

Maven 在私有 `.git/v` 的精确 Candidate 副本中运行。Execution 完成时 `currentRevision=B`，`lastVerifiedRevision` 仍为 A；只有全部既有 Criteria 具有同一个 B 的成功证据，才一次提交 `SUCCEEDED` 与 `lastVerifiedRevision=B`。查看 API 与 Git diff 后自行判断业务结果，不能只看 HTTP 200。

准备阶段拒绝时没有 mutation，可先根据证据纠正输入或配置。进入 EXECUTING / VERIFYING 后异常可能保留最后完整检查点。**M5 尚未实现：** 不手工改 SQLite、强制成功、reset 活跃 Candidate 或重发失败 Candidate 来伪造恢复；保留状态、日志与 Git 证据并停止该步骤。客户端请求失败或超时后先查询状态，避免并发或重复执行。

交付整合、最终测试和证据归档见 [M4 Delivery Closeout](validation/m4-delivery-closeout.md)。
