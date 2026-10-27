# Step 4 拒绝调查与执行界面简化

日期：2026-10-27。独立分支：`codex/step4-diagnostics-execution-ui`，基于 `d0f6a27`。
范围：无模型调查、有限诊断与展示修正；没有启动付费 Run、重做完整实验或修改 Taskarium 业务代码。

## 结论与历史边界

历史 Step 4 确认由正式 `run_checks(goal=test,directory=.)` 的
`POLICY_REJECTED / CHECK_MUTATED_SOURCE / TOOL_EXECUTION` 终止。
Run `e9f883f1-6210-4c72-b52b-38d9932089ff` 的事件 929 开始检查，930 拒绝且
`resultRecorded=false`，931 记录 FAILED。该 Run 没有 Candidate 或 Delivery。
这证明检查检测到不允许接受的完整性变化，**不能证明具体改动路径或其业务原因**。

当时的容器、源码卷和本地临时目录均已清理，历史事件没有路径、前后哈希或测试正文。
因此目前不能判断是新增 H2 材料、`data.sql`、配置、资源还是实际源码变化。
成功应用操作的路径记录只说明 Agent 曾修改过什么，不能等同于正式检查期间实际变化。
没有恢复或伪造历史草稿，也没有用现有 C3 的内容冒充 Step 4。

原四步 Plan 与 3 次正式 Delivery 保持；历史 9/9、19/19、28/28 是保留的原 Candidate 测试结果，
不是本次新跑的完整测试。原实验仍是 **FAIL_INCOMPLETE_FOUR_STEP_ACCEPTANCE**。
原报告 [独立实验](independent-multistep-e2e.md) 与 [修复后续跑](post-fix-resumed-multistep-e2e.md) 不改写。

## 本次无模型调查

检查了当前隔离实现、历史拒绝、可信 C3 的配置及现有测试。
C3 使用 `jdbc:h2:file:./database/taskarium_db`，`ddl-auto=update`；没有据此假定 Step 4 的最终配置相同。
历史草稿涉及配置、SQL、README 和新的重启测试，其完整字节已不可用。

从可信 C3 的精确归档复制到一次性、无网络检查环境，入口完整 snapshot 为
`91502c1f16f6e50f962427c3b3c55a09d9bad02aeeb7a99ae64bab5b3aa8a492`。
不改任何源文件、配置或测试，连续运行已有 `TaskariumApplicationTests` 两次，每次执行 1 项、通过 1 项。
两次唯一非生成物变化都是已有 `database/taskarium_db.mv.db` 的内容，模式保持 `100644`。
每次结果绑定各自执行前 snapshot，前后哈希保存在 [复现元数据](evidence/step4-c3-restart-probe.json)。

这只证明现有有限 H2 兼容能处理该可信材料的正常重复打开；不是 Step 4 的 RestartPersistenceTests 重建，
也不是新的 28 项全量或四步验收。没有复现出历史 Step 4 的额外变化。
**根因尚未建立，所以没有扩大 H2、路径或文件类型的接受规则。**

## 修改

### 安全诊断

`CHECK_MUTATED_SOURCE` 仍拒绝并终止 Run，增加检查前后 snapshot、变化总数与意外变化数，
最多 8 项相对路径、类型及前后 mode/SHA-256；类型为新增、删除、内容、模式、内容及模式。
意外变化优先，超额标记截断。Application 在事件持久化前再次过滤受保护或疑似秘密路径，保留隐藏数，
状态区分 RECORDED、PARTIAL、UNAVAILABLE。无效诊断仍按原规则拒绝，不变成成功。

不记录源码、配置内容、命令参数、Maven 输出或原异常；不补写旧事件。
已有 H2 内容变化仍仅发生于一次性副本；权威源码完整 snapshot 与 Candidate 的测试绑定保持不变。
新的结构化 Candidate 检查证据补充实际失败与错误计数，历史 Candidate 不更新。

### 用户界面

- Maven 检查显示最近结果、实际计数与历史未通过次数，默认收起全部已加载检查；按 40 项展开，保留顺序与逐次结果。
  普通过程失败退出主时间线；当前执行仍可见，安全拒绝与终止失败保持显著。最近 1000 事件上限保持不变。
- 只在 Run 的 Candidate 与正式 COMPLETED Delivery 身份匹配、且有正式 revision 时显示“步骤已完成”。
  其他显示候选等待交付、交付受阻或本次运行失败。旧失败尝试不因后来交付成功改写。
  刷新、Run/Step 切换和迟到响应受既有选择代次约束；已确认 COMPLETED 不被迟到的较早结果降级。
- 已正式交付并同步 Step 后，唯一主要文件清单来自版本核对过的 Step final-changes API。
  未交付使用 Candidate 路径与冻结 diff 分类。清单不可用时说明原因，不能以 Candidate 冒充。
  新增/修改/删除分别计数及着色，类型未知明确待解析；长路径可换行，超过 6 项展开全表。
  逐工具修改记录只留在默认关闭的执行历史；最终 diff 可单独展开。
- 正常测试区域解析 TESTED/NOT_TESTED 和与最终源码相符的非陈旧检查，显示执行、通过、失败、错误、跳过。
  最近只编译不会覆盖之前同源码的有意义测试结果。失败证据缺失的计数显示未记录；明确旧 TESTS_PASSED 可推断零失败/错误。
  缺失有效 finalSnapshot 绑定、快照不符、矛盾或陈旧证据不显示通过。
- 原始测试 JSON、baseline、Tree 和其他技术标识移入默认关闭的开发者详情；后端溯源全部保留。
- 完成的历史 Step 不再用其旧 baseline 与当前可信 revision 比较后错误提示“阻塞”；开始/确认前的实际核对保持。

## 新执行的验证

| 验证 | 结果 | 范围 |
|---|---|---|
| Python 全量策略 | 44 项：37 通过、7 镜像协议测试门控跳过（未配置专用镜像变量） | H2 正例、源/配置/SQL 负例、增删与模式、8 项截断与不泄露内容 |
| 真实 Docker 定向回归 | 3/3 通过，0 跳过 | 原基线数据库准备隔离、普通 Maven 产物与真实源码改写拒绝、权威快照保持 |
| 前端全量 | 268/268 通过 | 100 次检查聚合/完整历史、终止失败、历史交付、刷新与切换、分类和 TESTED/NOT_TESTED |
| 前端类型检查及生产构建 | 通过 | 未增加依赖 |
| 后端根项目 `mvnw -o verify` | 1,704 项：1,668 执行通过、36 跳过；BUILD SUCCESS，12:18 | 定向真实 Docker 证据另存，不能以默认跳过冒充执行 |

真实 Docker 使用新诊断镜像固定 ID：
JDK17 `sha256:ad8543eac120fc1b78268c3c7b2ce112d7f77a3b6fb71adb70f261d40b822319`，
JDK21 `sha256:697bf1768109fe74743855018b64ea7bfd8e4d4917ebad47a262edc0463127d2`。
[定向 JUnit](evidence/step4-docker-focused.junit.xml) 保留实际三项执行结果；
[后端全量计数](evidence/step4-backend-full-results.json) 分模块记录普通套件的执行与跳过。
本地完整命令日志与一致备份保存在 `E:/develop/DelveForge/data/step4-ui-review-20261027/`。

## 浏览器验收

实际打开 `http://127.0.0.1:5187/`，后端为新构建 JAR、端口 28862，使用真实历史 SQLite 的一致副本，
无有效 Provider key、Provider 地址为不可用的本地端口。不点击确认、开始、修复或重新交付。

- Step 4：主时间线只留生命周期与最终失败；折叠汇总真实 3 次检查、历史 2 次未通过。
  拒绝警示在折叠区外；展开确认原三次 BUILD_PASSED_NOT_TESTED、COMPILATION_FAILED、POLICY_REJECTED 顺序不变。
- Step 3：Run 和当前活动均显示“步骤已完成”；没有旧 baseline 的错误阻塞提示。
  主要清单为正式 7 文件（新增 2、修改 5、删除 0），先显示 6 项，展开后显示真实第 7 项。
  DOM 确认正式列表只有 1 个、Candidate 列表为 0；正常测试计数为 28/28/0/0/0。
- Step 1：两个历史 Run 分别显示完成与失败；选择旧失败时保持 30 次模型、65 次工具与原失败原因，
  当前 Run 显示失败且不显示 Candidate 文件；后来的成功不覆盖旧失败。
- 整页刷新恢复原项目，按既有规则默认选择未完成 Step 4；重新选 Step 3 并刷新记录后仍正确显示正式交付。
  本次没有新增“持久化当前 Step/Run 选择”的行为。
- 原始 JSON、版本溯源、逐工具修改和 Maven 历史默认关闭。最终 diff 实际展开读回 23,054 字符，再关闭。
  浏览器宽度 1265px，文档宽度同为 1265px，无页面横向溢出。未另行完成手机视口视觉验收；删除类型和
  NOT_TESTED 由确定性组件回归覆盖，真实 Step 3 没有删除文件且其证据为 TESTED。

[修正前 Maven](evidence/step4-ui-before-failure.jpg)、[修正后 Maven](evidence/step4-ui-after-failure.jpg)、
[修正前已交付步骤](evidence/step4-ui-before-delivered.jpg)、[正式文件与测试摘要](evidence/step4-ui-after-delivered-files.jpg)、
[历史失败保持](evidence/step4-ui-after-historical-failure.jpg)。
[实际 DOM 核对](evidence/step4-browser-delivered-proof.json) 与 [差异展开](evidence/step4-browser-diff-proof.json)
及 [最终代码复核](evidence/step4-browser-final-code-proof.json)
属于真实历史数据的隔离预览，**不是新执行或目标应用可用性证据**。

## 历史保护与限制

[最终保护核对](evidence/step4-preservation-proof.json)：原库所有表的行数与完整行哈希均与本轮备份一致；
Plan、Run/事件、Candidate、Delivery、Step、WorkingCopy 还逐行等于上一轮的最终一致备份。
预览库的这些领域记录也与原库相同：仍是 5 个 Run、3 个 Candidate、3 次 Delivery，未产生新运行或交付。
原仓库 HEAD 为 A，全部 73 文件大小与 SHA-256 等于实验前清单；WorkingCopy 干净且 current/lastVerified=C3。
原 SQLite integrity=ok、外键问题为 0，无活跃 Run。
备份和所有本次诊断都置于独立证据目录，不重置或清理原库、WorkingCopy 或历史证据。

尚未解决的是历史 Step 4 的具体变化来源及目标应用最终用户流程；本次不承诺目标应用完整可用。
要识别 Step 4 的实际新拒绝，需要新的合法 Agent 尝试产生诊断，或找回该历史草稿；当前后者不可得。

## 是否值得再运行 Step 4

带本次诊断的新合法尝试有调查价值：它能把再次出现的拒绝定位到有界路径和哈希。
但它不是已确认修好后的必过重试，仍可能失败并产生 API 费用。
本次不启动；后续须通过产品正常授权与费用确认，只运行原 Step 4，禁止复写 Plan、手改业务实现或忽略未知变化。
若再次出现已知具体副作用，先无模型复现并判断是允许范围需要明确支持，还是必须修正的真实完整性违规，
再决定最小后续处理，避免继续无依据付费试错。
