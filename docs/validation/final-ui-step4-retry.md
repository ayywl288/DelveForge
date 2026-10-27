# 最终界面清理与唯一一次 Step 4 重试

日期：2026-10-27。独立分支：`codex/final-ui-step4-retry`。
界面实现提交：`6a22c42`，基于已验证的 `b0f4d33`；未合并、未推送。

## 最终结论

**FAIL_INCOMPLETE_FOUR_STEP_ACCEPTANCE：仍为 3/4 正式交付。**
仅启动一次新的、真实付费 DeepSeek Run：`dc3465c3-4da8-4101-8dee-91f5482fc065`。
结果为 `FAILED / POLICY_REJECTED`，没有 Candidate、Delivery 或 C4。

本次阻塞与旧 Step 4 的 `CHECK_MUTATED_SOURCE` 不同：Agent 在正式 Maven 检查前，连续两次拟写入
`src/main/resources/application.properties` 时触发 `PATCH_CREDENTIALS / PATCH_CONTENT`。
系统已提供一次修正机会，第二次连续拒绝按既有规则终止；并非累计调用、tokens 或总时长限制。
没有第二次付费重试，没有放宽完整性/凭据政策，也没有手工修补 Taskarium。

## 界面清理与验证

彻底移除普通实施界面的“开发者详情 · 版本溯源”“开发者详情 · 原始测试证据”和“查看最终差异”及原始 diff。
候选版本折叠区也一并移除，避免成为替代溯源入口；原始正文状态、开关、刷新 diff 操作与对应样式删除。
既有 Run 标识诊断与执行时间线保持，不新增替代开发者区域，不改其他页面设计。

正式 Delivery 状态、唯一正式文件清单、新增/修改/删除计数及简明测试结果保留。
未交付 Candidate 仍需从冻结 diff 在内部判定文件类型，读回后仅保留分类；已交付页面不再请求 Candidate diff。
分类加载保留 Run/Step 选择守卫，迟到结果不能覆盖新 Candidate。底层 Git 身份、快照、测试原证据和 diff API 均未改动。

[前端验证](evidence/final-step4-retry-frontend-verification.json)：19 文件、268 项测试通过，类型检查与生产构建通过。
先前只断言已删除原始正文的测试改为验证用户摘要与不存在的原始区域；仍覆盖迟到交付、分类、未测试和版本核对。
真实历史库隔离预览中，Step 3 仍显示完成，正式 7 文件、28 执行/28 通过，三个指定标题和原始正文均不在 DOM。
[界面截图](evidence/final-ui-simplified.jpg) 与 [DOM 核对](evidence/final-ui-simplified-proof.json) 是真实保留记录的展示验收，
不是新的 Step 4 功能执行。没有重复运行无关后端完整回归；本轮没有后端代码改动。

## 启动前核对

- 原 Plan `281e9daa-e68c-48f1-8a8a-89326dffca29`，Step 4 `aee6fe13-e14d-44bb-9579-b267bdd7242e`；
  原四步定义和原模拟用户要求保持。前三步 SUCCEEDED，Step 4 已确认且 READY，无活跃 Run。
- WorkingCopy `8ab00aed-43c3-4a57-aadc-2d3391cb6618` 干净，Git HEAD、currentRevision、lastVerifiedRevision 均为
  C3 `6cad3bc93ca88e8cdf3a19d3a470e036d084d086`，三个既有 Delivery 均为 COMPLETED。
- 重新部署上轮通过完整后端回归的相同 JAR；实际嵌入的 sandbox.py、AgentPolicyRejection 和 AgentToolActivity
  字节与当前构建资源一致。两套固定镜像中的 sandbox.py SHA-256 均与当前源码一致。
  [预检](evidence/final-step4-retry-preflight.json)、[镜像核对](evidence/final-step4-retry-diagnostic-images.json)。
- 在一次性无网络副本中，对精确 C3 源码执行新的无模型全量 Maven 检查：**28/28 通过**。
  唯一运行期变化为已有 `database/taskarium_db.mv.db` 的内容，前后 mode/hash 已记录；权威源码不变。
  [新执行的 C3 基线检查](evidence/final-step4-retry-baseline-smoke.json)。这不是 Step 4 测试证据。

原实验后端监听 `127.0.0.1:28860`，界面 `127.0.0.1:5186`，JDK 17、原模型 `deepseek-flash`。
通过现有费用确认对话框主动勾选预算确认后提交 `paidUsageAcknowledged=true`，只创建本次一个新 Run。
未重新生成 Plan、重复 M1–M3、追加实现要求或更换用户目标。沿用无累计调用/tokens 限额的既有执行政策。

## 本次实际运行

| 项目 | 实际值 |
|---|---|
| Run | `dc3465c3-4da8-4101-8dee-91f5482fc065` |
| 可信基线 | C3 `6cad3bc93ca88e8cdf3a19d3a470e036d084d086` |
| 开始 / 结束（北京时间） | 2026-10-27 16:23:26.891 / 16:27:09.792 |
| 模型调用 / 工具调用 | **28 / 42** |
| Provider 报告 tokens | **461,751**，只统计本 Run，不换算金额、不重复加算 reasoning tokens |
| 工具 | list_files 1、search_code 2、read_file 25、edit_file 13、apply_patch 1 |
| 修改尝试结果 | PATCH_APPLIED 11、PATCH_CONFLICT 1、POLICY_REJECTED 2；都发生于隔离沙箱 |
| 正式 run_checks | **0 次** |
| Run / Step | FAILED / READY |
| Candidate / Delivery / C4 | 均不存在 |

[完整安全事件](evidence/final-step4-retry-events.jsonl) 与 [最终核对](evidence/final-step4-retry-proof.json)
保留原事实。旧失败 Run `e9f883f1-6210-4c72-b52b-38d9932089ff` 的 CHECK_MUTATED_SOURCE 历史仍保留，未改写。

## 精确拒绝证据与边界

两次都是 `edit_file`，目标为 `src/main/resources/application.properties`：

| 事件序号 | 规则 / 阶段 | 输入位置 | 写入与反馈 |
|---|---|---|---|
| 1044 | PATCH_CREDENTIALS / PATCH_CONTENT | `new_text` 第 6、9 行 | mutationNotStarted=true；允许一次修正；resultRecorded=true |
| 1047 | PATCH_CREDENTIALS / PATCH_CONTENT | `new_text` 第 7、10 行 | mutationNotStarted=true；resultRecorded=false；随后事件 1048 FAILED |

[拒绝元数据](evidence/final-step4-retry-refusals.json)。行号属于工具拟写入的 `new_text`，不是已接受源文件的行号。
记录没有拟写入值、源代码片段、密码或 Provider 凭据。

这里没有 CHECK_MUTATED_SOURCE，也没有正式 Maven 检查期间的变化；不应编造 changed-path 快照或 before/after hash。
两次拒绝都明确发生于写入前，故不存在这两次被拒写入的已应用变更哈希。
新 sourceChanges 诊断已经部署，但本次没有走到使用它的检查阶段。

**已确认的阻塞是拟写入配置内容触发凭据政策，不能据此确认 DelveForge 误报。**
安全元数据没有保存被拒绝值，失败沙箱也已正常销毁；不能区分真实秘密字面量与误判的普通本地配置，
更不能把这次失败解释为 H2 运行期变化、生成物变化或原工作副本被污染。
现有政策允许识别器存在误报的可能，但这不是本次误报的证据。

目前没有足以直接修改 DelveForge 政策的最小复现，因此本轮不做政策修补。
若后续要研究此问题，应先取得经过授权且脱敏的最小配置输入，在现有分类器边界无模型复现，再决定定向修正；
不为这件事继续付费试错，不用任意忽略凭据或强制成功替代证据。

## 历史保护与实际可用性

原库仍只有一个新增 Run 及其事件。逐行核对表明原 Plan、四个 Step、全部旧 Run/事件、三个 Candidate、
三个 Delivery 与 WorkingCopy 记录均等于启动前快照；新增 Run 数=1，新增 Candidate/Delivery 数=0。
原仓库 73 个文件的大小与 SHA-256 均等于实验前清单，Git 干净；WorkingCopy 仍干净且可信版本为 C3。
SQLite integrity=ok、外键问题=0，活跃 Run/Delivery 预约均为 0。

[保留 Git 链](evidence/final-step4-retry-chain.json)：
`91f69ab → 6eb7c59 → ed3bc1c → 6cad3bc`，没有第四个交付提交。
此前的 9/9、19/19、28/28 属于原三个成功 Candidate；本轮额外 C3 预检 28/28 与它们分开记录。
本次没有 Step 4 的测试通过证据，不将 C3 的通过结果冒充第四步成功。

本 Run 的容器、卷、网络与本地沙箱目录均无残留；共享构件缓存及其他用户资源未清理。
[清理核对](evidence/final-step4-retry-cleanup.json)。原库执行前后均有一致备份，保存在本地证据目录。

真实 DelveForge 页面中，新旧两个 Step 4 Run 均保留失败，新 Run 用量准确且无 Candidate 文件清单；
[失败截图](evidence/final-step4-retry-ui.jpg)、[DOM 核对](evidence/final-step4-retry-ui-proof.json)。
这属于 DelveForge UI 证据，不是 Taskarium 功能证据。
本次没有 C4，按失败停止边界没有启动所谓“最终四步应用”，也没有宣称完成注册登录、周计划、重启保留或第二账户隔离的最终浏览器验收。
原模拟用户的完整需求仍未获最终实用性验证。

## Codex 干预与停止点

Codex 只修改 DelveForge 的指定前端及说明、执行无模型 C3 预检、核对并部署已验证诊断、配置正常凭据、
通过既有费用确认启动一次原 Step 4，随后只读观察与归档。Taskarium 沙箱中的 11 次成功修改由 DeepSeek 独立产生；
Codex 没有手改 Taskarium 源码、测试、POM 或配置，没有发送修正提示或引导重规划。

一次从旧会话记录读取凭据的操作被自动审批拒绝，未执行；随后使用用户本轮直接提供的密钥，经不回显的
正常输入配置到后台子进程环境。密钥未写入仓库、配置文件、日志或本报告。

确认本次终态后即停止，执行意图已标记 `TERMINAL_FAILED_NO_FURTHER_RETRY`；没有自动启动更多付费 Run。
本次得到真实的凭据写入拒绝证据，四步验收仍不通过。失败与不确定性保留，不人为制造成功。
