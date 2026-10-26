# DelveForge 独立 MVP 审查与 FinTrack 真实交付

日期：2026-10-26，Asia/Shanghai。分支：`codex/mvp-fintrack`，基于 `1839e55`；未合并、未推送。
本地原始证据根目录：`E:/develop/DelveForge/data/mvp-fintrack-20261026/`。

## 结论与责任划分

**MVP 成功：DelveForge 已让真实 DeepSeek 探索外部 FinTrack、编写功能与测试、冻结 Candidate，
并完成正式 Delivery；交付提交可启动，核心认证 CSV 导出和真实浏览器下载已验证可用。**
Codex 没有代写 FinTrack 业务代码或仓库测试。M1–M3 使用保留的真实结果，不宣称重新完成了一次用户发现全旅程。

| 层次 | 实际成果与边界 |
|---|---|
| DeepSeek 自主实现 | Run05 完成原确认的 CSV 纵向 Step；Run07 在后续确认的反馈计划内自主探索、编写后端/前端/测试，自行修正内部测试失败，最终 12 项通过。 |
| Candidate / Delivery | 两次真实 Candidate 和正式 Delivery 均成功；Run05 的成果和历史保持，不因后续发现边界缺陷而被改称为交付机制失败。 |
| 基本可用性 | 正式 C 独立打包启动、真实 JWT 登录、本人 CSV、两用户范围及 UTF-8 文本冒烟通过；浏览器点击导出获得实际文件。 |
| 外部质量发现 | Run05 的 LF 开头文本前缀处理遗漏单独记录；Run07 的已知 LF 冒烟通过。没有穷尽表格工具或生产级质量矩阵。 |
| Codex 辅助反馈 | Run05 后的 LF 探针由 Codex 提出，并通过新增可选规划说明和正常 API 开始新轮次；这是 **Codex 辅助重规划**，不是 DelveForge 自动发现外部缺陷并自主重规划、自修复的证明。 |

按用户最后澄清的成功标准收尾。当前 Run 交付后没有再生成 Plan 或启动付费 Run；额外改进留待用户明确授权。

## 独立发现、取舍与平台修正

1. **凭据策略承担了过多源码分析职责。** 先前整文件拒绝阻碍普通 JWT 代码，随后表达式白名单又要求词法分析。
   本次删除 83 行 `SourceCredentialExpression`，改用已知密钥/私钥/JWT 等格式及直接凭据字面量规则，
   不新增词法器或 AST。普通运行时 token 表达式可编辑；明确凭据行仍脱敏且不可修改。
   ADR-0017 明确缩减对间接、插值、编码、未知格式等秘密的识别保证，不能把它宣传为通用秘密分析。
2. **任意累计配额偏离自主交付目标。** 删除累计模型/工具次数、tokens 和整体时限停止门槛及其配置。
   前端每次启动/重试显示费用并要求主动勾选预算与付费意愿，关闭或切换目标后重置；
   API 缺少 `paidUsageAcknowledged=true` 在 Run 预留前拒绝。真实用量、取消和技术保障保留，没有账单/余额系统。
3. **无进展与用量不是同一件事。** 保留最近 12 个结构化观察；长度 1–4 的相同工具循环重复三次且没有实际修改时停止。
   成功实际修改清空窗口，缓存旧结果不能冒充进展。这是有界短循环检测，不是通用语义进度判断。
4. **退出后的 CLI 进程清理会误用 PID。** Run03 的安全结构日志定位到 `WorkspaceCommandRunner.stop`，
   Run04 定位到 `AgentProcess.run`；二者都在正常退出后无条件枚举并强杀后代。
   JDK 21 本地源码与异常位置表明命中了当前进程保护；已退出父 PID 的后代扫描存在 PID/PPID 复用问题。
   修正为存活前置检查、扫描后再检查，并在任何强杀前拒绝含当前 JVM 的后代集合；`CompatibilityGit` 只停止存活进程。
   独立保留 Docker 停止、资源归属和清理证明。Run02 没有足够结构诊断，**不推断其精确根因**。
5. **安全修正机会错误地按 Run 累计。** Run06 在一次写入前拒绝、成功修改、另一独立拒绝后直接失败。
   `sourceEditRejections` 没有随实际修改进展重置。改为连续拒绝计数：只有本次真实调用的
   `ok=true/PATCH_APPLIED` 重置；读取、冲突、失败和缓存均不重置。连续第二次拒绝、未知写入、净化或记录失败仍终止。
   **凭据识别、受保护原文、实际写入和最终冻结规则没有放宽。** 拒绝原文不落日志，因此不猜测 Run06 的具体被拒绝字面量。
6. **规划没有提交验收反馈的入口。** 本次曾新增可选 `planningNotes`，经既有保守净化作为模型数据输入，
   不成为 Evidence 或授权；新计划仍经方向/事实校验、准备和 Step 确认。当前前端没有备注编辑器。
   此改动是 Codex 为后续反馈轮次引入的辅助能力，单独提交，不作为 MVP 自主修复能力的成功依据。

### 保留的成熟能力

- Java/Maven 固定 Git 预检、JDK 17/21 选择、精确生成物准备和 A→P 来源；未要求用户清理原项目。
- M1–M3 的既有画像、只读仓库分析、已选方向和历史规划；Domain/Application/Infrastructure/App 依赖方向不变。
- LLM 经 AI Gateway，本地代码经 Workspace；原库只读、独立 WorkingCopy、每 Run Docker 副本。
- `network=none`、只读 root、2 GiB/2 CPU/256 PID、逐操作超时、受控依赖准备、取消、有限错误恢复。
- 凭据路径排除、完整源码先净化再切片、真实 Git 预演、前后像核对、停止后独立冻结。
- 零上下文 D、重放 P+D=B、C 的 parent=P/tree=B、CAS 发布、SQLite 原子接受及恢复日志。

没有新增依赖、框架、数据库迁移或 Aggregate，没有改写原仓库或手写 FinTrack 功能替代 Agent。

## 工程验证：新执行与保留证据分别陈述

| 范围 | 实际结果 | 证据 |
|---|---|---|
| c336cef 全部后端 | `mvnw.cmd -o verify`；165 份新 XML，1694 总计、33 跳过、1661 实际执行，零失败/错误 | `planning-feedback-full-backend.log`、`planning-feedback-backend-verification.json`、XML zip |
| bfde6fb 最后控制流修正 | 新执行相关 Agent/凭据策略/存储/模型/API 测试及全部模块 package；18 份 XML，199 总计、16 跳过、183 执行，零失败/错误 | `recovery-progress-package.log`、`recovery-progress-verification.json`、XML zip |
| 最后包与完整验证包的关系 | 逐模块运行内容比对只有 `CodingAgentService.class` 改变；其余运行类和资源相同。最后修正没有再次跑整套 verify，不把保留的全量结果说成新执行 | `changedPayloadsSincePriorFull`；冻结包 SHA `1cb6f0a62de6df8cfe2248b1b866b42607f28dccd88b2d3bbbc78d1986a76286` |
| 前端 | 17 文件、253 项，类型检查及生产构建通过；此后前端平台源码未变，结果保留有效 | `frontend-tests-verified.log`、`frontend-build-verified.log` |
| Python / 固定镜像 | 39 项，含实际固定镜像脚本身份；资源此后未变，保留证据 | `python-actual-image.log` |
| 实际 Java/Docker 合成夹具 | 两项通过：凭据旁安全编辑、正向 Maven 与冻结；工具外凭据改动被冻结拒绝。不是 FinTrack 业务 E2E，最后沙箱代码未变 | `all-process-fixes-actual-docker.log`、保存的 XML |
| FinTrack 最终自测 | DeepSeek 最终全量 Maven test：12 项、0 跳过、0 失败/错误，快照与正式 C 一致 | `run-07/candidate.json` 的 `testEvidence` |
| 外部核心冒烟 | 正式 C 为启动而独立 package，使用 `-DskipTests`，没有把它称为再次通过测试；随后真实 HTTP 与浏览器成功 | `run-07/runtime-evidence/` |

PID 生命周期回归先红后绿。连续拒绝回归在有效协议夹具下 4 项仅“成功修改后仍累计拒绝”一项先失败，
修正后连同缓存、读取、冲突及失败不重置的用例通过。早期两个新增夹具缺字段已按真实协议修正，未降低断言。
保留实际失败日志和被中断的旧验证日志；不把一次中断的 `process-fix-full-backend.log` 声称为通过。

## 真实 Provider 执行及用量

下表仅为本任务证据根目录内的新 Run；更早的 `data/fintrack-autonomous-20261025/` 历史不重复计入。
次数是已记录的模型回复和工具调用，tokens 是提供者已返回的报告值；unknown 不补造为零或费用。

| Run | ID | 终态 / 原因 | 模型回复 | 工具调用 | 报告 tokens |
|---|---|---|---:|---:|---:|
| 01 | `ade8da3f-477b-4cfd-b8d7-f69e3e94e0a8` | FAILED / RESOURCE_OWNERSHIP | 0 | 0 | unknown |
| 02 | `24bd7411-5abd-4e48-9233-da65541d910e` | FAILED / INTERNAL_FAILURE | 0 | 0 | unknown |
| 03 | `bb51a1ed-c103-4e30-8d74-a8abc3a9d488` | FAILED / INTERNAL_FAILURE | 4 | 8 | 21302 |
| 04 | `53cebaee-90ba-42c0-bb3f-f0e946bb42d8` | FAILED / INTERNAL_FAILURE | 0 | 0 | unknown |
| 05 | `97344ee1-bf3e-4eda-806f-8cb891c64f48` | SUCCEEDED / CANDIDATE_FROZEN | 37 | 53 | 859761 |
| 06 | `36c8ab5a-f24a-4807-bf0b-d7b9f216918e` | FAILED / POLICY_REJECTED | 21 | 38 | 429368 |
| 07 | `bdfc2d83-5a78-44aa-8bf0-fa4576e1f47d` | SUCCEEDED / CANDIDATE_FROZEN | 38 | 50 | 886020 |

Run01 为复用缓存时配置 owner 不匹配，修正配置而未放宽归属检查。Run02 的精确原因未知。
Run03/04 的 CLI 清理缺陷有明确结构证据；Run06 有明确累计安全拒绝控制流证据。
Run05 已成功交付；Run07 在 Codex 辅助反馈的新计划内再次成功。

新增 Coding Agent 报告总量 **2,196,451 tokens**。另有一次真实规划操作，prompt 6982、completion 8731，
合计 **15,713 tokens**；reasoning 7213 已包含在 completion，不再次相加。
本任务可确认的报告总量为 **2,212,164 tokens**。不是金额估算或完整提供者账单。
用户澄清收尾边界后没有新启动付费 Run；当时已运行的 Run07 自然完成。

首次付费启动曾被自动审批拒绝，随后只读核对公开仓库来源、实际出站字段及既有用户授权，重试获准；
没有绕过拒绝。初始消息只含目标 Step 语义和可信基线，工具返回受控净化材料。
User Profile 原文、SQLite 文件和宿主其它资料未作为模型消息发送；API Key 只经无回显输入进入本次进程环境，未写入仓库或报告。

## 两次正式交付与当前展示版

| 事实 | 原方向 Run05 | Codex 反馈轮次 Run07 |
|---|---|---|
| Candidate | `c8f56419-93cd-498c-a127-c0d8fa08a930` | `2751151b-536c-436d-a09c-1cbb93912cf1` |
| Delivery | COMPLETED | COMPLETED |
| 正式 C | `5ef8bbcc17273f5b51060e68dd498b53ecbddf16` | `44c74a9005ac1d502037d29536373e6920855940` |
| B | `2604d05206e7940ae5cb49f62a7fdabcbb6392b2` | `beeddf61e138452e1870bfb89e693b687aafee6e` |
| Agent 自测 | 最终 3 项通过 | 最终 12 项通过 |

共同原分析版本 A：`0e7f0bce854dee6f4d43582f8315bb45cec369eb`；共同准备基线 P：`bc3b9f1fff6fb0591243f86f08cbc1dd49c640f0`。
Run07 的 WorkingCopy：`ffbc8dd8-c0fd-4793-b7e6-bea88b417e3e`；Step：`d2fee124-0c31-4f9b-aa97-25fcce2c91f9`。

独立读取 Git blobs/SQLite 证明：P 的父版本为 A；C 的父版本为 P，tree(C)=B；P 上只重放冻结 D 得到 B；
冻结 diff 摘要一致；54 个正式受跟踪文件计算出的快照为
`6f63d0ea0cd41b81979046f47feea7b907d9ea5b14b4b21b99c1e7bd94ccceb3`，与最终 12 项测试的非 stale 快照一致。
Step=SUCCEEDED，Delivery=COMPLETED，WorkingCopy.currentRevision=lastVerifiedRevision=C，正式副本干净。
Plan 仍为 ACTIVE、WorkingCopy 为 EVOLVING；当前实现没有在此自动关闭计划，不把它伪写为 COMPLETED，
也不把计划收尾功能追加为本次 MVP 门槛。旧 Plan 正常 SUPERSEDED，历史成功 Step 和 C 保留。

## 核心可用性与额外质量发现

从正式 C 导出的未改源码在隔离容器中打包启动，数据为独立 H2 内存库的合成账号和交易。
真实注册/登录得到 JWT；匿名导出拒绝；Alpha 的 CSV 包含本人两笔交易而没有 Beta 的私有标记，
Beta 也只导出本人数据。UTF-8 中文、逗号、双引号、内嵌换行和日期/金额/分类按 CSV 单元格解析正确。

浏览器通过实际表单登录 Alpha，进入 Transactions，点击 Export CSV，接收到完成的下载事件。
下载文件 `frontend-downloaded.csv` 为 158 bytes、2 条数据，与实际 HTTP CSV 的单元格一致；
SHA-256：`346e80d502ca2be31756722a9e05ef21caa6919719148d8e3f54b8bf85a42a49`。截图为 `frontend-download.png`，没有用手工请求或令牌注入冒充前端下载。

Docker Desktop 未发布内部网络容器端口，验收临时使用仅监听 127.0.0.1 的 HTTP 转发，经 Docker stdio 原样传递请求/响应，
保持容器无外网；这属于操作环境适配，没有修改应用源码或制造 CSV 响应。浏览器初次导航超时后页面实际已加载，读取当前页面继续成功。

**单列的质量发现：**

- Run05 漏掉 LF 开头文本的前缀处理；原失败证据保留为 `run-05/runtime-evidence/http-result.json`。
  它是附加质量发现，不抹去真实实现、Candidate 和 Delivery。后续 LF 要求来自 Codex 辅助反馈，不能称为系统自主重规划。
- Run07 已知 LF 探针通过；最终 Agent 测试也覆盖文本前缀。这里只证明记录的范围，
  不宣称适用于任意电子表格或保存重开的通用公式防护；相关边界见 [OWASP CSV Injection](https://community.owasp.org/attacks/CSV_Injection)。
- 登录后页面上方仍可见部分登录区域，截图如实保留；核心交易和下载可用。未调查该布局现象根因，未追加业务修补或付费尝试。

## 数据保护、交付边界与提交

最终原仓库 130 文件、旧 WorkingCopy 67 文件、Run05 已交付副本 96 文件及 main 的 8 份未跟踪草稿，
hash/size/mtime 均未变。main 仍为 `80cd124`，只有原有 `frontend/design/`、`grilling/` 未跟踪项；
原开发 worktree 保持干净 `1839e55`。SQLite integrity=ok、0 外键错误、0 活动占用，两次 Delivery 都为 COMPLETED；
使用 SQLite backup API 保存最终一致备份。证据：`final-protection.json` / `final-product.sqlite`。

平台改动分开提交：

- `636029c`：缩减凭据分析职责及 ADR-0017。
- `982ffac`：进展驱动执行、费用确认及 ADR-0018。
- `7d035bb`：两处已退出 CLI 的 PID 清理问题和安全结构诊断。
- `c336cef`：Codex 辅助规划说明入口。
- `bfde6fb`：成功实际修改后重置连续写入前拒绝恢复计数。

本报告另作交付记录提交。没有合并或推送，也没有在交付后继续优化 FinTrack。
这证明已授权本地场景下可演示的真实自主实现与正式交付；不扩张为任意仓库、多租户或生产级完美可靠性的结论。
